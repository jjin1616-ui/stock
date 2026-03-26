from __future__ import annotations

import logging
import os
import json
from glob import glob
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import pandas as pd

try:
    import FinanceDataReader as fdr
except Exception:  # pragma: no cover
    fdr = None

try:
    from pykrx import stock as krx_stock
except Exception:  # pragma: no cover
    krx_stock = None

logger = logging.getLogger("stock.universe")


def _data_dir() -> str:
    return os.getenv("STOCK_DATA_DIR") or os.getenv("DATA_DIR") or "/var/lib/stock-backend"


def _to_float(v: object) -> float:
    if v is None:
        return 0.0
    if isinstance(v, (int, float)):
        return float(v)
    s = str(v).replace(",", "").strip()
    if not s or s == "-":
        return 0.0
    try:
        return float(s)
    except Exception:
        return 0.0


def _load_universe_krx_cache() -> pd.DataFrame:
    cache_dir = os.path.join(_data_dir(), "krx_cache")
    rows: list[dict[str, object]] = []
    for market in ("KOSPI", "KOSDAQ"):
        files = sorted(glob(os.path.join(cache_dir, market, "*.json")), reverse=True)
        for path in files[:120]:
            try:
                with open(path, "r", encoding="utf-8") as f:
                    payload = json.load(f)
                items = payload.get("OutBlock_1", []) if isinstance(payload, dict) else []
                if not isinstance(items, list) or not items:
                    continue
                for it in items:
                    code = str(it.get("ISU_CD") or "").strip().zfill(6)
                    name = str(it.get("ISU_NM") or "").strip()
                    market_nm = str(it.get("MKT_NM") or market).strip().upper()
                    if not (len(code) == 6 and code.isdigit() and name):
                        continue
                    if market_nm not in ("KOSPI", "KOSDAQ"):
                        market_nm = market
                    rows.append(
                        {
                            "Code": code,
                            "Name": name,
                            "Market": market_nm,
                            "Marcap": _to_float(it.get("MKTCAP")),
                        }
                    )
                if rows:
                    break
            except Exception:
                logger.exception("failed reading krx cache file: %s", path)
    if not rows:
        return pd.DataFrame(columns=["Code", "Name", "Market"])
    df = pd.DataFrame(rows)
    return df.drop_duplicates(subset=["Code"]).reset_index(drop=True)


def load_universe() -> pd.DataFrame:
    # Prefer KRX Data API if a key is available (more reliable than web-scraping in headless/cloud envs).
    if os.getenv("KRX_API_KEY"):
        try:
            df = _load_universe_krx_api()
            if df is not None and not df.empty:
                return df
        except Exception:
            logger.exception("load_universe (krx api) failed; fallback to fdr/pykrx")

    if fdr is None:
        if krx_stock is None:
            logger.warning("FinanceDataReader/pykrx unavailable; fallback to KRX cache")
            return _load_universe_krx_cache()
        return _load_universe_pykrx()
    try:
        df = fdr.StockListing("KRX")
        if df is None or df.empty:
            if krx_stock is None:
                return pd.DataFrame(columns=["Code", "Name", "Market"])
            return _load_universe_pykrx()
        if "Code" not in df.columns and "Symbol" in df.columns:
            df = df.rename(columns={"Symbol": "Code"})
        cols = [c for c in ["Code", "Name", "Market", "Marcap"] if c in df.columns]
        if "Code" not in cols or "Name" not in cols or "Market" not in cols:
            return pd.DataFrame(columns=["Code", "Name", "Market"])
        return df[cols].copy()
    except Exception:
        logger.exception("load_universe failed")
        if krx_stock is None:
            cached = _load_universe_krx_cache()
            if not cached.empty:
                return cached
            return pd.DataFrame(columns=["Code", "Name", "Market"])
        df_pykrx = _load_universe_pykrx()
        if df_pykrx is not None and not df_pykrx.empty:
            return df_pykrx
        cached = _load_universe_krx_cache()
        if not cached.empty:
            return cached
        return pd.DataFrame(columns=["Code", "Name", "Market"])

