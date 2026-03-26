from __future__ import annotations

import argparse
from datetime import date, datetime

import pandas as pd

from engine.config import default_config
from engine.grid import EngineArtifacts, run_grid_with_artifacts
from engine.io import ensure_dir, write_csv, write_text
from engine.report import render_summary


def _parse_lookbacks(raw: str | None, fallback: int) -> list[int]:
    if not raw:
        return [fallback]
    vals = [v.strip() for v in raw.split(",") if v.strip()]
    out = []
    for v in vals:
        try:
            out.append(int(v))
        except ValueError:
            pass
    return out or [fallback]


def _merge_artifacts(items: list[EngineArtifacts]) -> EngineArtifacts:
    return EngineArtifacts(
        kpi_grid=pd.concat([x.kpi_grid for x in items], ignore_index=True) if items else pd.DataFrame(),
        top10_daily=pd.concat([x.top10_daily for x in items], ignore_index=True) if items else pd.DataFrame(),
        trade_log=pd.concat([x.trade_log for x in items], ignore_index=True) if items else pd.DataFrame(),
        gate_daily=pd.concat([x.gate_daily for x in items], ignore_index=True) if items else pd.DataFrame(),
        rejected_candidates=pd.concat([x.rejected_candidates for x in items], ignore_index=True) if items else pd.DataFrame(),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="KoreaStockDash Daytrade Selection Engine v2")
    parser.add_argument("--end", type=str, required=True, help="YYYY-MM-DD")
    parser.add_argument("--lookback_days", type=int, default=120)
    parser.add_argument("--lookbacks", type=str, default="", help="comma-separated, e.g. 60,120")
    parser.add_argument("--min_value", type=float, default=5e9)
    parser.add_argument("--out", type=str, default="results")
    args = parser.parse_args()

    end = datetime.strptime(args.end, "%Y-%m-%d").date()
    lookbacks = _parse_lookbacks(args.lookbacks, args.lookback_days)

    ensure_dir(args.out)
    runs: list[EngineArtifacts] = []
    for lb in lookbacks:
        cfg = default_config(end=end, lookback_days=lb, min_value_krw=args.min_value, out_dir=args.out)
        runs.append(run_grid_with_artifacts(cfg))

    merged = _merge_artifacts(runs)
    if merged.kpi_grid.empty:
        summary = "No result rows. Check network/data availability.\nNote: current-listed universe can induce survivorship bias."
        write_text(summary, f"{args.out}/summary.txt")
        print(summary)
        return

    cfg_for_summary = default_config(end=end, lookback_days=lookbacks[-1], min_value_krw=args.min_value, out_dir=args.out)
    summary = render_summary(cfg_for_summary, merged.kpi_grid)

    write_csv(merged.kpi_grid, f"{args.out}/kpi_grid.csv")
    write_csv(merged.top10_daily, f"{args.out}/top10_daily.csv")
    write_csv(merged.trade_log, f"{args.out}/trade_log.csv")
    write_csv(merged.gate_daily, f"{args.out}/gate_daily.csv")
    write_csv(merged.rejected_candidates, f"{args.out}/rejected_candidates.csv")
    write_text(summary, f"{args.out}/summary.txt")

    print(summary)


if __name__ == "__main__":
    main()
