# Company Analysis (기업분석) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a "Company Analysis" tab that lets everyday investors understand any listed company's financial health through friendly Korean language, a 100-point health score, and direct order integration.

**Architecture:** Server-side DART financial data collection → health score computation → template-based AI text generation → FastAPI endpoints. Android Compose UI consumes these APIs via existing Retrofit/Repository patterns. Reuses CommonReportItemCard for listing, new CompanyDetailScreen for drill-down.

**Tech Stack:** Python 3.10 / FastAPI / SQLAlchemy / requests (server); Kotlin / Jetpack Compose / Retrofit / Kotlin Serialization (app)

---

## File Structure

### Server — New Files

| File | Responsibility |
|------|---------------|
| `backend/app/dart_financial_client.py` | Fetch financial statements from DART OpenAPI, parse into dataclass, cache in SQLite |
| `backend/app/analysis_service.py` | Compute 5-category health score (100pt), build CompanyCard / CompanyDetail DTOs |
| `backend/app/analysis_text_generator.py` | Template-based Korean natural language summaries, investment points |

### Server — Modified Files

| File | Change |
|------|--------|
| `backend/app/models.py` | Add `CompanyFinancial` and `CompanyHealthScore` tables |
| `backend/app/main.py` | Add `/analysis/companies`, `/analysis/companies/{ticker}`, `/analysis/portfolio-health` endpoints |

### App — New Files

| File | Responsibility |
|------|---------------|
| `app/src/main/java/com/example/stock/ui/screens/CompanyAnalysisScreen.kt` | List screen: curation + search + filter chips + card list |
| `app/src/main/java/com/example/stock/ui/screens/CompanyDetailScreen.kt` | Detail screen: AI summary + financials + health gauge + invest points + order buttons |

### App — Modified Files

| File | Change |
|------|--------|
| `app/src/main/java/com/example/stock/data/api/ApiModels.kt` | Add CompanyCardDto, CompanyDetailDto, HealthScoreDto, etc. |
| `app/src/main/java/com/example/stock/data/api/StockApiService.kt` | Add `/analysis/*` Retrofit interface methods |
| `app/src/main/java/com/example/stock/data/repository/StockRepository.kt` | Add `getCompanies()`, `getCompanyDetail()`, `getPortfolioHealth()` |
| `app/src/main/java/com/example/stock/viewmodel/ViewModels.kt` | Add `CompanyAnalysisViewModel` |
| `app/src/main/java/com/example/stock/navigation/AppNavigation.kt` | Add `ANALYSIS` tab to `AppTab` enum and NavHost |

---

## Task 1: Database Models for Financial Data

**Files:**
- Modify: `backend/app/models.py`

- [ ] **Step 1: Add CompanyFinancial model**

Add after the last model class in `models.py`:

```python
class CompanyFinancial(Base):
    """Cached DART financial statement data per company per quarter."""
    __tablename__ = "company_financials"
    __table_args__ = (
        UniqueConstraint("corp_code", "bsns_year", "reprt_code", name="uq_cf_corp_year_reprt"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    corp_code: Mapped[str] = mapped_column(String(8), index=True, nullable=False)
    ticker: Mapped[str] = mapped_column(String(6), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    market: Mapped[str] = mapped_column(String(10), nullable=False)  # KOSPI / KOSDAQ
    sector: Mapped[str] = mapped_column(String(100), nullable=True)
    bsns_year: Mapped[str] = mapped_column(String(4), nullable=False)
    reprt_code: Mapped[str] = mapped_column(String(5), nullable=False)  # 11011/11012/11013/11014
    revenue: Mapped[int] = mapped_column(Integer, nullable=True)  # 매출액 (원)
    operating_profit: Mapped[int] = mapped_column(Integer, nullable=True)  # 영업이익
    net_income: Mapped[int] = mapped_column(Integer, nullable=True)  # 당기순이익
    total_assets: Mapped[int] = mapped_column(Integer, nullable=True)  # 자산총계
    total_liabilities: Mapped[int] = mapped_column(Integer, nullable=True)  # 부채총계
    total_equity: Mapped[int] = mapped_column(Integer, nullable=True)  # 자본총계
    current_assets: Mapped[int] = mapped_column(Integer, nullable=True)  # 유동자산
    current_liabilities: Mapped[int] = mapped_column(Integer, nullable=True)  # 유동부채
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
```

- [ ] **Step 2: Add CompanyHealthScore model**

```python
class CompanyHealthScore(Base):
    """Computed health scores, refreshed after each DART quarterly release."""
    __tablename__ = "company_health_scores"
    __table_args__ = (
        UniqueConstraint("ticker", "computed_date", name="uq_chs_ticker_date"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    ticker: Mapped[str] = mapped_column(String(6), index=True, nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    market: Mapped[str] = mapped_column(String(10), nullable=False)
    sector: Mapped[str] = mapped_column(String(100), nullable=True)
    total_score: Mapped[float] = mapped_column(Float, nullable=False)
    profitability: Mapped[float] = mapped_column(Float, nullable=True)
    stability: Mapped[float] = mapped_column(Float, nullable=True)
    growth: Mapped[float] = mapped_column(Float, nullable=True)
    efficiency: Mapped[float] = mapped_column(Float, nullable=True)
    valuation: Mapped[float] = mapped_column(Float, nullable=True)
    grade: Mapped[str] = mapped_column(String(10), nullable=False)  # good / normal / danger
    ai_summary: Mapped[str] = mapped_column(Text, nullable=True)
    ai_positive_points: Mapped[str] = mapped_column(Text, nullable=True)  # JSON list
    ai_risk_points: Mapped[str] = mapped_column(Text, nullable=True)  # JSON list
    ai_health_comment: Mapped[str] = mapped_column(Text, nullable=True)
    revenue: Mapped[int] = mapped_column(Integer, nullable=True)
    debt_ratio: Mapped[float] = mapped_column(Float, nullable=True)
    revenue_growth: Mapped[float] = mapped_column(Float, nullable=True)
    computed_date: Mapped[date] = mapped_column(Date, nullable=False)
```

