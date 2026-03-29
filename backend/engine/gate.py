from __future__ import annotations

from datetime import datetime

import pandas as pd


def _is_market_hours() -> bool:
    """장중 여부 판단 (평일 09:00~15:30 KST)."""
    try:
        from zoneinfo import ZoneInfo
    except ImportError:
        from backports.zoneinfo import ZoneInfo  # type: ignore[no-redef]
    now = datetime.now(ZoneInfo("Asia/Seoul"))
    # 주말이면 장외
    if now.weekday() >= 5:
        return False
    from datetime import time as _time
    return _time(9, 0) <= now.time() <= _time(15, 30)


def daily_mean_r(trade_log: pd.DataFrame) -> pd.DataFrame:
    if trade_log.empty:
        return pd.DataFrame(columns=["date", "daily_mean_R"])
    entered = trade_log[trade_log["entered"] & trade_log["R"].notna()].copy()
    if entered.empty:
        return pd.DataFrame(columns=["date", "daily_mean_R"])
    daily = entered.groupby("date", as_index=False)["R"].mean().rename(columns={"R": "daily_mean_R"})
    return daily.sort_values("date")


def compute_gate(
    daily: pd.DataFrame,
    N: int,
    threshold: float = 0.0,
    *,
    intraday: bool = False,
) -> pd.DataFrame:
    """Gate 시그널 계산.

    Args:
        intraday: 장중 모드.
            - True: 당일 데이터 포함 (shift=0) → 실시간 경로에서만 명시적으로 사용
            - False(기본): shift=1 유지 → 미래 참조 방지
    """
    if daily.empty:
        return pd.DataFrame(columns=["date", "daily_mean_R", "gate_metric", "gate_on"])
    out = daily.sort_values("date").copy()

    shift_n = 0 if intraday else 1

    out["gate_metric"] = out["daily_mean_R"].rolling(N, min_periods=N).mean().shift(shift_n)
    out["gate_on"] = out["gate_metric"].fillna(-1.0) >= threshold
    return out


def apply_gate(trade_log: pd.DataFrame, gate_daily: pd.DataFrame) -> pd.DataFrame:
    if trade_log.empty:
        return trade_log.copy()
    merged = trade_log.merge(gate_daily[["date", "gate_on"]], on="date", how="left")
    merged["gate_on"] = merged["gate_on"].fillna(False)
    return merged[merged["gate_on"]].copy()
