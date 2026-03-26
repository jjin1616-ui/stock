from __future__ import annotations

from enum import Enum
from typing import Literal

import numpy as np
import pandas as pd

from .config import Preset


# ---------------------------------------------------------------------------
# 시장 체제(Market Regime) 분류
# ---------------------------------------------------------------------------

class MarketRegime(str, Enum):
    """시장 변동성 + 추세 기반 4분류."""
    LOW_VOL_UP = "LOW_VOL_UP"         # 저변동 상승
    HIGH_VOL_UP = "HIGH_VOL_UP"       # 고변동 상승
    LOW_VOL_FLAT = "LOW_VOL_FLAT"     # 저변동 횡보/하락
    HIGH_VOL_DOWN = "HIGH_VOL_DOWN"   # 고변동 하락


# 체제별 TA/RE/RS 가중치 (합=1.0)
REGIME_WEIGHTS: dict[MarketRegime, tuple[float, float, float]] = {
    # 저변동 상승: 추세(RS) 비중 높임, 수급(TA) 유지
    MarketRegime.LOW_VOL_UP: (0.35, 0.20, 0.45),
    # 고변동 상승: 수급(TA) 중시, 캔들효율(RE) 높임
    MarketRegime.HIGH_VOL_UP: (0.45, 0.30, 0.25),
    # 저변동 횡보: 캔들효율(RE) 중시, 추세 비중 축소
    MarketRegime.LOW_VOL_FLAT: (0.30, 0.45, 0.25),
    # 고변동 하락: 수급(TA) 최우선, 추세 최소
    MarketRegime.HIGH_VOL_DOWN: (0.50, 0.35, 0.15),
}


def classify_regime(
    bench_close: pd.Series,
    window: int = 20,
    vol_threshold: float = 0.20,
    ret_up_threshold: float = 0.01,
    ret_down_threshold: float = -0.01,
) -> MarketRegime | None:
    """벤치마크 종가 기반 시장 체제 분류.

    Args:
        bench_close: 벤치마크 종가 시리즈 (최소 window+1 개).
        window: 실현변동성/수익률 계산 기간 (기본 20일).
        vol_threshold: 연환산 변동성 기준 (기본 20%, 0.20).
        ret_up_threshold: 상승 판단 수익률 (기본 +1%).
        ret_down_threshold: 하락 판단 수익률 (기본 -1%).

    Returns:
        MarketRegime 또는 데이터 부족 시 None.
    """
    if bench_close is None or not hasattr(bench_close, 'iloc') or len(bench_close) < window + 1:
        return None

    recent = bench_close.iloc[-(window + 1):]
    daily_ret = recent.pct_change().dropna()
    if len(daily_ret) < window:
        return None

    # 20일 실현변동성 (연환산)
    realized_vol = float(daily_ret.std() * np.sqrt(252))
    # 20일 누적수익률
    cum_ret = float(recent.iloc[-1] / recent.iloc[0] - 1.0)

    high_vol = realized_vol >= vol_threshold
    if cum_ret >= ret_up_threshold:
        return MarketRegime.HIGH_VOL_UP if high_vol else MarketRegime.LOW_VOL_UP
    elif cum_ret <= ret_down_threshold:
        return MarketRegime.HIGH_VOL_DOWN if high_vol else MarketRegime.LOW_VOL_FLAT
    else:
        # 횡보 구간
        return MarketRegime.HIGH_VOL_DOWN if high_vol else MarketRegime.LOW_VOL_FLAT


def resolve_dynamic_weights(
    preset: Preset,
    bench_close: pd.Series | None = None,
    window: int = 20,
) -> tuple[float, float, float]:
    """시장 체제 기반 동적 가중치 반환. 벤치마크 없으면 프리셋 고정 가중치(폴백).

    Returns:
        (w_ta, w_re, w_rs) 튜플.
    """
    if bench_close is None or bench_close.empty:
        return preset.weights

    regime = classify_regime(bench_close, window=window)
    if regime is None:
        return preset.weights

    return REGIME_WEIGHTS[regime]


# ---------------------------------------------------------------------------
# 기존 스코어링 함수
# ---------------------------------------------------------------------------

def concentration_penalty(value_ma20: pd.Series, p0: float, lam: float) -> pd.Series:
    p = value_ma20.rank(pct=True)
    return lam * (p - p0).clip(lower=0.0)


def compute_score(
    feats: pd.DataFrame,
    preset: Preset,
    value_ma20: pd.Series,
    *,
    bench_close: pd.Series | None = None,
) -> pd.Series:
    """종합 점수 계산. bench_close 제공 시 동적 가중치 적용."""
    w_ta, w_re, w_rs = resolve_dynamic_weights(preset, bench_close)
    cp = concentration_penalty(value_ma20=value_ma20, p0=preset.penalty_p0, lam=preset.penalty_lambda)
    return w_ta * feats["z_ta"] + w_re * feats["z_re"] + w_rs * feats["z_rs"] - cp
