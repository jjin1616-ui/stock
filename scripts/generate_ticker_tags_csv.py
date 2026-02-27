#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timedelta
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser(description="Generate ticker->tags CSV (sector/industry) using pykrx.")
    ap.add_argument("--out", required=True, help="Output CSV path")
    ap.add_argument("--days-back", type=int, default=10, help="How many days to try back for a valid trading day")
    args = ap.parse_args()

    try:
        from pykrx import stock as krx_stock
    except Exception as e:
        raise SystemExit(f"pykrx not available: {e}")

    def fetch(ds: str):
        rows: list[tuple[str, str]] = []
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
                ticker = str(code).strip().zfill(6)
                sector = str(r.get("업종명") or "").strip()
                if not ticker.isdigit() or len(ticker) != 6:
                    continue
                if not sector:
                    continue
                rows.append((ticker, sector))
        return rows

    rows: list[tuple[str, str]] = []
    today = datetime.now()
    for i in range(max(1, int(args.days_back))):
        ds = (today - timedelta(days=i)).strftime("%Y%m%d")
        rows = fetch(ds)
        if rows:
            break

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    # Deduplicate by ticker, keep first seen (KOSPI/KOSDAQ doesn't overlap in practice).
    seen = set()
    dedup: list[tuple[str, str]] = []
    for t, tag in rows:
        if t in seen:
            continue
        seen.add(t)
        dedup.append((t, tag))

    with out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["ticker", "tags", "source"])
        for t, tag in dedup:
            w.writerow([t, tag, "pykrx_sector"])

    print(f"wrote {len(dedup)} rows -> {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

