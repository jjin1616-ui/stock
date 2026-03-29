from __future__ import annotations

from datetime import datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.auth import now
from app.config import settings
from app.kis_broker import KisCredentialBundle
from app.models import UserBrokerCredential
from app.secret_box import decrypt_text, encrypt_text, mask_secret


def _clean(raw: Any) -> str:
    return str(raw or "").strip()


def _digits(raw: str) -> str:
    return "".join(ch for ch in raw if ch.isdigit())


def _mask_tail(raw: str | None, count: int = 4) -> str | None:
    value = _clean(raw)
    if not value:
        return None
    if count <= 0:
        return value
    if len(value) <= count:
        return "*" * len(value)
    return f"{value[:-count]}{'*' * count}"


def _normalize_account_product_code(raw: Any) -> str:
    value = _clean(raw)
    if not value:
        return "01"
    digits = _digits(value)
    if not digits:
        return "01"
    return digits[:2].zfill(2)


def _extract_account_fields(account_no_raw: Any, account_product_code_raw: Any) -> tuple[str, str | None]:
    account_no_value = _clean(account_no_raw)
    account_no_digits = _digits(account_no_value)
    inferred_product_code: str | None = None

    # Accept "12345678-01" input and split into account number / product code.
    if "-" in account_no_value:
        left, right = account_no_value.split("-", 1)
        left_digits = _digits(left)
        right_digits = _digits(right)
        account_no_digits = left_digits or account_no_digits
        if right_digits:
            inferred_product_code = _normalize_account_product_code(right_digits)
    elif len(account_no_digits) >= 10:
        # If user pasted 10 digits in one field, treat trailing 2 digits as product code.
        inferred_product_code = _normalize_account_product_code(account_no_digits[8:10])

    normalized_account_no = account_no_digits[:8]
    explicit_product_code = _clean(account_product_code_raw)
    if explicit_product_code:
        return normalized_account_no, _normalize_account_product_code(explicit_product_code)
    return normalized_account_no, inferred_product_code


def get_or_create_user_broker_credential(session: Session, user_id: int) -> UserBrokerCredential:
    row = session.scalar(select(UserBrokerCredential).where(UserBrokerCredential.user_id == user_id).limit(1))
    if row is not None:
        return row
    ts = now()
    row = UserBrokerCredential(
        user_id=user_id,
        use_user_credentials=False,
        app_key_demo_enc=None,
        app_secret_demo_enc=None,
        app_key_prod_enc=None,
        app_secret_prod_enc=None,
        account_no_demo_enc=None,
        account_no_prod_enc=None,
        account_no_enc=None,
        account_product_code_demo="01",
        account_product_code_prod="01",
        account_product_code="01",
        created_at=ts,
        updated_at=ts,
    )
    session.add(row)
    session.flush()
    return row


def _set_encrypted_field(row: UserBrokerCredential, attr: str, raw_value: Any) -> None:
    value = _clean(raw_value)
    if not value:
        return
    setattr(row, attr, encrypt_text(value))


