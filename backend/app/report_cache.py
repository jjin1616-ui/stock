from __future__ import annotations

from datetime import date


def premarket_cache_key(
    report_date: date,
    settings_hash: str,
    daytrade_limit: int | None,
    longterm_limit: int | None,
    algo_version: str | None = None,
) -> str:
    """
    Canonical PREMARKET cache key.

    Keep this in one place so on-demand generation (API) and scheduled pre-warm
    write/read the same key.
    """

    dt = str(daytrade_limit or "")
    lt = str(longterm_limit or "")
    av = (str(algo_version or "").strip().upper() or "V2")
    return f"PREMARKET:{report_date.isoformat()}:{settings_hash}:{dt}:{lt}:{av}"
