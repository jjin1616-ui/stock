from __future__ import annotations

import logging
import os
import re
from dataclasses import dataclass
from datetime import datetime, timedelta
from threading import Lock, Thread
from zoneinfo import ZoneInfo

import pandas as pd
import requests

from app.config import settings
from app.realtime_quotes import fetch_quotes
from engine.krx_api import KrxApiConfig, fetch_daily_market

logger = logging.getLogger("stock.movers")
SEOUL = ZoneInfo(settings.app_tz)
NAVER_POPULAR_URL = "https://finance.naver.com/sise/lastsearch2.naver"
PERIOD_TO_DAYS: dict[str, int] = {
    "1d": 1,
    "1w": 5,
    "1m": 20,
    "3m": 60,
    "6m": 120,
    "1y": 240,
}


@dataclass
class _CacheEntry:
    expires_at: datetime
    stale_expires_at: datetime
    payload: dict


_cache: dict[str, _CacheEntry] = {}
_refreshing: set[str] = set()
_lock = Lock()
_TTL_SECONDS = 8
_STALE_TTL_SECONDS = max(_TTL_SECONDS + 2, min(120, int(os.getenv("MOVERS_STALE_TTL_SECONDS", "30"))))


def _store_cache(cache_key: str, payload: dict, *, now_ts: datetime | None = None) -> None:
    ts = now_ts or datetime.now(tz=SEOUL)
    with _lock:
        _cache[cache_key] = _CacheEntry(
            expires_at=ts + timedelta(seconds=_TTL_SECONDS),
            stale_expires_at=ts + timedelta(seconds=_STALE_TTL_SECONDS),
            payload=payload,
        )


def _to_float(v: object) -> float:
    try:
        return float(v)
    except Exception:
        return 0.0


def _naver_logo_urls(ticker: str) -> tuple[str | None, str | None]:
    code = str(ticker or "").strip()
    if not re.fullmatch(r"\d{6}", code):
        return None, None
    return (
        f"https://ssl.pstatic.net/imgstock/fn/real/logo/stock/Stock{code}.svg",
        f"https://ssl.pstatic.net/imgstock/fn/real/logo/png/stock/Stock{code}.png",
    )


def _norm_period(period: str | None) -> str:
    p = (period or "1d").strip().lower()
    return p if p in PERIOD_TO_DAYS else "1d"


def _cfg() -> KrxApiConfig:
    return KrxApiConfig(
        api_key=settings.krx_api_key,
        endpoint_kospi=settings.krx_endpoint_kospi,
        endpoint_kosdaq=settings.krx_endpoint_kosdaq,
        endpoint_konex=settings.krx_endpoint_konex,
        cache_dir=f"{settings.data_dir}/krx_cache",
    )


def _most_recent_bas_dd(cfg: KrxApiConfig, *, lookback_days: int = 20) -> str:
    """
    Find the most recent basDd that has *non-empty* KOSPI data.

    KRX daily endpoints can return empty for the current day before EOD.
    """

    today = datetime.now(tz=SEOUL).date()
    # For movers we intentionally avoid querying the current day (KRX daily endpoints are often empty/unauthorized
    # before EOD). Start from "yesterday" and walk back until a non-empty day is found.
    for i in range(1, max(2, lookback_days) + 1):
        d = today - timedelta(days=i)
        bas_dd = d.strftime("%Y%m%d")
        df = fetch_daily_market(cfg, bas_dd, "KOSPI")
        if df is not None and not df.empty:
            return bas_dd
    return today.strftime("%Y%m%d")


def _estimate_bas_dd_n_trading_days_ago(trading_days: int) -> str:
    n = max(1, int(trading_days))
    d = datetime.now(tz=SEOUL).date()
    seen = 0
    while seen < n:
        d -= timedelta(days=1)
        if d.weekday() < 5:
            seen += 1
    return d.strftime("%Y%m%d")


def _resolve_bas_dd_for_period(cfg: KrxApiConfig, period: str) -> str:
    days = PERIOD_TO_DAYS.get(period, 1)
    if days <= 1:
        return ""
    probe = datetime.strptime(_estimate_bas_dd_n_trading_days_ago(days), "%Y%m%d").date()
    for _ in range(20):
        bas_dd = probe.strftime("%Y%m%d")
        df = fetch_daily_market(cfg, bas_dd, "KOSPI")
        if df is not None and not df.empty:
            return bas_dd
        probe -= timedelta(days=1)
    return ""


