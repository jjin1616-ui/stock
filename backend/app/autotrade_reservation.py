from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from sqlalchemy import desc
from sqlalchemy.orm import Session

from app.auth import now
from app.autotrade_service import get_or_create_autotrade_setting, run_autotrade_once
from app.broker_credentials import resolve_user_kis_credentials
from app.config import settings
from app.models import AutoTradeReservation, Device, SessionToken
from app.push import send_to_devices_and_log

logger = logging.getLogger("stock.autotrade.reservation")
SEOUL = ZoneInfo(settings.app_tz)

_OPEN_HOUR = max(0, min(23, int(getattr(settings, "autotrade_market_open_hour", 9))))
_OPEN_MINUTE = max(0, min(59, int(getattr(settings, "autotrade_market_open_minute", 0))))
_CLOSE_HOUR = max(0, min(23, int(getattr(settings, "autotrade_market_close_hour", 15))))
_CLOSE_MINUTE = max(0, min(59, int(getattr(settings, "autotrade_market_close_minute", 30))))


def _next_open_after(base_ts: datetime) -> datetime:
    ts = base_ts.astimezone(SEOUL)
    candidate = ts
    while True:
        if candidate.weekday() < 5:
            open_ts = candidate.replace(
                hour=_OPEN_HOUR,
                minute=_OPEN_MINUTE,
                second=0,
                microsecond=0,
            )
            if open_ts > ts:
                return open_ts
        candidate = (candidate + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)


def current_market_phase(now_ts: datetime | None = None) -> tuple[str, datetime]:
    ts = (now_ts or datetime.now(tz=SEOUL)).astimezone(SEOUL)
    if ts.weekday() >= 5:
        return "HOLIDAY", _next_open_after(ts)

    open_ts = ts.replace(hour=_OPEN_HOUR, minute=_OPEN_MINUTE, second=0, microsecond=0)
    close_ts = ts.replace(hour=_CLOSE_HOUR, minute=_CLOSE_MINUTE, second=0, microsecond=0)
    if ts < open_ts:
        return "PREOPEN", open_ts
    if ts >= close_ts:
        return "CLOSED", _next_open_after(ts)
    return "OPEN", ts


def _safe_json(raw: str | None) -> dict:
    if not raw:
        return {}
    try:
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _normalize_preview_items(raw: object) -> list[dict]:
    if not isinstance(raw, list):
        return []
    out: list[dict] = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        ticker = str(item.get("ticker") or "").strip()
        if not ticker:
            continue
        source_tab = str(item.get("source_tab") or "UNKNOWN").strip().upper()
        if source_tab not in {"DAYTRADE", "MOVERS", "SUPPLY", "PAPERS", "LONGTERM", "FAVORITES", "RECENT", "UNKNOWN"}:
            source_tab = "UNKNOWN"
        out.append(
            {
                "ticker": ticker,
                "name": (str(item.get("name")).strip() if item.get("name") is not None else None),
                "source_tab": source_tab,
            }
        )
    return out


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


def reservation_item_payload(row: AutoTradeReservation) -> dict:
    result_summary = _safe_json(row.result_json)
    payload = _safe_json(row.payload_json)
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
) -> AutoTradeReservation:
    now_ts = datetime.now(tz=SEOUL)
    _, next_open = current_market_phase(now_ts)
    payload = {
        "limit": (int(limit) if limit is not None else None),
        "trigger_phase": trigger_phase,
        "requested_at": now_ts.isoformat(),
        "confirm_timeout_min": int(timeout_min),
        "preview_count": max(0, int(preview_count or 0)),
        "preview_items": _normalize_preview_items(preview_items),
    }
    row = AutoTradeReservation(
        user_id=int(user_id),
        environment=str(environment or "demo"),
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
    return row


def _execute_reservation(session: Session, row: AutoTradeReservation) -> tuple[dict, dict]:
    row.status = "RUNNING"
    row.updated_at = now()
    session.flush()

    cfg = get_or_create_autotrade_setting(session, int(row.user_id))
    payload = _safe_json(row.payload_json)
    limit_raw = payload.get("limit")
    limit = int(limit_raw) if isinstance(limit_raw, int | float | str) and str(limit_raw).strip() != "" else None
    user_creds, use_user_creds = resolve_user_kis_credentials(session, int(row.user_id))
    result = run_autotrade_once(
        session,
        user_id=int(row.user_id),
        cfg=cfg,
        dry_run=False,
        limit=limit,
        broker_credentials=(user_creds if use_user_creds else None),
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
            row.execute_at = next_open
            row.reason_code = f"MARKET_{phase}"
            row.reason_message = "장시간 외로 다음 거래시간으로 이월"
            row.updated_at = now()
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
        row.status = "QUEUED"
        row.execute_at = next_open
        row.confirm_deadline_at = None
        row.reason_code = f"MARKET_{phase}"
        row.reason_message = "장시간 외로 예약 실행 시점 재조정"
        row.updated_at = now()
        session.flush()
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