- [ ] **Step 3: Add imports at top of models.py**

Ensure these imports exist at the top (add any missing):

```python
from datetime import date, datetime
```

- [ ] **Step 4: Verify table creation**

Run on EC2:
```bash
ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem ubuntu@16.176.148.77 \
  'cd /home/ubuntu/stock && python3 -c "from backend.app.models import Base; print([t for t in Base.metadata.tables if \"company\" in t])"'
```

Expected: `['company_financials', 'company_health_scores']`

- [ ] **Step 5: Commit**

```bash
git add backend/app/models.py
git commit -m "feat(analysis): add CompanyFinancial and CompanyHealthScore DB models"
```

---

## Task 2: DART Financial Data Client

**Files:**
- Create: `backend/app/dart_financial_client.py`

- [ ] **Step 1: Create the DART client module**

```python
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import datetime, timezone

import requests

from app.config import settings

logger = logging.getLogger(__name__)

# DART report codes
REPRT_ANNUAL = "11011"      # 사업보고서
REPRT_HALF = "11012"        # 반기보고서
REPRT_Q1 = "11013"          # 1분기보고서
REPRT_Q3 = "11014"          # 3분기보고서

# Account IDs we need from DART fnlttSinglAcnt
_ACCOUNT_MAP: dict[str, str] = {
    "매출액": "revenue",
    "영업이익": "operating_profit",
    "당기순이익": "net_income",
    "자산총계": "total_assets",
    "부채총계": "total_liabilities",
    "자본총계": "total_equity",
    "유동자산": "current_assets",
    "유동부채": "current_liabilities",
}

DART_BASE = "https://opendart.fss.or.kr/api"


@dataclass
class FinancialRow:
    corp_code: str
    ticker: str
    name: str
    bsns_year: str
    reprt_code: str
    revenue: int | None = None
    operating_profit: int | None = None
    net_income: int | None = None
    total_assets: int | None = None
    total_liabilities: int | None = None
    total_equity: int | None = None
    current_assets: int | None = None
    current_liabilities: int | None = None


def fetch_corp_codes() -> dict[str, str]:
    """Return {ticker: corp_code} mapping from DART corpCode API.

    DART uses corp_code (8-digit) internally; we store ticker (6-digit) for the app.
    """
    key = (settings.opendart_api_key or "").strip()
    if not key:
        logger.warning("OPENDART_API_KEY not set — skipping corp code fetch")
        return {}

    import zipfile
    import io
    import xml.etree.ElementTree as ET

    url = f"{DART_BASE}/corpCode.xml"
    try:
        resp = requests.get(url, params={"crtfc_key": key}, timeout=30)
        resp.raise_for_status()
    except Exception:
        logger.exception("DART corpCode fetch failed")
        return {}

    mapping: dict[str, str] = {}
    try:
        zf = zipfile.ZipFile(io.BytesIO(resp.content))
        xml_name = zf.namelist()[0]
        tree = ET.parse(zf.open(xml_name))
        for el in tree.iter("list"):
            stock_code = (el.findtext("stock_code") or "").strip()
            corp_code = (el.findtext("corp_code") or "").strip()
            if stock_code and corp_code:
                mapping[stock_code] = corp_code
    except Exception:
        logger.exception("DART corpCode parse failed")

    logger.info("DART corp codes loaded: %d tickers", len(mapping))
    return mapping


def fetch_financial(
    corp_code: str,
    bsns_year: str,
    reprt_code: str,
    *,
    ticker: str = "",
    name: str = "",
) -> FinancialRow | None:
    """Fetch single-company full financial statement from DART fnlttSinglAcnt API."""
    key = (settings.opendart_api_key or "").strip()
    if not key:
        return None

    url = f"{DART_BASE}/fnlttSinglAcnt.json"
    params = {
        "crtfc_key": key,
        "corp_code": corp_code,
        "bsns_year": bsns_year,
        "reprt_code": reprt_code,
        "fs_div": "CFS",  # 연결재무제표 우선
    }

    try:
        resp = requests.get(url, params=params, timeout=15)
        data = resp.json()
    except Exception:
        logger.exception("DART fnlttSinglAcnt failed for %s/%s/%s", corp_code, bsns_year, reprt_code)
        return None

    if str(data.get("status")) != "000":
        # 013 = no data for this period — normal for some companies
        if str(data.get("status")) != "013":
            logger.warning("DART fnlttSinglAcnt status=%s msg=%s for %s", data.get("status"), data.get("message"), corp_code)
        return None

    row = FinancialRow(
        corp_code=corp_code,
        ticker=ticker,
        name=name,
        bsns_year=bsns_year,
        reprt_code=reprt_code,
    )

    for item in data.get("list", []):
        account_nm = (item.get("account_nm") or "").strip()
        field = _ACCOUNT_MAP.get(account_nm)
        if field is None:
            continue
        # DART returns amounts as strings with commas
        raw = (item.get("thstrm_amount") or "").replace(",", "").strip()
        if raw and raw != "-":
            try:
                setattr(row, field, int(raw))
            except ValueError:
                pass

    return row


def batch_fetch_financials(
    tickers_corps: dict[str, str],
    bsns_year: str,
    reprt_code: str,
    *,
    ticker_names: dict[str, str] | None = None,
    delay: float = 0.15,
) -> list[FinancialRow]:
    """Fetch financials for multiple companies with rate limiting.

    Args:
        tickers_corps: {ticker: corp_code} mapping
        bsns_year: e.g. "2025"
        reprt_code: one of REPRT_ANNUAL/REPRT_HALF/REPRT_Q1/REPRT_Q3
        ticker_names: optional {ticker: name} mapping
        delay: seconds between API calls (DART rate limit ~1000/min)
    """
    names = ticker_names or {}
    results: list[FinancialRow] = []
    total = len(tickers_corps)

    for i, (ticker, corp_code) in enumerate(tickers_corps.items()):
        row = fetch_financial(
            corp_code=corp_code,
            bsns_year=bsns_year,
            reprt_code=reprt_code,
            ticker=ticker,
            name=names.get(ticker, ""),
        )
        if row is not None:
            results.append(row)

        if i % 50 == 0 and i > 0:
            logger.info("DART batch progress: %d/%d fetched, %d success", i, total, len(results))

        if delay > 0:
            time.sleep(delay)

    logger.info("DART batch complete: %d/%d success", len(results), total)
    return results
```

