from __future__ import annotations

import pandas as pd


def daily_mean_r(trade_log: pd.DataFrame) -> pd.DataFrame:
    if trade_log.empty:
        return pd.DataFrame(columns=["date", "daily_mean_R"])
    entered = trade_log[trade_log["entered"] & trade_log["R"].notna()].copy()
    if entered.empty:
        return pd.DataFrame(columns=["date", "daily_mean_R"])
    daily = entered.groupby("date", as_index=False)["R"].mean().rename(columns={"R": "daily_mean_R"})
    return daily.sort_values("date")


def compute_gate(daily: pd.DataFrame, N: int, threshold: float = 0.0) -> pd.DataFrame:
    if daily.empty:
        return pd.DataFrame(columns=["date", "daily_mean_R", "gate_metric", "gate_on"])
    out = daily.sort_values("date").copy()
    out["gate_metric"] = out["daily_mean_R"].rolling(N, min_periods=N).mean().shift(1)
    out["gate_on"] = out["gate_metric"].fillna(-1.0) >= threshold
    return out


def apply_gate(trade_log: pd.DataFrame, gate_daily: pd.DataFrame) -> pd.DataFrame:
    if trade_log.empty:
        return trade_log.copy()
    merged = trade_log.merge(gate_daily[["date", "gate_on"]], on="date", how="left")
    merged["gate_on"] = merged["gate_on"].fillna(False)
    return merged[merged["gate_on"]].copy()
