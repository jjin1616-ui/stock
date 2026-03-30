from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import datetime, timezone

import requests

from app.config import settings

logger = logging.getLogger(__name__)

REPRT_ANNUAL = "11011"
REPRT_HALF = "11012"
REPRT_Q1 = "11013"
REPRT_Q3 = "11014"

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
    key = (settings.opendart_api_key or "").strip()
    if not key:
        logger.warning("OPENDART_API_KEY not set")
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
    key = (settings.opendart_api_key or "").strip()
    if not key:
        return None

    url = f"{DART_BASE}/fnlttSinglAcnt.json"
    params = {
        "crtfc_key": key,
        "corp_code": corp_code,
        "bsns_year": bsns_year,
        "reprt_code": reprt_code,
        "fs_div": "CFS",
    }

    try:
        resp = requests.get(url, params=params, timeout=15)
        data = resp.json()
    except Exception:
        logger.exception("DART fnlttSinglAcnt failed for %s/%s/%s", corp_code, bsns_year, reprt_code)
        return None

    if str(data.get("status")) != "000":
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