- [ ] **Step 2: Commit**

```bash
git add backend/app/dart_financial_client.py
git commit -m "feat(analysis): add DART financial data client with batch fetching"
```

---

## Task 3: Health Score Computation Engine

**Files:**
- Create: `backend/app/analysis_service.py`

- [ ] **Step 1: Create the analysis service**

```python
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
```

- [ ] **Step 2: Commit**

```bash
git add backend/app/analysis_service.py
git commit -m "feat(analysis): add health score computation engine with 5 categories"
```

---

## Task 4: AI Text Generator

**Files:**
- Create: `backend/app/analysis_text_generator.py`

- [ ] **Step 1: Create the text generator**

```python
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
```

- [ ] **Step 2: Commit**

```bash
git add backend/app/analysis_text_generator.py
git commit -m "feat(analysis): add template-based Korean AI text generator"
```

---

## Task 5: FastAPI Endpoints

**Files:**
- Modify: `backend/app/main.py`

- [ ] **Step 1: Add imports at top of main.py**

Add these imports near the existing import block:

```python
from app.analysis_service import compute_health_score, score_to_grade, total_score, CategoryScore
from app.analysis_text_generator import (
    generate_one_liner, generate_summary,
    generate_positive_points, generate_risk_points,
    generate_health_comment, _fmt_krw,
)
from app.dart_financial_client import fetch_corp_codes, batch_fetch_financials, REPRT_ANNUAL
```

- [ ] **Step 2: Add GET /analysis/companies endpoint**

Add after the last endpoint in main.py:

```python
@app.get("/analysis/companies")
def get_analysis_companies(
    grade: str | None = Query(None, regex="^(good|normal|danger)$"),
    market: str | None = Query(None),
    sector: str | None = Query(None),
    sort_by: str = Query("healthScore", regex="^(healthScore|revenue|growthRate|marketCap)$"),
    sort_order: str = Query("desc", regex="^(asc|desc)$"),
    page: int = Query(1, ge=1),
    size: int = Query(20, ge=1, le=100),
):
    """기업분석 목록 — 건강점수 기반 종목 리스트 + 큐레이션."""
    with session_scope() as db:
        q = db.query(CompanyHealthScore).filter(CompanyHealthScore.total_score.isnot(None))

        if grade:
            q = q.filter(CompanyHealthScore.grade == grade)
        if market:
            q = q.filter(CompanyHealthScore.market == market)
        if sector:
            q = q.filter(CompanyHealthScore.sector == sector)

        total_count = q.count()

        # Sorting
        sort_col = {
            "healthScore": CompanyHealthScore.total_score,
            "revenue": CompanyHealthScore.revenue,
            "growthRate": CompanyHealthScore.revenue_growth,
        }.get(sort_by, CompanyHealthScore.total_score)

        if sort_order == "desc":
            q = q.order_by(sort_col.desc())
        else:
            q = q.order_by(sort_col.asc())

        companies = q.offset((page - 1) * size).limit(size).all()

        # Curation: top 5 by health score
        top5 = (
            db.query(CompanyHealthScore)
            .filter(CompanyHealthScore.total_score.isnot(None))
            .order_by(CompanyHealthScore.total_score.desc())
            .limit(5)
            .all()
        )

    def to_card(row: CompanyHealthScore) -> dict:
        return {
            "ticker": row.ticker,
            "name": row.name,
            "market": row.market,
            "sector": row.sector,
            "healthScore": row.total_score,
            "grade": row.grade,
            "revenue": row.revenue,
            "debtRatio": row.debt_ratio,
            "revenueGrowth": row.revenue_growth,
            "aiSummary": row.ai_summary,
        }

    return {
        "companies": [to_card(c) for c in companies],
        "curation": {"topCompanies": [to_card(c) for c in top5]},
        "totalCount": total_count,
        "page": page,
        "totalPages": (total_count + size - 1) // size,
    }
```

- [ ] **Step 3: Add GET /analysis/companies/{ticker} endpoint**

