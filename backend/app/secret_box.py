from __future__ import annotations

import base64
import hashlib
import hmac
import os

from app.config import settings


def _secret_key() -> bytes:
    raw = (settings.credentials_crypto_secret or settings.auth_pepper or "change-me-please").encode("utf-8")
    return hashlib.sha256(raw).digest()


def _keystream(key: bytes, nonce: bytes, nbytes: int) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < nbytes:
        block = hmac.new(key, nonce + counter.to_bytes(4, "big"), hashlib.sha256).digest()
        out.extend(block)
        counter += 1
    return bytes(out[:nbytes])


def encrypt_text(plain: str) -> str:
    data = (plain or "").encode("utf-8")
    nonce = os.urandom(16)
    key = _secret_key()
    stream = _keystream(key, nonce, len(data))
    cipher = bytes(a ^ b for a, b in zip(data, stream))
    mac = hmac.new(key, nonce + cipher, hashlib.sha256).digest()
    token = nonce + mac + cipher
    return base64.urlsafe_b64encode(token).decode("ascii")


def decrypt_text(token: str | None) -> str | None:
    if not token:
        return None
    try:
        raw = base64.urlsafe_b64decode(token.encode("ascii"))
    except Exception:
        return None
    if len(raw) < 48:
        return None
    nonce = raw[:16]
    mac = raw[16:48]
    cipher = raw[48:]
    key = _secret_key()
    expected = hmac.new(key, nonce + cipher, hashlib.sha256).digest()
    if not hmac.compare_digest(mac, expected):
        return None
    stream = _keystream(key, nonce, len(cipher))
    plain = bytes(a ^ b for a, b in zip(cipher, stream))
    try:
        return plain.decode("utf-8")
    except Exception:
        return None


def mask_secret(raw: str | None, *, prefix: int = 2, suffix: int = 2) -> str | None:
    if raw is None:
        return None
    v = raw.strip()
    if not v:
        return None
    if len(v) <= prefix + suffix:
        return "*" * len(v)
    return f"{v[:prefix]}{'*' * (len(v) - prefix - suffix)}{v[-suffix:]}"
