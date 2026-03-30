"""단타2 매매 엔진 — autotrade_service.py의 개선 버전.

개선 사항:
  P0-1: AVG_FALLBACK 3회 초과 시 강제 청산
  P0-2: fcntl 파일 락으로 동시 실행 방지
  P0-3: 일일 손실 한계 단계적 축소 (throttle → block)
  P0-4: Tick 반올림 역전 검증 (strategy2 호출)
  P1-1: 사유 코드별 suggestion
  P1-4: 부분 익절 (50% 선매도 → 잔량 2차 청산)

기존 autotrade와 독립 — 롤백 시 이 파일 + models.py autotrade2 테이블만 삭제.
"""
from __future__ import annotations

import fcntl
import json
import logging
import os
import tempfile
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from threading import Lock
from typing import Any
from uuid import uuid4

from sqlalchemy import desc, func, select
from sqlalchemy.orm import Session

from app.auth import now
from app.config import settings
from app.kis_broker import KisBrokerClient, KisCredentialBundle
from app.models import (
    AutoTrade2AvgFallbackTracker,
    AutoTrade2DailyMetric,
    AutoTrade2Order,
    AutoTrade2ReentryBlock,
    AutoTrade2Setting,
    AutoTrade2SymbolRule,
    Report,
    StrategySettings,
    UserFavorite,
)
from app.movers2 import compute_movers2
from app.realtime_quotes import fetch_quotes
from app.autotrade2_reason_codes import reason_code_suggestion
from engine.strategy import normalize_algo_version

logger = logging.getLogger(__name__)


# ── 헬퍼 ──

def _norm_ticker(raw: str) -> str:
    t = str(raw or "").strip().upper()
    return t.zfill(6) if t.isdigit() and len(t) <= 6 else t


def _is_kr_ticker(ticker: str) -> bool:
    return ticker.isdigit() and len(ticker) == 6


def _is_real_fill_status(status: str) -> bool:
    return status in {"PAPER_FILLED", "BROKER_FILLED"}


def _is_position_relevant_status(status: str) -> bool:
    return status in {"PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"}


_filled_status = _is_position_relevant_status


def _normalize_reentry_policy(raw: str | None) -> str:
    p = str(raw or "").strip().lower()
    return p if p in {"immediate", "cooldown", "day_block", "manual_block"} else "cooldown"


_MANUAL_BLOCK_EXPIRY_DAYS = 7


# ── 프리셋 설정 (P1-3) ──

PRESETS: dict[str, dict[str, Any]] = {
    "conservative": {
        "order_budget_krw": 100000.0,
        "max_orders_per_run": 3,
        "take_profit_pct": 5.0,
        "stop_loss_pct": 3.0,
        "partial_tp_enabled": True,
        "partial_tp_ratio": 0.5,
        "partial_tp_pct": 3.0,
        "final_tp_pct": 5.0,
        "daily_loss_throttle_pct": 2.0,
        "daily_loss_block_pct": 3.0,
        "label": "보수적 — 소액, 빠른 손절",
    },
    "balanced": {
        "order_budget_krw": 200000.0,
        "max_orders_per_run": 5,
        "take_profit_pct": 7.0,
        "stop_loss_pct": 5.0,
        "partial_tp_enabled": True,
        "partial_tp_ratio": 0.5,
        "partial_tp_pct": 5.0,
        "final_tp_pct": 7.0,
        "daily_loss_throttle_pct": 3.0,
        "daily_loss_block_pct": 5.0,
        "label": "균형 — 기본 설정",
    },
    "aggressive": {
        "order_budget_krw": 500000.0,
        "max_orders_per_run": 8,
        "take_profit_pct": 10.0,
        "stop_loss_pct": 7.0,
        "partial_tp_enabled": False,
        "partial_tp_ratio": 0.5,
        "partial_tp_pct": 7.0,
        "final_tp_pct": 10.0,
        "daily_loss_throttle_pct": 5.0,
        "daily_loss_block_pct": 8.0,
        "label": "공격적 — 대규모 주문, 넓은 범위",
    },
}


# ── 캐시 ──

_CANDIDATE_CACHE: dict[str, tuple[datetime, list[dict[str, Any]], dict[str, Any]]] = {}
_CANDIDATE_CACHE_LOCK = Lock()
_CANDIDATE_TTL = max(5, min(120, int(getattr(settings, "autotrade2_candidates_ttl_sec", 20))))


# ── 포지션/PnL 관련 데이터클래스 ──

@dataclass
class _OpenLot:
    order: AutoTrade2Order
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


@dataclass
class RunResult:
    run_id: str
    message: str
    candidates: list[dict[str, Any]]
    created_orders: list[AutoTrade2Order]
    metric: AutoTrade2DailyMetric | None


# ── 프리마켓 리포트 조회 ──

def _latest_premarket_payload(session: Session) -> dict | None:
    row = session.scalar(
        select(Report)
        .where(Report.type == "PREMARKET")
        .order_by(desc(Report.generated_at))
        .limit(1)
    )
    if row is None:
        return None
    try:
        payload = json.loads(row.payload_json or "{}")
        return payload if isinstance(payload, dict) else None
    except Exception:
        return None


