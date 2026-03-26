from __future__ import annotations

import json
import logging
import threading
import time
from collections import Counter
from datetime import datetime
from zoneinfo import ZoneInfo

from sqlalchemy import desc
from sqlalchemy.orm import Session

from app.auth import now
from app.autotrade_reason_codes import normalize_reason_code, reason_code_label
from app.config import settings
from app.models import AutoTradeOrder, Device, SessionToken
from app.push import send_to_devices_and_log

logger = logging.getLogger("stock.autotrade.push")
SEOUL = ZoneInfo(settings.app_tz)

_PUSH_SUCCESS_STATUSES = {"PAPER_FILLED", "BROKER_SUBMITTED", "BROKER_FILLED"}
_PUSH_REJECT_STATUSES = {"BROKER_REJECTED", "ERROR"}
_push_failure_lock = threading.Lock()
_push_failure_last_sent_ts: dict[tuple[int, str, str], float] = {}
_push_success_lock = threading.Lock()
_push_success_last_sent_ts: dict[tuple[int, str, str, str], float] = {}


def _collect_user_devices(session: Session, user_id: int, max_devices: int = 5) -> list[Device]:
    def _fallback_by_pref() -> list[Device]:
        out: list[Device] = []
        rows = session.query(Device).order_by(desc(Device.updated_at)).limit(max(100, max_devices * 20)).all()
        for d in rows:
            try:
                pref = json.loads(str(getattr(d, "pref_json", "") or "{}"))
                bound_user_id = int(pref.get("user_id") or 0)
            except Exception:
                bound_user_id = 0
            if bound_user_id != int(user_id):
                continue
            out.append(d)
            if len(out) >= max_devices:
                break
        return out

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
    for row in rows:
        did = str(row[0] or "").strip()
        if did and did not in device_ids:
            device_ids.append(did)
            if len(device_ids) >= max_devices:
                break
    if not device_ids:
        return _fallback_by_pref()
    by_id = {str(d.device_id): d for d in session.query(Device).filter(Device.device_id.in_(device_ids)).all()}
    out = [by_id[did] for did in device_ids if did in by_id]
    if out:
        return out
    return _fallback_by_pref()


def _environment_label(environment: str) -> str:
    env = str(environment or "").strip().lower()
    if env == "prod":
        return "실전"
    if env == "demo":
        return "모의투자"
    return "자동매매"


def _ticker_preview(orders: list[AutoTradeOrder], *, limit: int = 3) -> str:
    out: list[str] = []
    for order in orders:
        nm = str(getattr(order, "name", "") or "").strip()
        tk = str(getattr(order, "ticker", "") or "").strip()
        label = nm or tk
        if not label:
            continue
        if label in out:
            continue
        out.append(label)
        if len(out) >= limit:
            break
    return ", ".join(out)


def _normalize_orders(orders: list[AutoTradeOrder]) -> tuple[list[AutoTradeOrder], list[AutoTradeOrder], list[AutoTradeOrder]]:
    submitted: list[AutoTradeOrder] = []
    rejected: list[AutoTradeOrder] = []
    skipped: list[AutoTradeOrder] = []
    for order in orders:
        status = str(getattr(order, "status", "") or "").strip().upper()
        if status in _PUSH_SUCCESS_STATUSES:
            submitted.append(order)
        elif status in _PUSH_REJECT_STATUSES:
            rejected.append(order)
        elif status == "SKIPPED":
            skipped.append(order)
    return submitted, rejected, skipped


def _rejection_reason_code(order: AutoTradeOrder) -> str:
    return normalize_reason_code(
        str(getattr(order, "status", "") or ""),
        str(getattr(order, "reason", "") or ""),
    )


def _is_failure_push_allowed(
    *,
    user_id: int,
    environment: str,
    primary_reason_code: str,
) -> bool:
    cooldown_sec = max(60, min(3600, int(getattr(settings, "autotrade_push_failure_cooldown_sec", 600))))
    now_ts = time.time()
    key = (int(user_id), str(environment or "").lower(), str(primary_reason_code or "").upper())
    with _push_failure_lock:
        last_ts = float(_push_failure_last_sent_ts.get(key, 0.0))
        if (now_ts - last_ts) < float(cooldown_sec):
            return False
        _push_failure_last_sent_ts[key] = now_ts
    return True


def _is_success_push_allowed(
    *,
    user_id: int,
    environment: str,
    payload_type: str,
    signature: str,
) -> bool:
    cooldown_sec = max(30, min(3600, int(getattr(settings, "autotrade_push_success_cooldown_sec", 120))))
    now_ts = time.time()
    key = (
        int(user_id),
        str(environment or "").lower(),
        str(payload_type or "").upper(),
        str(signature or ""),
    )
    with _push_success_lock:
        last_ts = float(_push_success_last_sent_ts.get(key, 0.0))
        if (now_ts - last_ts) < float(cooldown_sec):
            return False
        _push_success_last_sent_ts[key] = now_ts
    return True


