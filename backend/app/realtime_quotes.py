from __future__ import annotations

from dataclasses import dataclass
from concurrent.futures import ThreadPoolExecutor, as_completed
import logging
import os
from datetime import datetime, timedelta
from threading import Lock
from zoneinfo import ZoneInfo
import re

import requests

from app.config import settings

SEOUL = ZoneInfo(settings.app_tz)


@dataclass
class QuoteItem:
    ticker: str
    price: float
    prev_close: float
    chg_pct: float
    volume: float
    value: float
    as_of: datetime
    source: str
    is_live: bool
    market_state: str | None = None
    over_status: str | None = None
    over_price: float | None = None
    over_volume: float | None = None
    over_value: float | None = None
    over_as_of: datetime | None = None


@dataclass
class _Cached:
    expires_at: datetime
    quote: QuoteItem


_cache: dict[str, _Cached] = {}
_lock = Lock()
_TTL_SECONDS = 15
_BATCH_CHUNK = max(30, min(240, int(os.getenv("NAVER_BATCH_CHUNK", "150"))))
_BATCH_WORKERS = max(1, min(8, int(os.getenv("NAVER_BATCH_WORKERS", "4"))))
_SINGLE_FALLBACK_WORKERS = max(1, min(12, int(os.getenv("NAVER_SINGLE_WORKERS", "8"))))
_FDR_FALLBACK_WORKERS = max(1, min(8, int(os.getenv("FDR_FALLBACK_WORKERS", "4"))))
_NAVER_TIMEOUT_FULL = max(0.3, min(3.0, float(os.getenv("NAVER_TIMEOUT_FULL_SECONDS", "0.9"))))
_NAVER_TIMEOUT_LIGHT = max(0.2, min(2.0, float(os.getenv("NAVER_TIMEOUT_LIGHT_SECONDS", "0.45"))))
logger = logging.getLogger("stock.realtime")


def _to_float(v: object) -> float:
    try:
        if isinstance(v, str):
            s = v.strip().replace(",", "").replace(" ", "")
            if not s:
                return 0.0
            multiplier = 1.0
            # Naver over-market payload may include Korean unit suffix
            # (e.g., "791,295백만"), so normalize to KRW numeric.
            if s.endswith("백만"):
                s = s[:-2]
                multiplier = 1_000_000.0
            elif s.endswith("억"):
                s = s[:-1]
                multiplier = 100_000_000.0
            elif s.endswith("만"):
                s = s[:-1]
                multiplier = 10_000.0
            s = s.replace("원", "").replace("주", "").replace("%", "")
            if not s:
                return 0.0
            return float(s) * multiplier
        return float(v)
    except Exception:
        return 0.0


def _to_dt(v: object) -> datetime | None:
    if v is None:
        return None
    s = str(v).strip()
    if not s:
        return None
    try:
        return datetime.fromisoformat(s)
    except Exception:
        return None


def _fetch_naver_quote(ticker: str, *, timeout_sec: float = _NAVER_TIMEOUT_FULL) -> QuoteItem | None:
    url = "https://polling.finance.naver.com/api/realtime"
    query = f"SERVICE_ITEM:{ticker}|SERVICE_RECENT_ITEM:{ticker}"
    r = requests.get(url, params={"query": query}, timeout=timeout_sec)
    r.raise_for_status()
    body = r.json()
    areas = body.get("result", {}).get("areas", [])
    datas = areas[0].get("datas", []) if areas else []
    if not datas:
        return None
    d = datas[0]
    price = _to_float(d.get("nv"))
    diff = _to_float(d.get("cv"))
    pct = _to_float(d.get("cr"))
    prev_close = _to_float(d.get("sv"))
    volume = _to_float(d.get("aq"))
    value = _to_float(d.get("aa"))
    nxt = d.get("nxtOverMarketPriceInfo") or {}
    over_status = str(nxt.get("overMarketStatus") or "").strip()
    over_price = _to_float(nxt.get("overPrice"))
    over_volume = _to_float(nxt.get("accumulatedTradingVolume"))
    over_value = _to_float(nxt.get("accumulatedTradingValue"))
    over_as_of = _to_dt(nxt.get("localTradedAt"))
    if prev_close <= 0:
        # sv가 비어있을 때만 보조값으로 추정
        prev_close = price - diff
    if prev_close <= 0 and pct != 0:
        prev_close = price / (1 + pct / 100.0)
    if price <= 0 or prev_close <= 0:
        return None
    return QuoteItem(
        ticker=ticker,
        price=price,
        prev_close=prev_close,
        chg_pct=((price / prev_close) - 1.0) * 100.0,
        volume=volume,
        value=value,
        as_of=datetime.now(tz=SEOUL),
        source="NAVER_RT",
        is_live=True,
        market_state=str(d.get("ms") or ""),
        over_status=over_status or None,
        over_price=over_price if over_price > 0 else None,
        over_volume=over_volume if over_volume > 0 else None,
        over_value=over_value if over_value > 0 else None,
        over_as_of=over_as_of,
    )


