from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
import json
from typing import Any
from uuid import uuid4

from sqlalchemy import desc, func, select
from sqlalchemy.orm import Session

from app.auth import now
from app.config import settings
from app.kis_broker import KisBrokerClient, KisCredentialBundle
from app.models import AutoTradeDailyMetric, AutoTradeOrder, AutoTradeReentryBlock, AutoTradeSetting, AutoTradeSymbolRule, Report, StrategySettings, UserFavorite
from app.movers2 import compute_movers2
from app.realtime_quotes import fetch_quotes
from engine.strategy import normalize_algo_version


def _norm_ticker(raw: str) -> str:
    t = str(raw or "").strip().upper()
    return t.zfill(6) if t.isdigit() and len(t) <= 6 else t


def _is_kr_ticker(ticker: str) -> bool:
    return ticker.isdigit() and len(ticker) == 6


def get_or_create_autotrade_setting(session: Session, user_id: int) -> AutoTradeSetting:
    row = session.scalar(select(AutoTradeSetting).where(AutoTradeSetting.user_id == user_id).limit(1))
    if row is not None:
        env = str(getattr(row, "environment", "") or "").strip().lower()
        if env not in {"demo", "prod"}:
            row.environment = "demo"
            row.updated_at = now()
            session.flush()
        return row
    ts = now()
    row = AutoTradeSetting(
        user_id=user_id,
        enabled=False,
        environment="demo",
        include_daytrade=True,
        include_movers=True,
        include_supply=True,
        include_papers=True,
        include_longterm=True,
        include_favorites=True,
        order_budget_krw=200000.0,
        max_orders_per_run=5,
        max_daily_loss_pct=3.0,
        seed_krw=10000000.0,
        take_profit_pct=7.0,
        stop_loss_pct=5.0,
        stoploss_reentry_policy="cooldown",
        stoploss_reentry_cooldown_min=30,
        takeprofit_reentry_policy="cooldown",
        takeprofit_reentry_cooldown_min=30,
        allow_market_order=False,
        offhours_reservation_enabled=True,
        offhours_reservation_mode="auto",
        offhours_confirm_timeout_min=3,
        offhours_confirm_timeout_action="cancel",
        created_at=ts,
        updated_at=ts,
    )
    session.add(row)
    session.flush()
    return row


def update_autotrade_setting(session: Session, user_id: int, payload: dict[str, Any]) -> AutoTradeSetting:
    row = get_or_create_autotrade_setting(session, user_id)
    row.enabled = bool(payload.get("enabled", row.enabled))
    env = str(payload.get("environment", row.environment)).lower()
    row.environment = env if env in ("demo", "prod") else "demo"
    row.include_daytrade = bool(payload.get("include_daytrade", row.include_daytrade))
    row.include_movers = bool(payload.get("include_movers", row.include_movers))
    row.include_supply = bool(payload.get("include_supply", row.include_supply))
    row.include_papers = bool(payload.get("include_papers", row.include_papers))
    row.include_longterm = bool(payload.get("include_longterm", row.include_longterm))
    row.include_favorites = bool(payload.get("include_favorites", row.include_favorites))
    row.order_budget_krw = max(10000.0, float(payload.get("order_budget_krw", row.order_budget_krw)))
    row.max_orders_per_run = max(1, min(100, int(payload.get("max_orders_per_run", row.max_orders_per_run))))
    row.max_daily_loss_pct = max(0.1, min(50.0, float(payload.get("max_daily_loss_pct", row.max_daily_loss_pct))))
    row.seed_krw = max(10000000.0, min(100000000.0, float(payload.get("seed_krw", row.seed_krw))))
    row.take_profit_pct = max(1.0, min(30.0, float(payload.get("take_profit_pct", row.take_profit_pct))))
    row.stop_loss_pct = max(0.5, min(30.0, float(payload.get("stop_loss_pct", row.stop_loss_pct))))
    stoploss_policy = str(payload.get("stoploss_reentry_policy", getattr(row, "stoploss_reentry_policy", "cooldown"))).strip().lower()
    row.stoploss_reentry_policy = stoploss_policy if stoploss_policy in {"immediate", "cooldown", "day_block", "manual_block"} else "cooldown"
    row.stoploss_reentry_cooldown_min = max(
        1,
        min(1440, int(payload.get("stoploss_reentry_cooldown_min", getattr(row, "stoploss_reentry_cooldown_min", 30)))),
    )
    takeprofit_policy = str(payload.get("takeprofit_reentry_policy", getattr(row, "takeprofit_reentry_policy", "cooldown"))).strip().lower()
    row.takeprofit_reentry_policy = takeprofit_policy if takeprofit_policy in {"immediate", "cooldown", "day_block", "manual_block"} else "cooldown"
    row.takeprofit_reentry_cooldown_min = max(
        1,
        min(1440, int(payload.get("takeprofit_reentry_cooldown_min", getattr(row, "takeprofit_reentry_cooldown_min", 30)))),
    )
    row.allow_market_order = bool(payload.get("allow_market_order", row.allow_market_order))
    row.offhours_reservation_enabled = bool(payload.get("offhours_reservation_enabled", row.offhours_reservation_enabled))
    reservation_mode = str(payload.get("offhours_reservation_mode", row.offhours_reservation_mode)).strip().lower()
    row.offhours_reservation_mode = reservation_mode if reservation_mode in {"auto", "confirm"} else "auto"
    row.offhours_confirm_timeout_min = max(
        1,
        min(30, int(payload.get("offhours_confirm_timeout_min", row.offhours_confirm_timeout_min))),
    )
    timeout_action = str(payload.get("offhours_confirm_timeout_action", row.offhours_confirm_timeout_action)).strip().lower()
    row.offhours_confirm_timeout_action = timeout_action if timeout_action in {"cancel", "auto"} else "cancel"
    row.updated_at = now()
    session.flush()
    return row


def _latest_premarket_payload(session: Session) -> dict[str, Any] | None:
    row = session.scalar(
        select(Report).where(Report.type == "PREMARKET").order_by(desc(Report.date), desc(Report.generated_at)).limit(1)
    )
    if row is None:
        return None
    try:
        payload = json.loads(row.payload_json or "{}")
        return payload if isinstance(payload, dict) else None
    except Exception:
        return None