def send_autotrade_run_push(
    session: Session,
    *,
    user_id: int,
    environment: str,
    run_id: str,
    source: str,
    orders: list[AutoTradeOrder],
    message: str | None = None,
) -> bool:
    if not bool(getattr(settings, "autotrade_push_enabled", True)):
        logger.info("autotrade push skipped user=%s run_id=%s reason=PUSH_DISABLED", user_id, run_id)
        return False
    devices = _collect_user_devices(session, int(user_id))
    if not devices:
        logger.info("autotrade push skipped user=%s run_id=%s reason=NO_ACTIVE_DEVICE", user_id, run_id)
        return False
    if not orders:
        logger.info("autotrade push skipped user=%s run_id=%s reason=NO_ORDERS", user_id, run_id)
        return False

    submitted, rejected, skipped = _normalize_orders(orders)
    if not submitted and not rejected:
        logger.info("autotrade push skipped user=%s run_id=%s reason=NO_PUSHABLE_STATUS", user_id, run_id)
        return False

    env_label = _environment_label(environment)
    upper_source = str(source or "ENGINE").strip().upper()
    title = ""
    body = ""
    payload_type = "AUTOTRADE_RUN_RESULT"
    primary_reason_code = ""

    take_profit = 0
    stop_loss = 0
    buy_submitted = 0
    sell_submitted = 0
    for order in submitted:
        side = str(getattr(order, "side", "") or "").strip().upper()
        reason_code = _rejection_reason_code(order)
        if side == "BUY":
            buy_submitted += 1
        elif side == "SELL":
            sell_submitted += 1
            if reason_code == "TAKE_PROFIT":
                take_profit += 1
            elif reason_code == "STOP_LOSS":
                stop_loss += 1

    if take_profit > 0 or stop_loss > 0:
        payload_type = "AUTOTRADE_EXIT_EXECUTED"
        title = f"자동매매 {env_label} 익절/손절 실행"
        body = f"익절 {take_profit}건 · 손절 {stop_loss}건 · 주문접수 {len(submitted)}건"
        preview = _ticker_preview([o for o in submitted if str(getattr(o, "side", "")).upper() == "SELL"])
        if preview:
            body = f"{body} ({preview})"
        sell_tickers = sorted({str(getattr(o, "ticker", "") or "").strip() for o in submitted if str(getattr(o, "side", "")).upper() == "SELL"})
        signature = f"TP:{take_profit}|SL:{stop_loss}|SELL:{','.join(sell_tickers[:6])}"
        if not _is_success_push_allowed(
            user_id=int(user_id),
            environment=environment,
            payload_type=payload_type,
            signature=signature,
        ):
            logger.info("autotrade push skipped user=%s run_id=%s reason=SUCCESS_COOLDOWN type=%s", user_id, run_id, payload_type)
            return False
    elif submitted:
        payload_type = "AUTOTRADE_ORDER_SUBMITTED"
        title = f"자동매매 {env_label} 주문 접수"
        body = f"매수 {buy_submitted}건 · 매도 {sell_submitted}건"
        preview = _ticker_preview(submitted)
        if preview:
            body = f"{body} ({preview})"
        submitted_tickers = sorted({str(getattr(o, "ticker", "") or "").strip() for o in submitted if str(getattr(o, "ticker", "") or "").strip()})
        signature = f"BUY:{buy_submitted}|SELL:{sell_submitted}|T:{','.join(submitted_tickers[:6])}"
        if not _is_success_push_allowed(
            user_id=int(user_id),
            environment=environment,
            payload_type=payload_type,
            signature=signature,
        ):
            logger.info("autotrade push skipped user=%s run_id=%s reason=SUCCESS_COOLDOWN type=%s", user_id, run_id, payload_type)
            return False
    else:
        reason_codes = [_rejection_reason_code(o) for o in rejected]
        primary_reason_code = Counter(reason_codes).most_common(1)[0][0] if reason_codes else "BROKER_REJECTED"
        if not _is_failure_push_allowed(
            user_id=int(user_id),
            environment=environment,
            primary_reason_code=primary_reason_code,
        ):
            return False
        payload_type = "AUTOTRADE_ORDER_FAILED"
        title = f"자동매매 {env_label} 주문 실패"
        body = f"{reason_code_label(primary_reason_code)} ({len(rejected)}건)"
        preview = _ticker_preview(rejected)
        if preview:
            body = f"{body} ({preview})"

    payload = {
        "type": payload_type,
        "route": "autotrade",
        "run_id": str(run_id or ""),
        "engine_source": upper_source,
        "environment": str(environment or "").lower(),
        "submitted_count": len(submitted),
        "rejected_count": len(rejected),
        "skipped_count": len(skipped),
        "take_profit_count": take_profit,
        "stop_loss_count": stop_loss,
        "message": str(message or ""),
        "primary_reason_code": primary_reason_code,
        "as_of": datetime.now(tz=SEOUL).isoformat(),
    }

    stats = send_to_devices_and_log(
        session,
        devices=devices,
        alert_type="TRIGGER",
        title=title,
        body=body,
        payload=payload,
        dry_run=False,
        respect_preferences=True,
    )
    logger.info(
        "autotrade push sent user=%s source=%s env=%s type=%s target=%s token=%s sent=%s failed=%s",
        user_id,
        upper_source,
        str(environment or "").lower(),
        payload_type,
        stats.target_count,
        stats.token_count,
        stats.sent_count,
        stats.failed_count,
    )
    return stats.sent_count > 0