def _fetch_market_frames(cfg: KrxApiConfig, bas_dd: str, markets: list[str]) -> list[pd.DataFrame]:
    frames: list[pd.DataFrame] = []
    for m in markets:
        try:
            df = fetch_daily_market(cfg, bas_dd, m)  # cached on disk per basDd/market
        except Exception:
            logger.exception("movers: krx fetch failed market=%s bas_dd=%s", m, bas_dd)
            df = pd.DataFrame()
        if df is None or df.empty:
            continue
        frames.append(df)
    return frames


def _close_map_for_bas_dd(cfg: KrxApiConfig, bas_dd: str, markets: list[str]) -> dict[str, float]:
    if not bas_dd:
        return {}
    frames = _fetch_market_frames(cfg, bas_dd, markets)
    if not frames:
        return {}
    base = pd.concat(frames, ignore_index=True)
    if base.empty:
        return {}
    base["ticker"] = base["ISU_CD"].astype(str).str.zfill(6)
    base["close_ref"] = base.get("TDD_CLSPRC", 0.0).astype(float)
    base = base[base["ticker"].str.match(r"^\d{6}$", na=False)]
    return {
        str(r.ticker): float(r.close_ref)
        for r in base[["ticker", "close_ref"]].itertuples(index=False)
        if float(r.close_ref or 0.0) > 0.0
    }


def _fetch_naver_popular_rows(limit: int) -> list[dict]:
    out: list[dict] = []
    seen: set[str] = set()
    try:
        resp = requests.get(NAVER_POPULAR_URL, timeout=3.0)
        resp.raise_for_status()
        text = resp.content.decode("euc-kr", errors="ignore")
    except Exception:
        logger.exception("movers: failed to fetch naver popular list")
        return out

    row_re = re.compile(
        r"<tr>\s*"
        r"<td class=\"no\">(?P<rank>\d+)</td>\s*"
        r"<td><a href=\"/item/main\.naver\?code=(?P<ticker>[0-9A-Za-z]+)\" class=\"tltle\">(?P<name>[^<]+)</a></td>\s*"
        r"<td class=\"number\">(?P<ratio>[0-9.]+)%</td>",
        re.IGNORECASE | re.DOTALL,
    )
    for m in row_re.finditer(text):
        ticker = str(m.group("ticker") or "").strip()
        if not re.fullmatch(r"\d{6}", ticker):
            continue
        if ticker in seen:
            continue
        seen.add(ticker)
        out.append(
            {
                "ticker": ticker,
                "name": str(m.group("name") or "").strip(),
                "rank": int(m.group("rank") or 0),
                "search_ratio": _to_float(m.group("ratio")),
            }
        )
        if len(out) >= max(10, limit):
            break
    return out


def _compute_popular_movers(*, count: int, period: str) -> dict:
    now_ts = datetime.now(tz=SEOUL)
    rows = _fetch_naver_popular_rows(limit=max(count, 30))
    if not rows:
        return {"as_of": now_ts.isoformat(), "bas_dd": "", "ref_bas_dd": "", "mode": "popular", "period": period, "items": []}

    tickers = [r["ticker"] for r in rows]
    qmap = {q.ticker: q for q in fetch_quotes(tickers)}
    items: list[dict] = []
    for r in rows:
        q = qmap.get(str(r["ticker"]))
        if q is None:
            continue
        logo_url, logo_png_url = _naver_logo_urls(str(r["ticker"]))
        price = _to_float(getattr(q, "price", 0.0))
        prev_close = _to_float(getattr(q, "prev_close", 0.0))
        if price <= 0 or prev_close <= 0:
            continue
        chg_pct = _to_float(getattr(q, "chg_pct", 0.0))
        vol = _to_float(getattr(q, "volume", 0.0))
        val = _to_float(getattr(q, "value", 0.0))
        items.append(
            {
                "ticker": str(r["ticker"]),
                "name": str(r.get("name") or ""),
                "market": "",
                "logo_url": logo_url,
                "logo_png_url": logo_png_url,
                "rank": int(r.get("rank") or 0),
                "search_ratio": _to_float(r.get("search_ratio")),
                "price": price,
                "prev_close": prev_close,
                "chg_pct": chg_pct,
                "volume": vol,
                "value": val,
                "baseline_value": None,
                "value_ratio": None,
                "as_of": getattr(q, "as_of", now_ts).isoformat() if getattr(q, "as_of", None) else now_ts.isoformat(),
                "source": getattr(q, "source", "") or "",
                "is_live": bool(getattr(q, "is_live", False)),
            }
        )
    items.sort(key=lambda x: int(x.get("rank") or 9999))
    return {
        "as_of": now_ts.isoformat(),
        "bas_dd": "",
        "ref_bas_dd": "",
        "mode": "popular",
        "period": period,
        "items": items[:count],
    }


