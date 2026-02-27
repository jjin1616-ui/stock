from __future__ import annotations

import argparse
from datetime import datetime

from app.report_generator import generate_and_export


def main() -> None:
    parser = argparse.ArgumentParser(description="KoreaStockDash v1.3 premarket report generator")
    parser.add_argument("--report_date", required=True, help="YYYY-MM-DD")
    parser.add_argument("--lookback", type=int, default=20)
    parser.add_argument("--risk", default="ADAPTIVE", choices=["DEFENSIVE", "ADAPTIVE", "AGGRESSIVE"])
    parser.add_argument("--theme_cap", type=int, default=2)
    parser.add_argument("--variant", type=int, default=0)
    parser.add_argument("--out", default="results")
    args = parser.parse_args()

    report_date = datetime.strptime(args.report_date, "%Y-%m-%d").date()
    payload, meta = generate_and_export(
        report_date=report_date,
        lookback=args.lookback,
        risk_preset=args.risk,
        theme_cap=args.theme_cap,
        variant=args.variant,
        out_dir=args.out,
    )

    gate = payload["daytrade_gate"]
    print(f"[OK] report={payload['date']} gate={'ON' if gate['on'] else 'OFF'} metric={gate['gate_metric']}")
    print(f"[OK] primary={len(payload.get('daytrade_primary', []))} watch={len(payload.get('daytrade_watch', []))} longterm={len(payload.get('longterm', []))}")
    print(f"[OK] diagnostics={meta.get('diagnostics', {})}")


if __name__ == "__main__":
    main()
