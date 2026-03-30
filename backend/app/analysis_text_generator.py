from __future__ import annotations

import logging
from dataclasses import dataclass

logger = logging.getLogger(__name__)


def _fmt_krw(value: int | None) -> str:
    """Format KRW amount in human-readable Korean (조/억 단위)."""
    if value is None:
        return "정보 없음"
    abs_val = abs(value)
    sign = "-" if value < 0 else ""
    if abs_val >= 1_000_000_000_000:
        return f"{sign}{abs_val / 1_000_000_000_000:.1f}조원"
    if abs_val >= 100_000_000:
        return f"{sign}{abs_val / 100_000_000:.0f}억원"
    return f"{sign}{abs_val:,}원"


def _fmt_pct(value: float | None) -> str:
    if value is None:
        return "정보 없음"
    return f"{value:+.1f}%"


def _grade_emoji(grade: str) -> str:
    return {"good": "🟢", "normal": "🟡", "danger": "🔴"}.get(grade, "⚪")


def generate_one_liner(
    name: str,
    revenue_growth: float | None,
    debt_ratio: float | None,
    sector: str | None,
) -> str:
    """Generate a ≤30-char summary for list cards."""
    parts: list[str] = []
    if revenue_growth is not None:
        if revenue_growth > 10:
            parts.append("매출 고성장")
        elif revenue_growth > 0:
            parts.append("매출 성장 중")
        elif revenue_growth > -10:
            parts.append("매출 소폭 감소")
        else:
            parts.append("매출 큰 폭 감소")

    if debt_ratio is not None:
        if debt_ratio < 50:
            parts.append("재무 건전")
        elif debt_ratio < 100:
            parts.append("부채 적정")
        elif debt_ratio < 200:
            parts.append("부채 주의")
        else:
            parts.append("부채 위험")

    return " · ".join(parts) if parts else f"{sector or ''} 종목"


def generate_summary(
    name: str,
    revenue: int | None,
    revenue_growth: float | None,
    operating_profit: int | None,
    debt_ratio: float | None,
    sector_avg_debt: float | None,
    total_score: float,
    grade: str,
) -> str:
    """Generate 3-5 sentence friendly Korean summary for detail screen."""
    sentences: list[str] = []

    # Sentence 1: Revenue
    if revenue is not None:
        rev_str = _fmt_krw(revenue)
        if revenue_growth is not None and revenue_growth != 0:
            direction = "늘었어요" if revenue_growth > 0 else "줄었어요"
            sentences.append(
                f"{name}은(는) 최근 분기 매출 {rev_str}을 기록했어요. "
                f"전년 같은 기간보다 {abs(revenue_growth):.1f}% {direction}."
            )
        else:
            sentences.append(f"{name}은(는) 최근 분기 매출 {rev_str}을 기록했어요.")

    # Sentence 2: Operating profit
    if operating_profit is not None:
        op_str = _fmt_krw(operating_profit)
        if operating_profit > 0:
            sentences.append(f"영업이익은 {op_str}으로 수익을 잘 내고 있어요.")
        else:
            sentences.append(f"영업이익은 {op_str}으로 현재 적자 상태예요.")

    # Sentence 3: Debt ratio with context
    if debt_ratio is not None:
        if sector_avg_debt and sector_avg_debt > 0:
            comparison = "낮아서" if debt_ratio < sector_avg_debt else "높아서"
            health = "재무 건전성이 우수해요" if debt_ratio < sector_avg_debt else "재무 관리에 주의가 필요해요"
            sentences.append(
                f"부채비율은 {debt_ratio:.0f}%로 업종 평균({sector_avg_debt:.0f}%)보다 {comparison} {health}."
            )
        else:
            if debt_ratio < 100:
                sentences.append(f"부채비율은 {debt_ratio:.0f}%로 안정적인 수준이에요.")
            else:
                sentences.append(f"부채비율은 {debt_ratio:.0f}%로 다소 높은 편이에요.")

    return " ".join(sentences) if sentences else f"{name}의 재무 데이터를 분석 중이에요."


def generate_positive_points(
    revenue_growth: float | None,
    debt_ratio: float | None,
    total_score: float,
) -> list[str]:
    """Generate 2-3 positive investment points."""
    points: list[str] = []
    if revenue_growth is not None and revenue_growth > 5:
        points.append(f"매출이 전년 대비 {revenue_growth:.1f}% 성장하고 있어요")
    if debt_ratio is not None and debt_ratio < 50:
        points.append("부채비율이 낮아 재무 안정성이 뛰어나요")
    if total_score >= 80:
        points.append("종합 건전성 점수가 우수 등급이에요")
    if not points:
        points.append("현재 특별한 긍정 요인은 분석 중이에요")
    return points[:3]


def generate_risk_points(
    revenue_growth: float | None,
    debt_ratio: float | None,
    total_score: float,
) -> list[str]:
    """Generate 2-3 risk factors."""
    points: list[str] = []
    if revenue_growth is not None and revenue_growth < -5:
        points.append(f"매출이 전년 대비 {abs(revenue_growth):.1f}% 감소하고 있어요")
    if debt_ratio is not None and debt_ratio > 150:
        points.append(f"부채비율이 {debt_ratio:.0f}%로 높은 편이에요")
    if total_score < 50:
        points.append("종합 건전성 점수가 주의 등급이에요")
    if not points:
        points.append("현재 특별한 위험 요인은 발견되지 않았어요")
    return points[:3]


def generate_health_comment(cat_scores: dict[str, float], total: float) -> str:
    """One-liner health comment for the gauge section."""
    strong: list[str] = []
    weak: list[str] = []
    labels = {
        "profitability": "수익성",
        "stability": "안정성",
        "growth": "성장성",
        "efficiency": "효율성",
        "valuation": "밸류에이션",
    }
    for key, label in labels.items():
        v = cat_scores.get(key, 0)
        if v >= 15:
            strong.append(label)
        elif v < 8:
            weak.append(label)

    if strong and weak:
        return f"{', '.join(strong[:2])}이(가) 우수하지만, {', '.join(weak[:2])}은(는) 개선이 필요해요."
    if strong:
        return f"{', '.join(strong[:2])}이(가) 특히 우수한 기업이에요."
    if weak:
        return f"{', '.join(weak[:2])}에서 주의가 필요해요."
    return "전반적으로 균형 잡힌 재무 상태예요."