def compute_movers(
    *,
    mode: str = "chg",
    count: int = 100,
    pool_size: int = 120,
    markets: list[str] | None = None,
    period: str = "1d",
    _bypass_cache: bool = False,
) -> dict:
    """
    "급등주" (real-time movers) over a liquid candidate pool.

    Candidate pool is derived from the most recent KRX daily market data (by trading value),
    then refreshed with real-time quotes from Naver polling API.
    """

    mode_norm = (mode or "chg").strip().lower()
    if mode_norm not in ("chg", "chg_down", "value", "volume", "value_ratio", "popular"):
        mode_norm = "chg"
    period_norm = _norm_period(period)
    count = max(5, min(100, int(count)))
    pool_size = max(30, min(400, int(pool_size)))
    mkts = markets or ["KOSPI", "KOSDAQ"]
    mkts = [m.strip().upper() for m in mkts if m and m.strip()]
    if not mkts:
        mkts = ["KOSPI", "KOSDAQ"]

    # Cache key (short TTL) to avoid hammering external endpoints.
    cache_key = f"movers:{mode_norm}:{period_norm}:{count}:{pool_size}:{','.join(mkts)}"
    now_ts = datetime.now(tz=SEOUL)
    if not _bypass_cache:
        stale_payload: dict | None = None
        should_refresh = False
        with _lock:
            hit = _cache.get(cache_key)
            if hit and hit.expires_at > now_ts:
                return hit.payload
            if hit and hit.stale_expires_at > now_ts:
                stale_payload = hit.payload
                if cache_key not in _refreshing:
                    _refreshing.add(cache_key)
                    should_refresh = True
        if stale_payload is not None:
            if should_refresh:
                def _refresh() -> None:
                    try:
                        compute_movers(
                            mode=mode_norm,
                            count=count,
                            pool_size=pool_size,
                            markets=mkts,
                            period=period_norm,
                            _bypass_cache=True,
                        )
                    except Exception:
                        logger.exception("movers: stale refresh failed key=%s", cache_key)
                    finally:
                        with _lock:
                            _refreshing.discard(cache_key)
                Thread(target=_refresh, name=f"movers-refresh-{mode_norm}", daemon=True).start()
            return stale_payload

    if mode_norm == "popular":
        payload = _compute_popular_movers(count=count, period=period_norm)
        _store_cache(cache_key, payload, now_ts=now_ts)
        return payload

    if not settings.krx_api_key:
        payload = {
            "as_of": now_ts.isoformat(),
            "bas_dd": "",
            "ref_bas_dd": "",
            "mode": mode_norm,
            "period": period_norm,
            "items": [],
        }
        _store_cache(cache_key, payload, now_ts=now_ts)
        return payload

    cfg = _cfg()
    bas_dd = _most_recent_bas_dd(cfg)
    ref_bas_dd = _resolve_bas_dd_for_period(cfg, period_norm) if mode_norm in ("chg", "chg_down") else ""
    ref_close_map = _close_map_for_bas_dd(cfg, ref_bas_dd, mkts) if ref_bas_dd else {}
    frames = _fetch_market_frames(cfg, bas_dd, mkts)

    if not frames:
        payload = {"as_of": now_ts.isoformat(), "bas_dd": bas_dd, "ref_bas_dd": ref_bas_dd, "mode": mode_norm, "period": period_norm, "items": []}
        _store_cache(cache_key, payload, now_ts=now_ts)
        return payload

    base = pd.concat(frames, ignore_index=True)
    base["ticker"] = base["ISU_CD"].astype(str).str.zfill(6)
    base["baseline_value"] = base.get("ACC_TRDVAL", 0.0).astype(float)
    base["baseline_vol"] = base.get("ACC_TRDVOL", 0.0).astype(float)
    base["name"] = base.get("ISU_NM", "").astype(str)
    base["market"] = base.get("MKT_NM", "").astype(str)
    base = base[base["ticker"].str.match(r"^\d{6}$", na=False)]

    # Build a liquid candidate pool using previous trading day's trading value.
    base = base.sort_values("baseline_value", ascending=False).head(pool_size).reset_index(drop=True)
    tickers = base["ticker"].tolist()

    quotes = fetch_quotes(tickers)
    qmap = {q.ticker: q for q in quotes}

    items: list[dict] = []
    for row in base.itertuples(index=False):
        ticker = getattr(row, "ticker")
        q = qmap.get(ticker)
        if q is None:
            continue
        logo_url, logo_png_url = _naver_logo_urls(str(ticker))
        price = _to_float(getattr(q, "price", 0.0))
        prev_close = _to_float(getattr(q, "prev_close", 0.0))
        if price <= 0 or prev_close <= 0:
            continue
        chg_pct = _to_float(getattr(q, "chg_pct", 0.0))
        ref_close = _to_float(ref_close_map.get(ticker))
        if mode_norm in ("chg", "chg_down") and ref_close > 0:
            chg_pct = ((price / ref_close) - 1.0) * 100.0

        if mode_norm == "chg":
            # Enforce a minimum threshold so +0~1% doesn't appear as "급등".
            # If the threshold is set too high, the endpoint may return fewer than `count`.
            min_pct = _to_float(getattr(settings, "movers_min_chg_pct", 0.0))
            if min_pct > 0 and chg_pct < min_pct:
                continue
        if mode_norm == "chg_down":
            min_pct = _to_float(getattr(settings, "movers_min_chg_pct", 0.0))
            if min_pct > 0 and chg_pct > -min_pct:
                continue
        vol = _to_float(getattr(q, "volume", 0.0))
        val = _to_float(getattr(q, "value", 0.0))
        baseline_val = _to_float(getattr(row, "baseline_value", 0.0))
        ratio = (val / baseline_val) if (baseline_val > 0 and val > 0) else None

        items.append(
            {
                "ticker": ticker,
                "name": getattr(row, "name", "") or "",
                "market": getattr(row, "market", "") or "",
                "logo_url": logo_url,
                "logo_png_url": logo_png_url,
                "rank": None,
                "search_ratio": None,
                "price": price,
                "prev_close": prev_close,
                "chg_pct": chg_pct,
                "volume": vol,
                "value": val,
                "baseline_value": baseline_val if baseline_val > 0 else None,
                "value_ratio": ratio,
                # Quote meta so clients can show NAVER_RT / live badges without extra API calls.
                "as_of": getattr(q, "as_of", now_ts).isoformat() if getattr(q, "as_of", None) else now_ts.isoformat(),
                "source": getattr(q, "source", "") or "",
                "is_live": bool(getattr(q, "is_live", False)),
            }
        )

    def _key(x: dict) -> float:
        if mode_norm == "value":
            return float(x.get("value") or 0.0)
        if mode_norm == "volume":
            return float(x.get("volume") or 0.0)
        if mode_norm == "value_ratio":
            v = x.get("value_ratio")
            return float(v) if v is not None else float("-inf")
        return float(x.get("chg_pct") or 0.0)

    items.sort(key=_key, reverse=(mode_norm != "chg_down"))
    payload = {
        "as_of": datetime.now(tz=SEOUL).isoformat(),
        "bas_dd": bas_dd,
        "ref_bas_dd": ref_bas_dd,
        "mode": mode_norm,
        "period": period_norm,
        "items": items[:count],
    }

    _store_cache(cache_key, payload)
    return payload
