"""단타2 Gate 엔진 — 시장 체제별 동적 임계값 + 시계열 DB 저장."""
from __future__ import annotations

import logging
from datetime import datetime

import pandas as pd

from .gate import daily_mean_r, compute_gate  # 기존 gate 함수 재사용
from .scoring import MarketRegime, classify_regime

logger = logging.getLogger("stock.gate2")


# 시장 체제별 Gate 임계값 (P2-4)
REGIME_GATE_THRESHOLD: dict[MarketRegime, float] = {
    MarketRegime.LOW_VOL_UP: 0.005,       # 저변동 상승: 약간 높게 (선별적)
    MarketRegime.HIGH_VOL_UP: 0.0,        # 고변동 상승: 기본
    MarketRegime.LOW_VOL_FLAT: -0.002,    # 저변동 횡보: 약간 낮게 (기회 유지)
    MarketRegime.HIGH_VOL_DOWN: 0.003,    # 고변동 하락: 방어적
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

    bench_close가 없으면 기존 고정 threshold 사용 (기존 gate 동작과 동일).
    """
    # 기본 gate 계산
    gate_df = compute_gate(daily, N, threshold, intraday=intraday)

    if gate_df.empty:
        gate_df["regime"] = None
        gate_df["dynamic_threshold"] = threshold
        return gate_df

    # 시장 체제 분류
    regime = None
    dynamic_threshold = threshold
    if bench_close is not None and len(bench_close) >= 21:
        regime = classify_regime(bench_close, window=20)
        if regime is not None:
            dynamic_threshold = REGIME_GATE_THRESHOLD.get(regime, threshold)
            # gate_on을 동적 임계값으로 재계산
            gate_df["gate_on"] = gate_df["gate_metric"].fillna(-1.0) >= dynamic_threshold

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
