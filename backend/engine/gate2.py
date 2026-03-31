"""단타2 Gate 엔진 — 시장 체제별 동적 임계값 + 시계열 DB 저장.

[C-1 수정] classify_regime2()로 횡보+고변동 오분류 해결.
[M-3 수정] fillna(-1.0) → notna() 방식으로 gate_on 판정.
"""
from __future__ import annotations

import logging
from datetime import datetime
from enum import Enum

import numpy as np
import pandas as pd

from .gate import daily_mean_r, compute_gate  # 기존 gate 함수 재사용
from .scoring import MarketRegime

logger = logging.getLogger("stock.gate2")


# ---------------------------------------------------------------------------
# [C-1] 5분류 Market Regime (gate2 전용)
# 원본 scoring.py의 4분류 MarketRegime은 건드리지 않음.
# ---------------------------------------------------------------------------

class MarketRegime2(str, Enum):
    """시장 변동성 + 추세 기반 5분류 (횡보+고변동 분리)."""
    LOW_VOL_UP = "LOW_VOL_UP"
    HIGH_VOL_UP = "HIGH_VOL_UP"
    LOW_VOL_FLAT = "LOW_VOL_FLAT"
    HIGH_VOL_FLAT = "HIGH_VOL_FLAT"    # ← 신규: 횡보+고변동
    HIGH_VOL_DOWN = "HIGH_VOL_DOWN"


def classify_regime2(
    bench_close: pd.Series,
    window: int = 20,
    vol_threshold: float = 0.20,
    ret_up_threshold: float = 0.01,
    ret_down_threshold: float = -0.01,
) -> MarketRegime2 | None:
    """[C-1 수정] 벤치마크 종가 기반 시장 체제 5분류.

    원본 classify_regime()은 횡보+고변동 → HIGH_VOL_DOWN으로 잘못 반환.
    이 함수는 HIGH_VOL_FLAT으로 올바르게 분류함.
    """
    if bench_close is None or not hasattr(bench_close, 'iloc') or len(bench_close) < window + 1:
        return None

    recent = bench_close.iloc[-(window + 1):]
    daily_ret = recent.pct_change().dropna()
    if len(daily_ret) < window:
        return None

    if recent.iloc[0] == 0:
        return None
    realized_vol = float(daily_ret.std() * np.sqrt(252))
    cum_ret = float(recent.iloc[-1] / recent.iloc[0] - 1.0)

    high_vol = realized_vol >= vol_threshold
    if cum_ret >= ret_up_threshold:
        return MarketRegime2.HIGH_VOL_UP if high_vol else MarketRegime2.LOW_VOL_UP
    elif cum_ret <= ret_down_threshold:
        return MarketRegime2.HIGH_VOL_DOWN if high_vol else MarketRegime2.LOW_VOL_FLAT
    else:
        # [C-1 핵심 수정] 횡보+고변동 → HIGH_VOL_FLAT (기존: HIGH_VOL_DOWN 오류)
        return MarketRegime2.HIGH_VOL_FLAT if high_vol else MarketRegime2.LOW_VOL_FLAT


# 시장 체제별 Gate 임계값 (P2-4) — 5분류 대응
REGIME_GATE_THRESHOLD: dict[MarketRegime2, float] = {
    MarketRegime2.LOW_VOL_UP: 0.005,       # 저변동 상승: 선별적
    MarketRegime2.HIGH_VOL_UP: 0.0,        # 고변동 상승: 기본
    MarketRegime2.LOW_VOL_FLAT: -0.002,    # 저변동 횡보: 기회 유지
    MarketRegime2.HIGH_VOL_FLAT: 0.001,    # 고변동 횡보: 약간 선별적 (신규)
    MarketRegime2.HIGH_VOL_DOWN: 0.003,    # 고변동 하락: 방어적
}


def compute_gate2(
    daily: pd.DataFrame,
    N: int,
    threshold: float = 0.0,
    *,
    bench_close: pd.Series | None = None,
    intraday: bool = False,
) -> pd.DataFrame:
    """Gate2: 시장 체제 감지 → 동적 임계값 적용.

    [C-1] classify_regime2() 사용 (5분류, 횡보+고변동 분리).
    [M-3] fillna(-1.0) 제거 → notna() & >= threshold 방식.
    bench_close가 없으면 기존 고정 threshold 사용 (기존 gate 동작과 동일).
    """
    # 기본 gate 계산
    gate_df = compute_gate(daily, N, threshold, intraday=intraday)

    if gate_df.empty:
        gate_df["regime"] = None
        gate_df["dynamic_threshold"] = threshold
        return gate_df

    # [M-3] 기본 gate_on도 notna 방식으로 재계산 (원본 gate.py의 fillna(-1.0) 버그 보정)
    gate_df["gate_on"] = gate_df["gate_metric"].notna() & (gate_df["gate_metric"] >= threshold)

    # [C-1] 시장 체제 분류 (5분류)
    regime = None
    dynamic_threshold = threshold
    if bench_close is not None and len(bench_close) >= 21:
        regime = classify_regime2(bench_close, window=20)
        if regime is not None:
            dynamic_threshold = REGIME_GATE_THRESHOLD.get(regime, threshold)
            # gate_on을 동적 임계값으로 재계산
            gate_df["gate_on"] = gate_df["gate_metric"].notna() & (gate_df["gate_metric"] >= dynamic_threshold)

    gate_df["regime"] = regime.value if regime else None
    gate_df["dynamic_threshold"] = dynamic_threshold
    return gate_df


def save_gate_history(session, gate_df: pd.DataFrame) -> int:
    """Gate 시계열을 DB에 저장. 반환: 저장된 행 수."""
    from app.auth import now
    from app.models import AutoTrade2GateHistory

    if gate_df.empty:
        return 0

    saved = 0
    ts = now()
    for _, row in gate_df.iterrows():
        ymd = row.get("date")
        if ymd is None:
            continue
        if isinstance(ymd, str):
            from datetime import date as _date
            ymd = _date.fromisoformat(ymd)

        from sqlalchemy import select as sa_select
        existing = session.scalar(
            sa_select(AutoTrade2GateHistory)
            .where(AutoTrade2GateHistory.ymd == ymd)
            .limit(1)
        )
        gate_metric_val = float(row.get("gate_metric") or 0.0)
        gate_on_val = bool(row.get("gate_on", False))
        regime_val = row.get("regime")
        dyn_thr = float(row.get("dynamic_threshold") or 0.0)
        daily_mean = float(row.get("daily_mean_R") or 0.0)

        if existing is not None:
            existing.gate_metric = gate_metric_val
            existing.gate_on = gate_on_val
            existing.regime = regime_val
            existing.dynamic_threshold = dyn_thr
            existing.daily_mean_r = daily_mean
            existing.updated_at = ts
        else:
            session.add(AutoTrade2GateHistory(
                ymd=ymd,
                gate_metric=gate_metric_val,
                gate_threshold=dyn_thr,
                gate_on=gate_on_val,
                regime=regime_val,
                dynamic_threshold=dyn_thr,
                daily_mean_r=daily_mean,
                updated_at=ts,
            ))
        saved += 1

    session.flush()
    return saved