def update_user_broker_credential(session: Session, user_id: int, payload: dict[str, Any]) -> UserBrokerCredential:
    row = get_or_create_user_broker_credential(session, user_id)
    if "use_user_credentials" in payload:
        row.use_user_credentials = bool(payload.get("use_user_credentials"))
    parsed_shared_product_code: str | None = None
    parsed_demo_product_code: str | None = None
    parsed_prod_product_code: str | None = None

    if bool(payload.get("clear_demo")):
        row.app_key_demo_enc = None
        row.app_secret_demo_enc = None
    if bool(payload.get("clear_prod")):
        row.app_key_prod_enc = None
        row.app_secret_prod_enc = None
    if bool(payload.get("clear_account")):
        row.account_no_enc = None
        row.account_no_demo_enc = None
        row.account_no_prod_enc = None

    _set_encrypted_field(row, "app_key_demo_enc", payload.get("app_key_demo"))
    _set_encrypted_field(row, "app_secret_demo_enc", payload.get("app_secret_demo"))
    _set_encrypted_field(row, "app_key_prod_enc", payload.get("app_key_prod"))
    _set_encrypted_field(row, "app_secret_prod_enc", payload.get("app_secret_prod"))

    if "account_no_demo" in payload:
        normalized_demo_no, parsed_demo_product_code = _extract_account_fields(
            payload.get("account_no_demo"),
            payload.get("account_product_code_demo"),
        )
        _set_encrypted_field(row, "account_no_demo_enc", normalized_demo_no)

    if "account_no_prod" in payload:
        normalized_prod_no, parsed_prod_product_code = _extract_account_fields(
            payload.get("account_no_prod"),
            payload.get("account_product_code_prod"),
        )
        _set_encrypted_field(row, "account_no_prod_enc", normalized_prod_no)

    if "account_no" in payload:
        normalized_account_no, parsed_shared_product_code = _extract_account_fields(
            payload.get("account_no"),
            payload.get("account_product_code"),
        )
        _set_encrypted_field(row, "account_no_enc", normalized_account_no)
        # Legacy shared account input should populate both env accounts unless explicitly provided.
        if "account_no_demo" not in payload:
            _set_encrypted_field(row, "account_no_demo_enc", normalized_account_no)
        if "account_no_prod" not in payload:
            _set_encrypted_field(row, "account_no_prod_enc", normalized_account_no)

    if "account_product_code_demo" in payload:
        row.account_product_code_demo = _normalize_account_product_code(payload.get("account_product_code_demo"))
    elif parsed_demo_product_code is not None:
        row.account_product_code_demo = parsed_demo_product_code

    if "account_product_code_prod" in payload:
        row.account_product_code_prod = _normalize_account_product_code(payload.get("account_product_code_prod"))
    elif parsed_prod_product_code is not None:
        row.account_product_code_prod = parsed_prod_product_code

    if "account_product_code" in payload:
        shared_code = _normalize_account_product_code(payload.get("account_product_code"))
        row.account_product_code = shared_code
        if "account_product_code_demo" not in payload:
            row.account_product_code_demo = shared_code
        if "account_product_code_prod" not in payload:
            row.account_product_code_prod = shared_code
    elif parsed_shared_product_code is not None:
        row.account_product_code = parsed_shared_product_code
        if "account_product_code_demo" not in payload and "account_no_demo" not in payload:
            row.account_product_code_demo = parsed_shared_product_code
        if "account_product_code_prod" not in payload and "account_no_prod" not in payload:
            row.account_product_code_prod = parsed_shared_product_code

    # Keep legacy code aligned with effective prod code for backward compatibility paths.
    row.account_product_code = _normalize_account_product_code(
        row.account_product_code_prod or row.account_product_code_demo or row.account_product_code
    )

    row.updated_at = now()
    session.flush()
    return row


def _decrypted_bundle(row: UserBrokerCredential | None) -> KisCredentialBundle | None:
    if row is None:
        return None
    demo_key = decrypt_text(row.app_key_demo_enc)
    demo_secret = decrypt_text(row.app_secret_demo_enc)
    prod_key = decrypt_text(row.app_key_prod_enc)
    prod_secret = decrypt_text(row.app_secret_prod_enc)
    legacy_account_no = decrypt_text(row.account_no_enc)
    demo_account_no = decrypt_text(row.account_no_demo_enc) or legacy_account_no
    prod_account_no = decrypt_text(row.account_no_prod_enc) or legacy_account_no
    demo_product_code = _normalize_account_product_code(row.account_product_code_demo or row.account_product_code)
    prod_product_code = _normalize_account_product_code(row.account_product_code_prod or row.account_product_code)
    legacy_product_code = _normalize_account_product_code(row.account_product_code)
    if not any([demo_key, demo_secret, prod_key, prod_secret, demo_account_no, prod_account_no, legacy_account_no]):
        return None
    return KisCredentialBundle(
        app_key_demo=demo_key or "",
        app_secret_demo=demo_secret or "",
        app_key_prod=prod_key or "",
        app_secret_prod=prod_secret or "",
        account_no=(prod_account_no or demo_account_no or legacy_account_no or ""),
        account_product_code=(prod_product_code or demo_product_code or legacy_product_code or "01"),
        account_no_demo=demo_account_no or "",
        account_no_prod=prod_account_no or "",
        account_product_code_demo=demo_product_code,
        account_product_code_prod=prod_product_code,
    )


