from __future__ import annotations

import hashlib
import json
import secrets
from dataclasses import dataclass
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
from typing import Optional

from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select, update

from app.config import settings
from app.models import AdminAuditLog, LoginEvent, RefreshToken, SessionToken, User
from app.storage import session_scope

security = HTTPBearer(auto_error=False)

ROLE_MASTER = "MASTER"
ROLE_USER = "USER"

STATUS_ACTIVE = "active"
STATUS_BLOCKED = "blocked"
STATUS_DELETED = "deleted"

INVITE_CREATED = "CREATED"
INVITE_SENT = "SENT"
INVITE_ACTIVATED = "ACTIVATED"
INVITE_PASSWORD_CHANGED = "PASSWORD_CHANGED"
INVITE_ACTIVE = "ACTIVE"

REASON_OK = "OK"
REASON_INVALID_CRED = "INVALID_CRED"
REASON_BLOCKED = "BLOCKED"
REASON_LOCKED = "LOCKED"
REASON_EXPIRED_TEMP_PASSWORD = "EXPIRED_TEMP_PASSWORD"
REASON_FORCE_CHANGE_REQUIRED = "FORCE_CHANGE_REQUIRED"
REASON_DELETED = "DELETED"
REASON_DEVICE_NOT_ALLOWED = "DEVICE_NOT_ALLOWED"
REASON_STATUS_INACTIVE = "STATUS_INACTIVE"
REASON_TOKEN_INVALID = "TOKEN_INVALID"
REASON_TOKEN_REVOKED = "TOKEN_REVOKED"


@dataclass
class TokenContext:
    user: User
    token_hash: str
    session_id: int


@dataclass
class TokenPair:
    user_id: int
    access_token: str
    access_expires_at: datetime
    refresh_token: str
    refresh_expires_at: datetime


def now() -> datetime:
    # SQLite + SQLAlchemy 조합에서 timezone-aware datetime이 round-trip 시
    # tzinfo가 유실되어(naive로) 비교 연산에서 TypeError가 발생할 수 있다.
    # Access Control은 '벽시각' 비교만 필요하므로, APP_TZ 기준의 naive datetime을 사용한다.
    return datetime.now(tz=ZoneInfo(settings.app_tz)).replace(tzinfo=None)


def hash_token(token: str) -> str:
    raw = f"{token}:{settings.auth_pepper}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def hash_refresh_token(token: str) -> str:
    raw = f"refresh:{token}:{settings.auth_pepper}".encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def issue_token(user: User, *, device_id: str | None, app_version: str | None, ip: str | None, user_agent: str | None) -> str:
    token = secrets.token_urlsafe(32)
    token_hash = hash_token(token)
    issued_at = now()
    expires_at = issued_at + timedelta(hours=settings.auth_token_ttl_hours)
    with session_scope() as session:
        session.add(
            SessionToken(
                user_id=user.id,
                token_hash=token_hash,
                issued_at=issued_at,
                expires_at=expires_at,
                revoked_at=None,
                device_id=device_id,
                app_version=app_version,
                ip=ip,
                user_agent=user_agent,
            )
        )
    return token


def issue_token_pair(
    user: User,
    *,
    device_id: str | None,
    app_version: str | None,
    ip: str | None,
    user_agent: str | None,
) -> TokenPair:
    access_token = secrets.token_urlsafe(32)
    refresh_token = secrets.token_urlsafe(48)
    access_hash = hash_token(access_token)
    refresh_hash = hash_refresh_token(refresh_token)
    issued_at = now()
    access_expires_at = issued_at + timedelta(hours=settings.auth_token_ttl_hours)
    refresh_expires_at = issued_at + timedelta(hours=max(1, settings.auth_refresh_token_ttl_hours))
    with session_scope() as session:
        session_row = SessionToken(
            user_id=user.id,
            token_hash=access_hash,
            issued_at=issued_at,
            expires_at=access_expires_at,
            revoked_at=None,
            device_id=device_id,
            app_version=app_version,
            ip=ip,
            user_agent=user_agent,
        )
        session.add(session_row)
        session.flush()
        session.add(
            RefreshToken(
                user_id=user.id,
                session_id=session_row.id,
                token_hash=refresh_hash,
                issued_at=issued_at,
                expires_at=refresh_expires_at,
                revoked_at=None,
                device_id=device_id,
                app_version=app_version,
                ip=ip,
                user_agent=user_agent,
            )
        )
    return TokenPair(
        user_id=int(user.id),
        access_token=access_token,
        access_expires_at=access_expires_at,
        refresh_token=refresh_token,
        refresh_expires_at=refresh_expires_at,
    )