def _load_universe_krx_api() -> pd.DataFrame:
    from .krx_api import KrxApiConfig, fetch_daily_market

    api_key = os.getenv("KRX_API_KEY", "").strip()
    if not api_key:
        return pd.DataFrame(columns=["Code", "Name", "Market"])

    cfg = KrxApiConfig(
        api_key=api_key,
        endpoint_kospi=os.getenv("KRX_ENDPOINT_KOSPI", "stk_bydd_trd"),
        endpoint_kosdaq=os.getenv("KRX_ENDPOINT_KOSDAQ", "ksq_bydd_trd"),
        endpoint_konex=os.getenv("KRX_ENDPOINT_KONEX", "knx_bydd_trd"),
        cache_dir=os.path.join(_data_dir(), "krx_cache"),
    )
    seoul = ZoneInfo(os.getenv("APP_TZ", "Asia/Seoul"))
    today = datetime.now(tz=seoul).date()

    bas_dd = None
    for i in range(1, 21):
        cand = (today - timedelta(days=i)).strftime("%Y%m%d")
        df_try = fetch_daily_market(cfg, cand, "KOSPI")
        if df_try is not None and not df_try.empty:
            bas_dd = cand
            break
    if bas_dd is None:
        return pd.DataFrame(columns=["Code", "Name", "Market"])

    frames = []
    for market in ("KOSPI", "KOSDAQ"):
        dfm = fetch_daily_market(cfg, bas_dd, market)
        if dfm is None or dfm.empty:
            continue
        frames.append(dfm)
    if not frames:
        return pd.DataFrame(columns=["Code", "Name", "Market"])

    base = pd.concat(frames, ignore_index=True)
    out = pd.DataFrame(
        {
            "Code": base["ISU_CD"].astype(str).str.zfill(6),
            "Name": base.get("ISU_NM", "").astype(str),
            "Market": base.get("MKT_NM", "").astype(str),
            "Marcap": base.get("MKTCAP", 0.0).astype(float),
        }
    )
    out = out[out["Code"].str.match(r"^\d{6}$", na=False)].copy()
    out = out[out["Market"].isin(["KOSPI", "KOSDAQ"])].copy()
    return out.drop_duplicates(subset=["Code"]).reset_index(drop=True)


def _load_universe_pykrx() -> pd.DataFrame:
    if krx_stock is None:
        return pd.DataFrame(columns=["Code", "Name", "Market"])
    from datetime import datetime, timedelta

    def _try_date(d):
        ds = d.strftime("%Y%m%d")
        rows = []
        for market in ("KOSPI", "KOSDAQ"):
            try:
                codes = krx_stock.get_market_ticker_list(ds, market=market)
            except Exception:
                codes = []
            for c in codes:
                try:
                    name = krx_stock.get_market_ticker_name(c)
                except Exception:
                    name = c
                rows.append({"Code": str(c).zfill(6), "Name": name, "Market": market})
        return rows

    today = datetime.now()
    rows = []
    for i in range(7):
        rows = _try_date(today - timedelta(days=i))
        if rows:
            break
    if not rows:
        return pd.DataFrame(columns=["Code", "Name", "Market"])
    return pd.DataFrame(rows)


def filter_universe(df_uni: pd.DataFrame) -> pd.DataFrame:
    df = df_uni.copy()
    if "Code" not in df.columns:
        raise ValueError("universe missing Code")
    if "Market" not in df.columns:
        df["Market"] = "KOSDAQ"
    df = df[df["Market"].isin(["KOSPI", "KOSDAQ"])].copy()
    df["Code"] = df["Code"].astype(str).str.zfill(6)
    return df.drop_duplicates(subset=["Code"]).reset_index(drop=True)
