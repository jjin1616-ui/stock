from __future__ import annotations

from datetime import date
from typing import Literal

from pydantic import BaseModel, Field


GateUniverse = Literal["TOP10_BASED", "TOP_M_BASED"]


class Preset(BaseModel):
    name: str
    weights: tuple[float, float, float]  # (wTA,wRE,wRS)
    penalty_p0: float
    penalty_lambda: float
    theme_cap_default: int
    gate_default_N: int
    gate_universe_default: GateUniverse


class GridConfig(BaseModel):
    theme_caps: list[int]
    gate_lookbacks: list[int]
    gate_thresholds: list[float]
    gate_universes: list[GateUniverse]
    exit_policies: list[str]
    costs: list[str]
    preset_names: list[str]


class Config(BaseModel):
    end: date
    lookback_days: int = 120
    min_value_krw: float = 5e9
    min_trades: int = 3
    winsor_pct: float = 0.02
    ta_short: int = 3
    ta_long: int = 20
    rs_k: int = 5
    theme_lookback: int = 20
    n_themes: int = 12
    gate_M: int = 200
    benchmark_mode: Literal["KOSPI", "KOSDAQ", "KOSDAQ_ONLY"] = "KOSDAQ_ONLY"
    out_dir: str = "results"
    max_universe: int = 180
    presets: dict[str, Preset] = Field(default_factory=dict)
    grid: GridConfig


def default_config(end: date, lookback_days: int, min_value_krw: float, out_dir: str) -> Config:
    presets = {
        "DEFENSIVE": Preset(
            name="DEFENSIVE",
            weights=(0.30, 0.40, 0.30),
            penalty_p0=0.65,
            penalty_lambda=0.90,
            theme_cap_default=1,
            gate_default_N=20,
            gate_universe_default="TOP_M_BASED",
        ),
        "ADAPTIVE": Preset(
            name="ADAPTIVE",
            weights=(0.40, 0.25, 0.35),
            penalty_p0=0.70,
            penalty_lambda=0.55,
            theme_cap_default=2,
            gate_default_N=20,
            gate_universe_default="TOP_M_BASED",
        ),
        "AGGRESSIVE": Preset(
            name="AGGRESSIVE",
            weights=(0.50, 0.20, 0.30),
            penalty_p0=0.78,
            penalty_lambda=0.30,
            theme_cap_default=3,
            gate_default_N=10,
            gate_universe_default="TOP10_BASED",
        ),
    }
    grid = GridConfig(
        theme_caps=[1, 2, 3],
        gate_lookbacks=[5, 10, 20, 60],
        gate_thresholds=[-0.05, 0.0, 0.05],
        gate_universes=["TOP10_BASED", "TOP_M_BASED"],
        exit_policies=["EOD", "NEXT_OPEN"],
        costs=["LOW", "BASE", "HIGH"],
        preset_names=["DEFENSIVE", "ADAPTIVE", "AGGRESSIVE"],
    )
    return Config(
        end=end,
        lookback_days=lookback_days,
        min_value_krw=min_value_krw,
        out_dir=out_dir,
        presets=presets,
        grid=grid,
    )
