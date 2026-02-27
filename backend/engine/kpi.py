from __future__ import annotations

import numpy as np
import pandas as pd


def _mdd(series: pd.Series) -> float:
    if series.empty:
        return 0.0
    cum = series.cumsum()
    peak = cum.cummax()
    dd = cum - peak
    return float(dd.min())


def compute_kpi(trade_log: pd.DataFrame, gate_daily: pd.DataFrame, top10_meta: pd.DataFrame) -> dict:
    entered = trade_log[trade_log["entered"]].copy() if not trade_log.empty else pd.DataFrame()
    r_exec = entered["R_exec"] if (not entered.empty and "R_exec" in entered.columns) else entered.get("R", pd.Series(dtype=float))
    r_raw = entered["R_raw"] if (not entered.empty and "R_raw" in entered.columns) else r_exec

    gate_recent = gate_daily.tail(60) if not gate_daily.empty else pd.DataFrame()
    on_days = int(gate_recent["gate_on"].sum()) if not gate_recent.empty else 0
    gate_metric_recent = float(gate_daily["gate_metric"].dropna().iloc[-1]) if (not gate_daily.empty and gate_daily["gate_metric"].notna().any()) else 0.0

    meta = top10_meta.copy() if not top10_meta.empty else pd.DataFrame()
    if not gate_daily.empty and "date" in meta.columns:
        meta = meta.merge(gate_daily[["date", "gate_on"]], on="date", how="left")
        meta = meta[meta["gate_on"].fillna(False)]

    out = {
        "trades_total": int(len(r_exec)),
        "win_rate": float((r_exec > 0).mean()) if len(r_exec) else 0.0,
        "avg_r": float(r_exec.mean()) if len(r_exec) else 0.0,
        "expectancy_r": float(r_exec.mean()) if len(r_exec) else 0.0,
        "mdd_r": _mdd(r_exec) if len(r_exec) else 0.0,
        "avg_r_raw": float(r_raw.mean()) if len(r_raw) else 0.0,
        "expectancy_r_raw": float(r_raw.mean()) if len(r_raw) else 0.0,
        "avg_r_exec": float(r_exec.mean()) if len(r_exec) else 0.0,
        "expectancy_r_exec": float(r_exec.mean()) if len(r_exec) else 0.0,
        "mdd_r_exec": _mdd(r_exec) if len(r_exec) else 0.0,
        "delta_cost_impact": float(r_raw.mean() - r_exec.mean()) if len(r_exec) else 0.0,
        "hit_target_rate": float((entered["exit_reason"] == "TARGET").mean()) if len(entered) else 0.0,
        "hit_stop_rate": float(entered["exit_reason"].astype(str).str.startswith("STOP").mean()) if len(entered) else 0.0,
        "median_r": float(r_exec.median()) if len(r_exec) else 0.0,
        "tail_5pct_r": float(r_exec.nsmallest(max(1, int(len(r_exec) * 0.05))).mean()) if len(r_exec) else 0.0,
        "gate_on_days_recent": on_days,
        "gate_metric_recent": gate_metric_recent,
        "top10_avg_value_krw": float(meta["top10_avg_value_krw"].mean()) if (not meta.empty and "top10_avg_value_krw" in meta.columns) else 0.0,
        "theme_hhi": float(meta["theme_hhi"].mean()) if (not meta.empty and "theme_hhi" in meta.columns) else 0.0,
        "theme_count": float(meta["theme_count"].mean()) if (not meta.empty and "theme_count" in meta.columns) else 0.0,
        "errors_count": int(trade_log["error"].notna().sum()) if (not trade_log.empty and "error" in trade_log.columns) else 0,
        "skipped_count": int((trade_log["entered"] == False).sum()) if (not trade_log.empty and "entered" in trade_log.columns) else 0,  # noqa: E712
    }
    for k, v in out.items():
        if isinstance(v, float) and (np.isnan(v) or np.isinf(v)):
            out[k] = 0.0
    return out