def build_autotrade_candidates(
    session: Session,
    user_id: int,
    cfg: AutoTradeSetting,
    limit: int = 50,
    diagnostics: dict[str, Any] | None = None,
    profile: str = "full",
) -> list[dict[str, Any]]:
    profile_mode = str(profile or "full").strip().lower()
    if profile_mode not in {"full", "initial"}:
        profile_mode = "full"
    bucket: dict[str, dict[str, Any]] = {}
    source_counts: dict[str, int] = {"DAYTRADE": 0, "MOVERS": 0, "SUPPLY": 0, "PAPERS": 0, "LONGTERM": 0, "FAVORITES": 0, "RECENT": 0}
    warnings: list[str] = []

    def _put(source_tab: str, ticker: str, name: str | None, signal_price: float | None, note: str | None = None) -> None:
        tk = _norm_ticker(ticker)
        if not tk:
            return
        if tk in bucket:
            return
        source_key = source_tab if source_tab in source_counts else "RECENT"
        source_counts[source_key] = int(source_counts.get(source_key, 0)) + 1
        bucket[tk] = {
            "ticker": tk,
            "name": (name or "").strip() or None,
            "source_tab": source_tab,
            "signal_price": float(signal_price) if (signal_price is not None) else None,
            "current_price": None,
            "chg_pct": None,
            "note": note,
        }

    premarket = _latest_premarket_payload(session)
    if premarket is None and (cfg.include_daytrade or cfg.include_papers or cfg.include_longterm):
        warnings.append("PREMARKET_REPORT_UNAVAILABLE")
    if premarket and cfg.include_daytrade:
        for it in (premarket.get("daytrade_top") or []):
            if isinstance(it, dict):
                _put("DAYTRADE", str(it.get("ticker") or ""), it.get("name"), it.get("trigger_buy"), "단타 추천")
        for it in (premarket.get("daytrade_watch") or []):
            if isinstance(it, dict):
                _put("DAYTRADE", str(it.get("ticker") or ""), it.get("name"), it.get("trigger_buy"), "단타 관찰")

    if premarket and cfg.include_papers:
        papers = premarket.get("var7_top10") or premarket.get("base_top10") or []
        for it in papers:
            if isinstance(it, dict):
                _put("PAPERS", str(it.get("ticker") or ""), it.get("name"), it.get("trigger_buy"), "논문 변형 신호")

    if premarket and cfg.include_longterm:
        for it in (premarket.get("longterm") or []):
            if isinstance(it, dict):
                signal = None
                buy_zone = it.get("buy_zone")
                if isinstance(buy_zone, dict):
                    signal = buy_zone.get("low") or buy_zone.get("high")
                if signal is None:
                    signal = it.get("d1_close")
                _put("LONGTERM", str(it.get("ticker") or ""), it.get("name"), signal, "장투 추천")

    if cfg.include_movers:
        try:
            strategy_row = session.get(StrategySettings, 1)
            movers_algo = normalize_algo_version(getattr(strategy_row, "algo_version", None))
            movers_limit = max(10, min(100, limit))
            if profile_mode == "initial":
                movers_limit = max(10, min(50, max(limit, 20)))
            movers = compute_movers2(
                session="regular",
                direction="up",
                count=movers_limit,
                universe_top_value=500,
                universe_top_chg=200,
                markets=["KOSPI", "KOSDAQ"],
                algo_version=movers_algo,
            )
            for it in (movers.get("items") or []):
                if isinstance(it, dict):
                    _put("MOVERS", str(it.get("ticker") or ""), it.get("name"), it.get("price"), "급등 세션 신호")
        except Exception as exc:
            warnings.append(f"MOVERS_SOURCE_ERROR:{exc.__class__.__name__}")

    if cfg.include_supply:
        try:
            # Keep autotrade aligned with the same supply algorithm used by the 수급 screen.
            from app.main import _compute_supply_live  # type: ignore

            supply_count = max(20, min(100, max(limit * 2, 40)))
            if profile_mode == "initial":
                supply_count = max(20, min(60, max(limit, 30)))
            supply = _compute_supply_live(
                count=supply_count,
                days=20,
                universe_top_value=450,
                universe_top_chg=220,
                markets=["KOSPI", "KOSDAQ"],
                include_contrarian=True,
            )
            for it in (getattr(supply, "items", None) or []):
                _put(
                    "SUPPLY",
                    str(getattr(it, "ticker", "") or ""),
                    getattr(it, "name", None),
                    getattr(it, "price", None),
                    "수급 포켓 신호",
                )
        except Exception as exc:
            warnings.append(f"SUPPLY_SOURCE_ERROR:{exc.__class__.__name__}")

    if cfg.include_favorites:
        rows = (
            session.query(UserFavorite)
            .filter(UserFavorite.user_id == user_id)
            .order_by(UserFavorite.favorited_at.desc())
            .limit(max(10, limit))
            .all()
        )
        for r in rows:
            _put("FAVORITES", str(r.ticker), r.name, float(r.baseline_price or 0.0), "관심 종목")

    # Fallback: if all configured sources are empty, expose recent executed/submitted tickers
    # so the UI can still explain what is being traded.
    if not bucket:
        recent_rows = (
            session.query(AutoTradeOrder)
            .filter(
                AutoTradeOrder.user_id == user_id,
                AutoTradeOrder.status.in_(["PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"]),
            )
            .order_by(AutoTradeOrder.requested_at.desc())
            .limit(max(20, limit * 3))
            .all()
        )
        for row in recent_rows:
            px = float(row.current_price or row.filled_price or row.requested_price or 0.0)
            _put(
                str(row.source_tab or "UNKNOWN"),
                str(row.ticker or ""),
                (str(row.name) if row.name is not None else None),
                px if px > 0.0 else None,
                "최근 자동매매 이력",
            )
        if recent_rows:
            warnings.append("SOURCE_EMPTY_FALLBACK_RECENT_ORDERS")

    ordered = list(bucket.values())[: max(1, min(limit, 300))]
    kr = [x["ticker"] for x in ordered if _is_kr_ticker(str(x["ticker"]))]
    quotes = {q.ticker: q for q in fetch_quotes(kr)} if kr else {}
    for x in ordered:
        q = quotes.get(str(x["ticker"]))
        if q is None:
            continue
        x["current_price"] = float(q.price or 0.0)
        x["chg_pct"] = float(q.chg_pct or 0.0)
        if not x.get("signal_price") and x["current_price"] > 0.0:
            x["signal_price"] = x["current_price"]
    if not ordered:
        warnings.append("NO_CANDIDATES")
    if diagnostics is not None:
        visible_counts: dict[str, int] = {"DAYTRADE": 0, "MOVERS": 0, "SUPPLY": 0, "PAPERS": 0, "LONGTERM": 0, "FAVORITES": 0, "RECENT": 0}
        for row in ordered:
            source_key = str(row.get("source_tab") or "RECENT").upper()
            if source_key not in visible_counts:
                source_key = "RECENT"
            visible_counts[source_key] = int(visible_counts.get(source_key, 0)) + 1
        diagnostics["source_counts"] = visible_counts
        diagnostics["warnings"] = warnings
        diagnostics["premarket_available"] = premarket is not None
    return ordered