# ── 후보 빌드 (기존 autotrade_service.build_autotrade_candidates와 동일) ──

def build_autotrade2_candidates(
    session: Session,
    user_id: int,
    cfg: AutoTrade2Setting,
    limit: int = 50,
    diagnostics: dict[str, Any] | None = None,
    profile: str = "full",
    use_cache: bool = True,
) -> list[dict[str, Any]]:
    """후보 빌드 — 기존 autotrade와 동일 로직."""
    bucket: dict[str, dict[str, Any]] = {}
    source_counts: dict[str, int] = {"DAYTRADE": 0, "MOVERS": 0, "SUPPLY": 0, "PAPERS": 0, "LONGTERM": 0, "FAVORITES": 0, "RECENT": 0}
    warnings: list[str] = []

    def _put(source_tab: str, ticker: str, name: str | None, signal_price: float | None, note: str | None = None) -> None:
        tk = _norm_ticker(ticker)
        if not tk or tk in bucket:
            return
        source_key = source_tab if source_tab in source_counts else "RECENT"
        source_counts[source_key] = int(source_counts.get(source_key, 0)) + 1
        bucket[tk] = {
            "ticker": tk,
            "name": (name or "").strip() or None,
            "source_tab": source_tab,
            "signal_price": float(signal_price) if signal_price is not None else None,
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
            movers = compute_movers2(
                session="regular", direction="up", count=movers_limit,
                universe_top_value=500, universe_top_chg=200,
                markets=["KOSPI", "KOSDAQ"], algo_version=movers_algo,
            )
            for it in (movers.get("items") or []):
                if isinstance(it, dict):
                    _put("MOVERS", str(it.get("ticker") or ""), it.get("name"), it.get("price"), "급등 세션 신호")
        except Exception as exc:
            warnings.append(f"MOVERS_SOURCE_ERROR:{exc.__class__.__name__}")
    if cfg.include_supply:
        try:
            from app.main import _compute_supply_live  # type: ignore
            supply_count = max(20, min(100, max(limit * 2, 40)))
            supply = _compute_supply_live(
                count=supply_count, days=20, universe_top_value=450, universe_top_chg=220,
                markets=["KOSPI", "KOSDAQ"], include_contrarian=True,
            )
            for it in (getattr(supply, "items", None) or []):
                _put("SUPPLY", str(getattr(it, "ticker", "") or ""), getattr(it, "name", None),
                     getattr(it, "price", None), "수급 포켓 신호")
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
    if diagnostics is not None:
        diagnostics["source_counts"] = source_counts
        diagnostics["warnings"] = warnings
        diagnostics["premarket_available"] = premarket is not None
    return ordered


# ── PnL 스냅샷 빌드 ──

def _build_pnl_snapshot(session: Session, user_id: int, ymd: date) -> _PnLSnapshot:
    rows = (
        session.query(AutoTrade2Order)
        .filter(AutoTrade2Order.user_id == user_id)
        .order_by(AutoTrade2Order.requested_at.asc(), AutoTrade2Order.id.asc())
        .all()
    )
    lots_by_ticker: dict[str, list[_OpenLot]] = {}
    realized_today = 0.0
    realized_cost_today = 0.0
    closed_pnl_pct_today: list[float] = []

    for row in rows:
        if not _is_real_fill_status(str(row.status)):
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
                _OpenLot(order=row, ticker=ticker, source_tab=str(row.source_tab or "UNKNOWN"),
                         qty=qty, entry_price=price, opened_at=row.requested_at)
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
            ticker=ticker, source_tab=source_tab, qty=total_qty,
            avg_price=(total_cost / total_qty) if total_qty > 0 else 0.0, lots=active_lots,
        )
    return _PnLSnapshot(
        positions=positions, realized_today_krw=realized_today,
        realized_cost_today_krw=realized_cost_today, closed_pnl_pct_today=closed_pnl_pct_today,
    )


# ── 일일 메트릭 재계산 ──

