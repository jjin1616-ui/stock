from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta
from threading import Lock
from zoneinfo import ZoneInfo

import holidays
from sqlalchemy import desc
from sqlalchemy.orm import Session

from app.auth import now
from app.autotrade_service import get_or_create_autotrade_setting, recompute_daily_metric, run_autotrade_once
from app.broker_credentials import resolve_user_kis_credentials
from app.config import settings
from app.kis_broker import KisBrokerClient
from app.models import AutoTradeOrder, AutoTradeReservation, Device, SessionToken
from app.push import send_to_devices_and_log

logger = logging.getLogger("stock.autotrade.reservation")
SEOUL = ZoneInfo(settings.app_tz)

_OPEN_HOUR = max(0, min(23, int(getattr(settings, "autotrade_market_open_hour", 9))))
_OPEN_MINUTE = max(0, min(59, int(getattr(settings, "autotrade_market_open_minute", 0))))
_CLOSE_HOUR = max(0, min(23, int(getattr(settings, "autotrade_market_close_hour", 15))))
_CLOSE_MINUTE = max(0, min(59, int(getattr(settings, "autotrade_market_close_minute", 30))))
_DEFAULT_IMMEDIATE_WINDOW = f"{_OPEN_HOUR:02d}:{_OPEN_MINUTE:02d}-{_CLOSE_HOUR:02d}:{_CLOSE_MINUTE:02d}"


def _parse_hhmm(raw: str) -> int | None:
    txt = str(raw or "").strip()
    if not txt or ":" not in txt:
        return None
    try:
        hh_raw, mm_raw = txt.split(":", 1)
        hh = int(hh_raw)
        mm = int(mm_raw)
    except Exception:
        return None
    if not (0 <= hh <= 23 and 0 <= mm <= 59):
        return None
    return hh * 60 + mm


def _parse_immediate_windows(raw: str) -> list[tuple[int, int]]:
    items: list[tuple[int, int]] = []
    for chunk in str(raw or "").split(","):
        token = chunk.strip()
        if not token or "-" not in token:
            continue
        start_raw, end_raw = token.split("-", 1)
        start_min = _parse_hhmm(start_raw)
        end_min = _parse_hhmm(end_raw)
        if start_min is None or end_min is None or end_min <= start_min:
            continue
        items.append((start_min, end_min))
    items.sort(key=lambda x: x[0])
    return items


_IMMEDIATE_WINDOWS = _parse_immediate_windows(
    str(getattr(settings, "autotrade_immediate_windows", _DEFAULT_IMMEDIATE_WINDOW) or _DEFAULT_IMMEDIATE_WINDOW)
)
if not _IMMEDIATE_WINDOWS:
    _IMMEDIATE_WINDOWS = _parse_immediate_windows(_DEFAULT_IMMEDIATE_WINDOW)
_TRADING_DAY_CACHE_LOCK = Lock()
_KR_HOLIDAY_CACHE_BY_YEAR: dict[int, set[str]] = {}


def _parse_override_dates(raw: str | None) -> set[str]:
    items: set[str] = set()
    for token in str(raw or "").split(","):
        txt = token.strip()
        if not txt:
            continue
        if len(txt) == 8 and txt.isdigit():
            items.add(f"{txt[:4]}-{txt[4:6]}-{txt[6:]}")
            continue
        if len(txt) == 10 and txt[4] == "-" and txt[7] == "-":
            y, m, d = txt.split("-")
            if y.isdigit() and m.isdigit() and d.isdigit():
                items.add(txt)
    return items


_EXTRA_HOLIDAYS = _parse_override_dates(getattr(settings, "autotrade_extra_holidays", ""))
_EXTRA_TRADING_DAYS = _parse_override_dates(getattr(settings, "autotrade_extra_trading_days", ""))


def _kr_holidays_for_year(year: int) -> set[str]:
    y = int(year)
    with _TRADING_DAY_CACHE_LOCK:
        hit = _KR_HOLIDAY_CACHE_BY_YEAR.get(y)
    if hit is not None:
        return hit

    values: set[str] = set()
    try:
        cal = holidays.country_holidays("KR", years=[y])
        values = {d.isoformat() for d in cal.keys()}
    except Exception as exc:
        logger.warning("KR holiday calendar load failed year=%s err=%s", y, exc)

    with _TRADING_DAY_CACHE_LOCK:
        _KR_HOLIDAY_CACHE_BY_YEAR[y] = values
    return values


def _is_krx_trading_day(ts: datetime) -> bool:
    local_ts = ts.astimezone(SEOUL)
    day = local_ts.date().isoformat()

    if day in _EXTRA_TRADING_DAYS:
        return True
    if local_ts.weekday() >= 5:
        return False
    if day in _EXTRA_HOLIDAYS:
        return False

    holidays_of_year = _kr_holidays_for_year(local_ts.year)
    if day in holidays_of_year:
        return False
    return True


