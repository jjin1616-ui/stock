from __future__ import annotations

import json
import logging
from datetime import date, datetime
from zoneinfo import ZoneInfo

from app.config import settings
from engine.strategy import StrategySettings, generate_premarket

logger = logging.getLogger("stock.report_generator")
SEOUL = ZoneInfo("Asia/Seoul")


def generate_premarket_report(
    report_date: date,
    strategy: StrategySettings,
    *,
    daytrade_limit: int | None = None,
    longterm_limit: int | None = None,
) -> tuple[dict, dict]:
    payload, diagnostics = generate_premarket(
        report_date,
        strategy,
        data_dir=settings.data_dir,
        daytrade_limit=daytrade_limit,
        longterm_limit=longterm_limit,
    )
    return payload, diagnostics


def generate_and_export(
    *,
    report_date: date,
    lookback: int,
    risk_preset: str,
    theme_cap: int,
    variant: int,
    out_dir: str,
) -> tuple[dict, dict]:
    from engine.io import ensure_dir, write_csv, write_text
    import pandas as pd

    ensure_dir(out_dir)
    strategy = StrategySettings(risk_preset=risk_preset, theme_cap=theme_cap)
    payload, diagnostics = generate_premarket_report(report_date, strategy)

    def _to_df(items: list[dict]) -> pd.DataFrame:
        return pd.DataFrame(items) if items else pd.DataFrame()

    write_csv(_to_df(payload.get("daytrade_primary", [])), f"{out_dir}/daytrade_primary10.csv")
    write_csv(_to_df(payload.get("daytrade_watch", [])), f"{out_dir}/daytrade_watch20.csv")
    write_csv(_to_df(payload.get("longterm", [])), f"{out_dir}/longterm30.csv")

    lines = [
        f"장전 리포트 ({payload.get('date')})",
        f"게이트: {'ON' if payload.get('daytrade_gate', {}).get('on') else 'OFF'} / metric={payload.get('daytrade_gate', {}).get('gate_metric')}",
        "[실행 우선 10]",
        "[관찰 20]",
        "[장투 30]",
    ]
    write_text("\n".join(lines), f"{out_dir}/report_premarket.txt")
    return payload, {"diagnostics": diagnostics}


def generate_eod(report_date: date) -> dict:
    return {
        "date": report_date.isoformat(),
        "generated_at": datetime.now(tz=SEOUL).isoformat(),
        "summary": ["데이터 수집 중"],
        "themes_worked": [],
        "themes_failed": [],
        "tomorrow_improvements": [],
    }


def dumps(obj: dict) -> str:
    return json.dumps(obj, ensure_ascii=False)