def recompute_daily_metric(session: Session, user_id: int, ymd: date, *, cfg: AutoTrade2Setting | None = None) -> AutoTrade2DailyMetric:
    day_key = ymd.isoformat()
    day_orders = (
        session.query(AutoTrade2Order)
        .filter(AutoTrade2Order.user_id == user_id, func.date(AutoTrade2Order.requested_at) == day_key)
        .order_by(AutoTrade2Order.requested_at.desc())
        .all()
    )
    day_filled = [o for o in day_orders if _is_real_fill_status(str(o.status))]
    snapshot = _build_pnl_snapshot(session, user_id, ymd)
    tickers = [tk for tk in snapshot.positions.keys() if _is_kr_ticker(str(tk))]
    qmap = {q.ticker: q for q in fetch_quotes(tickers)} if tickers else {}

    buy_amount = eval_amount = unrealized = 0.0
    wins = pnl_samples = 0
    min_pnl_pct = 0.0
    for pos in snapshot.positions.values():
        q = qmap.get(pos.ticker)
        current = float(q.price or 0.0) if q is not None and (q.price or 0.0) > 0.0 else float(pos.avg_price)
        pnl_pct = ((current / pos.avg_price) - 1.0) * 100.0 if pos.avg_price > 0.0 else 0.0
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

    # P0-3: 단계적 손실 상태 판단
    throttle_pct = float(cfg.daily_loss_throttle_pct or 3.0) if cfg else 3.0
    block_pct = float(cfg.daily_loss_block_pct or 5.0) if cfg else 5.0
    loss_throttle_active = roi_pct <= -abs(throttle_pct)
    loss_blocked = roi_pct <= -abs(block_pct)

    row = session.scalar(
        select(AutoTrade2DailyMetric)
        .where(AutoTrade2DailyMetric.user_id == user_id, AutoTrade2DailyMetric.ymd == ymd)
        .limit(1)
    )
    ts = now()
    if row is None:
        row = AutoTrade2DailyMetric(user_id=user_id, ymd=ymd, updated_at=ts)
        session.add(row)
    row.orders_total = len(day_orders)
    row.filled_total = len(day_filled)
    row.buy_amount_krw = buy_amount
    row.eval_amount_krw = eval_amount
    row.realized_pnl_krw = snapshot.realized_today_krw
    row.unrealized_pnl_krw = unrealized
    row.roi_pct = roi_pct
    row.win_rate = win_rate
    row.mdd_pct = mdd_pct
    row.loss_throttle_active = loss_throttle_active
    row.loss_blocked = loss_blocked
    row.updated_at = ts
    session.flush()
    return row


# ── 리엔트리 헬퍼 ──

def _order_environment(row: AutoTrade2Order) -> str | None:
    try:
        meta = json.loads(str(getattr(row, "metadata_json", None) or "{}"))
    except Exception:
        meta = {}
    env = str(meta.get("environment") or "").strip().lower()
    return env if env in {"paper", "demo", "prod"} else None


def _recent_pending_order_tickers(session: Session, *, user_id: int, environment: str, side: str, within_sec: int) -> set[str]:
    if within_sec <= 0:
        return set()
    cutoff = now() - timedelta(seconds=int(within_sec))
    side_u = "SELL" if str(side or "").strip().upper() == "SELL" else "BUY"
    rows = (
        session.query(AutoTrade2Order)
        .filter(AutoTrade2Order.user_id == int(user_id), AutoTrade2Order.side == side_u,
                AutoTrade2Order.status == "BROKER_SUBMITTED", AutoTrade2Order.requested_at >= cutoff)
        .limit(400).all()
    )
    out: set[str] = set()
    target_env = str(environment or "").strip().lower()
    for row in rows:
        tk = _norm_ticker(str(getattr(row, "ticker", "") or ""))
        if not tk:
            continue
        row_env = _order_environment(row)
        if target_env and row_env != target_env:
            continue
        out.add(tk)
    return out


def _recent_trigger_blocked_tickers(session: Session, *, user_id: int, environment: str, trigger_reason: str, policy: str, cooldown_min: int) -> set[str]:
    policy_u = _normalize_reentry_policy(policy)
    if policy_u == "immediate":
        return set()
    if policy_u == "manual_block":
        rows = (
            session.query(AutoTrade2ReentryBlock)
            .filter(AutoTrade2ReentryBlock.user_id == int(user_id),
                    AutoTrade2ReentryBlock.environment == str(environment or "demo").strip().lower(),
                    AutoTrade2ReentryBlock.trigger_reason == str(trigger_reason or "").strip().upper(),
                    AutoTrade2ReentryBlock.is_active.is_(True), AutoTrade2ReentryBlock.released_at.is_(None))
            .limit(1000).all()
        )
        now_ts = now()
        active: set[str] = set()
        for row in rows:
            tk = _norm_ticker(str(getattr(row, "ticker", "") or ""))
            if not tk:
                continue
            blocked_at = getattr(row, "blocked_at", None)
            if blocked_at and (now_ts - blocked_at) >= timedelta(days=_MANUAL_BLOCK_EXPIRY_DAYS):
                row.is_active = False
                row.released_at = now_ts
                row.note = (row.note or "") + " | AUTO_EXPIRED_7D"
                continue
            active.add(tk)
        return active

    now_ts = now()
    if policy_u == "day_block":
        cutoff = datetime.combine(now_ts.date(), datetime.min.time(), tzinfo=now_ts.tzinfo)
    else:
        cutoff = now_ts - timedelta(minutes=max(1, min(1440, int(cooldown_min))))
    rows = (
        session.query(AutoTrade2Order)
        .filter(AutoTrade2Order.user_id == int(user_id), AutoTrade2Order.side == "SELL",
                AutoTrade2Order.status.in_(["PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"]),
                AutoTrade2Order.reason == str(trigger_reason or "").strip().upper(),
                AutoTrade2Order.requested_at >= cutoff)
        .limit(400).all()
    )
    out: set[str] = set()
    target_env = str(environment or "").strip().lower()
    for row in rows:
        tk = _norm_ticker(str(getattr(row, "ticker", "") or ""))
        if tk:
            row_env = _order_environment(row)
            if not target_env or row_env == target_env:
                out.add(tk)
    return out


