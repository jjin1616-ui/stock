from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta


@dataclass
class BacktestResult:
    daily_mean_r: list[tuple[date, float]]
    top10: list[dict]
    kpi: dict


def run_backtest_or_none(end_date: date) -> BacktestResult | None:
    _ = end_date
    # 운영 환경에서는 가짜 티커/가짜 데이터 fallback을 노출하지 않는다.
    return None
