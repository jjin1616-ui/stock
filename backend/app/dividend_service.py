from __future__ import annotations

import time
import logging
import datetime
from dataclasses import dataclass, field
from typing import List, Optional

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

# Naver Finance 배당 일정 (간이 구현)
NAVER_DIVIDEND_URL = "https://finance.naver.com/sise/dividend_list.naver"

@dataclass
class DividendItem:
    ticker: str = ""
    name: str = ""
    ex_date: str = ""
    dividend_per_share: float = 0.0
    dividend_yield: float = 0.0

@dataclass
class DividendResponse:
    items: List[DividendItem] = field(default_factory=list)
    as_of: str = ""

_cache: Optional[DividendResponse] = None
_cache_ts: float = 0.0
_CACHE_TTL = 86400  # 1 day


async def fetch_dividends() -> DividendResponse:
    """Fetch dividend info.

    Note: Naver doesn't have a clean dividend calendar page.
    This is a placeholder that returns an empty list for now.
    Will be enhanced with pykrx data in a future iteration.
    """
    global _cache, _cache_ts

    if _cache and (time.time() - _cache_ts) < _CACHE_TTL:
        return _cache

    # Placeholder - pykrx integration will be added later
    # pykrx.stock.get_market_cap_by_date() has dividend yield column
    result = DividendResponse(
        items=[],
        as_of=datetime.datetime.now().isoformat(),
    )
    _cache = result
    _cache_ts = time.time()
    return result