def resolve_user_kis_credentials(session: Session, user_id: int) -> tuple[KisCredentialBundle | None, bool]:
    row = get_or_create_user_broker_credential(session, user_id)
    return _decrypted_bundle(row), bool(row.use_user_credentials)


def _bundle_ready(bundle: KisCredentialBundle | None, env: str) -> bool:
    if bundle is None:
        return False
    if env == "demo":
        return bool(bundle.app_key_demo and bundle.app_secret_demo and bundle.account_no_demo and bundle.account_product_code_demo)
    return bool(bundle.app_key_prod and bundle.app_secret_prod and bundle.account_no_prod and bundle.account_product_code_prod)


def _server_ready(env: str) -> bool:
    if env == "demo":
        return bool(
            settings.kis_app_key_demo
            and settings.kis_app_secret_demo
            and settings.kis_account_no
            and settings.kis_account_product_code
        )
    return bool(
        settings.kis_app_key_prod
        and settings.kis_app_secret_prod
        and settings.kis_account_no
        and settings.kis_account_product_code
    )


def broker_credential_status(session: Session, user_id: int) -> dict[str, Any]:
    row = get_or_create_user_broker_credential(session, user_id)
    bundle = _decrypted_bundle(row)
    demo_account_no = (bundle.account_no_demo or bundle.account_no) if bundle is not None else None
    prod_account_no = (bundle.account_no_prod or bundle.account_no) if bundle is not None else None
    account_no = prod_account_no or demo_account_no
    prod_app_key = bundle.app_key_prod if bundle is not None else None
    prod_app_secret = bundle.app_secret_prod if bundle is not None else None
    demo_ready_user = _bundle_ready(bundle, "demo")
    prod_ready_user = _bundle_ready(bundle, "prod")
    demo_ready_server = _server_ready("demo")
    prod_ready_server = _server_ready("prod")
    source = "USER" if bool(row.use_user_credentials) else "SERVER_ENV"
    demo_product_code = _normalize_account_product_code(
        (bundle.account_product_code_demo if bundle is not None else row.account_product_code_demo) or row.account_product_code
    )
    prod_product_code = _normalize_account_product_code(
        (bundle.account_product_code_prod if bundle is not None else row.account_product_code_prod) or row.account_product_code
    )
    return {
        "kis_trading_enabled": bool(settings.kis_trading_enabled),
        "use_user_credentials": bool(row.use_user_credentials),
        "has_demo_app_key": bool(bundle and bundle.app_key_demo),
        "has_demo_app_secret": bool(bundle and bundle.app_secret_demo),
        "has_prod_app_key": bool(bundle and bundle.app_key_prod),
        "has_prod_app_secret": bool(bundle and bundle.app_secret_prod),
        "has_account_no": bool(account_no),
        "has_demo_account_no": bool(demo_account_no),
        "has_prod_account_no": bool(prod_account_no),
        "masked_account_no": mask_secret(account_no, prefix=3, suffix=2),
        "masked_demo_account_no": mask_secret(demo_account_no, prefix=3, suffix=2),
        "masked_prod_account_no": mask_secret(prod_account_no, prefix=3, suffix=2),
        "masked_prod_app_key": _mask_tail(prod_app_key, 4),
        "masked_prod_app_secret": _mask_tail(prod_app_secret, 4),
        "account_product_code": prod_product_code,
        "account_product_code_demo": demo_product_code,
        "account_product_code_prod": prod_product_code,
        "demo_ready_user": demo_ready_user,
        "prod_ready_user": prod_ready_user,
        "demo_ready_server": demo_ready_server,
        "prod_ready_server": prod_ready_server,
        "demo_ready_effective": demo_ready_user if bool(row.use_user_credentials) else demo_ready_server,
        "prod_ready_effective": prod_ready_user if bool(row.use_user_credentials) else prod_ready_server,
        "source": source,
        "updated_at": row.updated_at if isinstance(row.updated_at, datetime) else now(),
    }