def _load_symbol_rules(session: Session, user_id: int) -> dict[str, AutoTrade2SymbolRule]:
    rows = session.query(AutoTrade2SymbolRule).filter(AutoTrade2SymbolRule.user_id == user_id).all()
    return {_norm_ticker(str(r.ticker or "")): r for r in rows if _norm_ticker(str(r.ticker or ""))}


def _load_symbol_exit_rules(rules: dict[str, AutoTrade2SymbolRule]) -> dict[str, tuple[float, float, bool | None]]:
    """(tp_pct, sl_pct, partial_tp_enabled) 튜플 반환."""
    out: dict[str, tuple[float, float, bool | None]] = {}
    for tk, r in rules.items():
        if not bool(r.enabled):
            continue
        tp = max(1.0, min(30.0, float(r.take_profit_pct or 0.0)))
        sl = max(0.5, min(30.0, abs(float(r.stop_loss_pct or 0.0))))
        out[tk] = (tp, sl, r.partial_tp_enabled)
    return out


def _upsert_manual_reentry_block(session: Session, *, user_id: int, environment: str, ticker: str, trigger_reason: str) -> None:
    tk = _norm_ticker(ticker)
    if not tk:
        return
    env = str(environment or "demo").strip().lower()
    trigger = str(trigger_reason or "").strip().upper()
    if trigger not in {"STOP_LOSS", "TAKE_PROFIT"}:
        return
    ts = now()
    row = session.scalar(
        select(AutoTrade2ReentryBlock)
        .where(AutoTrade2ReentryBlock.user_id == int(user_id), AutoTrade2ReentryBlock.environment == env,
               AutoTrade2ReentryBlock.ticker == tk, AutoTrade2ReentryBlock.trigger_reason == trigger,
               AutoTrade2ReentryBlock.is_active.is_(True))
        .limit(1)
    )
    if row is None:
        session.add(AutoTrade2ReentryBlock(
            user_id=int(user_id), environment=env, ticker=tk,
            trigger_reason=trigger, is_active=True, blocked_at=ts,
            note=f"{trigger}_MANUAL_BLOCK",
        ))
    else:
        row.blocked_at = ts
        row.released_at = None


# ── P0-1: AVG_FALLBACK 추적 ──

def _track_avg_fallback(session: Session, *, user_id: int, ticker: str, environment: str, cfg: AutoTrade2Setting) -> tuple[int, bool]:
    """AVG_FALLBACK 카운트 증가. (count, should_force_exit) 반환."""
    tk = _norm_ticker(ticker)
    env = str(environment or "demo").strip().lower()
    row = session.scalar(
        select(AutoTrade2AvgFallbackTracker)
        .where(AutoTrade2AvgFallbackTracker.user_id == user_id,
               AutoTrade2AvgFallbackTracker.ticker == tk,
               AutoTrade2AvgFallbackTracker.environment == env)
        .limit(1)
    )
    ts = now()
    max_count = max(1, int(cfg.avg_fallback_max_count or 3))
    if row is None:
        row = AutoTrade2AvgFallbackTracker(
            user_id=user_id, ticker=tk, environment=env,
            fallback_count=1, last_fallback_at=ts, force_exited=False,
        )
        session.add(row)
        return 1, False
    row.fallback_count = int(row.fallback_count or 0) + 1
    row.last_fallback_at = ts
    should_force = bool(cfg.avg_fallback_force_exit) and row.fallback_count >= max_count
    if should_force:
        row.force_exited = True
    return row.fallback_count, should_force


def _reset_avg_fallback(session: Session, *, user_id: int, ticker: str, environment: str) -> None:
    """실시간 가격 정상 확보 시 카운트 리셋."""
    tk = _norm_ticker(ticker)
    env = str(environment or "demo").strip().lower()
    row = session.scalar(
        select(AutoTrade2AvgFallbackTracker)
        .where(AutoTrade2AvgFallbackTracker.user_id == user_id,
               AutoTrade2AvgFallbackTracker.ticker == tk,
               AutoTrade2AvgFallbackTracker.environment == env)
        .limit(1)
    )
    if row is not None and row.fallback_count > 0:
        row.fallback_count = 0
        row.force_exited = False


# ── 핵심 실행 엔진 ──

def run_autotrade2_once(
    session: Session,
    user_id: int,
    cfg: AutoTrade2Setting,
    *,
    dry_run: bool = False,
    limit: int | None = None,
    broker_credentials: KisCredentialBundle | None = None,
    record_skipped_orders: bool = True,
    candidate_profile: str = "full",
    execution_mode: str = "all",
    candidate_tickers: list[str] | set[str] | None = None,
) -> RunResult:
    # P0-2: 사용자별 동시 실행 방지 (fcntl 파일 락)
    lock_path = os.path.join(tempfile.gettempdir(), f"autotrade2_lock_{user_id}")
    lock_file = open(lock_path, "w")
    try:
        fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except (OSError, BlockingIOError):
        lock_file.close()
        return RunResult(
            run_id="locked", message="CONCURRENT_RUN_BLOCKED",
            candidates=[], created_orders=[], metric=None,
        )
    try:
        return _run_inner(
            session, user_id, cfg,
            dry_run=dry_run, limit=limit,
            broker_credentials=broker_credentials,
            record_skipped_orders=record_skipped_orders,
            candidate_profile=candidate_profile,
            execution_mode=execution_mode,
            candidate_tickers=candidate_tickers,
        )
    finally:
        fcntl.flock(lock_file, fcntl.LOCK_UN)
        lock_file.close()


