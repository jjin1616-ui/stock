from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field, asdict
from datetime import date, datetime, timezone

import numpy as np

from app.models import CompanyFinancial, CompanyHealthScore

logger = logging.getLogger(__name__)


@dataclass
class CategoryScore:
    profitability: float = 0.0
    stability: float = 0.0
    growth: float = 0.0
    efficiency: float = 0.0
    valuation: float = 0.0


def _percentile_score(values: list[float], value: float, max_points: float = 20.0, reverse: bool = False) -> float:
    """Convert a value to a 0-20 score based on percentile rank within the group.

    Args:
        values: all values in the sector for this metric
        value: the company's value
        max_points: maximum score (20)
        reverse: True for metrics where lower is better (debt ratio, PER)
    """
    if not values or np.isnan(value):
        return 0.0

    arr = np.array([v for v in values if not np.isnan(v)])
    if len(arr) == 0:
        return 0.0

    # percentile rank: what fraction of values is this value >= (or <= for reverse)
    if reverse:
        rank = float(np.sum(arr >= value)) / len(arr)
    else:
        rank = float(np.sum(arr <= value)) / len(arr)

    return round(rank * max_points, 1)


def compute_health_score(
    financials: list[CompanyFinancial],
    sector_financials: list[CompanyFinancial],
) -> CategoryScore | None:
    """Compute 5-category health score for a single company.

    Args:
        financials: this company's financial records (latest + previous year for growth)
        sector_financials: all companies in same sector (latest quarter, for percentile)

    Returns:
        CategoryScore with each category 0-20, or None if insufficient data.
    """
    if not financials:
        return None

    latest = financials[0]  # most recent quarter
    prev = financials[1] if len(financials) > 1 else None

    # --- Derived ratios for this company ---
    def safe_div(a: int | None, b: int | None) -> float:
        if a is None or b is None or b == 0:
            return float("nan")
        return a / b

    op_margin = safe_div(latest.operating_profit, latest.revenue) * 100
    net_margin = safe_div(latest.net_income, latest.revenue) * 100
    roe = safe_div(latest.net_income, latest.total_equity) * 100
    debt_ratio = safe_div(latest.total_liabilities, latest.total_equity) * 100
    current_ratio = safe_div(latest.current_assets, latest.current_liabilities) * 100
    asset_turnover = safe_div(latest.revenue, latest.total_assets)

    # Growth (YoY)
    rev_growth = float("nan")
    profit_growth = float("nan")
    if prev and prev.revenue and prev.revenue != 0 and latest.revenue:
        rev_growth = ((latest.revenue - prev.revenue) / abs(prev.revenue)) * 100
    if prev and prev.operating_profit and prev.operating_profit != 0 and latest.operating_profit:
        profit_growth = ((latest.operating_profit - prev.operating_profit) / abs(prev.operating_profit)) * 100

    # --- Same ratios for all sector peers ---
    def sector_values(fn) -> list[float]:
        return [fn(f) for f in sector_financials if not np.isnan(fn(f))]

    def s_op_margin(f: CompanyFinancial) -> float:
        return safe_div(f.operating_profit, f.revenue) * 100

    def s_roe(f: CompanyFinancial) -> float:
        return safe_div(f.net_income, f.total_equity) * 100

    def s_net_margin(f: CompanyFinancial) -> float:
        return safe_div(f.net_income, f.revenue) * 100

    def s_debt(f: CompanyFinancial) -> float:
        return safe_div(f.total_liabilities, f.total_equity) * 100

    def s_current(f: CompanyFinancial) -> float:
        return safe_div(f.current_assets, f.current_liabilities) * 100

    def s_asset_turn(f: CompanyFinancial) -> float:
        return safe_div(f.revenue, f.total_assets)

    # --- Score each category ---
    score = CategoryScore()

    # 1. Profitability (20pt): op_margin 40% + ROE 30% + net_margin 30%
    score.profitability = round(
        _percentile_score(sector_values(s_op_margin), op_margin) * 0.4
        + _percentile_score(sector_values(s_roe), roe) * 0.3
        + _percentile_score(sector_values(s_net_margin), net_margin) * 0.3,
        1,
    )

    # 2. Stability (20pt): debt_ratio 40%(reverse) + current_ratio 30% + interest_coverage 30%
    # Interest coverage skipped for MVP (DART doesn't provide interest expense directly)
    # Redistribute: debt 55% + current 45%
    score.stability = round(
        _percentile_score(sector_values(s_debt), debt_ratio, reverse=True) * 0.55
        + _percentile_score(sector_values(s_current), current_ratio) * 0.45,
        1,
    )

    # 3. Growth (20pt): rev_growth 50% + profit_growth 50%
    # Growth is across all sectors (not sector-specific) for fairness
    if not np.isnan(rev_growth):
        score.growth = round(rev_growth * 0.1 + 10, 1)  # simple linear: 0% → 10pt, +100% → 20pt
        score.growth = max(0.0, min(20.0, score.growth))
    if not np.isnan(profit_growth):
        pg_score = round(profit_growth * 0.1 + 10, 1)
        pg_score = max(0.0, min(20.0, pg_score))
        score.growth = round((score.growth + pg_score) / 2, 1)

    # 4. Efficiency (20pt): asset_turnover 100% (inventory turnover skipped for MVP)
    score.efficiency = round(
        _percentile_score(sector_values(s_asset_turn), asset_turnover),
        1,
    )

    # 5. Valuation (20pt): skip for MVP (needs market data PER/PBR)
    # Placeholder: 10pt (neutral) until Phase 2 adds market data
    score.valuation = 10.0

    return score


def score_to_grade(total: float) -> str:
    if total >= 80:
        return "good"
    if total >= 50:
        return "normal"
    return "danger"


def total_score(cat: CategoryScore) -> float:
    return round(cat.profitability + cat.stability + cat.growth + cat.efficiency + cat.valuation, 1)
