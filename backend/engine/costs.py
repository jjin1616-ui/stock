from __future__ import annotations

from dataclasses import dataclass
import math


@dataclass(frozen=True)
class CostProfile:
    cost_in_bps: float
    cost_out_bps: float
    spread_bps_min: float


COST_PROFILES: dict[str, CostProfile] = {
    "LOW": CostProfile(cost_in_bps=3.0, cost_out_bps=3.0, spread_bps_min=1.0),
    "BASE": CostProfile(cost_in_bps=7.0, cost_out_bps=7.0, spread_bps_min=2.0),
    "HIGH": CostProfile(cost_in_bps=12.0, cost_out_bps=12.0, spread_bps_min=4.0),
}


def tick_size(price: float) -> int:
    p = max(float(price), 0.0)
    if p < 1_000:
        return 1
    if p < 5_000:
        return 5
    if p < 10_000:
        return 10
    if p < 50_000:
        return 50
    if p < 100_000:
        return 100
    if p < 500_000:
        return 500
    return 1_000


def round_to_tick(price: float, side: str) -> float:
    tick = tick_size(price)
    ratio = float(price) / tick
    mode = side.upper()
    if mode == "UP":
        return float(math.ceil(ratio) * tick)
    if mode == "DOWN":
        return float(math.floor(ratio) * tick)
    if mode == "NEAREST":
        return float(round(ratio) * tick)
    raise ValueError(f"unknown tick rounding side: {side}")


def apply_costs(
    entry_tick: float,
    exit_tick: float,
    cost_in_bps: float,
    cost_out_bps: float,
    spread_bps_min: float,
    price_ref: float,
) -> tuple[float, float]:
    spread_abs = max(float(price_ref), 0.0) * (float(spread_bps_min) / 10_000.0)
    entry_exec = float(entry_tick) * (1.0 + float(cost_in_bps) / 10_000.0) + spread_abs
    exit_exec = float(exit_tick) * (1.0 - float(cost_out_bps) / 10_000.0) - spread_abs
    return max(entry_exec, 0.0), max(exit_exec, 0.0)