def _run_inner(
    session: Session,
    user_id: int,
    cfg: AutoTrade2Setting,
    *,
    dry_run: bool = False,
    limit: int | None = None,
    broker_credentials: KisCredentialBundle | None = None,
    record_skipped_orders: bool = True,
    candidate_profile: str = "full",
    execution_mode: str = "all",
    candidate_tickers: list[str] | set[str] | None = None,
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
            logger.warning("[autotrade2] AVG_FALLBACK: ticker=%s, avg_price=%.2f", pos.ticker, avg)
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
        candidates = build_autotrade2_candidates(
            session, user_id, cfg,
            limit=max(20, max_orders * 4),
            profile=candidate_profile, use_cache=False,
        )
        allowed = {_norm_ticker(str(tk or "")) for tk in (candidate_tickers or []) if _norm_ticker(str(tk or ""))}
        if allowed:
            candidates = [c for c in candidates if _norm_ticker(str(c.get("ticker") or "")) in allowed]

    run_id = uuid4().hex[:12]
    if dry_run:
        return RunResult(run_id=run_id, message=f"DRY_RUN[{mode}]: candidates={len(candidates)}",
                         candidates=candidates, created_orders=[], metric=None)
    if not cfg.enabled:
        return RunResult(run_id=run_id, message="AUTOTRADE_DISABLED",
                         candidates=candidates, created_orders=[], metric=None)

    today = datetime.now().date()
    latest_metric = session.scalar(
        select(AutoTrade2DailyMetric)
        .where(AutoTrade2DailyMetric.user_id == user_id, AutoTrade2DailyMetric.ymd == today)
        .limit(1)
    )

    # P0-3: 단계적 일일 손실 판단
    current_roi = float(latest_metric.roi_pct or 0.0) if latest_metric else 0.0
    throttle_pct = abs(float(cfg.daily_loss_throttle_pct or 3.0))
    block_pct = abs(float(cfg.daily_loss_block_pct or 5.0))
    throttle_ratio = max(0.1, min(0.9, float(cfg.daily_loss_throttle_ratio or 0.5)))
    entry_blocked_by_daily_loss = current_roi <= -block_pct
    entry_throttled = not entry_blocked_by_daily_loss and current_roi <= -throttle_pct

    broker = KisBrokerClient(credentials=broker_credentials)
    target_env = "demo" if cfg.environment == "demo" else "prod"
    use_live_positions = cfg.environment in {"demo", "prod"} and settings.kis_trading_enabled
    if use_live_positions and not broker.has_required_config(target_env):
        return RunResult(run_id=run_id, message="BROKER_CREDENTIAL_MISSING",
                         candidates=candidates, created_orders=[], metric=latest_metric)

    pnl_snapshot = _build_pnl_snapshot(session, user_id, today)

    runtime_positions: list[_RuntimePosition]
    available_orderable_cash: float | None = None
    if use_live_positions:
        bal = broker.inquire_balance(env=target_env)
        if not bal.ok or bal.snapshot is None:
            return RunResult(run_id=run_id, message=f"BROKER_BALANCE_UNAVAILABLE:{bal.message}",
                             candidates=candidates, created_orders=[], metric=latest_metric)
        available_orderable_cash = max(0.0, float(bal.snapshot.orderable_cash_amount or bal.snapshot.cash_amount or 0.0))
        runtime_positions = []
        for p in bal.snapshot.positions:
            tk = _norm_ticker(str(p.ticker or ""))
            if not tk or int(p.qty) <= 0:
                continue
            runtime_positions.append(_RuntimePosition(
                ticker=tk, name=(str(p.name).strip() or None), source_tab="BROKER_LIVE",
                qty=max(0, int(p.qty)), sellable_qty=max(0, int(getattr(p, "orderable_qty", p.qty))),
                avg_price=max(0.0, float(p.avg_price or 0.0)), current_price=max(0.0, float(p.current_price or 0.0)),
            ))
    else:
        runtime_positions = []
        for pos in pnl_snapshot.positions.values():
            runtime_positions.append(_RuntimePosition(
                ticker=_norm_ticker(str(pos.ticker or "")),
                name=next((lot.order.name for lot in pos.lots if lot.order.name), None),
                source_tab=pos.source_tab, qty=max(0, int(pos.qty)),
                sellable_qty=max(0, int(pos.qty)),
                avg_price=max(0.0, float(pos.avg_price or 0.0)), current_price=0.0,
            ))

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
    stoploss_cooldown = max(1, min(1440, int(getattr(cfg, "stoploss_reentry_cooldown_min", 30) or 30)))
    takeprofit_reentry_policy = _normalize_reentry_policy(str(getattr(cfg, "takeprofit_reentry_policy", "cooldown") or "cooldown"))
    takeprofit_cooldown = max(1, min(1440, int(getattr(cfg, "takeprofit_reentry_cooldown_min", 30) or 30)))
    pending_guard_sec = max(30, min(3600, int(getattr(settings, "autotrade_pending_order_guard_sec", 300))))

    pending_buy_tickers = _recent_pending_order_tickers(session, user_id=user_id, environment=target_env, side="BUY", within_sec=pending_guard_sec)
    pending_sell_tickers = _recent_pending_order_tickers(session, user_id=user_id, environment=target_env, side="SELL", within_sec=pending_guard_sec)
    stoploss_blocked = _recent_trigger_blocked_tickers(session, user_id=user_id, environment=target_env, trigger_reason="STOP_LOSS", policy=stoploss_reentry_policy, cooldown_min=stoploss_cooldown)
    takeprofit_blocked = _recent_trigger_blocked_tickers(session, user_id=user_id, environment=target_env, trigger_reason="TAKE_PROFIT", policy=takeprofit_reentry_policy, cooldown_min=takeprofit_cooldown)

    created: list[AutoTrade2Order] = []

    def _submit_order(*, side: str, source_tab: str, ticker: str, name: str | None,
                      qty: int, price: float, reason: str | None,
                      metadata: dict[str, Any], force_skip: bool = False) -> AutoTrade2Order | None:
        status = "SKIPPED"
        broker_order_no = fill_price = fill_at = None
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
            result = broker.order_cash(
                env=target_env, side=("sell" if side.upper() == "SELL" else "buy"),
                ticker=ticker, qty=qty, price=price,
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

        # P1-1: suggestion 추가
        suggestion = reason_code_suggestion(reason_text or "") if reason_text else ""

        row = AutoTrade2Order(
            user_id=user_id, run_id=run_id, source_tab=source_tab,
            ticker=ticker, name=name, side=("SELL" if side.upper() == "SELL" else "BUY"),
            qty=max(0, qty), requested_price=price, filled_price=fill_price,
            current_price=price if price > 0.0 else None, pnl_pct=None,
            status=status, broker_order_no=broker_order_no,
            reason=reason_text, suggestion=suggestion,
            metadata_json=json.dumps(metadata, ensure_ascii=False),
            requested_at=now(), filled_at=fill_at,
        )
        session.add(row)
        created.append(row)
        return row

    # ── 1) Exit Phase ──
    if run_exit_phase:
        for pos in sorted(runtime_positions, key=lambda x: x.ticker):
            ticker = _norm_ticker(str(pos.ticker or ""))
            if not ticker:
                continue
            if ticker in pending_sell_tickers:
                _submit_order(
                    side="SELL", source_tab=pos.source_tab, ticker=ticker, name=pos.name,
                    qty=max(0, min(int(pos.qty), int(pos.sellable_qty))),
                    price=max(0.0, float(pos.current_price or 0.0)),
                    reason="PENDING_SELL_ORDER",
                    metadata={"kind": "EXIT", "pending_guard_sec": pending_guard_sec},
                    force_skip=True,
                )
                continue

            q = open_quote_map.get(ticker)
            qpx = float(q.price or 0.0) if q is not None else 0.0
            current, price_source = _resolve_runtime_price(pos, qpx)
            if current <= 0.0 or pos.avg_price <= 0.0 or pos.qty <= 0:
                continue

            # P0-1: AVG_FALLBACK 처리 — 카운트 추적 + 강제 청산
            if price_source == "AVG_FALLBACK":
                fb_count, should_force = _track_avg_fallback(
                    session, user_id=user_id, ticker=ticker, environment=target_env, cfg=cfg,
                )
                if should_force:
                    # 강제 청산
                    logger.warning("[autotrade2] AVG_FALLBACK 강제 청산: ticker=%s, count=%d", ticker, fb_count)
                    _submit_order(
                        side="SELL", source_tab=pos.source_tab, ticker=ticker, name=pos.name,
                        qty=max(0, min(int(pos.qty), int(pos.sellable_qty))),
                        price=current, reason="AVG_FALLBACK_FORCE_EXIT",
                        metadata={"kind": "EXIT", "price_source": price_source, "fallback_count": fb_count,
                                  "avg_price": pos.avg_price},
                    )
                    pending_sell_tickers.add(ticker)
                else:
                    # 아직 한도 미달 — 스킵 + 기록
                    _submit_order(
                        side="SELL", source_tab=pos.source_tab, ticker=ticker, name=pos.name,
                        qty=max(0, min(int(pos.qty), int(pos.sellable_qty))),
                        price=current, reason="SKIPPED_PRICE_UNCERTAIN",
                        metadata={"kind": "EXIT", "price_source": price_source, "fallback_count": fb_count,
                                  "avg_price": pos.avg_price, "max_before_force": int(cfg.avg_fallback_max_count or 3)},
                        force_skip=True,
                    )
                continue
            else:
                # 실시간 가격 정상 → 카운트 리셋
                _reset_avg_fallback(session, user_id=user_id, ticker=ticker, environment=target_env)

            pnl_pct = ((current / pos.avg_price) - 1.0) * 100.0
            per_symbol = symbol_exit_rules.get(ticker)
            take_profit_pct = per_symbol[0] if per_symbol is not None else take_profit_pct_global
            stop_loss_pct = per_symbol[1] if per_symbol is not None else stop_loss_pct_global

            # P1-4: 부분 익절 판단
            partial_tp_enabled = bool(cfg.partial_tp_enabled)
            if per_symbol is not None and per_symbol[2] is not None:
                partial_tp_enabled = bool(per_symbol[2])
            partial_tp_pct = float(cfg.partial_tp_pct or 5.0)
            partial_tp_ratio = float(cfg.partial_tp_ratio or 0.5)
            final_tp_pct = float(cfg.final_tp_pct or take_profit_pct)

            exit_reason = None
            sell_ratio = 1.0  # 전량 매도 기본

            if pnl_pct >= final_tp_pct:
                exit_reason = "TAKE_PROFIT"
            elif partial_tp_enabled and pnl_pct >= partial_tp_pct:
                # 부분 익절: 이미 부분 익절 했는지 확인 (metadata 검사)
                already_partial = session.scalar(
                    select(AutoTrade2Order)
                    .where(AutoTrade2Order.user_id == user_id, AutoTrade2Order.ticker == ticker,
                           AutoTrade2Order.side == "SELL", AutoTrade2Order.reason == "PARTIAL_TAKE_PROFIT",
                           AutoTrade2Order.status.in_(["PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"]))
                    .limit(1)
                )
                if already_partial is None:
                    exit_reason = "PARTIAL_TAKE_PROFIT"
                    sell_ratio = partial_tp_ratio
            elif pnl_pct <= -stop_loss_pct:
                exit_reason = "STOP_LOSS"

            if exit_reason is None:
                continue

            total_sellable = max(0, min(int(pos.qty), int(pos.sellable_qty)))
            if sell_ratio < 1.0:
                sell_qty = max(1, int(total_sellable * sell_ratio))
            else:
                sell_qty = total_sellable

            if sell_qty <= 0:
                _submit_order(
                    side="SELL", source_tab=pos.source_tab, ticker=ticker, name=pos.name,
                    qty=0, price=current, reason="SELLABLE_QTY_ZERO",
                    metadata={"kind": "EXIT", "trigger": exit_reason, "pnl_pct": pnl_pct},
                    force_skip=True,
                )
                continue

            created_row = _submit_order(
                side="SELL", source_tab=pos.source_tab, ticker=ticker, name=pos.name,
                qty=sell_qty, price=current, reason=exit_reason,
                metadata={
                    "kind": "EXIT", "trigger": exit_reason, "entry_avg_price": pos.avg_price,
                    "current_price": current, "price_source": price_source, "pnl_pct": pnl_pct,
                    "holding_qty": int(pos.qty), "sellable_qty": int(pos.sellable_qty),
                    "sell_ratio": sell_ratio, "partial_tp": sell_ratio < 1.0,
                    "take_profit_pct": take_profit_pct, "stop_loss_pct": stop_loss_pct,
                },
            )
            if created_row is not None and str(created_row.status).upper() in {"PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"}:
                if exit_reason == "STOP_LOSS" and stoploss_reentry_policy == "manual_block":
                    _upsert_manual_reentry_block(session, user_id=user_id, environment=target_env, ticker=ticker, trigger_reason="STOP_LOSS")
                elif exit_reason == "TAKE_PROFIT" and takeprofit_reentry_policy == "manual_block":
                    _upsert_manual_reentry_block(session, user_id=user_id, environment=target_env, ticker=ticker, trigger_reason="TAKE_PROFIT")
            pending_sell_tickers.add(ticker)

    # P0-3: 일일 손실 한계 — 완전 블락
    if run_entry_phase and entry_blocked_by_daily_loss:
        session.flush()
        metric = recompute_daily_metric(session, user_id, today, cfg=cfg)
        return RunResult(run_id=run_id, message="DAILY_LOSS_LIMIT_REACHED",
                         candidates=candidates, created_orders=created, metric=metric)

    # ── 2) Entry Phase ──
    if run_entry_phase:
        def _reentry_block_reason(trigger: str, policy: str) -> str:
            trigger_u = str(trigger or "").strip().upper()
            policy_u = _normalize_reentry_policy(policy)
            if trigger_u == "STOP_LOSS":
                if policy_u == "day_block": return "STOPLOSS_REENTRY_BLOCKED_TODAY"
                if policy_u == "manual_block": return "STOPLOSS_REENTRY_BLOCKED_MANUAL"
                return "STOPLOSS_REENTRY_COOLDOWN"
            if policy_u == "day_block": return "TAKEPROFIT_REENTRY_BLOCKED_TODAY"
            if policy_u == "manual_block": return "TAKEPROFIT_REENTRY_BLOCKED_MANUAL"
            return "TAKEPROFIT_REENTRY_COOLDOWN"

        for c in candidates[:max_orders]:
            ticker = _norm_ticker(str(c.get("ticker") or ""))
            symbol_rule = symbol_rule_rows.get(ticker)
            price = float(c.get("current_price") or c.get("signal_price") or 0.0)
            budget = float(cfg.order_budget_krw or 0.0)

            # P0-3: 쓰로틀 모드면 예산 축소
            if entry_throttled:
                budget *= throttle_ratio

            qty = int(budget // price) if price > 0.0 else 0
            required_cash = price * qty
            reason = None

            if not ticker:
                reason = "TICKER_EMPTY"
            elif symbol_rule is not None and not bool(symbol_rule.enabled):
                reason = "ENTRY_BLOCKED_MANUAL"
            elif ticker in open_tickers:
                reason = "ALREADY_OPEN_POSITION"
            elif ticker in pending_buy_tickers:
                reason = "PENDING_BUY_ORDER"
            elif ticker in stoploss_blocked:
                reason = _reentry_block_reason("STOP_LOSS", stoploss_reentry_policy)
            elif ticker in takeprofit_blocked:
                reason = _reentry_block_reason("TAKE_PROFIT", takeprofit_reentry_policy)
            elif price <= 0.0:
                reason = "PRICE_UNAVAILABLE"
            elif qty <= 0:
                reason = "QTY_ZERO"
            elif available_orderable_cash is not None and required_cash > available_orderable_cash:
                reason = "ORDERABLE_CASH_LIMIT"
            elif current_exposure + (price * qty) > max_seed:
                reason = "SEED_LIMIT_EXCEEDED"
            # P0-3: 쓰로틀 상태 알림
            elif entry_throttled:
                reason = None  # 통과하되, metadata에 쓰로틀 표시

            if reason is not None:
                _submit_order(
                    side="BUY", source_tab=str(c.get("source_tab") or "UNKNOWN"),
                    ticker=ticker, name=str(c.get("name") or "") or None,
                    qty=max(0, qty), price=price, reason=reason,
                    metadata={
                        "kind": "ENTRY", "candidate": c, "required_cash": required_cash,
                        "available_orderable_cash": available_orderable_cash,
                        "current_exposure_krw": current_exposure, "seed_limit_krw": max_seed,
                        "throttled": entry_throttled,
                    },
                    force_skip=True,
                )
                continue

            row = _submit_order(
                side="BUY", source_tab=str(c.get("source_tab") or "UNKNOWN"),
                ticker=ticker, name=str(c.get("name") or "") or None,
                qty=qty, price=price, reason=("DAILY_LOSS_THROTTLED" if entry_throttled else None),
                metadata={"kind": "ENTRY", "candidate": c, "throttled": entry_throttled,
                           "throttle_ratio": throttle_ratio if entry_throttled else 1.0},
            )
            if row is not None and _filled_status(str(row.status)):
                current_exposure += price * qty
                open_tickers.add(ticker)
                pending_buy_tickers.add(ticker)
                if available_orderable_cash is not None:
                    available_orderable_cash = max(0.0, available_orderable_cash - required_cash)

    session.flush()
    metric = recompute_daily_metric(session, user_id, today, cfg=cfg)
    buy_count = sum(1 for x in created if str(x.side).upper() == "BUY")
    sell_count = sum(1 for x in created if str(x.side).upper() == "SELL")
    mode_msg = {"all": "ALL", "exit_only": "EXIT_ONLY", "entry_only": "ENTRY_ONLY"}.get(mode, "ALL")
    return RunResult(
        run_id=run_id,
        message=f"RUN_OK[{mode_msg}]: orders={len(created)} (buy={buy_count}, sell={sell_count})"
                + (f" [THROTTLED:{throttle_ratio:.0%}]" if entry_throttled else ""),
        candidates=candidates, created_orders=created, metric=metric,
    )


# ── 리엔트리 블락 관리 API ──

def list_active_reentry_blocks2(session: Session, *, user_id: int, environment: str | None = None, limit: int = 200) -> list[AutoTrade2ReentryBlock]:
    q = session.query(AutoTrade2ReentryBlock).filter(
        AutoTrade2ReentryBlock.user_id == int(user_id),
        AutoTrade2ReentryBlock.is_active.is_(True), AutoTrade2ReentryBlock.released_at.is_(None))
    env = str(environment or "").strip().lower()
    if env in {"demo", "prod"}:
        q = q.filter(AutoTrade2ReentryBlock.environment == env)
    return q.order_by(desc(AutoTrade2ReentryBlock.blocked_at)).limit(max(1, min(500, int(limit)))).all()


def release_reentry_blocks2(session: Session, *, user_id: int, environment: str | None = None, ticker: str | None = None, release_all: bool = False) -> int:
    q = session.query(AutoTrade2ReentryBlock).filter(
        AutoTrade2ReentryBlock.user_id == int(user_id),
        AutoTrade2ReentryBlock.is_active.is_(True), AutoTrade2ReentryBlock.released_at.is_(None))
    env = str(environment or "").strip().lower()
    if env in {"demo", "prod"}:
        q = q.filter(AutoTrade2ReentryBlock.environment == env)
    tk = _norm_ticker(str(ticker or ""))
    if tk:
        q = q.filter(AutoTrade2ReentryBlock.ticker == tk)
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
