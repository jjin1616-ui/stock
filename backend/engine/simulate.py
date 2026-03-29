from __future__ import annotations

import numpy as np
import pandas as pd

from .costs import COST_PROFILES, apply_costs, round_to_tick, spread_cost


def simulate_trades(
    df: pd.DataFrame,
    *,
    exit_policy: str = "EOD",
    costs: str = "BASE",
) -> pd.DataFrame:
    cost_profile = COST_PROFILES.get(costs.upper(), COST_PROFILES["BASE"])
    exit_mode = exit_policy.upper()
    if df.empty:
        return pd.DataFrame(
            columns=[
                "date",
                "code",
                "entry_raw",
                "stop_raw",
                "target_raw",
                "exit_raw",
                "entry_tick",
                "stop_tick",
                "target_tick",
                "exit_tick",
                "entry_exec",
                "exit_exec",
                "entry",
                "exit",
                "stop",
                "target",
                "pnl_pct",
                "R",
                "R_raw",
                "R_exec",
                "spread_cost",
                "exit_reason",
                "hit_stop",
                "hit_target",
                "entered",
            ]
        )

    rows = []
    for _, r in df.iterrows():
        date = r["date"]
        entry_raw = float(r["entry"])
        stop_raw = float(r["stop"])
        target_raw = float(r["target"])
        next_high = float(r["next_high"])
        next_low = float(r["next_low"])
        next_close = float(r["next_close"])
        next_open = float(r["next_open"]) if "next_open" in r and pd.notna(r["next_open"]) else next_close

        entry_tick = round_to_tick(entry_raw, "UP")
        stop_tick = round_to_tick(stop_raw, "DOWN")
        target_tick = round_to_tick(target_raw, "UP")

        entered = next_high >= entry_tick
        hit_stop = entered and (next_low <= stop_tick)
        hit_target = entered and (next_high >= target_tick)

        if not entered:
            rows.append(
                {
                    "date": date,
                    "code": r["code"],
                    "entry_raw": entry_raw,
                    "stop_raw": stop_raw,
                    "target_raw": target_raw,
                    "exit_raw": np.nan,
                    "entry_tick": entry_tick,
                    "stop_tick": stop_tick,
                    "target_tick": target_tick,
                    "exit_tick": np.nan,
                    "entry_exec": np.nan,
                    "exit_exec": np.nan,
                    "entry": entry_tick,
                    "exit": np.nan,
                    "stop": stop_tick,
                    "target": target_tick,
                    "pnl_pct": np.nan,
                    "R": np.nan,
                    "R_raw": np.nan,
                    "R_exec": np.nan,
                    "spread_cost": 0.0,
                    "exit_reason": "NO_ENTRY",
                    "hit_stop": False,
                    "hit_target": False,
                    "entered": False,
                }
            )
            continue

        if hit_stop and hit_target:
            exit_raw = stop_raw
            exit_tick = stop_tick
            reason = "STOP_FIRST"
        elif hit_stop:
            exit_raw = stop_raw
            exit_tick = stop_tick
            reason = "STOP"
        elif hit_target:
            exit_raw = target_raw
            exit_tick = target_tick
            reason = "TARGET"
        else:
            if exit_mode == "NEXT_OPEN":
                exit_raw = next_open
                reason = "NEXT_OPEN"
            else:
                exit_raw = next_close
                reason = "EOD"
            exit_tick = round_to_tick(exit_raw, "NEAREST")

        # 비용 적용 (수수료 + 스프레드 + 슬리피지 0.5틱)
        entry_exec, exit_exec = apply_costs(
            entry_tick=entry_tick,
            exit_tick=exit_tick,
            cost_in_bps=cost_profile.cost_in_bps,
            cost_out_bps=cost_profile.cost_out_bps,
            spread_bps_min=cost_profile.spread_bps_min,
            price_ref=entry_tick,
            slip_ticks=0.5,
        )

        # 진입/청산 양방향 스프레드 비용 합산 (진단용)
        sc = spread_cost(entry_tick, cost_profile.spread_bps_min, slip_ticks=0.5) * 2.0

        risk_raw = max(entry_raw - stop_raw, 1e-9)
        risk_exec = max(entry_exec - stop_tick, 1e-9)
        r_raw = (exit_raw - entry_raw) / risk_raw
        r_exec = (exit_exec - entry_exec) / risk_exec
        rows.append(
            {
                "date": date,
                "code": r["code"],
                "entry_raw": entry_raw,
                "stop_raw": stop_raw,
                "target_raw": target_raw,
                "exit_raw": exit_raw,
                "entry_tick": entry_tick,
                "stop_tick": stop_tick,
                "target_tick": target_tick,
                "exit_tick": exit_tick,
                "entry_exec": entry_exec,
                "exit_exec": exit_exec,
                "entry": entry_tick,
                "exit": exit_exec,
                "stop": stop_tick,
                "target": target_tick,
                "pnl_pct": (exit_exec - entry_exec) / max(entry_exec, 1e-9),
                "R": r_exec,
                "R_raw": r_raw,
                "R_exec": r_exec,
                "spread_cost": sc,
                "exit_reason": reason,
                "hit_stop": bool(reason.startswith("STOP")),
                "hit_target": reason == "TARGET",
                "entered": True,
            }
        )

    return pd.DataFrame(rows)
