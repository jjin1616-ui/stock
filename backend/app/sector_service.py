from __future__ import annotations

import asyncio
import time
import logging
from dataclasses import dataclass, field
from typing import List, Optional

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

NAVER_SECTOR_URL = "https://finance.naver.com/sise/sise_group.naver?type=upjong"


@dataclass
class SectorItem:
    name: str = ""
    change_pct: float = 0.0
    volume: int = 0
    top_stocks: list = field(default_factory=list)


@dataclass
class SectorResponse:
    items: List[SectorItem] = field(default_factory=list)
    as_of: str = ""
    source: str = "NAVER"


# Simple in-memory cache
_cache: Optional[SectorResponse] = None
_cache_ts: float = 0.0
_CACHE_TTL_MARKET = 300   # 5 min during market hours
_CACHE_TTL_CLOSED = 3600  # 1 hour after market


def _is_market_hours() -> bool:
    """Check if within KST market hours (09:00-15:30)"""
    import datetime
    try:
        from zoneinfo import ZoneInfo
        kst = ZoneInfo("Asia/Seoul")
    except ImportError:
        import pytz
        kst = pytz.timezone("Asia/Seoul")
    now = datetime.datetime.now(kst)
    market_open = now.replace(hour=9, minute=0, second=0, microsecond=0)
    market_close = now.replace(hour=15, minute=30, second=0, microsecond=0)
    return market_open <= now <= market_close and now.weekday() < 5


async def fetch_sectors() -> SectorResponse:
    global _cache, _cache_ts

    ttl = _CACHE_TTL_MARKET if _is_market_hours() else _CACHE_TTL_CLOSED
    if _cache and (time.time() - _cache_ts) < ttl:
        return _cache

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(NAVER_SECTOR_URL, headers={"User-Agent": "Mozilla/5.0"})
            resp.raise_for_status()

        soup = BeautifulSoup(resp.text, "html.parser")
        items = []

        # Parse the sector table
        table = soup.find("table", {"class": "type_1"})
        if not table:
            logger.warning("sector table not found")
            return _cache or SectorResponse()

        rows = table.find_all("tr")
        for row in rows:
            cols = row.find_all("td")
            if len(cols) < 4:
                continue

            name_tag = cols[0].find("a")
            if not name_tag:
                continue

            name = name_tag.get_text(strip=True)

            # Change percent
            change_text = cols[1].get_text(strip=True).replace("%", "").replace("+", "").replace(",", "")
            try:
                change_pct = float(change_text)
            except ValueError:
                change_pct = 0.0

            # Check if it's a down indicator
            if row.find("img", {"alt": "하락"}):
                change_pct = -abs(change_pct)

            items.append(SectorItem(name=name, change_pct=change_pct))

        import datetime
        result = SectorResponse(
            items=items[:30],  # top 30 sectors
            as_of=datetime.datetime.now().isoformat(),
        )
        _cache = result
        _cache_ts = time.time()
        return result

    except Exception as e:
        logger.error(f"sector fetch failed: {e}")
        return _cache or SectorResponse()