def rotate_refresh_token(
    raw_refresh_token: str,
    *,
    device_id: str | None,
    app_version: str | None,
    ip: str | None,
    user_agent: str | None,
) -> TokenPair:
    token_raw = str(raw_refresh_token or "").strip()
    if not token_raw:
        raise HTTPException(status_code=401, detail=REASON_TOKEN_INVALID)
    refresh_hash = hash_refresh_token(token_raw)
    with session_scope() as session:
        row = session.execute(
            select(RefreshToken, User)
            .join(User, User.id == RefreshToken.user_id)
            .where(RefreshToken.token_hash == refresh_hash)
        ).first()
        if row is None:
            raise HTTPException(status_code=401, detail=REASON_TOKEN_INVALID)
        refresh_row, user = row
        if refresh_row.revoked_at is not None:
            raise HTTPException(status_code=401, detail=REASON_TOKEN_REVOKED)
        if refresh_row.expires_at <= now():
            refresh_row.revoked_at = now()
            raise HTTPException(status_code=401, detail="TOKEN_EXPIRED")
        if user.status != STATUS_ACTIVE:
            raise HTTPException(status_code=401, detail=REASON_TOKEN_REVOKED)
        if user.locked_until and user.locked_until > now():
            raise HTTPException(status_code=401, detail=REASON_TOKEN_REVOKED)
        refresh_row.revoked_at = now()
        if refresh_row.session_id:
            session.execute(
                update(SessionToken)
                .where(SessionToken.id == refresh_row.session_id, SessionToken.revoked_at.is_(None))
                .values(revoked_at=now())
            )
    return issue_token_pair(
        user,
        device_id=device_id,
        app_version=app_version,
        ip=ip,
        user_agent=user_agent,
    )


def revoke_all_sessions(user_id: int) -> None:
    with session_scope() as session:
        session.execute(
            update(SessionToken)
            .where(SessionToken.user_id == user_id, SessionToken.revoked_at.is_(None))
            .values(revoked_at=now())
        )
        session.execute(
            update(RefreshToken)
            .where(RefreshToken.user_id == user_id, RefreshToken.revoked_at.is_(None))
            .values(revoked_at=now())
        )


def revoke_session(token_hash: str) -> None:
    with session_scope() as session:
        token_row = session.execute(
            select(SessionToken).where(SessionToken.token_hash == token_hash).limit(1)
        ).scalar_one_or_none()
        session.execute(
            update(SessionToken)
            .where(SessionToken.token_hash == token_hash, SessionToken.revoked_at.is_(None))
            .values(revoked_at=now())
        )
        if token_row is not None:
            session.execute(
                update(RefreshToken)
                .where(RefreshToken.session_id == token_row.id, RefreshToken.revoked_at.is_(None))
                .values(revoked_at=now())
            )


def log_login_event(*, user: User | None, user_code: str | None, result: str, reason: str, ip: str | None, device_id: str | None, app_version: str | None) -> None:
    with session_scope() as session:
        session.add(
            LoginEvent(
                ts=now(),
                user_id=user.id if user else None,
                user_code=user.user_code if user else user_code,
                result=result,
                reason_code=reason,
                ip=ip,
                device_id=device_id,
                app_version=app_version,
            )
        )


def log_admin_action(*, admin_user_id: int, action: str, detail: dict | None = None) -> None:
    with session_scope() as session:
        session.add(
            AdminAuditLog(
                ts=now(),
                admin_user_id=admin_user_id,
                action=action,
                detail_json=json.dumps(detail or {}, ensure_ascii=False),
            )
        )


def get_request_ip(request: Request) -> str | None:
    return request.client.host if request.client else None


def get_request_user_agent(request: Request) -> str | None:
    return request.headers.get("user-agent")


async def get_token_context(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(security),
) -> TokenContext:
    if credentials is None or not credentials.credentials:
        raise HTTPException(status_code=401, detail="UNAUTHORIZED")
    token_hash = hash_token(credentials.credentials)
    with session_scope() as session:
        row = session.execute(
            select(SessionToken, User)
            .join(User, User.id == SessionToken.user_id)
            .where(SessionToken.token_hash == token_hash)
        ).first()
        if row is None:
            raise HTTPException(status_code=401, detail=REASON_TOKEN_INVALID)
        session_token, user = row
        if session_token.revoked_at is not None:
            raise HTTPException(status_code=401, detail=REASON_TOKEN_REVOKED)
        if session_token.expires_at <= now():
            raise HTTPException(status_code=401, detail="TOKEN_EXPIRED")
        return TokenContext(user=user, token_hash=token_hash, session_id=session_token.id)


def require_active_user(ctx: TokenContext, *, allow_force_change: bool = False) -> User:
    if ctx.user.status == STATUS_BLOCKED:
        raise HTTPException(status_code=403, detail=REASON_BLOCKED)
    if ctx.user.status == STATUS_DELETED:
        raise HTTPException(status_code=403, detail=REASON_DELETED)
    if ctx.user.locked_until and ctx.user.locked_until > now():
        raise HTTPException(status_code=403, detail=REASON_LOCKED)
    if ctx.user.force_password_change and not allow_force_change:
        raise HTTPException(status_code=403, detail=REASON_FORCE_CHANGE_REQUIRED)
    return ctx.user


def require_master(ctx: TokenContext) -> User:
    user = require_active_user(ctx, allow_force_change=True)
    if user.role != ROLE_MASTER:
        raise HTTPException(status_code=403, detail="FORBIDDEN")
    return user