```python
@app.get("/analysis/companies/{ticker}")
def get_analysis_company_detail(ticker: str):
    """기업분석 상세 — 재무 + 건강점수 + AI 분석."""
    with session_scope() as db:
        score_row = (
            db.query(CompanyHealthScore)
            .filter(CompanyHealthScore.ticker == ticker)
            .order_by(CompanyHealthScore.computed_date.desc())
            .first()
        )
        if not score_row:
            raise HTTPException(status_code=404, detail=f"No analysis data for {ticker}")

        # Latest financial data
        fin = (
            db.query(CompanyFinancial)
            .filter(CompanyFinancial.ticker == ticker)
            .order_by(CompanyFinancial.bsns_year.desc(), CompanyFinancial.reprt_code.desc())
            .first()
        )

        # Revenue history (last 8 quarters)
        fin_history = (
            db.query(CompanyFinancial)
            .filter(CompanyFinancial.ticker == ticker)
            .order_by(CompanyFinancial.bsns_year.asc(), CompanyFinancial.reprt_code.asc())
            .limit(8)
            .all()
        )

    positive = json.loads(score_row.ai_positive_points) if score_row.ai_positive_points else []
    risks = json.loads(score_row.ai_risk_points) if score_row.ai_risk_points else []

    financials = {}
    if fin:
        equity = fin.total_equity or 0
        revenue = fin.revenue or 0
        financials = {
            "revenue": fin.revenue,
            "operatingProfit": fin.operating_profit,
            "netIncome": fin.net_income,
            "debtRatio": round((fin.total_liabilities or 0) / equity * 100, 1) if equity else None,
            "roe": round((fin.net_income or 0) / equity * 100, 1) if equity else None,
            "currentRatio": round((fin.current_assets or 0) / (fin.current_liabilities or 1) * 100, 1),
            "revenueGrowth": score_row.revenue_growth,
        }

    return {
        "basicInfo": {
            "ticker": score_row.ticker,
            "name": score_row.name,
            "market": score_row.market,
            "sector": score_row.sector,
        },
        "financials": financials,
        "healthScore": {
            "total": score_row.total_score,
            "profitability": score_row.profitability,
            "stability": score_row.stability,
            "growth": score_row.growth,
            "efficiency": score_row.efficiency,
            "valuation": score_row.valuation,
        },
        "aiAnalysis": {
            "summary": score_row.ai_summary,
            "positivePoints": positive,
            "riskPoints": risks,
            "healthComment": score_row.ai_health_comment,
        },
        "charts": {
            "revenueHistory": [
                {"period": f"{f.bsns_year}Q{{'11013':'1','11012':'2','11014':'3','11011':'4'}.get(f.reprt_code,'?')}", "value": f.revenue}
                for f in fin_history
                if f.revenue
            ],
            "profitHistory": [
                {"period": f"{f.bsns_year}Q{{'11013':'1','11012':'2','11014':'3','11011':'4'}.get(f.reprt_code,'?')}", "value": f.operating_profit}
                for f in fin_history
                if f.operating_profit
            ],
        },
    }
```

- [ ] **Step 4: Add model import**

At the top of main.py, add to existing model imports:

```python
from app.models import CompanyFinancial, CompanyHealthScore
```

Also add:

```python
import json
```

- [ ] **Step 5: Commit**

```bash
git add backend/app/main.py
git commit -m "feat(analysis): add /analysis/companies and /analysis/companies/{ticker} endpoints"
```

---

## Task 6: Android DTO Models

**Files:**
- Modify: `app/src/main/java/com/example/stock/data/api/ApiModels.kt`

- [ ] **Step 1: Add Company Analysis DTOs**

Add at the end of ApiModels.kt:

```kotlin
// ── Company Analysis ─────────────────────────

@Serializable
data class CompanyCardDto(
    val ticker: String = "",
    val name: String = "",
    val market: String = "",
    val sector: String? = null,
    val healthScore: Double? = null,
    val grade: String = "normal",  // good / normal / danger
    val revenue: Long? = null,
    val debtRatio: Double? = null,
    val revenueGrowth: Double? = null,
    val aiSummary: String? = null,
)

@Serializable
data class CompanyListResponseDto(
    val companies: List<CompanyCardDto> = emptyList(),
    val curation: CurationDto? = null,
    val totalCount: Int = 0,
    val page: Int = 1,
    val totalPages: Int = 0,
)

@Serializable
data class CurationDto(
    val topCompanies: List<CompanyCardDto> = emptyList(),
)

@Serializable
data class HealthScoreDto(
    val total: Double? = null,
    val profitability: Double? = null,
    val stability: Double? = null,
    val growth: Double? = null,
    val efficiency: Double? = null,
    val valuation: Double? = null,
)

@Serializable
data class CompanyFinancialsDto(
    val revenue: Long? = null,
    val operatingProfit: Long? = null,
    val netIncome: Long? = null,
    val debtRatio: Double? = null,
    val roe: Double? = null,
    val currentRatio: Double? = null,
    val revenueGrowth: Double? = null,
)

@Serializable
data class AiAnalysisDto(
    val summary: String? = null,
    val positivePoints: List<String> = emptyList(),
    val riskPoints: List<String> = emptyList(),
    val healthComment: String? = null,
)

@Serializable
data class ChartPointSimpleDto(
    val period: String = "",
    val value: Long? = null,
)

@Serializable
data class CompanyChartsDto(
    val revenueHistory: List<ChartPointSimpleDto> = emptyList(),
    val profitHistory: List<ChartPointSimpleDto> = emptyList(),
)

@Serializable
data class BasicInfoDto(
    val ticker: String = "",
    val name: String = "",
    val market: String = "",
    val sector: String? = null,
)

@Serializable
data class CompanyDetailDto(
    val basicInfo: BasicInfoDto = BasicInfoDto(),
    val financials: CompanyFinancialsDto = CompanyFinancialsDto(),
    val healthScore: HealthScoreDto = HealthScoreDto(),
    val aiAnalysis: AiAnalysisDto = AiAnalysisDto(),
    val charts: CompanyChartsDto = CompanyChartsDto(),
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/stock/data/api/ApiModels.kt
git commit -m "feat(analysis): add Company Analysis DTOs to ApiModels.kt"
```