def _norm_ticker(raw: object) -> str:
    """티커 정규화: 6자리 숫자 종목코드 또는 알파벳+숫자 지수코드(KPI200, KQ150 등) 허용."""
    s = str(raw or "").strip()
    if not s:
        return ""
    # 6자리 숫자 종목코드 (기존 동작 유지)
    m = re.search(r"(\d{6})", s)
    if m:
        return m.group(1)
    # 알파벳+숫자 혼합 지수코드 (예: KPI200, KQ150)
    m = re.search(r"([A-Za-z]+\d+)", s)
    if m:
        return m.group(1)
    return ""


def _fetch_naver_quotes_batch(
    tickers: list[str],
    *,
    timeout_sec: float = _NAVER_TIMEOUT_FULL,
) -> dict[str, QuoteItem]:
    if not tickers:
        return {}
    # Naver batch query: comma-separated codes in a single SERVICE_ITEM clause.
    # NOTE: `SERVICE_ITEM:005930|SERVICE_ITEM:000660` only returns the last item.
    codes = [t.strip() for t in tickers if t and t.strip()]
    query = "SERVICE_ITEM:" + ",".join(codes)
    r = requests.get("https://polling.finance.naver.com/api/realtime", params={"query": query}, timeout=timeout_sec)
    r.raise_for_status()
    body = r.json()
    areas = body.get("result", {}).get("areas", [])
    if not areas:
        return {}
    datas = areas[0].get("datas", []) or []
    out: dict[str, QuoteItem] = {}
    now = datetime.now(tz=SEOUL)
    for d in datas:
        ticker = _norm_ticker(d.get("cd") or d.get("itemCode") or d.get("nm"))
        if not ticker:
            continue
        price = _to_float(d.get("nv"))
        diff = _to_float(d.get("cv"))
        pct = _to_float(d.get("cr"))
        prev_close = _to_float(d.get("sv"))
        volume = _to_float(d.get("aq"))
        value = _to_float(d.get("aa"))
        nxt = d.get("nxtOverMarketPriceInfo") or {}
        over_status = str(nxt.get("overMarketStatus") or "").strip()
        over_price = _to_float(nxt.get("overPrice"))
        over_volume = _to_float(nxt.get("accumulatedTradingVolume"))
        over_value = _to_float(nxt.get("accumulatedTradingValue"))
        over_as_of = _to_dt(nxt.get("localTradedAt"))
        if prev_close <= 0:
            prev_close = price - diff
        if prev_close <= 0 and pct != 0:
            prev_close = price / (1 + pct / 100.0)
        if price <= 0 or prev_close <= 0:
            continue
        out[ticker] = QuoteItem(
            ticker=ticker,
            price=price,
            prev_close=prev_close,
            chg_pct=((price / prev_close) - 1.0) * 100.0,
            volume=volume,
            value=value,
            as_of=now,
            source="NAVER_RT",
            is_live=True,
            market_state=str(d.get("ms") or ""),
            over_status=over_status or None,
            over_price=over_price if over_price > 0 else None,
            over_volume=over_volume if over_volume > 0 else None,
            over_value=over_value if over_value > 0 else None,
            over_as_of=over_as_of,
        )
    return out


def _chunks(items: list[str], size: int) -> list[list[str]]:
    return [items[i : i + size] for i in range(0, len(items), size)]


def _normalize_mode(mode: str | None) -> str:
    return "light" if str(mode or "").strip().lower() == "light" else "full"


def _fetch_naver_single_parallel(
    tickers: list[str],
    *,
    timeout_sec: float,
    errors: list[str],
) -> dict[str, QuoteItem]:
    out: dict[str, QuoteItem] = {}
    if not tickers:
        return out
    max_workers = min(_SINGLE_FALLBACK_WORKERS, len(tickers))
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futs = {pool.submit(_fetch_naver_quote, t, timeout_sec=timeout_sec): t for t in tickers}
        for fut in as_completed(futs):
            ticker = futs[fut]
            try:
                q = fut.result()
            except Exception as exc:
                errors.append(f"naver_single:{exc}")
                continue
            if q is not None:
                out[ticker] = q
    return out