def _filled_status(status: str) -> bool:
    return status in {"PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"}


def _parse_order_meta(raw: str | None) -> dict[str, Any]:
    try:
        payload = json.loads(str(raw or "{}"))
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


def _order_environment(row: AutoTradeOrder) -> str | None:
    meta = _parse_order_meta(getattr(row, "metadata_json", None))
    env = str(meta.get("environment") or "").strip().lower()
    if env in {"paper", "demo", "prod"}:
        return env
    return None


def _recent_pending_order_tickers(
    session: Session,
    *,
    user_id: int,
    environment: str,
    side: str,
    within_sec: int,
) -> set[str]:
    if within_sec <= 0:
        return set()
    cutoff = now() - timedelta(seconds=int(within_sec))
    side_u = "SELL" if str(side or "").strip().upper() == "SELL" else "BUY"
    rows = (
        session.query(AutoTradeOrder)
        .filter(
            AutoTradeOrder.user_id == int(user_id),
            AutoTradeOrder.side == side_u,
            AutoTradeOrder.status == "BROKER_SUBMITTED",
            AutoTradeOrder.requested_at >= cutoff,
        )
        .order_by(desc(AutoTradeOrder.requested_at))
        .limit(400)
        .all()
    )
    out: set[str] = set()
    target_env = str(environment or "").strip().lower()
    for row in rows:
        ticker = _norm_ticker(str(getattr(row, "ticker", "") or ""))
        if not ticker:
            continue
        row_env = _order_environment(row)
        if row_env and target_env and row_env != target_env:
            continue
        out.add(ticker)
    return out


def _normalize_reentry_policy(raw: str | None) -> str:
    p = str(raw or "").strip().lower()
    return p if p in {"immediate", "cooldown", "day_block", "manual_block"} else "cooldown"


def _active_manual_reentry_block_tickers(
    session: Session,
    *,
    user_id: int,
    environment: str,
    trigger_reason: str,
) -> set[str]:
    rows = (
        session.query(AutoTradeReentryBlock)
        .filter(
            AutoTradeReentryBlock.user_id == int(user_id),
            AutoTradeReentryBlock.environment == str(environment or "demo").strip().lower(),
            AutoTradeReentryBlock.trigger_reason == str(trigger_reason or "").strip().upper(),
            AutoTradeReentryBlock.is_active.is_(True),
            AutoTradeReentryBlock.released_at.is_(None),
        )
        .order_by(desc(AutoTradeReentryBlock.blocked_at))
        .limit(1000)
        .all()
    )
    return {
        _norm_ticker(str(getattr(row, "ticker", "") or ""))
        for row in rows
        if _norm_ticker(str(getattr(row, "ticker", "") or ""))
    }


def _upsert_manual_reentry_block(
    session: Session,
    *,
    user_id: int,
    environment: str,
    ticker: str,
    trigger_reason: str,
) -> None:
    tk = _norm_ticker(ticker)
    if not tk:
        return
    env = str(environment or "demo").strip().lower()
    trigger = str(trigger_reason or "").strip().upper()
    if trigger not in {"STOP_LOSS", "TAKE_PROFIT"}:
        return
    row = session.scalar(
        select(AutoTradeReentryBlock)
        .where(
            AutoTradeReentryBlock.user_id == int(user_id),
            AutoTradeReentryBlock.environment == env,
            AutoTradeReentryBlock.ticker == tk,
            AutoTradeReentryBlock.trigger_reason == trigger,
            AutoTradeReentryBlock.is_active.is_(True),
            AutoTradeReentryBlock.released_at.is_(None),
        )
        .order_by(desc(AutoTradeReentryBlock.blocked_at))
        .limit(1)
    )
    ts = now()
    if row is None:
        session.add(
            AutoTradeReentryBlock(
                user_id=int(user_id),
                environment=env,
                ticker=tk,
                trigger_reason=trigger,
                is_active=True,
                blocked_at=ts,
                released_at=None,
                note=f"{trigger}_MANUAL_BLOCK",
            )
        )
        return
    row.is_active = True
    row.blocked_at = ts
    row.released_at = None
    row.note = f"{trigger}_MANUAL_BLOCK"


def _recent_trigger_blocked_tickers(
    session: Session,
    *,
    user_id: int,
    environment: str,
    trigger_reason: str,
    policy: str,
    cooldown_min: int,
) -> set[str]:
    policy_u = _normalize_reentry_policy(policy)
    if policy_u == "immediate":
        return set()
    if policy_u == "manual_block":
        return _active_manual_reentry_block_tickers(
            session,
            user_id=user_id,
            environment=environment,
            trigger_reason=trigger_reason,
        )
    now_ts = now()
    cutoff: datetime
    if policy_u == "day_block":
        cutoff = datetime.combine(now_ts.date(), datetime.min.time(), tzinfo=now_ts.tzinfo)
    else:
        cooldown = max(1, min(1440, int(cooldown_min)))
        cutoff = now_ts - timedelta(minutes=cooldown)
    rows = (
        session.query(AutoTradeOrder)
        .filter(
            AutoTradeOrder.user_id == int(user_id),
            AutoTradeOrder.side == "SELL",
            AutoTradeOrder.status.in_(["PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"]),
            AutoTradeOrder.reason == str(trigger_reason or "").strip().upper(),
            AutoTradeOrder.requested_at >= cutoff,
        )
        .order_by(desc(AutoTradeOrder.requested_at))
        .limit(400)
        .all()
    )
    out: set[str] = set()
    target_env = str(environment or "").strip().lower()
    for row in rows:
        ticker = _norm_ticker(str(getattr(row, "ticker", "") or ""))
        if not ticker:
            continue
        row_env = _order_environment(row)
        if row_env and target_env and row_env != target_env:
            continue
        out.add(ticker)
    return out