---

## Task 7: Retrofit API Interface

**Files:**
- Modify: `app/src/main/java/com/example/stock/data/api/StockApiService.kt`

- [ ] **Step 1: Add analysis endpoints to StockApiService**

Add these methods inside the `StockApiService` interface:

```kotlin
    @GET("analysis/companies")
    suspend fun getAnalysisCompanies(
        @Query("grade") grade: String? = null,
        @Query("market") market: String? = null,
        @Query("sector") sector: String? = null,
        @Query("sort_by") sortBy: String = "healthScore",
        @Query("sort_order") sortOrder: String = "desc",
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 20,
    ): CompanyListResponseDto

    @GET("analysis/companies/{ticker}")
    suspend fun getAnalysisCompanyDetail(
        @Path("ticker") ticker: String,
    ): CompanyDetailDto
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/stock/data/api/StockApiService.kt
git commit -m "feat(analysis): add /analysis/* Retrofit API interface methods"
```

---

## Task 8: Repository Methods

**Files:**
- Modify: `app/src/main/java/com/example/stock/data/repository/StockRepository.kt`

- [ ] **Step 1: Add analysis methods to StockRepository**

Add these methods inside the `StockRepository` class:

```kotlin
    suspend fun getAnalysisCompanies(
        grade: String? = null,
        market: String? = null,
        sector: String? = null,
        sortBy: String = "healthScore",
        sortOrder: String = "desc",
        page: Int = 1,
    ): Result<CompanyListResponseDto> = suspendRunCatching {
        val s = settingsStore.get()
        withTimeout(15_000L) {
            NetworkModule.api(s.baseUrl).getAnalysisCompanies(
                grade = grade, market = market, sector = sector,
                sortBy = sortBy, sortOrder = sortOrder, page = page,
            )
        }
    }

    suspend fun getAnalysisCompanyDetail(ticker: String): Result<CompanyDetailDto> = suspendRunCatching {
        val s = settingsStore.get()
        withTimeout(15_000L) {
            NetworkModule.api(s.baseUrl).getAnalysisCompanyDetail(ticker)
        }
    }
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/stock/data/repository/StockRepository.kt
git commit -m "feat(analysis): add analysis repository methods"
```

---

## Task 9: ViewModel

**Files:**
- Modify: `app/src/main/java/com/example/stock/viewmodel/ViewModels.kt`

- [ ] **Step 1: Add CompanyAnalysisViewModel**

Add this class at the end of ViewModels.kt:

```kotlin
class CompanyAnalysisViewModel(private val repository: StockRepository) : ViewModel() {
    // --- List screen state ---
    val listState = mutableStateOf(UiState<CompanyListResponseDto>(loading = true))
    val selectedGrade = mutableStateOf<String?>(null)
    val selectedMarket = mutableStateOf<String?>(null)
    val selectedSector = mutableStateOf<String?>(null)
    val sortBy = mutableStateOf("healthScore")
    val currentPage = mutableStateOf(1)

    // --- Detail screen state ---
    val detailState = mutableStateOf(UiState<CompanyDetailDto>())

    private var listJob: Job? = null
    private var detailJob: Job? = null

    fun loadCompanies(page: Int = 1) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            listState.value = listState.value.copy(loading = true)
            val result = repository.getAnalysisCompanies(
                grade = selectedGrade.value,
                market = selectedMarket.value,
                sector = selectedSector.value,
                sortBy = sortBy.value,
                page = page,
            )
            result.onSuccess { data ->
                listState.value = UiState(data = data, loading = false)
                currentPage.value = page
            }.onFailure { e ->
                listState.value = UiState(error = e.message ?: "로드 실패", loading = false)
            }
        }
    }

    fun loadDetail(ticker: String) {
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            detailState.value = UiState(loading = true)
            val result = repository.getAnalysisCompanyDetail(ticker)
            result.onSuccess { data ->
                detailState.value = UiState(data = data, loading = false)
            }.onFailure { e ->
                detailState.value = UiState(error = e.message ?: "상세 로드 실패", loading = false)
            }
        }
    }

    fun setGradeFilter(grade: String?) {
        selectedGrade.value = grade
        loadCompanies()
    }

    fun setMarketFilter(market: String?) {
        selectedMarket.value = market
        loadCompanies()
    }

    fun setSortBy(sort: String) {
        sortBy.value = sort
        loadCompanies()
    }
}
```

- [ ] **Step 2: Register in AppViewModelFactory**

In the `AppViewModelFactory` class, add this case to the `create()` method:

```kotlin
CompanyAnalysisViewModel::class.java -> CompanyAnalysisViewModel(repository) as T
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/stock/viewmodel/ViewModels.kt
git commit -m "feat(analysis): add CompanyAnalysisViewModel with list/detail state"
```

---

## Task 10: Company Analysis List Screen

**Files:**
- Create: `app/src/main/java/com/example/stock/ui/screens/CompanyAnalysisScreen.kt`

- [ ] **Step 1: Create CompanyAnalysisScreen.kt**

