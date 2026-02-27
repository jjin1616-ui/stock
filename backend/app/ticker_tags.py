from __future__ import annotations

import csv
import json
import os
from dataclasses import dataclass
from datetime import datetime
from typing import Iterable
from zoneinfo import ZoneInfo

from sqlalchemy import select

from app.config import settings
from app.models import TickerTag

SEOUL = ZoneInfo(settings.app_tz)

try:
    from pykrx import stock as krx_stock
except Exception:  # pragma: no cover
    krx_stock = None


def _norm_ticker(t: str) -> str:
    t = (t or "").strip()
    t = t.replace("-", "").replace(" ", "")
    if t.isdigit() and len(t) < 6:
        t = t.zfill(6)
    return t


def _parse_tags(raw: str) -> list[str]:
    s = (raw or "").strip()
    if not s:
        return []
    # Accept multiple separators for admin convenience.
    parts: list[str] = []
    for chunk in s.replace("|", ",").replace(";", ",").split(","):
        v = chunk.strip()
        if not v:
            continue
        parts.append(v)
    # Deduplicate while preserving order.
    seen = set()
    out = []
    for p in parts:
        if p in seen:
            continue
        seen.add(p)
        out.append(p)
    return out[:12]


@dataclass(frozen=True)
class TagRow:
    ticker: str
    tags: list[str]
    source: str = "manual"


def upsert_tags(session, rows: Iterable[TagRow]) -> int:
    now = datetime.now(tz=SEOUL)
    n = 0
    for r in rows:
        ticker = _norm_ticker(r.ticker)
        if not ticker:
            continue
        tags = r.tags or []
        row = session.get(TickerTag, ticker)
        if row:
            row.tags_json = json.dumps(tags, ensure_ascii=False)
            row.source = r.source or row.source
            row.updated_at = now
        else:
            session.add(TickerTag(ticker=ticker, tags_json=json.dumps(tags, ensure_ascii=False), source=r.source or "manual", updated_at=now))
        n += 1
    return n


def _dedupe_keep_order(items: list[str]) -> list[str]:
    seen = set()
    out: list[str] = []
    for it in items:
        v = (it or "").strip()
        if not v:
            continue
        if v in seen:
            continue
        seen.add(v)
        out.append(v)
    return out


def upsert_tags_merge(session, rows: Iterable[TagRow]) -> int:
    """
    Merge refresh rows into DB without destroying existing manual tags.

    Rules:
    - If existing source is 'manual', keep its tags order; append new tags that aren't present.
    - Otherwise, prefer existing tags order, append new tags, and update source.
    """
    now = datetime.now(tz=SEOUL)
    n = 0
    for r in rows:
        ticker = _norm_ticker(r.ticker)
        if not ticker:
            continue
        new_tags = _dedupe_keep_order(r.tags or [])
        if not new_tags:
            continue

        row = session.get(TickerTag, ticker)
        if not row:
            session.add(TickerTag(ticker=ticker, tags_json=json.dumps(new_tags, ensure_ascii=False), source=r.source or "pykrx", updated_at=now))
            n += 1
            continue

        try:
            existing_tags = json.loads(row.tags_json or "[]") or []
        except Exception:
            existing_tags = []
        existing_tags = _dedupe_keep_order([str(x) for x in existing_tags])

        merged = existing_tags + [t for t in new_tags if t not in existing_tags]

        # If manual, preserve manual as primary source; do not flip source.
        if (row.source or "").lower() != "manual":
            row.source = r.source or row.source
        row.tags_json = json.dumps(merged[:12], ensure_ascii=False)
        row.updated_at = now
        n += 1
    return n


def get_tags_map(session, tickers: list[str]) -> dict[str, list[str]]:
    norm = [_norm_ticker(t) for t in tickers if _norm_ticker(t)]
    if not norm:
        return {}
    rows = session.scalars(select(TickerTag).where(TickerTag.ticker.in_(norm))).all()
    out: dict[str, list[str]] = {}
    for r in rows:
        try:
            out[r.ticker] = json.loads(r.tags_json or "[]") or []
        except Exception:
            out[r.ticker] = []
    return out


def load_tags_csv(path: str) -> list[TagRow]:
    if not path or not os.path.exists(path):
        return []
    rows: list[TagRow] = []
    with open(path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for r in reader:
            ticker = _norm_ticker(str(r.get("ticker") or r.get("code") or ""))
            tags = _parse_tags(str(r.get("tags") or r.get("theme") or r.get("themes") or ""))
            source = str(r.get("source") or "csv").strip() or "csv"
            if not ticker or not tags:
                continue
            rows.append(TagRow(ticker=ticker, tags=tags, source=source))
    return rows


def refresh_from_csv(session) -> int:
    if not settings.ticker_tags_enabled:
        return 0
    rows = load_tags_csv(settings.ticker_tags_csv_path)
    if not rows:
        return 0
    return upsert_tags_merge(session, rows)


def refresh_from_pykrx_sector(session, *, days_back: int = 10) -> int:
    """
    Populate ticker->sector/industry tags using pykrx (KRX website data).
    This gives a human-readable tag even when no manual/CSV mapping exists.
    """
    if not settings.ticker_tags_enabled:
        return 0
    if krx_stock is None:
        return 0
    from datetime import datetime, timedelta

    def _try_date(ds: str) -> list[TagRow]:
        out: list[TagRow] = []
        for market in ("KOSPI", "KOSDAQ"):
            try:
                df = krx_stock.get_market_sector_classifications(ds, market=market)
            except Exception:
                df = None
            if df is None or getattr(df, "empty", True):
                continue
            if "업종명" not in df.columns:
                continue
            for code, r in df.iterrows():
                ticker = _norm_ticker(str(code))
                sector = str(r.get("업종명") or "").strip()
                if not ticker or not sector:
                    continue
                out.append(TagRow(ticker=ticker, tags=[sector], source="pykrx_sector"))
        return out

    rows: list[TagRow] = []
    today = datetime.now(tz=SEOUL)
    for i in range(max(1, days_back)):
        ds = (today - timedelta(days=i)).strftime("%Y%m%d")
        rows = _try_date(ds)
        if rows:
            break
    if not rows:
        return 0
    return upsert_tags_merge(session, rows)