def _load_symbol_rules(session: Session, user_id: int) -> dict[str, AutoTradeSymbolRule]:
    rows = (
        session.query(AutoTradeSymbolRule)
        .filter(AutoTradeSymbolRule.user_id == user_id)
        .all()
    )
    out: dict[str, AutoTradeSymbolRule] = {}
    for row in rows:
        tk = _norm_ticker(str(row.ticker or ""))
        if not tk:
            continue
        out[tk] = row
    return out


def _load_symbol_exit_rules(symbol_rules: dict[str, AutoTradeSymbolRule]) -> dict[str, tuple[float, float]]:
    out: dict[str, tuple[float, float]] = {}
    for tk, row in symbol_rules.items():
        if not bool(row.enabled):
            continue
        tp = max(1.0, min(30.0, float(row.take_profit_pct or 0.0)))
        sl = max(0.5, min(30.0, abs(float(row.stop_loss_pct or 0.0))))
        out[tk] = (tp, sl)
    return out


def list_active_reentry_blocks(
    session: Session,
    *,
    user_id: int,
    environment: str | None = None,
    trigger_reason: str | None = None,
    limit: int = 200,
) -> list[AutoTradeReentryBlock]:
    q = session.query(AutoTradeReentryBlock).filter(
        AutoTradeReentryBlock.user_id == int(user_id),
        AutoTradeReentryBlock.is_active.is_(True),
        AutoTradeReentryBlock.released_at.is_(None),
    )
    env = str(environment or "").strip().lower()
    if env in {"demo", "prod"}:
        q = q.filter(AutoTradeReentryBlock.environment == env)
    trigger = str(trigger_reason or "").strip().upper()
    if trigger in {"STOP_LOSS", "TAKE_PROFIT"}:
        q = q.filter(AutoTradeReentryBlock.trigger_reason == trigger)
    return (
        q.order_by(desc(AutoTradeReentryBlock.blocked_at), desc(AutoTradeReentryBlock.id))
        .limit(max(1, min(500, int(limit))))
        .all()
    )


def release_reentry_blocks(
    session: Session,
    *,
    user_id: int,
    environment: str | None = None,
    ticker: str | None = None,
    trigger_reason: str | None = None,
    release_all: bool = False,
) -> int:
    q = session.query(AutoTradeReentryBlock).filter(
        AutoTradeReentryBlock.user_id == int(user_id),
        AutoTradeReentryBlock.is_active.is_(True),
        AutoTradeReentryBlock.released_at.is_(None),
    )
    env = str(environment or "").strip().lower()
    if env in {"demo", "prod"}:
        q = q.filter(AutoTradeReentryBlock.environment == env)
    trigger = str(trigger_reason or "").strip().upper()
    if trigger in {"STOP_LOSS", "TAKE_PROFIT"}:
        q = q.filter(AutoTradeReentryBlock.trigger_reason == trigger)
    tk = _norm_ticker(str(ticker or ""))
    if tk:
        q = q.filter(AutoTradeReentryBlock.ticker == tk)
    if not release_all and not tk:
        return 0
    rows = q.all()
    if not rows:
        return 0
    ts = now()
    for row in rows:
        row.is_active = False
        row.released_at = ts
        row.note = (str(row.note or "").strip() + "|USER_RELEASED").strip("|")
    session.flush()
    return len(rows)


@dataclass
class _OpenLot:
    order: AutoTradeOrder
    ticker: str
    source_tab: str
    qty: int
    entry_price: float
    opened_at: datetime


@dataclass
class _PositionSummary:
    ticker: str
    source_tab: str
    qty: int
    avg_price: float
    lots: list[_OpenLot]


@dataclass
class _PnLSnapshot:
    positions: dict[str, _PositionSummary]
    realized_today_krw: float
    realized_cost_today_krw: float
    closed_pnl_pct_today: list[float]


@dataclass
class _RuntimePosition:
    ticker: str
    name: str | None
    source_tab: str
    qty: int
    sellable_qty: int
    avg_price: float
    current_price: float