```kotlin
package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.data.api.CompanyCardDto
import com.example.stock.data.api.CompanyListResponseDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.util.ServiceLocator
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.CompanyAnalysisViewModel

// ── Design Tokens ──
private val CaBg = Color(0xFFF7F8FA)
private val CaCardBg = Color(0xFFFFFFFF)
private val CaCardBorder = Color(0xFFE8EBF0)
private val CaCardRadius = 14.dp
private val CaTextPrimary = Color(0xFF1A1D26)
private val CaTextSecondary = Color(0xFF6B7280)
private val CaGood = Color(0xFF4CAF50)
private val CaNormal = Color(0xFFFFC107)
private val CaDanger = Color(0xFFFF4747)
private val CaUp = Color(0xFFD32F2F)
private val CaDown = Color(0xFF1565C0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyAnalysisScreen(
    onCompanyClick: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: CompanyAnalysisViewModel = viewModel(factory = AppViewModelFactory(repo))

    LaunchedEffect(Unit) { vm.loadCompanies() }

    val state by vm.listState
    val data = state.data

    Scaffold(
        topBar = { AppTopBar(title = "기업분석") },
        containerColor = CaBg,
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // ── Curation: Top companies ──
            val topCompanies = data?.curation?.topCompanies.orEmpty()
            if (topCompanies.isNotEmpty()) {
                item {
                    Text(
                        "🏆 오늘의 우량주",
                        color = CaTextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(topCompanies, key = { it.ticker }) { card ->
                            CurationCard(card) { onCompanyClick(card.ticker, card.name) }
                        }
                    }
                }
            }

            // ── Filter chips ──
            item {
                Spacer(Modifier.height(12.dp))
                FilterChipRow(vm)
            }

            // ── Loading / Error ──
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CaGood)
                    }
                }
            } else if (state.error != null) {
                item {
                    Text(
                        state.error ?: "오류 발생",
                        color = CaDanger,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            // ── Company cards ──
            val companies = data?.companies.orEmpty()
            items(companies, key = { it.ticker }) { card ->
                CompanyCardItem(card) { onCompanyClick(card.ticker, card.name) }
            }

            // ── Empty state ──
            if (!state.loading && companies.isEmpty() && state.error == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("조건에 맞는 기업이 없어요", color = CaTextSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurationCard(card: CompanyCardDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(CaCardRadius),
        colors = CardDefaults.cardColors(containerColor = gradeColor(card.grade).copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                card.name,
                color = CaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${card.healthScore?.toInt() ?: "-"}점",
                color = gradeColor(card.grade),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                gradeLabel(card.grade),
                color = gradeColor(card.grade),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun FilterChipRow(vm: CompanyAnalysisViewModel) {
    val grade by vm.selectedGrade
    val grades = listOf(null to "전체", "good" to "🟢 좋은기업", "normal" to "🟡 보통", "danger" to "🔴 위험")

    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        grades.forEach { (value, label) ->
            val selected = grade == value
            FilterChip(
                selected = selected,
                onClick = { vm.setGradeFilter(value) },
                label = { Text(label, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CaGood.copy(alpha = 0.15f),
                    selectedLabelColor = CaGood,
                ),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CompanyCardItem(card: CompanyCardDto, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(CaCardRadius),
        colors = CardDefaults.cardColors(containerColor = CaCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Row 1: name + score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        gradeEmoji(card.grade),
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        card.name,
                        color = CaTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        card.ticker,
                        color = CaTextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(gradeColor(card.grade).copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        "${card.healthScore?.toInt() ?: "-"}점",
                        color = gradeColor(card.grade),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Row 2: metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricMini("매출", formatKrw(card.revenue))
                MetricMini("부채비율", card.debtRatio?.let { "${it.toInt()}%" } ?: "-")
                MetricMini("성장률", card.revenueGrowth?.let { "${if (it >= 0) "+" else ""}${it.toInt()}%" } ?: "-",
                    valueColor = card.revenueGrowth?.let { if (it >= 0) CaUp else CaDown })
            }

            // Row 3: AI summary
            if (!card.aiSummary.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    card.aiSummary,
                    color = CaTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MetricMini(label: String, value: String, valueColor: Color? = null) {
    Column {
        Text(label, color = CaTextSecondary, fontSize = 10.sp)
        Text(
            value,
            color = valueColor ?: CaTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

// ── Helpers ──

private fun gradeColor(grade: String): Color = when (grade) {
    "good" -> CaGood
    "normal" -> CaNormal
    "danger" -> CaDanger
    else -> CaNormal
}

private fun gradeEmoji(grade: String): String = when (grade) {
    "good" -> "🟢"
    "normal" -> "🟡"
    "danger" -> "🔴"
    else -> "⚪"
}

private fun gradeLabel(grade: String): String = when (grade) {
    "good" -> "좋은 기업"
    "normal" -> "보통"
    "danger" -> "위험"
    else -> ""
}

private fun formatKrw(value: Long?): String {
    if (value == null) return "-"
    val abs = kotlin.math.abs(value)
    return when {
        abs >= 1_000_000_000_000 -> "${value / 1_000_000_000_000.0}조".take(6)
        abs >= 100_000_000 -> "${value / 100_000_000}억"
        else -> "${value}원"
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/stock/ui/screens/CompanyAnalysisScreen.kt
git commit -m "feat(analysis): add CompanyAnalysisScreen with curation, filters, and cards"
```

---

## Task 11: Company Detail Screen

**Files:**
- Create: `app/src/main/java/com/example/stock/ui/screens/CompanyDetailScreen.kt`

- [ ] **Step 1: Create CompanyDetailScreen.kt**

