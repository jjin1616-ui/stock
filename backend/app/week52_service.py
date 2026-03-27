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

NAVER_HIGH_URL = "https://finance.naver.com/sise/sise_new_high.naver"
NAVER_LOW_URL = "https://finance.naver.com/sise/sise_new_low.naver"

@dataclass
class WeekExtremeItem:
    ticker: str = ""
    name: str = ""
    price: float = 0.0
    prev_extreme: float = 0.0

@dataclass
class WeekExtremeResponse:
    highs: List[WeekExtremeItem] = field(default_factory=list)
    lows: List[WeekExtremeItem] = field(default_factory=list)
    as_of: str = ""

_cache: Optional[WeekExtremeResponse] = None
_cache_ts: float = 0.0
_CACHE_TTL = 1800  # 30 min


async def _parse_extreme_page(url: str) -> List[WeekExtremeItem]:
    items = []
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(url, headers={"User-Agent": "Mozilla/5.0"})
            resp.raise_for_status()

        soup = BeautifulSoup(resp.text, "html.parser")
        table = soup.find("table", {"class": "type_1"})
        if not table:
            return items

        rows = table.find_all("tr")
        for row in rows:
            cols = row.find_all("td")
            if len(cols) < 4:
                continue
            name_tag = cols[0].find("a")
            if not name_tag:
                continue

            href = name_tag.get("href", "")
            ticker = ""
            if "code=" in href:
                ticker = href.split("code=")[-1][:6]

            name = name_tag.get_text(strip=True)

            price_text = cols[1].get_text(strip=True).replace(",", "")
            try:
                price = float(price_text)
            except ValueError:
                price = 0.0

            items.append(WeekExtremeItem(ticker=ticker, name=name, price=price))

    except Exception as e:
        logger.error(f"52week parse failed for {url}: {e}")

    return items[:10]


async def fetch_52week_extremes() -> WeekExtremeResponse:
    global _cache, _cache_ts

    if _cache and (time.time() - _cache_ts) < _CACHE_TTL:
        return _cache

    highs, lows = await asyncio.gather(
        _parse_extreme_page(NAVER_HIGH_URL),
        _parse_extreme_page(NAVER_LOW_URL),
    )

    result = WeekExtremeResponse(
        highs=highs,
        lows=lows,
        as_of=datetime.datetime.now().isoformat(),
    )
    _cache = result
    _cache_ts = time.time()
    return result
