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


def slippage_cost(price: float, slip_ticks: float = 0.5) -> float:
    """슬리피지 비용 계산 (기본 0.5틱).

    Args:
        price: 기준 가격.
        slip_ticks: 슬리피지 틱 수 (기본 0.5).

    Returns:
        슬리피지 절대 금액.
    """
    return tick_size(price) * slip_ticks


def spread_cost(price: float, spread_bps_min: float, slip_ticks: float = 0.5) -> float:
    """스프레드 + 슬리피지 합산 비용.

    Args:
        price: 기준 가격.
        spread_bps_min: 최소 스프레드 (bps).
        slip_ticks: 슬리피지 틱 수.

    Returns:
        총 스프레드 비용 (절대 금액).
    """
    spread_abs = max(float(price), 0.0) * (float(spread_bps_min) / 10_000.0)
    slip_abs = slippage_cost(price, slip_ticks)
    return spread_abs + slip_abs


def apply_costs(
    entry_tick: float,
    exit_tick: float,
    cost_in_bps: float,
    cost_out_bps: float,
    spread_bps_min: float,
    price_ref: float,
    slip_ticks: float = 0.5,
) -> tuple[float, float]:
    """거래 비용 적용 (수수료 + 스프레드 + 슬리피지).

    Args:
        slip_ticks: 슬리피지 틱 수 (기본 0.5). 0으로 설정하면 슬리피지 미반영.
    """
    total_spread = spread_cost(price_ref, spread_bps_min, slip_ticks)
    entry_exec = float(entry_tick) * (1.0 + float(cost_in_bps) / 10_000.0) + total_spread
    exit_exec = float(exit_tick) * (1.0 - float(cost_out_bps) / 10_000.0) - total_spread
    return max(entry_exec, 0.0), max(exit_exec, 0.0)

