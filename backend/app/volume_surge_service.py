from __future__ import annotations

import asyncio
import time
import logging
import datetime
from dataclasses import dataclass, field
from typing import List, Optional

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

# Naver Finance 거래량 상위 종목
NAVER_VOLUME_URL = "https://finance.naver.com/sise/sise_quant.naver"

@dataclass
class VolumeSurgeItem:
    ticker: str = ""
    name: str = ""
    volume_ratio: float = 0.0  # vs 20-day avg
    price: float = 0.0
    change_pct: float = 0.0

@dataclass
class VolumeSurgeResponse:
    items: List[VolumeSurgeItem] = field(default_factory=list)
    as_of: str = ""

_cache: Optional[VolumeSurgeResponse] = None
_cache_ts: float = 0.0
_CACHE_TTL = 180  # 3 min


async def fetch_volume_surge() -> VolumeSurgeResponse:
    global _cache, _cache_ts

    if _cache and (time.time() - _cache_ts) < _CACHE_TTL:
        return _cache

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(NAVER_VOLUME_URL, headers={"User-Agent": "Mozilla/5.0"})
            resp.raise_for_status()

        soup = BeautifulSoup(resp.text, "html.parser")
        items = []

        table = soup.find("table", {"class": "type_2"})
        if not table:
            logger.warning("volume table not found")
            return _cache or VolumeSurgeResponse()

        rows = table.find_all("tr")
        for row in rows:
            cols = row.find_all("td")
            if len(cols) < 6:
                continue

            name_tag = cols[1].find("a")
            if not name_tag:
                continue

            href = name_tag.get("href", "")
            ticker = ""
            if "code=" in href:
                ticker = href.split("code=")[-1][:6]

            name = name_tag.get_text(strip=True)

            # Current price
            price_text = cols[2].get_text(strip=True).replace(",", "")
            try:
                price = float(price_text)
            except ValueError:
                price = 0.0

            # Change percent
            pct_text = cols[4].get_text(strip=True).replace("%", "").replace("+", "").replace(",", "")
            try:
                change_pct = float(pct_text)
            except ValueError:
                change_pct = 0.0
            if row.find("img", {"alt": "하락"}):
                change_pct = -abs(change_pct)

            # Volume
            vol_text = cols[5].get_text(strip=True).replace(",", "")
            try:
                volume = int(vol_text)
            except ValueError:
                volume = 0

            # We don't have 20-day avg from this page, so volumeRatio = 0 for now
            # Can be enhanced later with actual ratio calculation
            items.append(VolumeSurgeItem(
                ticker=ticker, name=name, price=price,
                change_pct=change_pct, volume_ratio=0.0
            ))

        result = VolumeSurgeResponse(
            items=items[:20],
            as_of=datetime.datetime.now().isoformat(),
        )
        _cache = result
        _cache_ts = time.time()
        return result

    except Exception as e:
        logger.error(f"volume surge fetch failed: {e}")
        return _cache or VolumeSurgeResponse()