def _fetch_fdr_parallel(tickers: list[str], *, errors: list[str]) -> dict[str, QuoteItem]:
    out: dict[str, QuoteItem] = {}
    if not tickers:
        return out
    max_workers = min(_FDR_FALLBACK_WORKERS, len(tickers))
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futs = {pool.submit(_fetch_fdr_fallback, t): t for t in tickers}
        for fut in as_completed(futs):
            ticker = futs[fut]
            try:
                q = fut.result()
            except Exception as exc:
                errors.append(f"fdr:{exc}")
                continue
            if q is not None:
                out[ticker] = q
    return out


def _fetch_fdr_fallback(ticker: str) -> QuoteItem | None:
    try:
        import FinanceDataReader as fdr  # type: ignore
    except Exception:
        return None

    today = datetime.now(tz=SEOUL).date()
    start = today - timedelta(days=10)
    df = fdr.DataReader(ticker, start, today)
    if df is None or df.empty:
        return None
    hist = df.dropna()
    if len(hist) < 2:
        return None
    price = float(hist.iloc[-1]["Close"])
    prev_close = float(hist.iloc[-2]["Close"])
    return QuoteItem(
        ticker=ticker,
        price=price,
        prev_close=prev_close,
        chg_pct=((price / prev_close) - 1.0) * 100.0,
        volume=0.0,
        value=0.0,
        as_of=datetime.now(tz=SEOUL),
        source="FDR_DAILY",
        is_live=False,
        market_state="FDR_DAILY",
    )


def _get_cached(ticker: str) -> QuoteItem | None:
    now = datetime.now(tz=SEOUL)
    with _lock:
        cached = _cache.get(ticker)
        if cached and cached.expires_at > now:
            return cached.quote
    return None


def _get_cached_any(ticker: str) -> QuoteItem | None:
    with _lock:
        cached = _cache.get(ticker)
        return cached.quote if cached else None


def _set_cached(ticker: str, quote: QuoteItem) -> None:
    with _lock:
        _cache[ticker] = _Cached(expires_at=datetime.now(tz=SEOUL) + timedelta(seconds=_TTL_SECONDS), quote=quote)


def fetch_quotes(tickers: list[str], mode: str = "full") -> list[QuoteItem]:
    mode_norm = _normalize_mode(mode)
    timeout_sec = _NAVER_TIMEOUT_LIGHT if mode_norm == "light" else _NAVER_TIMEOUT_FULL
    out: list[QuoteItem] = []
    seen: set[str] = set()
    pending: list[str] = []
    errors: list[str] = []

    for ticker in tickers:
        t = ticker.strip()
        if not t or t in seen:
            continue
        seen.add(t)
        cached = _get_cached(t)
        if cached:
            out.append(cached)
        else:
            pending.append(t)

    # batch first (latency 절감)
    batch_found: dict[str, QuoteItem] = {}
    chunks = _chunks(pending, _BATCH_CHUNK)
    if len(chunks) <= 1:
        for chunk in chunks:
            try:
                batch_found.update(_fetch_naver_quotes_batch(chunk, timeout_sec=timeout_sec))
            except Exception as exc:
                errors.append(f"naver_batch:{exc}")
    else:
        max_workers = min(_BATCH_WORKERS, len(chunks))
        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            futs = {
                pool.submit(_fetch_naver_quotes_batch, chunk, timeout_sec=timeout_sec): chunk
                for chunk in chunks
            }
            for fut in as_completed(futs):
                try:
                    batch_found.update(fut.result())
                except Exception as exc:
                    errors.append(f"naver_batch:{exc}")

    missing: list[str] = []
    for t in pending:
        q = batch_found.get(t)
        if q is not None:
            _set_cached(t, q)
            out.append(q)
        else:
            missing.append(t)

    single_found = _fetch_naver_single_parallel(missing, timeout_sec=timeout_sec, errors=errors)
    for t in missing[:]:
        q = single_found.get(t)
        if q is not None:
            _set_cached(t, q)
            out.append(q)
            missing.remove(t)

    # daily fallback (accuracy lower, but better than empty).
    # Skip in light mode to keep latency short.
    if mode_norm != "light":
        fdr_found = _fetch_fdr_parallel(missing, errors=errors)
        for t in missing[:]:
            q = fdr_found.get(t)
            if q is not None:
                _set_cached(t, q)
                out.append(q)
                missing.remove(t)

    # stale cache fallback
    for t in missing:
        stale = _get_cached_any(t)
        if stale is not None:
            out.append(stale)

    if errors:
        logger.warning("realtime quote batch errors=%d sample=%s", len(errors), errors[:2])

    return out