```kotlin
package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.data.api.CompanyDetailDto
import com.example.stock.data.api.HealthScoreDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.util.ServiceLocator
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.CompanyAnalysisViewModel

// Reuse design tokens from CompanyAnalysisScreen
private val CdBg = Color(0xFFF7F8FA)
private val CdCardBg = Color(0xFFFFFFFF)
private val CdCardRadius = 14.dp
private val CdTextPrimary = Color(0xFF1A1D26)
private val CdTextSecondary = Color(0xFF6B7280)
private val CdGood = Color(0xFF4CAF50)
private val CdNormal = Color(0xFFFFC107)
private val CdDanger = Color(0xFFFF4747)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanyDetailScreen(
    ticker: String,
    name: String,
    onBack: () -> Unit = {},
    onBuy: (String) -> Unit = {},
    onAddWatchlist: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: CompanyAnalysisViewModel = viewModel(factory = AppViewModelFactory(repo))

    LaunchedEffect(ticker) { vm.loadDetail(ticker) }

    val state by vm.detailState
    val detail = state.data

    Scaffold(
        topBar = { AppTopBar(title = "$name ($ticker)", onBack = onBack) },
        containerColor = CdBg,
    ) { inner ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CdGood)
            }
            return@Scaffold
        }

        if (detail == null) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(state.error ?: "데이터를 불러올 수 없어요", color = CdDanger)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── AI Summary Card ──
            AiSummaryCard(detail)

            // ── Key Financials ──
            FinancialsCard(detail)

            // ── Health Gauge ──
            HealthGaugeCard(detail.healthScore, detail.aiAnalysis.healthComment)

            // ── Investment Points ──
            InvestmentPointsCard(
                positive = detail.aiAnalysis.positivePoints,
                risks = detail.aiAnalysis.riskPoints,
            )

            // ── Action Buttons ──
            ActionButtonsCard(
                ticker = ticker,
                onBuy = onBuy,
                onAddWatchlist = onAddWatchlist,
            )

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(CdCardRadius),
        colors = CardDefaults.cardColors(containerColor = CdCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = CdTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun AiSummaryCard(detail: CompanyDetailDto) {
    SectionCard("💬 AI가 알려주는 기업 현황") {
        Text(
            detail.aiAnalysis.summary ?: "분석 데이터를 준비 중이에요.",
            color = CdTextPrimary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun FinancialsCard(detail: CompanyDetailDto) {
    val f = detail.financials
    SectionCard("📊 핵심 재무 지표") {
        FinancialRow("매출액", formatKrw(f.revenue), statusForDebtOrGrowth(null))
        FinancialRow("영업이익", formatKrw(f.operatingProfit), statusForProfit(f.operatingProfit))
        FinancialRow("순이익", formatKrw(f.netIncome), statusForProfit(f.netIncome))
        FinancialRow("부채비율", f.debtRatio?.let { "${it.toInt()}%" } ?: "-", statusForDebt(f.debtRatio))
        FinancialRow("ROE", f.roe?.let { "${String.format("%.1f", it)}%" } ?: "-", statusForRoe(f.roe))
        FinancialRow("유동비율", f.currentRatio?.let { "${it.toInt()}%" } ?: "-", statusForCurrent(f.currentRatio))
        FinancialRow("매출 성장률", f.revenueGrowth?.let { "${if (it >= 0) "+" else ""}${String.format("%.1f", it)}%" } ?: "-",
            statusForGrowth(f.revenueGrowth))
    }
}

@Composable
private fun FinancialRow(label: String, value: String, status: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CdTextSecondary, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                color = CdTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(6.dp))
            Text(status, fontSize = 12.sp)
        }
    }
}

@Composable
private fun HealthGaugeCard(score: HealthScoreDto, comment: String?) {
    val total = score.total ?: 0.0
    val grade = when {
        total >= 80 -> "good"
        total >= 50 -> "normal"
        else -> "danger"
    }

    SectionCard("🏥 건전성 진단서") {
        // Total score
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "종합 점수: ",
                color = CdTextSecondary,
                fontSize = 14.sp,
            )
            Text(
                "${total.toInt()}점",
                color = gradeColor(grade),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                " / 100점 ${gradeEmoji(grade)}",
                color = CdTextSecondary,
                fontSize = 14.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Category bars
        GaugeBar("수익성", score.profitability)
        GaugeBar("안정성", score.stability)
        GaugeBar("성장성", score.growth)
        GaugeBar("효율성", score.efficiency)
        GaugeBar("밸류에이션", score.valuation)

        if (!comment.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                comment,
                color = CdTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun GaugeBar(label: String, value: Double?) {
    val v = value ?: 0.0
    val fraction = (v / 20.0).toFloat().coerceIn(0f, 1f)
    val color = when {
        v >= 15 -> CdGood
        v >= 8 -> CdNormal
        else -> CdDanger
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = CdTextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(70.dp),
        )
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = Color(0xFFE8EBF0),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${v.toInt()}",
            color = CdTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(24.dp),
        )
    }
}

@Composable
private fun InvestmentPointsCard(positive: List<String>, risks: List<String>) {
    SectionCard("🔮 투자 포인트") {
        if (positive.isNotEmpty()) {
            Text("✅ 기대 요인", color = CdGood, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            positive.forEach { point ->
                Text("· $point", color = CdTextPrimary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }
        }
        if (risks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("⚠️ 리스크 요인", color = CdDanger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            risks.forEach { point ->
                Text("· $point", color = CdTextPrimary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
            }
        }
    }
}

@Composable
private fun ActionButtonsCard(
    ticker: String,
    onBuy: (String) -> Unit,
    onAddWatchlist: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(CdCardRadius),
        colors = CardDefaults.cardColors(containerColor = CdCardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onBuy(ticker) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = CdGood),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("📈 매수하기", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { onAddWatchlist(ticker) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("♥ 관심종목", fontSize = 13.sp, color = CdTextPrimary)
            }
        }
    }
}

// ── Helpers (local copies to avoid cross-file dependency) ──

private fun gradeColor(grade: String): Color = when (grade) {
    "good" -> CdGood
    "normal" -> CdNormal
    "danger" -> CdDanger
    else -> CdNormal
}

private fun gradeEmoji(grade: String): String = when (grade) {
    "good" -> "🟢"; "normal" -> "🟡"; "danger" -> "🔴"; else -> "⚪"
}

private fun formatKrw(value: Long?): String {
    if (value == null) return "-"
    val abs = kotlin.math.abs(value)
    return when {
        abs >= 1_000_000_000_000 -> "${String.format("%.1f", value / 1_000_000_000_000.0)}조"
        abs >= 100_000_000 -> "${value / 100_000_000}억"
        else -> "${value}원"
    }
}

private fun statusForDebt(v: Double?): String = when {
    v == null -> ""; v < 50 -> "🟢"; v < 100 -> "🟡"; else -> "🔴"
}
private fun statusForRoe(v: Double?): String = when {
    v == null -> ""; v >= 15 -> "🟢"; v >= 5 -> "🟡"; else -> "🔴"
}
private fun statusForCurrent(v: Double?): String = when {
    v == null -> ""; v >= 200 -> "🟢"; v >= 100 -> "🟡"; else -> "🔴"
}
private fun statusForGrowth(v: Double?): String = when {
    v == null -> ""; v >= 10 -> "🟢"; v >= 0 -> "🟡"; else -> "🔴"
}
private fun statusForProfit(v: Long?): String = when {
    v == null -> ""; v > 0 -> "🟢"; else -> "🔴"
}
private fun statusForDebtOrGrowth(v: Double?): String = ""
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/stock/ui/screens/CompanyDetailScreen.kt
git commit -m "feat(analysis): add CompanyDetailScreen with AI summary, financials, health gauge"
```