def _build_pnl_snapshot(session: Session, user_id: int, ymd: date) -> _PnLSnapshot:
    rows = (
        session.query(AutoTradeOrder)
        .filter(AutoTradeOrder.user_id == user_id)
        .order_by(AutoTradeOrder.requested_at.asc(), AutoTradeOrder.id.asc())
        .all()
    )
    lots_by_ticker: dict[str, list[_OpenLot]] = {}
    realized_today = 0.0
    realized_cost_today = 0.0
    closed_pnl_pct_today: list[float] = []

    for row in rows:
        if not _filled_status(str(row.status)):
            continue
        qty = max(0, int(row.qty or 0))
        price = float(row.filled_price or row.requested_price or 0.0)
        if qty <= 0 or price <= 0.0:
            continue
        ticker = str(row.ticker or "")
        if not ticker:
            continue
        side = str(row.side or "BUY").upper()
        if side == "BUY":
            lots_by_ticker.setdefault(ticker, []).append(
                _OpenLot(
                    order=row,
                    ticker=ticker,
                    source_tab=str(row.source_tab or "UNKNOWN"),
                    qty=qty,
                    entry_price=price,
                    opened_at=row.requested_at,
                )
            )
            continue
        if side != "SELL":
            continue

        close_qty = qty
        lots = lots_by_ticker.get(ticker) or []
        while close_qty > 0 and lots:
            lot = lots[0]
            matched = min(close_qty, lot.qty)
            pnl_krw = (price - lot.entry_price) * matched
            if row.requested_at.date() == ymd:
                realized_today += pnl_krw
                realized_cost_today += lot.entry_price * matched
                close_pct = ((price / lot.entry_price) - 1.0) * 100.0 if lot.entry_price > 0.0 else 0.0
                closed_pnl_pct_today.append(close_pct)
            lot.qty -= matched
            close_qty -= matched
            if lot.qty <= 0:
                lots.pop(0)
        if lots:
            lots_by_ticker[ticker] = lots
        elif ticker in lots_by_ticker:
            del lots_by_ticker[ticker]

    positions: dict[str, _PositionSummary] = {}
    for ticker, lots in lots_by_ticker.items():
        active_lots = [lot for lot in lots if lot.qty > 0]
        if not active_lots:
            continue
        total_qty = sum(lot.qty for lot in active_lots)
        if total_qty <= 0:
            continue
        total_cost = sum(lot.entry_price * lot.qty for lot in active_lots)
        source_tab = max(active_lots, key=lambda x: x.qty).source_tab
        positions[ticker] = _PositionSummary(
            ticker=ticker,
            source_tab=source_tab,
            qty=total_qty,
            avg_price=(total_cost / total_qty) if total_qty > 0 else 0.0,
            lots=active_lots,
        )
    return _PnLSnapshot(
        positions=positions,
        realized_today_krw=realized_today,
        realized_cost_today_krw=realized_cost_today,
        closed_pnl_pct_today=closed_pnl_pct_today,
    )


def recompute_daily_metric(session: Session, user_id: int, ymd: date) -> AutoTradeDailyMetric:
    day_key = ymd.isoformat()
    day_orders = (
        session.query(AutoTradeOrder)
        .filter(
            AutoTradeOrder.user_id == user_id,
            func.date(AutoTradeOrder.requested_at) == day_key,
        )
        .order_by(AutoTradeOrder.requested_at.desc())
        .all()
    )
    day_filled_orders = [o for o in day_orders if _filled_status(str(o.status))]
    snapshot = _build_pnl_snapshot(session, user_id, ymd)
    open_positions = snapshot.positions
    tickers = [tk for tk in open_positions.keys() if _is_kr_ticker(str(tk))]
    qmap = {q.ticker: q for q in fetch_quotes(tickers)} if tickers else {}

    buy_amount = 0.0
    eval_amount = 0.0
    unrealized = 0.0
    wins = 0
    pnl_samples = 0
    min_pnl_pct = 0.0
    for pos in open_positions.values():
        q = qmap.get(pos.ticker)
        current = float(q.price or 0.0) if q is not None and (q.price or 0.0) > 0.0 else float(pos.avg_price)
        pnl_pct = ((current / pos.avg_price) - 1.0) * 100.0 if pos.avg_price > 0.0 else 0.0
        for lot in pos.lots:
            lot.order.current_price = current
            lot.order.pnl_pct = pnl_pct
        buy_amount += pos.avg_price * pos.qty
        eval_amount += current * pos.qty
        unrealized += (current - pos.avg_price) * pos.qty
        if pnl_pct > 0.0:
            wins += 1
        pnl_samples += 1
        if pnl_pct < min_pnl_pct:
            min_pnl_pct = pnl_pct
    for pnl_pct in snapshot.closed_pnl_pct_today:
        if pnl_pct > 0.0:
            wins += 1
        pnl_samples += 1
        if pnl_pct < min_pnl_pct:
            min_pnl_pct = pnl_pct

    base_amount = buy_amount + snapshot.realized_cost_today_krw
    total_pnl = snapshot.realized_today_krw + unrealized
    roi_pct = ((total_pnl / base_amount) * 100.0) if base_amount > 0.0 else 0.0
    win_rate = (wins / pnl_samples) if pnl_samples > 0 else 0.0
    mdd_pct = abs(min(0.0, min_pnl_pct))
    row = session.scalar(
        select(AutoTradeDailyMetric)
        .where(AutoTradeDailyMetric.user_id == user_id, AutoTradeDailyMetric.ymd == ymd)
        .limit(1)
    )
    ts = now()
    if row is None:
        row = AutoTradeDailyMetric(user_id=user_id, ymd=ymd, updated_at=ts)
        session.add(row)
    row.orders_total = len(day_orders)
    row.filled_total = len(day_filled_orders)
    row.buy_amount_krw = buy_amount
    row.eval_amount_krw = eval_amount
    row.realized_pnl_krw = snapshot.realized_today_krw
    row.unrealized_pnl_krw = unrealized
    row.roi_pct = roi_pct
    row.win_rate = win_rate
    row.mdd_pct = mdd_pct
    row.updated_at = ts
    session.flush()
    return row


@dataclass
class RunResult:
    run_id: str
    message: str
    candidates: list[dict[str, Any]]
    created_orders: list[AutoTradeOrder]
    metric: AutoTradeDailyMetric | None