def _window_start_dt(base_ts: datetime, start_minutes: int) -> datetime:
    hh = int(start_minutes // 60)
    mm = int(start_minutes % 60)
    return base_ts.replace(hour=hh, minute=mm, second=0, microsecond=0)


def _next_open_after(base_ts: datetime) -> datetime:
    ts = base_ts.astimezone(SEOUL)
    candidate = ts
    first_start = _IMMEDIATE_WINDOWS[0][0]
    while True:
        if _is_krx_trading_day(candidate):
            open_ts = _window_start_dt(candidate, first_start)
            if open_ts > ts:
                return open_ts
        candidate = (candidate + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)


def current_market_phase(now_ts: datetime | None = None) -> tuple[str, datetime]:
    ts = (now_ts or datetime.now(tz=SEOUL)).astimezone(SEOUL)
    if not _is_krx_trading_day(ts):
        return "HOLIDAY", _next_open_after(ts)
    now_min = ts.hour * 60 + ts.minute
    first_start = _IMMEDIATE_WINDOWS[0][0]
    for start_min, end_min in _IMMEDIATE_WINDOWS:
        if start_min <= now_min < end_min:
            return "OPEN", ts
    future_windows = [start_min for (start_min, _end_min) in _IMMEDIATE_WINDOWS if start_min > now_min]
    if future_windows:
        phase = "PREOPEN" if now_min < first_start else "BREAK"
        return phase, _window_start_dt(ts, min(future_windows))
    return "CLOSED", _next_open_after(ts)


def _safe_json(raw: str | None) -> dict:
    if not raw:
        return {}
    try:
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _normalize_ticker(raw: object) -> str:
    txt = str(raw or "").strip().upper()
    if txt.isdigit() and 0 < len(txt) <= 6:
        return txt.zfill(6)
    return ""


def _safe_float(raw: object) -> float | None:
    if raw is None:
        return None
    try:
        value = float(raw)
    except Exception:
        return None
    return value if value == value else None


def _safe_int(raw: object) -> int | None:
    if raw is None:
        return None
    try:
        value = int(float(raw))
    except Exception:
        return None
    return value


def _normalize_environment(raw: object, fallback: str = "demo") -> str:
    txt = str(raw or "").strip().lower()
    if txt == "paper":
        txt = "demo"
    if txt in {"demo", "prod"}:
        return txt
    fb = str(fallback or "demo").strip().lower()
    if fb == "paper":
        fb = "demo"
    return fb if fb in {"demo", "prod"} else "demo"


def _is_cancel_target_missing_message(message: str | None) -> bool:
    text = str(message or "").strip().lower()
    if not text:
        return False
    keywords = (
        "원주문번호가 존재하지 않습니다",
        "원주문번호가 없습니다",
        "주문번호가 존재하지 않습니다",
        "주문번호가 없습니다",
        "존재하지 않는 주문",
        "order not found",
        "40320000",
    )
    return any(key in text for key in keywords)


def _normalize_preview_items(raw: object) -> list[dict]:
    if not isinstance(raw, list):
        return []
    out: list[dict] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        ticker = _normalize_ticker(item.get("ticker"))
        if not ticker:
            continue
        source_tab = str(item.get("source_tab") or "UNKNOWN").strip().upper()
        if source_tab not in {
            "DAYTRADE",
            "MOVERS",
            "SUPPLY",
            "PAPERS",
            "LONGTERM",
            "FAVORITES",
            "RECENT",
            "PENDING_CANCEL",
            "DETAIL_CARD",
            "HOLDINGS",
            "UNKNOWN",
        }:
            source_tab = "UNKNOWN"
        out.append(
            {
                "ticker": ticker,
                "name": (str(item.get("name")).strip() if item.get("name") is not None else None),
                "source_tab": source_tab,
                "signal_price": _safe_float(item.get("signal_price")),
                "current_price": _safe_float(item.get("current_price")),
                "chg_pct": _safe_float(item.get("chg_pct")),
                "planned_qty": _safe_int(item.get("planned_qty")),
                "planned_price": _safe_float(item.get("planned_price")),
                "planned_amount_krw": _safe_float(item.get("planned_amount_krw")),
                "order_type": (
                    "MARKET"
                    if str(item.get("order_type") or "").strip().upper() == "MARKET"
                    else ("LIMIT" if str(item.get("order_type") or "").strip() else None)
                ),
                "merged_count": max(1, int(_safe_int(item.get("merged_count")) or 1)),
            }
        )
    return out


def _merge_preview_items(raw: object) -> list[dict]:
    items = _normalize_preview_items(raw)
    merged_by_ticker: dict[str, dict] = {}
    for item in items:
        ticker = _normalize_ticker(item.get("ticker"))
        if not ticker:
            continue
        current = merged_by_ticker.get(ticker)
        if current is None:
            base = dict(item)
            base["merged_count"] = max(1, int(_safe_int(base.get("merged_count")) or 1))
            merged_by_ticker[ticker] = base
            continue
        current_count = max(1, int(_safe_int(current.get("merged_count")) or 1))
        incoming_count = max(1, int(_safe_int(item.get("merged_count")) or 1))
        current["merged_count"] = current_count + incoming_count
        if item.get("name"):
            current["name"] = item.get("name")
        if item.get("source_tab"):
            current["source_tab"] = item.get("source_tab")
        for field in ("signal_price", "current_price", "chg_pct", "planned_qty", "planned_price", "planned_amount_krw"):
            if item.get(field) is not None:
                current[field] = item.get(field)
        if item.get("order_type"):
            current["order_type"] = item.get("order_type")
    return [merged_by_ticker[k] for k in sorted(merged_by_ticker.keys())]


def _collect_user_devices(session: Session, user_id: int, max_devices: int = 5) -> list[Device]:
    rows = (
        session.query(SessionToken.device_id)
        .filter(
            SessionToken.user_id == int(user_id),
            SessionToken.device_id.is_not(None),
            SessionToken.revoked_at.is_(None),
            SessionToken.expires_at > now(),
        )
        .order_by(desc(SessionToken.issued_at))
        .limit(max_devices * 4)
        .all()
    )
    device_ids: list[str] = []
    for r in rows:
        did = str(r[0] or "").strip()
        if did and did not in device_ids:
            device_ids.append(did)
            if len(device_ids) >= max_devices:
                break
    if not device_ids:
        return []
    by_id = {str(d.device_id): d for d in session.query(Device).filter(Device.device_id.in_(device_ids)).all()}
    return [by_id[did] for did in device_ids if did in by_id]


def _send_confirm_push(session: Session, row: AutoTradeReservation) -> None:
    devices = _collect_user_devices(session, int(row.user_id))
    if not devices:
        return
    send_to_devices_and_log(
        session,
        devices=devices,
        alert_type="TRIGGER",
        title="자동매매 예약 확인",
        body="거래 가능 시간입니다. 예약 주문을 실행할까요?",
        payload={
            "type": "AUTOTRADE_RESERVATION_CONFIRM",
            "reservation_id": int(row.id),
            "route": "autotrade",
        },
        dry_run=False,
        respect_preferences=True,
    )


def _send_result_push(
    session: Session,
    row: AutoTradeReservation,
    *,
    submitted_count: int,
    skipped_count: int,
    message: str,
) -> None:
    devices = _collect_user_devices(session, int(row.user_id))
    if not devices:
        return
    send_to_devices_and_log(
        session,
        devices=devices,
        alert_type="TRIGGER",
        title="자동매매 예약 실행 결과",
        body=f"접수 {submitted_count}건 · 스킵 {skipped_count}건",
        payload={
            "type": "AUTOTRADE_RESERVATION_RESULT",
            "reservation_id": int(row.id),
            "submitted_count": submitted_count,
            "skipped_count": skipped_count,
            "message": message,
            "route": "autotrade",
        },
        dry_run=False,
        respect_preferences=True,
    )


def _phase_label(phase: str) -> str:
    up = str(phase or "").strip().upper()
    if up == "PREOPEN":
        return "장 시작 전"
    if up == "BREAK":
        return "세션 휴장 구간"
    if up == "CLOSED":
        return "장 종료"
    if up == "HOLIDAY":
        return "휴장일"
    return up or "UNKNOWN"


def _mark_reservation_auto_canceled(
    session: Session,
    row: AutoTradeReservation,
    *,
    phase: str,
    next_open: datetime,
) -> None:
    payload = _safe_json(row.payload_json)
    preview_count = 0
    try:
        preview_count = max(0, int(payload.get("preview_count") or 0))
    except Exception:
        preview_count = 0
    reason_code = f"MARKET_{str(phase or '').upper()}_AUTO_CANCELED"
    conclusion = f"{_phase_label(phase)}에는 주문이 불가하여 예약이 자동 취소되었습니다."
    action = f"다음 주문 가능 시각({next_open.strftime('%m-%d %H:%M')})에 다시 실행하세요."
    row.status = "CANCELED"
    row.confirm_deadline_at = None
    row.reason_code = reason_code
    row.reason_message = conclusion
    row.result_json = json.dumps(
        {
            "conclusion": conclusion,
            "reason_code": reason_code,
            "evidence": {
                "market_phase": str(phase or "").upper(),
                "next_open_at": next_open.isoformat(),
                "preview_count": str(preview_count),
            },
            "action": action,
            "requested_count": preview_count,
            "submitted_count": 0,
            "filled_count": 0,
            "skipped_count": preview_count,
            "rejected_count": 0,
        },
        ensure_ascii=False,
    )
    row.updated_at = now()
    session.flush()
    _send_result_push(
        session,
        row,
        submitted_count=0,
        skipped_count=preview_count,
        message=f"{conclusion} {action}",
    )


def reservation_item_payload(row: AutoTradeReservation) -> dict:
    result_summary = _safe_json(row.result_json)
    payload = _safe_json(row.payload_json)
    kind = str(payload.get("kind") or "AUTOTRADE_ENTRY").strip().upper()
    if kind not in {"AUTOTRADE_ENTRY", "ORDER_CANCEL", "MANUAL_ORDER"}:
        kind = "AUTOTRADE_ENTRY"
    preview_items = _normalize_preview_items(payload.get("preview_items"))
    preview_count_raw = payload.get("preview_count")
    preview_count = len(preview_items)
    if str(preview_count_raw or "").strip():
        try:
            preview_count = int(preview_count_raw)
        except Exception:
            preview_count = len(preview_items)
    return {
        "id": int(row.id),
        "environment": str(row.environment or "demo"),
        "kind": kind,
        "mode": str(row.mode or "auto"),
        "status": str(row.status or "QUEUED"),
        "requested_at": row.requested_at,
        "execute_at": row.execute_at,
        "confirm_deadline_at": row.confirm_deadline_at,
        "timeout_action": str(row.timeout_action or "cancel"),
        "reason_code": row.reason_code,
        "reason_message": row.reason_message,
        "result_run_id": row.result_run_id,
        "preview_count": max(0, preview_count),
        "preview_items": preview_items,
        "result_summary": result_summary if result_summary else None,
        "updated_at": row.updated_at,
    }


def enqueue_reservation(
    session: Session,
    *,
    user_id: int,
    environment: str,
    mode: str,
    timeout_action: str,
    timeout_min: int,
    limit: int | None,
    trigger_phase: str,
    preview_count: int | None = None,
    preview_items: list[dict] | None = None,
) -> tuple[AutoTradeReservation, dict[str, int | bool]]:
    now_ts = datetime.now(tz=SEOUL)
    _, next_open = current_market_phase(now_ts)
    env_norm = _normalize_environment(environment, fallback="demo")
    normalized_preview = _merge_preview_items(preview_items)
    payload = {
        "kind": "AUTOTRADE_ENTRY",
        "limit": (int(limit) if limit is not None else None),
        "trigger_phase": trigger_phase,
        "requested_at": now_ts.isoformat(),
        "confirm_timeout_min": int(timeout_min),
        "preview_count": len(normalized_preview),
        "preview_items": normalized_preview,
        "merge_request_count": 1,
    }
    merge_candidate_rows = (
        session.query(AutoTradeReservation)
        .filter(
            AutoTradeReservation.user_id == int(user_id),
            AutoTradeReservation.environment == env_norm,
            AutoTradeReservation.status.in_(["QUEUED", "WAIT_CONFIRM"]),
        )
        .order_by(AutoTradeReservation.requested_at.desc(), AutoTradeReservation.id.desc())
        .limit(30)
        .all()
    )
    for candidate in merge_candidate_rows:
        candidate_payload = _safe_json(candidate.payload_json)
        if str(candidate_payload.get("kind") or "AUTOTRADE_ENTRY").strip().upper() != "AUTOTRADE_ENTRY":
            continue

        existing_items = _merge_preview_items(candidate_payload.get("preview_items"))
        merged_by_ticker: dict[str, dict] = {
            _normalize_ticker(item.get("ticker")): dict(item)
            for item in existing_items
            if _normalize_ticker(item.get("ticker"))
        }
        merged_overlap = 0
        merged_added = 0
        for item in normalized_preview:
            ticker = _normalize_ticker(item.get("ticker"))
            if not ticker:
                continue
            current = merged_by_ticker.get(ticker)
            if current is None:
                merged_by_ticker[ticker] = dict(item)
                merged_added += 1
                continue
            merged_overlap += 1
            current_count = max(1, int(_safe_int(current.get("merged_count")) or 1))
            incoming_count = max(1, int(_safe_int(item.get("merged_count")) or 1))
            current["merged_count"] = current_count + incoming_count
            if item.get("name"):
                current["name"] = item.get("name")
            if item.get("source_tab"):
                current["source_tab"] = item.get("source_tab")
            for field in ("signal_price", "current_price", "chg_pct", "planned_qty", "planned_price", "planned_amount_krw"):
                if item.get(field) is not None:
                    current[field] = item.get(field)
            if item.get("order_type"):
                current["order_type"] = item.get("order_type")

        merged_items = [merged_by_ticker[k] for k in sorted(merged_by_ticker.keys())]
        candidate_payload["limit"] = int(limit) if limit is not None else candidate_payload.get("limit")
        candidate_payload["trigger_phase"] = str(trigger_phase or "").upper() or candidate_payload.get("trigger_phase")
        candidate_payload["requested_at"] = now_ts.isoformat()
        candidate_payload["confirm_timeout_min"] = int(timeout_min)
        candidate_payload["preview_items"] = _merge_preview_items(merged_items)
        candidate_payload["preview_count"] = len(candidate_payload["preview_items"])
        candidate_payload["merge_request_count"] = max(1, int(_safe_int(candidate_payload.get("merge_request_count")) or 1) + 1)
        candidate.payload_json = json.dumps(candidate_payload, ensure_ascii=False)
        candidate.requested_at = now_ts
        candidate.execute_at = next_open
        candidate.mode = ("confirm" if str(mode).lower() == "confirm" else "auto")
        candidate.timeout_action = ("auto" if str(timeout_action).lower() == "auto" else "cancel")
        candidate.reason_code = f"MARKET_{str(trigger_phase or '').upper()}_RESERVATION_MERGED"
        candidate.reason_message = "장시간 외 예약이 기존 예약과 병합되었습니다."
        candidate.updated_at = now_ts
        session.flush()
        return candidate, {
            "merged": True,
            "added_count": int(merged_added),
            "overlap_count": int(merged_overlap),
            "merge_request_count": int(candidate_payload["merge_request_count"]),
            "preview_count": int(candidate_payload["preview_count"]),
        }

    row = AutoTradeReservation(
        user_id=int(user_id),
        environment=env_norm,
        mode=("confirm" if str(mode).lower() == "confirm" else "auto"),
        status="QUEUED",
        requested_at=now_ts,
        execute_at=next_open,
        confirm_deadline_at=None,
        timeout_action=("auto" if str(timeout_action).lower() == "auto" else "cancel"),
        payload_json=json.dumps(payload, ensure_ascii=False),
        reason_code=f"MARKET_{trigger_phase}",
        reason_message="장시간 외 예약 등록",
        result_run_id=None,
        result_json=None,
        created_at=now_ts,
        updated_at=now_ts,
    )
    session.add(row)
    session.flush()
    return row, {
        "merged": False,
        "added_count": len(normalized_preview),
        "overlap_count": 0,
        "merge_request_count": 1,
        "preview_count": len(normalized_preview),
    }


def enqueue_manual_order_reservation(
    session: Session,
    *,
    user_id: int,
    environment: str,
    side: str,
    source_tab: str,
    ticker: str,
    name: str | None,
    qty: int,
    request_price: float,
    market_order: bool,
    order_type: str,
    trigger_phase: str,
    timeout_action: str = "auto",
) -> AutoTradeReservation:
    now_ts = datetime.now(tz=SEOUL)
    _, next_open = current_market_phase(now_ts)
    side_u = "SELL" if str(side or "").strip().upper() == "SELL" else "BUY"
    ticker_norm = _normalize_ticker(ticker)
    source_u = str(source_tab or "DETAIL_CARD").strip().upper() or "DETAIL_CARD"
    qty_i = max(0, int(qty or 0))
    request_price_f = max(0.0, float(request_price or 0.0))
    order_type_u = "MARKET" if str(order_type or "").strip().upper() == "MARKET" else "LIMIT"
    payload = {
        "kind": "MANUAL_ORDER",
        "trigger_phase": str(trigger_phase or "").upper(),
        "requested_at": now_ts.isoformat(),
        "side": side_u,
        "source_tab": source_u,
        "ticker": ticker_norm,
        "name": (str(name).strip() if name is not None else None),
        "qty": qty_i,
        "request_price": request_price_f,
        "market_order": bool(market_order),
        "order_type": order_type_u,
        "preview_count": 1 if ticker_norm else 0,
        "preview_items": _normalize_preview_items(
            [
                {
                    "ticker": ticker_norm,
                    "name": (str(name).strip() if name is not None else None),
                    "source_tab": source_u,
                    "signal_price": request_price_f,
                    "current_price": request_price_f,
                    "chg_pct": None,
                    "planned_qty": qty_i,
                    "planned_price": request_price_f,
                    "planned_amount_krw": (request_price_f * qty_i if request_price_f > 0.0 and qty_i > 0 else None),
                    "order_type": order_type_u,
                    "merged_count": 1,
                }
            ]
        ),
    }
    row = AutoTradeReservation(
        user_id=int(user_id),
        environment=_normalize_environment(environment, fallback="demo"),
        mode="auto",
        status="QUEUED",
        requested_at=now_ts,
        execute_at=next_open,
        confirm_deadline_at=None,
        timeout_action=("auto" if str(timeout_action).lower() == "auto" else "cancel"),
        payload_json=json.dumps(payload, ensure_ascii=False),
        reason_code=f"MARKET_{str(trigger_phase or '').upper()}_MANUAL_RESERVED",
        reason_message="장시간 외 수동 주문 예약 등록",
        result_run_id=None,
        result_json=None,
        created_at=now_ts,
        updated_at=now_ts,
    )
    session.add(row)
    session.flush()
    return row


def _order_environment(order_row: AutoTradeOrder, fallback_environment: str) -> str:
    meta = _safe_json(getattr(order_row, "metadata_json", None))
    env = _normalize_environment(meta.get("environment"), fallback=fallback_environment)
    return env


def enqueue_order_cancel_reservation(
    session: Session,
    *,
    user_id: int,
    environment: str,
    order_rows: list[AutoTradeOrder],
    trigger_phase: str,
    timeout_action: str = "cancel",
) -> AutoTradeReservation | None:
    if not order_rows:
        return None
    now_ts = datetime.now(tz=SEOUL)
    _, next_open = current_market_phase(now_ts)
    env_norm = _normalize_environment(environment, fallback="demo")
    targets: list[dict] = []
    preview_items: list[dict] = []
    for row in order_rows:
        order_id = int(getattr(row, "id", 0) or 0)
        if order_id <= 0:
            continue
        order_env = _order_environment(row, fallback_environment=env_norm)
        ticker = _normalize_ticker(getattr(row, "ticker", None))
        targets.append(
            {
                "order_id": order_id,
                "order_env": order_env,
            }
        )
        if ticker:
            preview_items.append(
                {
                    "ticker": ticker,
                    "name": (str(getattr(row, "name", "") or "").strip() or None),
                    "source_tab": "PENDING_CANCEL",
                    "signal_price": _safe_float(getattr(row, "requested_price", None)),
                    "current_price": _safe_float(getattr(row, "current_price", None)),
                    "chg_pct": _safe_float(getattr(row, "pnl_pct", None)),
                    "planned_qty": max(0, int(getattr(row, "qty", 0) or 0)),
                    "planned_price": _safe_float(getattr(row, "requested_price", None)),
                    "planned_amount_krw": (
                        float(max(0, int(getattr(row, "qty", 0) or 0)))
                        * float(_safe_float(getattr(row, "requested_price", None)) or 0.0)
                    ),
                    "order_type": (
                        "MARKET"
                        if str(_safe_json(getattr(row, "metadata_json", None)).get("order_type") or "").strip().upper() == "MARKET"
                        else "LIMIT"
                    ),
                    "merged_count": 1,
                }
            )
    if not targets:
        return None

    payload = {
        "kind": "ORDER_CANCEL",
        "trigger_phase": str(trigger_phase or "").upper(),
        "requested_at": now_ts.isoformat(),
        "preview_count": len(preview_items),
        "preview_items": _normalize_preview_items(preview_items),
        "cancel_targets": targets,
    }
    row = AutoTradeReservation(
        user_id=int(user_id),
        environment=env_norm,
        mode="auto",
        status="QUEUED",
        requested_at=now_ts,
        execute_at=next_open,
        confirm_deadline_at=None,
        timeout_action=("auto" if str(timeout_action).lower() == "auto" else "cancel"),
        payload_json=json.dumps(payload, ensure_ascii=False),
        reason_code=f"MARKET_{str(trigger_phase or '').upper()}_CANCEL_RESERVED",
        reason_message="장시간 외 접수취소 자동 예약 등록",
        result_run_id=None,
        result_json=None,
        created_at=now_ts,
        updated_at=now_ts,
    )
    session.add(row)
    session.flush()
    return row


def cancel_reservation_preview_item(
    session: Session,
    *,
    user_id: int,
    reservation_id: int,
    ticker: str,
) -> tuple[AutoTradeReservation, int]:
    row = session.get(AutoTradeReservation, int(reservation_id))
    if row is None or int(row.user_id) != int(user_id):
        raise ValueError("NOT_FOUND")
    if str(row.status or "").upper() not in {"QUEUED", "WAIT_CONFIRM"}:
        raise ValueError("STATUS_NOT_CANCELABLE")

    target = _normalize_ticker(ticker)
    if not target:
        raise ValueError("TICKER_EMPTY")

    payload = _safe_json(row.payload_json)
    preview_items = _normalize_preview_items(payload.get("preview_items"))
    if not preview_items:
        raise ValueError("PREVIEW_EMPTY")

    kept: list[dict] = []
    removed: list[dict] = []
    for item in preview_items:
        item_ticker = _normalize_ticker(item.get("ticker"))
        if item_ticker == target:
            removed.append(item)
        else:
            kept.append(item)
    if not removed:
        raise ValueError("ITEM_NOT_FOUND")

    payload["preview_items"] = kept
    payload["preview_count"] = len(kept)
    row.payload_json = json.dumps(payload, ensure_ascii=False)
    row.updated_at = now()

    if not kept:
        row.status = "CANCELED"
        row.confirm_deadline_at = None
        row.reason_code = "RESERVATION_EMPTY_AFTER_ITEM_CANCEL"
        row.reason_message = "예약 종목이 모두 취소되어 예약을 종료했습니다."
        row.result_json = json.dumps(
            {
                "conclusion": "예약 대상이 0건이 되어 예약이 취소되었습니다.",
                "reason_code": "RESERVATION_EMPTY_AFTER_ITEM_CANCEL",
                "evidence": {
                    "removed_ticker": target,
                    "removed_count": str(len(removed)),
                },
                "action": "새 후보로 다시 예약 실행",
                "requested_count": len(preview_items),
                "submitted_count": 0,
                "filled_count": 0,
                "skipped_count": len(preview_items),
                "rejected_count": 0,
            },
            ensure_ascii=False,
        )
    session.flush()
    return row, len(removed)


def _execute_cancel_reservation(session: Session, row: AutoTradeReservation, payload: dict) -> tuple[dict, dict]:
    targets_raw = payload.get("cancel_targets")
    targets: list[dict] = targets_raw if isinstance(targets_raw, list) else []
    if not targets:
        row.status = "FAILED"
        row.reason_code = "CANCEL_TARGET_EMPTY"
        row.reason_message = "취소 대상 주문이 없어 예약을 종료했습니다."
        summary = {
            "message": "CANCEL_TARGET_EMPTY",
            "requested_count": 0,
            "submitted_count": 0,
            "filled_count": 0,
            "skipped_count": 0,
            "rejected_count": 0,
        }
        row.result_json = json.dumps(summary, ensure_ascii=False)
        row.updated_at = now()
        session.flush()
        return summary, {
            "run_id": "",
            "message": "CANCEL_TARGET_EMPTY",
            "requested_count": 0,
            "submitted_count": 0,
            "filled_count": 0,
            "skipped_count": 0,
            "rejected_count": 0,
        }

    order_ids = [int(t.get("order_id") or 0) for t in targets if int(t.get("order_id") or 0) > 0]
    rows = (
        session.query(AutoTradeOrder)
        .filter(
            AutoTradeOrder.user_id == int(row.user_id),
            AutoTradeOrder.id.in_(order_ids),
        )
        .all()
        if order_ids
        else []
    )
    row_map = {int(r.id): r for r in rows}

    user_creds, use_user_creds = resolve_user_kis_credentials(session, int(row.user_id))
    broker = KisBrokerClient(credentials=(user_creds if use_user_creds else None))

    canceled_count = 0
    closed_count = 0
    skipped_count = 0
    failed_count = 0
    fail_reasons: list[str] = []
    for target in targets:
        order_id = int(target.get("order_id") or 0)
        if order_id <= 0:
            skipped_count += 1
            continue
        order_row = row_map.get(order_id)
        if order_row is None:
            skipped_count += 1
            continue
        if str(order_row.status or "").upper() != "BROKER_SUBMITTED":
            skipped_count += 1
            continue
        order_no = str(getattr(order_row, "broker_order_no", "") or "").strip()
        if not order_no:
            meta = _safe_json(getattr(order_row, "metadata_json", None))
            meta["cancel_last"] = {
                "ok": False,
                "status_code": 400,
                "message": "BROKER_ORDER_NO_MISSING",
                "requested_at": datetime.now(tz=SEOUL).isoformat(),
                "environment": _normalize_environment(target.get("order_env"), fallback=str(row.environment or "demo")),
                "source": "RESERVATION",
                "normalized_result": "CLOSED_WITHOUT_ORDER_NO",
            }
            order_row.metadata_json = json.dumps(meta, ensure_ascii=False)
            order_row.status = "BROKER_CLOSED"
            order_row.reason = "증권사 주문번호가 없어 접수 상태를 종료 처리했습니다."
            order_row.filled_at = now()
            closed_count += 1
            continue
        env = _normalize_environment(target.get("order_env"), fallback=str(row.environment or "demo"))
        result = broker.cancel_order(
            env=env,
            order_no=order_no,
            qty=max(0, int(getattr(order_row, "qty", 0) or 0)),
        )
        meta = _safe_json(getattr(order_row, "metadata_json", None))
        meta["cancel_last"] = {
            "ok": bool(result.ok),
            "status_code": int(result.status_code),
            "message": str(result.message or ""),
            "requested_at": datetime.now(tz=SEOUL).isoformat(),
            "environment": env,
            "source": "RESERVATION",
        }
        if result.raw is not None:
            meta["cancel_last"]["raw"] = result.raw
        order_row.metadata_json = json.dumps(meta, ensure_ascii=False)
        if result.ok:
            order_row.status = "BROKER_CANCELED"
            order_row.reason = str(result.message or "예약 자동 취소 완료")
            order_row.filled_at = now()
            canceled_count += 1
        elif _is_cancel_target_missing_message(result.message):
            meta["cancel_last"]["normalized_result"] = "CLOSED_NOT_FOUND"
            order_row.metadata_json = json.dumps(meta, ensure_ascii=False)
            order_row.status = "BROKER_CLOSED"
            order_row.reason = str(result.message or "취소 대상 주문이 존재하지 않습니다.")
            order_row.filled_at = now()
            closed_count += 1
        else:
            failed_count += 1
            fail_reasons.append(f"#{order_id}:{str(result.message or 'CANCEL_FAILED')}")
    requested = len(targets)
    success_count = canceled_count + closed_count
    if success_count > 0 and failed_count == 0:
        row.status = "DONE"
        row.reason_code = "ORDER_CANCEL_DONE"
        row.reason_message = "예약 접수취소가 완료되었습니다."
    elif success_count > 0:
        row.status = "PARTIAL"
        row.reason_code = "ORDER_CANCEL_PARTIAL"
        row.reason_message = "예약 접수취소가 일부만 처리되었습니다."
    else:
        row.status = "FAILED"
        row.reason_code = "ORDER_CANCEL_FAILED"
        row.reason_message = "예약 접수취소에 실패했습니다."
    summary = {
        "message": row.reason_message,
        "requested_count": requested,
        "submitted_count": canceled_count,
        "closed_count": closed_count,
        "filled_count": 0,
        "skipped_count": skipped_count,
        "rejected_count": failed_count,
        "failed_reasons": fail_reasons[:10],
    }
    row.result_json = json.dumps(summary, ensure_ascii=False)
    row.updated_at = now()
    session.flush()
    _send_result_push(
        session,
        row,
        submitted_count=canceled_count,
        skipped_count=skipped_count + failed_count,
        message=str(row.reason_message or ""),
    )
    return summary, {
        "run_id": "",
        "message": str(row.reason_message or ""),
        "requested_count": requested,
        "submitted_count": canceled_count,
        "filled_count": 0,
        "skipped_count": skipped_count + failed_count,
    }


def _execute_manual_order_reservation(session: Session, row: AutoTradeReservation, payload: dict) -> tuple[dict, dict]:
    environment = _normalize_environment(row.environment, fallback="demo")
    side = "SELL" if str(payload.get("side") or "").strip().upper() == "SELL" else "BUY"
    source_tab = str(payload.get("source_tab") or ("HOLDINGS" if side == "SELL" else "DETAIL_CARD")).strip().upper()
    ticker = _normalize_ticker(payload.get("ticker"))
    name_raw = payload.get("name")
    name = str(name_raw).strip() if name_raw is not None and str(name_raw).strip() else None
    qty = max(0, int(payload.get("qty") or 0))
    request_price = max(0.0, float(payload.get("request_price") or 0.0))
    market_order = bool(payload.get("market_order"))
    order_type = "MARKET" if market_order else "LIMIT"
    run_id = f"manual-reserve-{int(row.id)}"

    status = "SKIPPED"
    reason = None
    broker_order_no = None
    if not ticker:
        reason = "TICKER_EMPTY"
    elif qty <= 0:
        reason = "QTY_ZERO"
    elif request_price <= 0.0:
        reason = "PRICE_UNAVAILABLE"
    elif not settings.kis_trading_enabled:
        reason = "KIS_TRADING_DISABLED"
    else:
        user_creds, use_user_creds = resolve_user_kis_credentials(session, int(row.user_id))
        broker = KisBrokerClient(credentials=(user_creds if use_user_creds else None))
        if not broker.has_required_config(environment):
            reason = "BROKER_CREDENTIAL_MISSING"
        else:
            result = broker.order_cash(
                env=environment,
                side=("sell" if side == "SELL" else "buy"),
                ticker=ticker,
                qty=qty,
                price=request_price,
                market_order=market_order,
            )
            if result.ok:
                status = "BROKER_SUBMITTED"
                broker_order_no = result.order_no
                reason = str(result.message or f"MANUAL_{side}_{environment.upper()}_{order_type}")
            else:
                status = "BROKER_REJECTED"
                reason = str(result.message or "BROKER_ORDER_FAILED")

    order_row = AutoTradeOrder(
        user_id=int(row.user_id),
        run_id=run_id[:32],
        source_tab=source_tab,
        ticker=ticker,
        name=name,
        side=side,
        qty=qty,
        requested_price=request_price,
        filled_price=None,
        current_price=(request_price if request_price > 0.0 else None),
        pnl_pct=None,
        status=status,
        broker_order_no=broker_order_no,
        reason=reason,
        metadata_json=json.dumps(
            {
                "kind": "MANUAL_ORDER_RESERVATION",
                "reservation_id": int(row.id),
                "environment": environment,
                "side": side,
                "source_tab": source_tab,
                "market_order": market_order,
                "order_type": order_type,
            },
            ensure_ascii=False,
        ),
        requested_at=now(),
        filled_at=None,
    )
    session.add(order_row)
    session.flush()

    try:
        recompute_daily_metric(session, int(row.user_id), datetime.now().date())
    except Exception:
        logger.warning("manual reservation metric recompute failed reservation_id=%s", int(row.id))

    submitted_count = 0 if status == "SKIPPED" else 1
    filled_count = 0
    skipped_count = 1 if status == "SKIPPED" else 0
    rejected_count = 1 if status in {"BROKER_REJECTED", "ERROR"} else 0
    if status == "BROKER_SUBMITTED":
        row.status = "DONE"
        row.reason_code = "MANUAL_ORDER_SUBMITTED"
        row.reason_message = "수동 예약 주문 접수 완료"
    elif status == "BROKER_REJECTED":
        row.status = "FAILED"
        row.reason_code = "MANUAL_ORDER_REJECTED"
        row.reason_message = "수동 예약 주문 접수 실패"
    else:
        row.status = "FAILED"
        row.reason_code = "MANUAL_ORDER_SKIPPED"
        row.reason_message = "수동 예약 주문 조건 미충족으로 스킵"

    summary = {
        "message": str(reason or status),
        "requested_count": 1,
        "submitted_count": submitted_count,
        "filled_count": filled_count,
        "skipped_count": skipped_count,
        "rejected_count": rejected_count,
    }
    row.result_run_id = run_id[:32]
    row.result_json = json.dumps(summary, ensure_ascii=False)
    row.updated_at = now()
    session.flush()
    _send_result_push(
        session,
        row,
        submitted_count=submitted_count,
        skipped_count=skipped_count + rejected_count,
        message=str(row.reason_message or ""),
    )
    return summary, {
        "run_id": run_id[:32],
        "message": str(reason or status),
        "requested_count": 1,
        "submitted_count": submitted_count,
        "filled_count": filled_count,
        "skipped_count": skipped_count + rejected_count,
    }


def _execute_reservation(session: Session, row: AutoTradeReservation) -> tuple[dict, dict]:
    row.status = "RUNNING"
    row.updated_at = now()
    session.flush()

    cfg = get_or_create_autotrade_setting(session, int(row.user_id))
    payload = _safe_json(row.payload_json)
    kind = str(payload.get("kind") or "AUTOTRADE_ENTRY").strip().upper()
    if kind == "ORDER_CANCEL":
        return _execute_cancel_reservation(session, row, payload)
    if kind == "MANUAL_ORDER":
        return _execute_manual_order_reservation(session, row, payload)
    limit_raw = payload.get("limit")
    limit = int(limit_raw) if isinstance(limit_raw, int | float | str) and str(limit_raw).strip() != "" else None
    user_creds, use_user_creds = resolve_user_kis_credentials(session, int(row.user_id))
    preview_items = _normalize_preview_items(payload.get("preview_items"))
    allowed_tickers = [_normalize_ticker(item.get("ticker")) for item in preview_items if _normalize_ticker(item.get("ticker"))]
    if not allowed_tickers:
        row.status = "CANCELED"
        row.confirm_deadline_at = None
        row.reason_code = "RESERVATION_EMPTY"
        row.reason_message = "예약 대상 종목이 없어 자동 취소되었습니다."
        row.result_run_id = None
        row.result_json = json.dumps(
            {
                "conclusion": "예약 대상 0건으로 실행하지 않았습니다.",
                "reason_code": "RESERVATION_EMPTY",
                "evidence": {"preview_count": "0"},
                "action": "예약 종목을 확인 후 다시 실행하세요.",
                "requested_count": 0,
                "submitted_count": 0,
                "filled_count": 0,
                "skipped_count": 0,
                "rejected_count": 0,
            },
            ensure_ascii=False,
        )
        row.updated_at = now()
        session.flush()
        return payload, {
            "run_id": "",
            "message": "RESERVATION_EMPTY",
            "requested_count": 0,
            "submitted_count": 0,
            "filled_count": 0,
            "skipped_count": 0,
            "rejected_count": 0,
        }

    result = run_autotrade_once(
        session,
        user_id=int(row.user_id),
        cfg=cfg,
        dry_run=False,
        limit=limit,
        broker_credentials=(user_creds if use_user_creds else None),
        candidate_tickers=allowed_tickers,
    )
    submitted_count = sum(1 for o in result.created_orders if str(o.status) != "SKIPPED")
    filled_count = sum(1 for o in result.created_orders if str(o.status) in {"PAPER_FILLED", "BROKER_FILLED"})
    skipped_count = sum(1 for o in result.created_orders if str(o.status) == "SKIPPED")
    rejected_count = sum(1 for o in result.created_orders if str(o.status) in {"BROKER_REJECTED", "ERROR"})

    if submitted_count > 0 and rejected_count == 0:
        row.status = "DONE"
        row.reason_code = "RESERVATION_EXECUTED"
        row.reason_message = "예약 주문 실행 완료"
    elif submitted_count > 0:
        row.status = "PARTIAL"
        row.reason_code = "RESERVATION_PARTIAL"
        row.reason_message = "예약 주문 일부 실패"
    else:
        row.status = "FAILED"
        row.reason_code = "RESERVATION_NO_SUBMISSION"
        row.reason_message = "예약 주문이 접수되지 않음"

    row.result_run_id = result.run_id
    summary = {
        "message": result.message,
        "requested_count": len(result.candidates),
        "submitted_count": submitted_count,
        "filled_count": filled_count,
        "skipped_count": skipped_count,
        "rejected_count": rejected_count,
    }
    row.result_json = json.dumps(summary, ensure_ascii=False)
    row.updated_at = now()
    session.flush()
    _send_result_push(
        session,
        row,
        submitted_count=submitted_count,
        skipped_count=skipped_count,
        message=str(result.message or ""),
    )
    return summary, {
        "run_id": result.run_id,
        "message": result.message,
        "requested_count": len(result.candidates),
        "submitted_count": submitted_count,
        "filled_count": filled_count,
        "skipped_count": skipped_count,
    }


def process_due_reservations(session: Session, *, batch_size: int = 30) -> int:
    now_ts = datetime.now(tz=SEOUL)
    phase, next_open = current_market_phase(now_ts)
    processed = 0

    if phase != "OPEN":
        overdue = (
            session.query(AutoTradeReservation)
            .filter(
                AutoTradeReservation.status == "QUEUED",
                AutoTradeReservation.execute_at <= now_ts,
            )
            .order_by(AutoTradeReservation.execute_at.asc(), AutoTradeReservation.id.asc())
            .limit(batch_size)
            .all()
        )
        for row in overdue:
            _mark_reservation_auto_canceled(
                session,
                row,
                phase=phase,
                next_open=next_open,
            )
            processed += 1
        return processed

    timeout_rows = (
        session.query(AutoTradeReservation)
        .filter(
            AutoTradeReservation.status == "WAIT_CONFIRM",
            AutoTradeReservation.confirm_deadline_at.is_not(None),
            AutoTradeReservation.confirm_deadline_at <= now_ts,
        )
        .order_by(AutoTradeReservation.confirm_deadline_at.asc(), AutoTradeReservation.id.asc())
        .limit(batch_size)
        .all()
    )
    for row in timeout_rows:
        if str(row.timeout_action or "cancel").lower() == "auto":
            _execute_reservation(session, row)
        else:
            row.status = "EXPIRED"
            row.reason_code = "CONFIRM_TIMEOUT"
            row.reason_message = "확인 시간이 초과되어 예약 취소"
            row.updated_at = now()
            session.flush()
        processed += 1

    queued = (
        session.query(AutoTradeReservation)
        .filter(
            AutoTradeReservation.status == "QUEUED",
            AutoTradeReservation.execute_at <= now_ts,
        )
        .order_by(AutoTradeReservation.execute_at.asc(), AutoTradeReservation.id.asc())
        .limit(max(0, batch_size - processed))
        .all()
    )
    for row in queued:
        mode = str(row.mode or "auto").lower()
        if mode == "confirm":
            payload = _safe_json(row.payload_json)
            timeout_min = int(payload.get("confirm_timeout_min") or 3)
            timeout_min = max(1, min(30, timeout_min))
            row.status = "WAIT_CONFIRM"
            row.confirm_deadline_at = now_ts + timedelta(minutes=timeout_min)
            row.reason_code = "WAIT_USER_CONFIRM"
            row.reason_message = "거래 가능 시간 도달, 사용자 확인 대기"
            row.updated_at = now()
            session.flush()
            _send_confirm_push(session, row)
        else:
            _execute_reservation(session, row)
        processed += 1
    return processed


def confirm_reservation_execute(session: Session, *, user_id: int, reservation_id: int) -> tuple[AutoTradeReservation, dict | None]:
    row = session.get(AutoTradeReservation, int(reservation_id))
    if row is None or int(row.user_id) != int(user_id):
        raise ValueError("NOT_FOUND")
    if str(row.status) in {"CANCELED", "EXPIRED", "DONE", "FAILED", "PARTIAL"}:
        return row, None
    phase, next_open = current_market_phase()
    if phase != "OPEN":
        _mark_reservation_auto_canceled(
            session,
            row,
            phase=phase,
            next_open=next_open,
        )
        return row, None
    _, run_result = _execute_reservation(session, row)
    return row, run_result


def cancel_reservation(session: Session, *, user_id: int, reservation_id: int) -> AutoTradeReservation:
    row = session.get(AutoTradeReservation, int(reservation_id))
    if row is None or int(row.user_id) != int(user_id):
        raise ValueError("NOT_FOUND")
    if str(row.status) in {"DONE", "FAILED", "PARTIAL", "CANCELED", "EXPIRED"}:
        return row
    row.status = "CANCELED"
    row.reason_code = "USER_CANCELED"
    row.reason_message = "사용자 요청으로 예약 취소"
    row.updated_at = now()
    session.flush()
    return row
