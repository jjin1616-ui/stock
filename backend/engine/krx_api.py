from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
import json
import logging
import os
from typing import Literal

import pandas as pd
import requests

logger = logging.getLogger("stock.krx_api")

Market = Literal["KOSPI", "KOSDAQ", "KONEX"]


@dataclass
class KrxApiConfig:
    api_key: str
    base_url: str = "https://data-dbg.krx.co.kr/svc/apis/sto"
    endpoint_kospi: str = "stk_bydd_trd"
    endpoint_kosdaq: str = "ksq_bydd_trd"
    endpoint_konex: str = "knx_bydd_trd"
    cache_dir: str = "/var/lib/stock-backend/krx_cache"


def _endpoint_for_market(cfg: KrxApiConfig, market: Market) -> str:
    if market == "KOSPI":
        return cfg.endpoint_kospi
    if market == "KOSDAQ":
        return cfg.endpoint_kosdaq
    return cfg.endpoint_konex


def _cache_path(cfg: KrxApiConfig, market: Market, bas_dd: str) -> str:
    return os.path.join(cfg.cache_dir, market, f"{bas_dd}.json")


def _load_cache(cfg: KrxApiConfig, market: Market, bas_dd: str) -> list[dict]:
    path = _cache_path(cfg, market, bas_dd)
    if not os.path.exists(path):
        return []
    try:
        with open(path, "r", encoding="utf-8") as f:
            payload = json.load(f)
        return payload.get("OutBlock_1", []) if isinstance(payload, dict) else []
    except Exception:
        logger.exception("failed to read cache %s", path)
        return []


def _save_cache(cfg: KrxApiConfig, market: Market, bas_dd: str, payload: dict) -> None:
    path = _cache_path(cfg, market, bas_dd)
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
    except Exception:
        logger.exception("failed to save cache %s", path)


def fetch_daily_market(cfg: KrxApiConfig, bas_dd: str, market: Market) -> pd.DataFrame:
    cached = _load_cache(cfg, market, bas_dd)
    if cached:
        return _to_df(cached)

    endpoint = _endpoint_for_market(cfg, market)
    url = f"{cfg.base_url}/{endpoint}"
    headers = {"AUTH_KEY": cfg.api_key}
    params = {"basDd": bas_dd}
    try:
        resp = requests.get(url, headers=headers, params=params, timeout=10)
        resp.raise_for_status()
        payload = resp.json()
        _save_cache(cfg, market, bas_dd, payload)
        items = payload.get("OutBlock_1", []) if isinstance(payload, dict) else []
        return _to_df(items)
    except Exception:
        logger.exception("krx api fetch failed: %s %s %s", market, bas_dd, url)
        return pd.DataFrame()


def _to_number(val: object) -> float:
    if val is None:
        return 0.0
    if isinstance(val, (int, float)):
        return float(val)
    s = str(val).replace(",", "").strip()
    if s in ("", "-", "None"):
        return 0.0
    try:
        return float(s)
    except Exception:
        return 0.0


def _to_df(items: list[dict]) -> pd.DataFrame:
    if not items:
        return pd.DataFrame()
    rows = []
    for it in items:
        rows.append(
            {
                "BAS_DD": str(it.get("BAS_DD") or ""),
                "ISU_CD": str(it.get("ISU_CD") or ""),
                "ISU_NM": str(it.get("ISU_NM") or ""),
                "MKT_NM": str(it.get("MKT_NM") or ""),
                "TDD_OPNPRC": _to_number(it.get("TDD_OPNPRC")),
                "TDD_HGPRC": _to_number(it.get("TDD_HGPRC")),
                "TDD_LWPRC": _to_number(it.get("TDD_LWPRC")),
                "TDD_CLSPRC": _to_number(it.get("TDD_CLSPRC")),
                "ACC_TRDVOL": _to_number(it.get("ACC_TRDVOL")),
                "ACC_TRDVAL": _to_number(it.get("ACC_TRDVAL")),
                "MKTCAP": _to_number(it.get("MKTCAP")),
                "LIST_SHRS": _to_number(it.get("LIST_SHRS")),
            }
        )
    return pd.DataFrame(rows)


def available_dates(cfg: KrxApiConfig, days: int = 10) -> list[str]:
    today = datetime.now().date()
    out = []
    for i in range(days):
        d = (today if i == 0 else today - pd.Timedelta(days=i)).strftime("%Y%m%d")
        out.append(d)
    return out