def run_autotrade_once(
    session: Session,
    user_id: int,
    cfg: AutoTradeSetting,
    *,
    dry_run: bool = False,
    limit: int | None = None,
    broker_credentials: KisCredentialBundle | None = None,
    record_skipped_orders: bool = True,
    candidate_profile: str = "full",
    execution_mode: str = "all",
) -> RunResult:
    def _resolve_runtime_price(pos: _RuntimePosition, quote_price: float | None) -> tuple[float, str]:
        broker_px = float(pos.current_price or 0.0)
        if broker_px > 0.0:
            return broker_px, "BROKER_BALANCE"
        qpx = float(quote_price or 0.0)
        if qpx > 0.0:
            return qpx, "QUOTE"
        avg = float(pos.avg_price or 0.0)
        if avg > 0.0:
            return avg, "AVG_FALLBACK"
        return 0.0, "UNAVAILABLE"

    mode = str(execution_mode or "all").strip().lower()
    if mode not in {"all", "exit_only", "entry_only"}:
        mode = "all"
    run_exit_phase = mode in {"all", "exit_only"}
    run_entry_phase = mode in {"all", "entry_only"}

    max_orders = max(1, min(int(limit or cfg.max_orders_per_run), 100))
    candidates: list[dict[str, Any]] = []
    if run_entry_phase or dry_run:
        candidates = build_autotrade_candidates(
            session,
            user_id,
            cfg,
            limit=max(20, max_orders * 4),
            profile=candidate_profile,
        )
    run_id = uuid4().hex[:12]
    if dry_run:
        return RunResult(
            run_id=run_id,
            message=f"DRY_RUN[{mode}]: candidates={len(candidates)}",
            candidates=candidates,
            created_orders=[],
            metric=None,
        )
    if not cfg.enabled:
        return RunResult(
            run_id=run_id,
            message="AUTOTRADE_DISABLED",
            candidates=candidates,
            created_orders=[],
            metric=None,
        )

    today = datetime.now().date()
    latest_metric = session.scalar(
        select(AutoTradeDailyMetric)
        .where(AutoTradeDailyMetric.user_id == user_id, AutoTradeDailyMetric.ymd == today)
        .limit(1)
    )
    entry_blocked_by_daily_loss = (
        latest_metric is not None and float(latest_metric.roi_pct or 0.0) <= -abs(float(cfg.max_daily_loss_pct or 0.0))
    )

    broker = KisBrokerClient(credentials=broker_credentials)
    target_env = "demo" if cfg.environment == "demo" else "prod"
    use_live_positions = cfg.environment in {"demo", "prod"} and settings.kis_trading_enabled
    if use_live_positions and not broker.has_required_config(target_env):
        return RunResult(
            run_id=run_id,
            message="BROKER_CREDENTIAL_MISSING",
            candidates=candidates,
            created_orders=[],
            metric=latest_metric,
        )

    pnl_snapshot = _build_pnl_snapshot(session, user_id, today)
    runtime_positions: list[_RuntimePosition]
    available_orderable_cash: float | None = None
    if use_live_positions:
        bal = broker.inquire_balance(env=target_env)
        if not bal.ok or bal.snapshot is None:
            return RunResult(
                run_id=run_id,
                message=f"BROKER_BALANCE_UNAVAILABLE:{bal.message}",
                candidates=candidates,
                created_orders=[],
                metric=latest_metric,
            )
        available_orderable_cash = max(
            0.0,
            float(bal.snapshot.orderable_cash_amount or bal.snapshot.cash_amount or 0.0),
        )
        runtime_positions = []
        for p in bal.snapshot.positions:
            tk = _norm_ticker(str(p.ticker or ""))
            if not tk or int(p.qty) <= 0:
                continue
            runtime_positions.append(
                _RuntimePosition(
                    ticker=tk,
                    name=(str(p.name).strip() or None),
                    source_tab="BROKER_LIVE",
                    qty=max(0, int(p.qty)),
                    sellable_qty=max(0, int(getattr(p, "orderable_qty", p.qty))),
                    avg_price=max(0.0, float(p.avg_price or 0.0)),
                    current_price=max(0.0, float(p.current_price or 0.0)),
                )
            )
    else:
        runtime_positions = []
        for pos in pnl_snapshot.positions.values():
            runtime_positions.append(
                _RuntimePosition(
                    ticker=_norm_ticker(str(pos.ticker or "")),
                    name=next((lot.order.name for lot in pos.lots if lot.order.name), None),
                    source_tab=pos.source_tab,
                    qty=max(0, int(pos.qty)),
                    sellable_qty=max(0, int(pos.qty)),
                    avg_price=max(0.0, float(pos.avg_price or 0.0)),
                    current_price=0.0,
                )
            )

    open_tickers = {_norm_ticker(str(pos.ticker or "")) for pos in runtime_positions if _norm_ticker(str(pos.ticker or ""))}
    runtime_pos_map = {_norm_ticker(str(pos.ticker or "")): pos for pos in runtime_positions if _norm_ticker(str(pos.ticker or ""))}
    kr_open_tickers = [tk for tk in open_tickers if _is_kr_ticker(str(tk))]
    open_quote_map = {_norm_ticker(q.ticker): q for q in fetch_quotes(kr_open_tickers)} if kr_open_tickers else {}

    current_exposure = 0.0
    for pos in runtime_positions:
        q = open_quote_map.get(_norm_ticker(str(pos.ticker)))
        qpx = float(q.price or 0.0) if q is not None else 0.0
        px, _ = _resolve_runtime_price(pos, qpx)
        current_exposure += px * max(0, int(pos.qty))
    max_seed = max(10000000.0, float(cfg.seed_krw or 0.0))
    take_profit_pct_global = max(1.0, float(cfg.take_profit_pct or 0.0))
    stop_loss_pct_global = abs(float(cfg.stop_loss_pct or 0.0))
    symbol_rule_rows = _load_symbol_rules(session, user_id)
    symbol_exit_rules = _load_symbol_exit_rules(symbol_rule_rows)
    stoploss_reentry_policy = _normalize_reentry_policy(str(getattr(cfg, "stoploss_reentry_policy", "cooldown") or "cooldown"))
    stoploss_reentry_cooldown_min = max(1, min(1440, int(getattr(cfg, "stoploss_reentry_cooldown_min", 30) or 30)))
    takeprofit_reentry_policy = _normalize_reentry_policy(str(getattr(cfg, "takeprofit_reentry_policy", "cooldown") or "cooldown"))
    takeprofit_reentry_cooldown_min = max(1, min(1440, int(getattr(cfg, "takeprofit_reentry_cooldown_min", 30) or 30)))
    pending_guard_sec = max(30, min(3600, int(getattr(settings, "autotrade_pending_order_guard_sec", 300))))
    pending_buy_tickers = _recent_pending_order_tickers(
        session,
        user_id=user_id,
        environment=target_env,
        side="BUY",
        within_sec=pending_guard_sec,
    )
    pending_sell_tickers = _recent_pending_order_tickers(
        session,
        user_id=user_id,
        environment=target_env,
        side="SELL",
        within_sec=pending_guard_sec,
    )
    stoploss_blocked_tickers = _recent_trigger_blocked_tickers(
        session,
        user_id=user_id,
        environment=target_env,
        trigger_reason="STOP_LOSS",
        policy=stoploss_reentry_policy,
        cooldown_min=stoploss_reentry_cooldown_min,
    )
    takeprofit_blocked_tickers = _recent_trigger_blocked_tickers(
        session,
        user_id=user_id,
        environment=target_env,
        trigger_reason="TAKE_PROFIT",
        policy=takeprofit_reentry_policy,
        cooldown_min=takeprofit_reentry_cooldown_min,
    )

    created: list[AutoTradeOrder] = []

    def _submit_order(
        *,
        side: str,
        source_tab: str,
        ticker: str,
        name: str | None,
        qty: int,
        price: float,
        reason: str | None,
        metadata: dict[str, Any],
        force_skip: bool = False,
    ) -> AutoTradeOrder | None:
        status = "SKIPPED"
        broker_order_no = None
        fill_price = None
        fill_at = None
        reason_text = reason
        env_tag = str(getattr(cfg, "environment", "demo") or "demo").strip().lower()
        if env_tag not in {"paper", "demo", "prod"}:
            env_tag = "demo"
        metadata["environment"] = env_tag
        if force_skip:
            reason_text = reason_text or "SKIPPED_BY_RULE"
        elif price <= 0.0:
            reason_text = "PRICE_UNAVAILABLE"
        elif qty <= 0:
            reason_text = "QTY_ZERO"
        elif cfg.environment == "paper":
            status = "PAPER_FILLED"
            fill_price = price
            fill_at = now()
            reason_text = reason_text or "PAPER_MODE"
        elif not settings.kis_trading_enabled:
            reason_text = reason_text or "KIS_TRADING_DISABLED"
        else:
            env = target_env
            result = broker.order_cash(
                env=env,
                side=("sell" if side.upper() == "SELL" else "buy"),
                ticker=ticker,
                qty=qty,
                price=price,
                market_order=bool(cfg.allow_market_order),
            )
            metadata["broker"] = {"ok": result.ok, "status_code": result.status_code, "message": result.message}
            if result.ok:
                status = "BROKER_SUBMITTED"
                broker_order_no = result.order_no
                reason_text = reason_text or result.message
            else:
                status = "BROKER_REJECTED"
                reason_text = result.message

        if status == "SKIPPED" and not record_skipped_orders:
            return None

        row = AutoTradeOrder(
            user_id=user_id,
            run_id=run_id,
            source_tab=source_tab,
            ticker=ticker,
            name=name,
            side=("SELL" if side.upper() == "SELL" else "BUY"),
            qty=max(0, qty),
            requested_price=price,
            filled_price=fill_price,
            current_price=price if price > 0.0 else None,
            pnl_pct=None,
            status=status,
            broker_order_no=broker_order_no,
            reason=reason_text,
            metadata_json=json.dumps(metadata, ensure_ascii=False),
            requested_at=now(),
            filled_at=fill_at,
        )
        session.add(row)
        created.append(row)
        return row

    # 1) Exit phase: apply take-profit / stop-loss to open positions first.
    if run_exit_phase:
        for pos in sorted(runtime_positions, key=lambda x: x.ticker):
            ticker = _norm_ticker(str(pos.ticker or ""))
            if not ticker:
                continue
            if ticker in pending_sell_tickers:
                sellable_qty = max(0, min(int(pos.qty), int(pos.sellable_qty)))
                _submit_order(
                    side="SELL",
                    source_tab=pos.source_tab,
                    ticker=ticker,
                    name=pos.name,
                    qty=sellable_qty,
                    price=max(0.0, float(pos.current_price or 0.0)),
                    reason="PENDING_SELL_ORDER",
                    metadata={
                        "kind": "EXIT",
                        "holding_qty": int(pos.qty),
                        "sellable_qty": int(pos.sellable_qty),
                        "pending_guard_sec": int(pending_guard_sec),
                    },
                    force_skip=True,
                )
                continue
            q = open_quote_map.get(ticker)
            qpx = float(q.price or 0.0) if q is not None else 0.0
            current, price_source = _resolve_runtime_price(pos, qpx)
            if current <= 0.0 or pos.avg_price <= 0.0 or pos.qty <= 0:
                continue
            pnl_pct = ((current / pos.avg_price) - 1.0) * 100.0
            per_symbol = symbol_exit_rules.get(ticker)
            take_profit_pct = per_symbol[0] if per_symbol is not None else take_profit_pct_global
            stop_loss_pct = per_symbol[1] if per_symbol is not None else stop_loss_pct_global
            exit_reason = None
            if pnl_pct >= take_profit_pct:
                exit_reason = "TAKE_PROFIT"
            elif pnl_pct <= -stop_loss_pct:
                exit_reason = "STOP_LOSS"
            if exit_reason is None:
                continue
            sell_qty = max(0, min(int(pos.qty), int(pos.sellable_qty)))
            if sell_qty <= 0:
                _submit_order(
                    side="SELL",
                    source_tab=pos.source_tab,
                    ticker=ticker,
                    name=pos.name,
                    qty=0,
                    price=current,
                    reason="SELLABLE_QTY_ZERO",
                    metadata={
                        "kind": "EXIT",
                        "trigger": exit_reason,
                        "entry_avg_price": pos.avg_price,
                        "current_price": current,
                        "price_source": price_source,
                        "pnl_pct": pnl_pct,
                        "holding_qty": int(pos.qty),
                        "sellable_qty": int(pos.sellable_qty),
                        "exit_rule_source": ("SYMBOL" if per_symbol is not None else "GLOBAL"),
                        "take_profit_pct": take_profit_pct,
                        "stop_loss_pct": stop_loss_pct,
                    },
                    force_skip=True,
                )
                continue
            created_row = _submit_order(
                side="SELL",
                source_tab=pos.source_tab,
                ticker=ticker,
                name=pos.name,
                qty=sell_qty,
                price=current,
                reason=exit_reason,
                metadata={
                    "kind": "EXIT",
                    "trigger": exit_reason,
                    "entry_avg_price": pos.avg_price,
                    "current_price": current,
                    "price_source": price_source,
                    "pnl_pct": pnl_pct,
                    "holding_qty": int(pos.qty),
                    "sellable_qty": int(pos.sellable_qty),
                    "exit_rule_source": ("SYMBOL" if per_symbol is not None else "GLOBAL"),
                    "take_profit_pct": take_profit_pct,
                    "stop_loss_pct": stop_loss_pct,
                },
            )
            if created_row is not None and str(created_row.status).upper() in {"PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"}:
                if exit_reason == "STOP_LOSS" and stoploss_reentry_policy == "manual_block":
                    _upsert_manual_reentry_block(
                        session,
                        user_id=user_id,
                        environment=target_env,
                        ticker=ticker,
                        trigger_reason="STOP_LOSS",
                    )
                elif exit_reason == "TAKE_PROFIT" and takeprofit_reentry_policy == "manual_block":
                    _upsert_manual_reentry_block(
                        session,
                        user_id=user_id,
                        environment=target_env,
                        ticker=ticker,
                        trigger_reason="TAKE_PROFIT",
                    )
            pending_sell_tickers.add(ticker)

    if run_entry_phase and entry_blocked_by_daily_loss:
        session.flush()
        metric = recompute_daily_metric(session, user_id, datetime.now().date())
        return RunResult(
            run_id=run_id,
            message="DAILY_LOSS_LIMIT_REACHED",
            candidates=candidates,
            created_orders=created,
            metric=metric,
        )

    # 2) Entry phase: buy candidates under seed exposure cap.
    if run_entry_phase:
        def _reentry_block_reason(trigger: str, policy: str) -> str:
            trigger_u = str(trigger or "").strip().upper()
            policy_u = _normalize_reentry_policy(policy)
            if trigger_u == "STOP_LOSS":
                if policy_u == "day_block":
                    return "STOPLOSS_REENTRY_BLOCKED_TODAY"
                if policy_u == "manual_block":
                    return "STOPLOSS_REENTRY_BLOCKED_MANUAL"
                return "STOPLOSS_REENTRY_COOLDOWN"
            if policy_u == "day_block":
                return "TAKEPROFIT_REENTRY_BLOCKED_TODAY"
            if policy_u == "manual_block":
                return "TAKEPROFIT_REENTRY_BLOCKED_MANUAL"
            return "TAKEPROFIT_REENTRY_COOLDOWN"

        for c in candidates[: max_orders]:
            ticker = _norm_ticker(str(c.get("ticker") or ""))
            symbol_rule = symbol_rule_rows.get(ticker)
            price = float(c.get("current_price") or c.get("signal_price") or 0.0)
            qty = int(float(cfg.order_budget_krw or 0.0) // price) if price > 0.0 else 0
            required_cash = price * qty
            reason = None
            if not ticker:
                reason = "TICKER_EMPTY"
            elif symbol_rule is not None and (not bool(symbol_rule.enabled)):
                reason = "ENTRY_BLOCKED_MANUAL"
            elif ticker in open_tickers:
                reason = "ALREADY_OPEN_POSITION"
            elif ticker in pending_buy_tickers:
                reason = "PENDING_BUY_ORDER"
            elif ticker in stoploss_blocked_tickers:
                reason = _reentry_block_reason("STOP_LOSS", stoploss_reentry_policy)
            elif ticker in takeprofit_blocked_tickers:
                reason = _reentry_block_reason("TAKE_PROFIT", takeprofit_reentry_policy)
            elif price <= 0.0:
                reason = "PRICE_UNAVAILABLE"
            elif qty <= 0:
                reason = "QTY_ZERO"
            elif available_orderable_cash is not None and required_cash > available_orderable_cash:
                reason = "ORDERABLE_CASH_LIMIT"
            elif current_exposure + (price * qty) > max_seed:
                reason = "SEED_LIMIT_EXCEEDED"

            if reason is not None:
                open_pos = runtime_pos_map.get(ticker)
                _submit_order(
                    side="BUY",
                    source_tab=str(c.get("source_tab") or "UNKNOWN"),
                    ticker=ticker,
                    name=str(c.get("name") or "") or None,
                    qty=max(0, qty),
                    price=price,
                    reason=reason,
                    metadata={
                        "kind": "ENTRY",
                        "candidate": c,
                        "required_cash": required_cash,
                        "available_orderable_cash": available_orderable_cash,
                        "current_exposure_krw": current_exposure,
                        "seed_limit_krw": max_seed,
                        "order_budget_krw": float(cfg.order_budget_krw or 0.0),
                        "requested_price": price,
                        "computed_qty": qty,
                        "pending_guard_sec": int(pending_guard_sec),
                        "open_qty": (int(open_pos.qty) if open_pos is not None else None),
                        "open_avg_price": (float(open_pos.avg_price) if open_pos is not None else None),
                        "stoploss_reentry_policy": stoploss_reentry_policy,
                        "stoploss_reentry_cooldown_min": int(stoploss_reentry_cooldown_min),
                        "takeprofit_reentry_policy": takeprofit_reentry_policy,
                        "takeprofit_reentry_cooldown_min": int(takeprofit_reentry_cooldown_min),
                    },
                    force_skip=True,
                )
                continue

            row = _submit_order(
                side="BUY",
                source_tab=str(c.get("source_tab") or "UNKNOWN"),
                ticker=ticker,
                name=str(c.get("name") or "") or None,
                qty=qty,
                price=price,
                reason=None,
                metadata={"kind": "ENTRY", "candidate": c},
            )
            if row is not None and _filled_status(str(row.status)):
                current_exposure += price * qty
                open_tickers.add(ticker)
                pending_buy_tickers.add(ticker)
                if available_orderable_cash is not None:
                    available_orderable_cash = max(0.0, available_orderable_cash - required_cash)

    session.flush()
    metric = recompute_daily_metric(session, user_id, datetime.now().date())
    buy_count = sum(1 for x in created if str(x.side).upper() == "BUY")
    sell_count = sum(1 for x in created if str(x.side).upper() == "SELL")
    mode_msg = {
        "all": "ALL",
        "exit_only": "EXIT_ONLY",
        "entry_only": "ENTRY_ONLY",
    }.get(mode, "ALL")
    return RunResult(
        run_id=run_id,
        message=f"RUN_OK[{mode_msg}]: orders={len(created)} (buy={buy_count}, sell={sell_count})",
        candidates=candidates,
        created_orders=created,
        metric=metric,
    )
