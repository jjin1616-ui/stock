from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Iterable
from zoneinfo import ZoneInfo

from app.config import settings
from app.models import Alert, Device

SEOUL = ZoneInfo(settings.app_tz)
logger = logging.getLogger("stock.push")

_firebase_ready = False
messaging = None
if settings.firebase_admin_json:
    try:
        import firebase_admin
        from firebase_admin import credentials, messaging as _messaging

        if os.path.exists(settings.firebase_admin_json):
            firebase_admin.initialize_app(credentials.Certificate(settings.firebase_admin_json))
            messaging = _messaging
            _firebase_ready = True
    except Exception:
        _firebase_ready = False


@dataclass
class PushDispatchStats:
    target_count: int = 0
    token_count: int = 0
    sent_count: int = 0
    failed_count: int = 0
    skipped_count: int = 0
    skipped_no_token_count: int = 0
    skipped_pref_count: int = 0
    sample_tokens_masked: list[str] = field(default_factory=list)


def mask_token(raw: str | None) -> str:
    token = str(raw or "").strip()
    if not token:
        return ""
    if len(token) <= 8:
        return token[0] + ("*" * max(0, len(token) - 2)) + token[-1]
    return f"{token[:6]}***{token[-4:]}"


def _parse_pref(pref_json: str | None) -> dict[str, Any]:
    try:
        data = json.loads(pref_json or "{}")
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _is_enabled_by_pref(alert_type: str, pref: dict[str, Any]) -> bool:
    if alert_type == "PREMARKET":
        return bool(pref.get("push_premarket", True))
    if alert_type == "EOD":
        return bool(pref.get("push_eod", True))
    if alert_type == "TRIGGER":
        return bool(pref.get("push_triggers", True))
    # ADMIN/UPDATE 등 운영자 수동 발송은 사용자 알림 설정과 무관하게 전달.
    return True


def is_push_ready() -> bool:
    return bool(_firebase_ready and messaging is not None)


def send_to_devices_and_log(
    session,
    *,
    devices: Iterable[Device],
    alert_type: str,
    title: str,
    body: str,
    payload: dict[str, Any],
    dry_run: bool = False,
    respect_preferences: bool = True,
) -> PushDispatchStats:
    stats = PushDispatchStats()
    payload_data = {k: str(v) for k, v in payload.items()}
    sample_seen: set[str] = set()
    payload_str = json.dumps(payload, ensure_ascii=False)

    for d in devices:
        stats.target_count += 1
        pref = _parse_pref(getattr(d, "pref_json", None))
        if respect_preferences and not _is_enabled_by_pref(alert_type, pref):
            stats.skipped_count += 1
            stats.skipped_pref_count += 1
            continue
        token = str(getattr(d, "fcm_token", "") or "").strip()
        if not token:
            stats.skipped_count += 1
            stats.skipped_no_token_count += 1
            continue
        stats.token_count += 1
        masked = mask_token(token)
        if masked and masked not in sample_seen and len(stats.sample_tokens_masked) < 3:
            sample_seen.add(masked)
            stats.sample_tokens_masked.append(masked)
        if dry_run:
            stats.skipped_count += 1
            continue
        if not (_firebase_ready and messaging is not None):
            stats.failed_count += 1
            logger.warning("push send failed alert_type=%s token=%s reason=FIREBASE_NOT_READY", alert_type, masked)
            continue
        try:
            msg = messaging.Message(
                token=token,
                notification=messaging.Notification(title=title, body=body),
                data=payload_data,
            )
            messaging.send(msg, dry_run=False)
            stats.sent_count += 1
        except Exception as exc:
            stats.failed_count += 1
            # token 원문이 로그에 남지 않도록 마스킹 토큰 + 예외 클래스만 기록한다.
            logger.warning(
                "push send failed alert_type=%s token=%s error=%s",
                alert_type,
                masked,
                exc.__class__.__name__,
            )

    if not dry_run:
        session.add(
            Alert(
                ts=datetime.now(tz=SEOUL),
                type=alert_type,
                title=title,
                body=body,
                payload_json=payload_str,
            )
        )
    return stats


def send_and_log(session, alert_type: str, title: str, body: str, payload: dict) -> int:
    stats = send_to_devices_and_log(
        session,
        devices=session.query(Device).all(),
        alert_type=alert_type,
        title=title,
        body=body,
        payload=payload,
        dry_run=False,
        respect_preferences=True,
    )
    return stats.sent_count