---

## Task 12: Navigation — Add ANALYSIS Tab

**Files:**
- Modify: `app/src/main/java/com/example/stock/navigation/AppNavigation.kt`

- [ ] **Step 1: Add ANALYSIS to AppTab enum**

In the `AppTab` enum, add after HOME2 (or at a logical position):

```kotlin
ANALYSIS("analysis", "기업분석", R.drawable.ic_tab_home),
```

Note: Uses `ic_tab_home` as placeholder icon. A dedicated icon can be added later.

- [ ] **Step 2: Add composable route in NavHost**

Inside the `NavHost` block, add:

```kotlin
composable(AppTab.ANALYSIS.route) {
    CompanyAnalysisScreen(
        onCompanyClick = { ticker, name ->
            nav.navigate("company_detail/$ticker/$name")
        },
    )
}
composable("company_detail/{ticker}/{name}") { backStack ->
    val ticker = backStack.arguments?.getString("ticker") ?: return@composable
    val cName = backStack.arguments?.getString("name") ?: ""
    CompanyDetailScreen(
        ticker = ticker,
        name = cName,
        onBack = { nav.popBackStack() },
        onBuy = { t -> StockDetailActivity.open(context, t, cName, "analysis", emptyList()) },
        onAddWatchlist = { /* TODO: call watchlist API */ },
    )
}
```

- [ ] **Step 3: Add import**

At the top of AppNavigation.kt, add:

```kotlin
import com.example.stock.ui.screens.CompanyAnalysisScreen
import com.example.stock.ui.screens.CompanyDetailScreen
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/stock/navigation/AppNavigation.kt
git commit -m "feat(analysis): add ANALYSIS tab and company_detail route to navigation"
```

---

## Task 13: Build Verification

- [ ] **Step 1: Verify Android build compiles**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd ~/AndroidStudioProjects/stock
./gradlew assembleDebug 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Fix any compilation errors**

If the build fails, check:
- Missing imports in modified files
- Type mismatches between DTOs and ViewModel
- AppViewModelFactory `create()` method includes `CompanyAnalysisViewModel`

- [ ] **Step 3: Verify Python syntax**

```bash
python3 -c "
import ast
for f in ['backend/app/dart_financial_client.py', 'backend/app/analysis_service.py', 'backend/app/analysis_text_generator.py']:
    ast.parse(open(f).read())
    print(f'{f}: OK')
"
```

Expected: All 3 files print OK

- [ ] **Step 4: Commit if fixes were needed**

```bash
git add -A
git commit -m "fix(analysis): resolve build errors from initial implementation"
```

---

## Task 14: Server Deployment & API Verification

- [ ] **Step 1: Deploy server code via rsync**

```bash
rsync -avz \
  --exclude='__pycache__' --exclude='*.pyc' \
  --exclude='*.db' --exclude='*.db-*' \
  --exclude='.venv' --exclude='results/' --exclude='.localdata/' \
  -e "ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem" \
  backend/app/ ubuntu@16.176.148.77:/home/ubuntu/stock/backend/app/
```

- [ ] **Step 2: Restart server**

```bash
ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem ubuntu@16.176.148.77 \
  'sudo systemctl restart stock-backend.service && sleep 2 && sudo systemctl is-active stock-backend.service'
```

Expected: `active`

- [ ] **Step 3: Verify API responds**

```bash
ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem ubuntu@16.176.148.77 \
  'curl -s http://localhost:8000/analysis/companies?page=1&size=5 | python3 -m json.tool | head -30'
```

Expected: JSON response with `companies`, `curation`, `totalCount` fields (may be empty if no data loaded yet)

- [ ] **Step 4: Deploy APK**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd ~/AndroidStudioProjects/stock
bash scripts/publish_apk_ec2.sh
```

- [ ] **Step 5: Commit deployment**

```bash
git add -A
git commit -m "deploy: company analysis MVP — server + APK V3_xxx"
```

---

Plan complete and saved to `docs/superpowers/plans/2026-03-30-company-analysis.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?