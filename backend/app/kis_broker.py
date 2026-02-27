from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import contextlib
import fcntl
import hashlib
import json
import logging
import os
import threading
from typing import Any
from zoneinfo import ZoneInfo

import requests

from app.config import settings

logger = logging.getLogger("stock.kis")
SEOUL = ZoneInfo("Asia/Seoul")
UTC = timezone.utc


def _utc_now() -> datetime:
    return datetime.now(tz=UTC)


@dataclass
class KisOrderResult:
    ok: bool
    status_code: int
    message: str
    order_no: str | None = None
    raw: dict[str, Any] | None = None


@dataclass
class KisBalancePosition:
    ticker: str
    name: str
    qty: int
    orderable_qty: int
    avg_price: float
    current_price: float
    eval_amount: float
    pnl_amount: float
    pnl_pct: float


@dataclass
class KisBalanceSnapshot:
    cash_amount: float
    orderable_cash_amount: float
    stock_eval_amount: float
    total_asset_amount: float
    total_pnl_amount: float
    positions: list[KisBalancePosition]
    raw: dict[str, Any] | None = None


@dataclass
class KisBalanceResult:
    ok: bool
    status_code: int
    message: str
    snapshot: KisBalanceSnapshot | None = None
    raw: dict[str, Any] | None = None


@dataclass
class _TokenCache:
    token: str
    expires_at: datetime


@dataclass
class _TokenIssueState:
    fail_count: int
    next_issue_at: datetime


@dataclass
class KisCredentialBundle:
    app_key_demo: str = ""
    app_secret_demo: str = ""
    app_key_prod: str = ""
    app_secret_prod: str = ""
    # Legacy shared account fields.
    account_no: str = ""
    account_product_code: str = "01"
    # Preferred env-specific account fields.
    account_no_demo: str = ""
    account_no_prod: str = ""
    account_product_code_demo: str = "01"
    account_product_code_prod: str = "01"


class KisBrokerClient:
    """Small KIS REST client based on the official open-trading-api sample flow."""

    _shared_token_cache: dict[str, _TokenCache] = {}
    _shared_token_lock = threading.Lock()
    _shared_issue_state: dict[str, _TokenIssueState] = {}

    def __init__(self, credentials: KisCredentialBundle | None = None) -> None:
        self._token_cache: dict[str, _TokenCache] = {}
        self._credentials = credentials

    def _env_conf(self, env: str) -> tuple[str, str, str]:
        if env == "demo":
            app_key = self._credentials.app_key_demo if self._credentials is not None else settings.kis_app_key_demo
            app_secret = self._credentials.app_secret_demo if self._credentials is not None else settings.kis_app_secret_demo
            return settings.kis_base_url_demo, app_key, app_secret
        app_key = self._credentials.app_key_prod if self._credentials is not None else settings.kis_app_key_prod
        app_secret = self._credentials.app_secret_prod if self._credentials is not None else settings.kis_app_secret_prod
        return settings.kis_base_url_prod, app_key, app_secret

    def _account_conf(self, env: str) -> tuple[str, str]:
        if self._credentials is not None:
            if env == "demo":
                account_no = self._credentials.account_no_demo or self._credentials.account_no
                account_product_code = self._credentials.account_product_code_demo or self._credentials.account_product_code
                return account_no, account_product_code
            account_no = self._credentials.account_no_prod or self._credentials.account_no
            account_product_code = self._credentials.account_product_code_prod or self._credentials.account_product_code
            return account_no, account_product_code
        return settings.kis_account_no, settings.kis_account_product_code

    def has_required_config(self, env: str) -> bool:
        base_url, app_key, app_secret = self._env_conf(env)
        account_no, account_product_code = self._account_conf(env)
        return bool(base_url and app_key and app_secret and account_no and account_product_code)

    def _issue_access_token(self, env: str) -> _TokenCache:
        base_url, app_key, app_secret = self._env_conf(env)
        resp = requests.post(
            f"{base_url}/oauth2/tokenP",
            headers={"Content-Type": "application/json"},
            json={
                "grant_type": "client_credentials",
                "appkey": app_key,
                "appsecret": app_secret,
            },
            timeout=max(3, settings.kis_order_timeout_sec),
        )
        status = int(resp.status_code)
        if status != 200:
            raise RuntimeError(f"KIS token issue failed: {status} {resp.text[:240]}")
        payload = resp.json()
        token = str(payload.get("access_token") or "").strip()
        expire_raw = str(payload.get("access_token_token_expired") or "").strip()
        if not token:
            raise RuntimeError("KIS token missing in response")
        expires_at = _utc_now() + timedelta(hours=23)
        if expire_raw:
            try:
                # KIS expire 문자열은 한국시간(KST) 기준이므로 UTC로 정규화한다.
                expires_at = (
                    datetime.strptime(expire_raw, "%Y-%m-%d %H:%M:%S")
                    .replace(tzinfo=SEOUL)
                    .astimezone(UTC)
                )
            except Exception:
                pass
        cache = _TokenCache(token=token, expires_at=expires_at)
        self._token_cache[env] = cache
        return cache

    def _token_cache_key(self, env: str) -> str:
        _, app_key, app_secret = self._env_conf(env)
        raw = f"{env}|{app_key}|{app_secret}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _token_cache_path(self, env: str) -> str:
        base_dir = os.path.join(settings.data_dir, "kis_token_cache")
        os.makedirs(base_dir, mode=0o700, exist_ok=True)
        try:
            os.chmod(base_dir, 0o700)
        except Exception:
            pass
        return os.path.join(base_dir, f"{self._token_cache_key(env)}.json")

    def _token_lock_path(self, env: str) -> str:
        return f"{self._token_cache_path(env)}.lock"

    @contextlib.contextmanager
    def _issue_lock(self, env: str):
        path = self._token_lock_path(env)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "a+", encoding="utf-8") as fh:
            try:
                try:
                    os.chmod(path, 0o600)
                except Exception:
                    pass
                fcntl.flock(fh.fileno(), fcntl.LOCK_EX)
                yield
            finally:
                try:
                    fcntl.flock(fh.fileno(), fcntl.LOCK_UN)
                except Exception:
                    pass

    def _load_token_from_disk(self, env: str) -> _TokenCache | None:
        path = self._token_cache_path(env)
        if not os.path.exists(path):
            return None
        try:
            with open(path, "r", encoding="utf-8") as fh:
                payload = json.load(fh)
            token = str(payload.get("token") or "").strip()
            expires_raw = str(payload.get("expires_at") or "").strip()
            if not token or not expires_raw:
                return None
            expires_at = datetime.fromisoformat(expires_raw)
            if expires_at.tzinfo is None:
                # Legacy naive cache(시간대 미기록)는 만료 판정 오차가 날 수 있어 재발급한다.
                return None
            expires_at = expires_at.astimezone(UTC)
            return _TokenCache(token=token, expires_at=expires_at)
        except Exception:
            return None

    @staticmethod
    def _is_token_expired_response(status_code: int, msg_cd: str, msg1: str) -> bool:
        code = str(msg_cd or "").strip().upper()
        message = str(msg1 or "").strip().lower()
        if status_code == 401:
            return True
        if code in {"EGW00123", "EGW00121", "EGW00134"}:
            return True
        if code.startswith("EGW0012") and ("token" in message or "토큰" in message):
            return True
        expired_markers = (
            "기간이 만료된 token",
            "기간이 만료된 토큰",
            "만료된 token",
            "만료된 토큰",
            "token expired",
            "access token expired",
            "토큰 만료",
        )
        return any(marker in message for marker in expired_markers)

    @staticmethod
    def _parse_json_payload(resp: requests.Response) -> dict[str, Any]:
        try:
            return resp.json() if resp.text else {}
        except Exception:
            return {"raw_text": resp.text[:500]}

    def _invalidate_token_cache(self, env: str) -> None:
        cache_key = self._token_cache_key(env)
        with self._shared_token_lock:
            self._token_cache.pop(env, None)
            self._shared_token_cache.pop(cache_key, None)
            self._shared_issue_state.pop(cache_key, None)
        path = self._token_cache_path(env)
        try:
            if os.path.exists(path):
                os.remove(path)
        except Exception:
            logger.warning("KIS token cache file remove failed env=%s path=%s", env, path)

    @staticmethod
    def _krx_tick_size(price: float) -> int:
        p = max(0.0, float(price))
        if p < 2_000:
            return 1
        if p < 5_000:
            return 5
        if p < 20_000:
            return 10
        if p < 50_000:
            return 50
        if p < 200_000:
            return 100
        if p < 500_000:
            return 500
        return 1_000

    @classmethod
    def _normalize_krx_limit_price(cls, price: float) -> int:
        p = max(0.0, float(price))
        if p <= 0.0:
            return 0
        tick = cls._krx_tick_size(p)
        snapped = int(round(p / tick) * tick)
        return max(tick, snapped)

    def _save_token_to_disk(self, env: str, cache: _TokenCache) -> None:
        path = self._token_cache_path(env)
        tmp = f"{path}.tmp"
        try:
            payload = {"token": cache.token, "expires_at": cache.expires_at.isoformat()}
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(payload, fh)
            os.replace(tmp, path)
            try:
                os.chmod(path, 0o600)
            except Exception:
                pass
        except Exception:
            try:
                if os.path.exists(tmp):
                    os.remove(tmp)
            except Exception:
                pass

    def _access_token(self, env: str) -> str:
        cache_key = self._token_cache_key(env)
        min_valid_until = _utc_now() + timedelta(minutes=5)
        fallback_valid_until = _utc_now() + timedelta(seconds=30)
        now = _utc_now()
        with self._shared_token_lock:
            local_cached = self._token_cache.get(env)
            if local_cached is not None and local_cached.expires_at > min_valid_until:
                return local_cached.token

            shared_cached = self._shared_token_cache.get(cache_key)
            if shared_cached is not None and shared_cached.expires_at > min_valid_until:
                self._token_cache[env] = shared_cached
                return shared_cached.token

            disk_cached = self._load_token_from_disk(env)
            if disk_cached is not None and disk_cached.expires_at > min_valid_until:
                self._token_cache[env] = disk_cached
                self._shared_token_cache[cache_key] = disk_cached
                return disk_cached.token
            issue_state = self._shared_issue_state.get(cache_key)
            if issue_state is not None and issue_state.next_issue_at > now:
                stale = shared_cached or local_cached or disk_cached
                if stale is not None and stale.expires_at > fallback_valid_until:
                    logger.warning("KIS token issuance backoff; using cached token env=%s", env)
                    self._token_cache[env] = stale
                    self._shared_token_cache[cache_key] = stale
                    return stale.token
                raise RuntimeError("KIS token issuance backoff")

        with self._issue_lock(env):
            with self._shared_token_lock:
                local_cached = self._token_cache.get(env)
                if local_cached is not None and local_cached.expires_at > min_valid_until:
                    return local_cached.token
                shared_cached = self._shared_token_cache.get(cache_key)
                if shared_cached is not None and shared_cached.expires_at > min_valid_until:
                    self._token_cache[env] = shared_cached
                    return shared_cached.token
                disk_cached = self._load_token_from_disk(env)
                if disk_cached is not None and disk_cached.expires_at > min_valid_until:
                    self._token_cache[env] = disk_cached
                    self._shared_token_cache[cache_key] = disk_cached
                    return disk_cached.token

            try:
                issued = self._issue_access_token(env)
                with self._shared_token_lock:
                    self._token_cache[env] = issued
                    self._shared_token_cache[cache_key] = issued
                    self._shared_issue_state.pop(cache_key, None)
                self._save_token_to_disk(env, issued)
                return issued.token
            except Exception:
                with self._shared_token_lock:
                    issue_state = self._shared_issue_state.get(cache_key)
                    if issue_state is None:
                        issue_state = _TokenIssueState(fail_count=0, next_issue_at=now)
                    issue_state.fail_count = min(issue_state.fail_count + 1, 6)
                    delay = min(2 ** issue_state.fail_count, 60)
                    issue_state.next_issue_at = _utc_now() + timedelta(seconds=delay)
                    self._shared_issue_state[cache_key] = issue_state
                    # KIS access-token endpoint may rate-limit (EGW00133).
                    # If an already-issued token is still valid, prefer continuity.
                    stale = self._shared_token_cache.get(cache_key) or self._token_cache.get(env) or disk_cached
                    if stale is not None and stale.expires_at > fallback_valid_until:
                        logger.warning("KIS token issuance failed; using cached token fallback env=%s", env)
                        self._token_cache[env] = stale
                        self._shared_token_cache[cache_key] = stale
                        self._save_token_to_disk(env, stale)
                        return stale.token
                raise

    @staticmethod
    def _normalize_token_error(exc: Exception) -> tuple[int, str]:
        raw = str(exc or "").strip()
        up = raw.upper()
        if "EGW00133" in up or "1분당 1회" in raw:
            return 429, "KIS_TOKEN_RATE_LIMIT"
        if "BACKOFF" in up:
            return 429, "KIS_TOKEN_RATE_LIMIT"
        if "KIS TOKEN MISSING" in up:
            return 502, "KIS_TOKEN_MISSING"
        if "KIS TOKEN ISSUE FAILED" in up:
            return 502, "KIS_TOKEN_ISSUE"
        msg = raw or "KIS_TOKEN_ERROR"
        return 500, f"KIS_TOKEN_ERROR:{msg[:180]}"

    def _hashkey(self, env: str, token: str, body: dict[str, Any]) -> str | None:
        base_url, app_key, app_secret = self._env_conf(env)
        try:
            resp = requests.post(
                f"{base_url}/uapi/hashkey",
                headers={
                    "Content-Type": "application/json",
                    "authorization": f"Bearer {token}",
                    "appkey": app_key,
                    "appsecret": app_secret,
                },
                data=json.dumps(body, ensure_ascii=False),
                timeout=max(3, settings.kis_order_timeout_sec),
            )
            if resp.status_code != 200:
                return None
            return str(resp.json().get("HASH") or "").strip() or None
        except Exception:
            return None

    @staticmethod
    def _to_float(raw: Any) -> float:
        if raw is None:
            return 0.0
        if isinstance(raw, (int, float)):
            return float(raw)
        txt = str(raw).strip().replace(",", "")
        if not txt or txt in {"-", "--"}:
            return 0.0
        try:
            return float(txt)
        except Exception:
            return 0.0

    @staticmethod
    def _to_int(raw: Any) -> int:
        try:
            return int(round(KisBrokerClient._to_float(raw)))
        except Exception:
            return 0

    @staticmethod
    def _first_float(obj: Any, keys: list[str]) -> float:
        if not isinstance(obj, dict):
            return 0.0
        for key in keys:
            if key in obj and obj.get(key) not in (None, ""):
                val = KisBrokerClient._to_float(obj.get(key))
                if val != 0.0:
                    return val
        for key in keys:
            if key in obj:
                return KisBrokerClient._to_float(obj.get(key))
        return 0.0

    def inquire_balance(self, *, env: str) -> KisBalanceResult:
        env_key = "demo" if env == "demo" else "prod"
        if not self.has_required_config(env_key):
            return KisBalanceResult(ok=False, status_code=400, message="KIS_CONFIG_MISSING")

        base_url, app_key, app_secret = self._env_conf(env_key)
        account_no, account_product_code = self._account_conf(env_key)
        try:
            token = self._access_token(env_key)
        except Exception as exc:
            status, msg = self._normalize_token_error(exc)
            logger.warning("KIS balance token acquisition failed env=%s status=%s msg=%s", env_key, status, msg)
            return KisBalanceResult(ok=False, status_code=status, message=msg)
        tr_id = "VTTC8434R" if env_key == "demo" else "TTTC8434R"
        endpoint = f"{base_url}/uapi/domestic-stock/v1/trading/inquire-balance"
        timeout_sec = max(3, settings.kis_order_timeout_sec)

        def _request_page(
            token_value: str,
            *,
            ctx_fk100: str,
            ctx_nk100: str,
        ) -> tuple[str, int, dict[str, Any], KisBalanceResult | None]:
            params = {
                "CANO": account_no,
                "ACNT_PRDT_CD": account_product_code,
                "AFHR_FLPR_YN": "N",
                "OFL_YN": "",
                "INQR_DVSN": "02",
                "UNPR_DVSN": "01",
                "FUND_STTL_ICLD_YN": "N",
                "FNCG_AMT_AUTO_RDPT_YN": "N",
                "PRCS_DVSN": "00",
                "CTX_AREA_FK100": ctx_fk100,
                "CTX_AREA_NK100": ctx_nk100,
            }
            headers = {
                "Content-Type": "application/json",
                "authorization": f"Bearer {token_value}",
                "appkey": app_key,
                "appsecret": app_secret,
                "tr_id": tr_id,
                "custtype": "P",
            }
            try:
                resp = requests.get(
                    endpoint,
                    headers=headers,
                    params=params,
                    timeout=timeout_sec,
                )
            except Exception as exc:
                logger.exception("KIS balance request failed env=%s", env_key)
                return token_value, 500, {}, KisBalanceResult(
                    ok=False,
                    status_code=500,
                    message=f"KIS_BALANCE_REQUEST_ERROR: {exc}",
                )

            status_code = int(resp.status_code)
            payload = self._parse_json_payload(resp)
            msg_cd = str(payload.get("msg_cd") or "").strip()
            msg1 = str(payload.get("msg1") or "").strip()
            if not self._is_token_expired_response(status_code, msg_cd, msg1):
                return token_value, status_code, payload, None

            logger.warning("KIS balance token expired; invalidate and retry env=%s msg_cd=%s", env_key, msg_cd or "-")
            self._invalidate_token_cache(env_key)
            try:
                retry_token = self._access_token(env_key)
            except Exception as exc:
                token_status, token_msg = self._normalize_token_error(exc)
                return token_value, token_status, payload, KisBalanceResult(
                    ok=False,
                    status_code=token_status,
                    message=token_msg,
                    raw=payload,
                )

            retry_headers = dict(headers)
            retry_headers["authorization"] = f"Bearer {retry_token}"
            try:
                retry_resp = requests.get(
                    endpoint,
                    headers=retry_headers,
                    params=params,
                    timeout=timeout_sec,
                )
            except Exception as exc:
                logger.exception("KIS balance retry request failed env=%s", env_key)
                return retry_token, 500, payload, KisBalanceResult(
                    ok=False,
                    status_code=500,
                    message=f"KIS_BALANCE_REQUEST_ERROR: {exc}",
                    raw=payload,
                )
            return retry_token, int(retry_resp.status_code), self._parse_json_payload(retry_resp), None

        token_in_use = token
        ctx_fk100 = ""
        ctx_nk100 = ""
        seen_ctx: set[tuple[str, str]] = set()
        page_payloads: list[dict[str, Any]] = []
        merged_rows: list[dict[str, Any]] = []
        summary_output2: dict[str, Any] = {}
        max_pages = 100

        for _ in range(max_pages):
            current_ctx = (ctx_fk100, ctx_nk100)
            if current_ctx in seen_ctx:
                logger.warning("KIS balance pagination loop detected env=%s ctx=%s", env_key, current_ctx)
                break
            seen_ctx.add(current_ctx)

            token_in_use, status, data, err = _request_page(
                token_in_use,
                ctx_fk100=ctx_fk100,
                ctx_nk100=ctx_nk100,
            )
            if err is not None:
                return err

            rt_cd = str(data.get("rt_cd") or "")
            msg = str(data.get("msg1") or data.get("msg_cd") or "KIS_BALANCE_FAILED")
            if status != 200 or rt_cd != "0":
                return KisBalanceResult(ok=False, status_code=status, message=msg, raw=data)

            page_payloads.append(data)
            output1_raw = data.get("output1")
            if isinstance(output1_raw, list):
                merged_rows.extend([row for row in output1_raw if isinstance(row, dict)])

            output2_raw = data.get("output2")
            page_output2: dict[str, Any]
            if isinstance(output2_raw, dict):
                page_output2 = output2_raw
            elif isinstance(output2_raw, list) and output2_raw and isinstance(output2_raw[0], dict):
                page_output2 = output2_raw[0]
            else:
                page_output2 = {}
            if page_output2:
                summary_output2 = page_output2

            next_fk100 = str(data.get("ctx_area_fk100") or data.get("CTX_AREA_FK100") or "").strip()
            next_nk100 = str(data.get("ctx_area_nk100") or data.get("CTX_AREA_NK100") or "").strip()
            if not next_nk100:
                break
            if (next_fk100, next_nk100) == (ctx_fk100, ctx_nk100):
                logger.warning("KIS balance pagination stagnation env=%s ctx=%s", env_key, (ctx_fk100, ctx_nk100))
                break
            ctx_fk100, ctx_nk100 = next_fk100, next_nk100
        else:
            logger.warning(
                "KIS balance pagination reached max pages env=%s pages=%s last_ctx=(%s,%s)",
                env_key,
                max_pages,
                ctx_fk100,
                ctx_nk100,
            )

        positions_by_ticker: dict[str, KisBalancePosition] = {}
        for row in merged_rows:
            if not isinstance(row, dict):
                continue
            hold_qty = self._to_int(row.get("hldg_qty") or row.get("hold_qty"))
            if hold_qty <= 0:
                continue
            if ("ord_psbl_qty" in row) and (row.get("ord_psbl_qty") not in (None, "")):
                orderable_qty = self._to_int(row.get("ord_psbl_qty"))
            else:
                orderable_qty = hold_qty
            orderable_qty = max(0, min(hold_qty, orderable_qty))
            ticker = str(row.get("pdno") or row.get("mksc_shrn_iscd") or "").strip()
            if not ticker:
                continue
            name = str(row.get("prdt_name") or row.get("hts_kor_isnm") or ticker).strip()
            avg_price = self._first_float(row, ["pchs_avg_pric", "pchs_avg_pric2", "pchs_avg_price"])
            current_price = self._first_float(row, ["prpr", "now_pric", "cur_prc"])
            eval_amount = self._first_float(row, ["evlu_amt", "evlu_amt2", "evlu_tot"])
            if eval_amount <= 0.0 and current_price > 0.0:
                eval_amount = current_price * float(hold_qty)
            pnl_amount = self._first_float(row, ["evlu_pfls_amt", "evlu_pfls_smtl_amt"])
            if pnl_amount == 0.0 and avg_price > 0.0 and current_price > 0.0:
                pnl_amount = (current_price - avg_price) * float(hold_qty)
            pnl_pct = self._first_float(row, ["evlu_pfls_rt", "evlu_pfls_rate"])
            if pnl_pct == 0.0 and avg_price > 0.0 and current_price > 0.0:
                pnl_pct = ((current_price / avg_price) - 1.0) * 100.0
            candidate = KisBalancePosition(
                ticker=ticker,
                name=name,
                qty=hold_qty,
                orderable_qty=orderable_qty,
                avg_price=avg_price,
                current_price=current_price,
                eval_amount=eval_amount,
                pnl_amount=pnl_amount,
                pnl_pct=pnl_pct,
            )
            existing = positions_by_ticker.get(ticker)
            if existing is None:
                positions_by_ticker[ticker] = candidate
                continue

            merged_qty = existing.qty + candidate.qty
            merged_orderable = existing.orderable_qty + candidate.orderable_qty
            merged_eval = existing.eval_amount + candidate.eval_amount
            merged_pnl = existing.pnl_amount + candidate.pnl_amount

            purchase_amount = (existing.avg_price * float(existing.qty)) + (candidate.avg_price * float(candidate.qty))
            merged_avg_price = 0.0
            if merged_qty > 0 and purchase_amount > 0.0:
                merged_avg_price = purchase_amount / float(merged_qty)
            elif existing.avg_price > 0.0:
                merged_avg_price = existing.avg_price
            else:
                merged_avg_price = candidate.avg_price

            merged_current = candidate.current_price if candidate.current_price > 0.0 else existing.current_price
            if merged_avg_price > 0.0 and merged_current > 0.0:
                merged_pnl_pct = ((merged_current / merged_avg_price) - 1.0) * 100.0
            else:
                invested = merged_eval - merged_pnl
                merged_pnl_pct = (merged_pnl / invested * 100.0) if invested > 0.0 else 0.0

            positions_by_ticker[ticker] = KisBalancePosition(
                ticker=ticker,
                name=existing.name or candidate.name,
                qty=merged_qty,
                orderable_qty=max(0, min(merged_qty, merged_orderable)),
                avg_price=merged_avg_price,
                current_price=merged_current,
                eval_amount=merged_eval,
                pnl_amount=merged_pnl,
                pnl_pct=merged_pnl_pct,
            )

        positions = sorted(positions_by_ticker.values(), key=lambda p: p.eval_amount, reverse=True)
        positions_eval_sum = sum(p.eval_amount for p in positions)
        positions_pnl_sum = sum(p.pnl_amount for p in positions)

        cash_amount = self._first_float(summary_output2, ["dnca_tot_amt", "dnca_tot_amt2", "dnca_tot"])
        orderable_cash = self._first_float(summary_output2, ["ord_psbl_cash", "ord_psbl_amt", "prvs_rcdl_excc_amt"])
        if orderable_cash <= 0.0:
            orderable_cash = cash_amount
        summary_stock_eval = self._first_float(summary_output2, ["scts_evlu_amt", "scts_evlu_amt2", "evlu_amt_smtl_amt"])
        if positions_eval_sum > 0.0:
            stock_eval = positions_eval_sum
            diff = abs(summary_stock_eval - positions_eval_sum)
            tolerance = max(1.0, positions_eval_sum * 0.01)
            if summary_stock_eval > 0.0 and diff > tolerance:
                logger.warning(
                    "KIS balance stock_eval mismatch env=%s summary=%.0f positions=%.0f pages=%s",
                    env_key,
                    summary_stock_eval,
                    positions_eval_sum,
                    len(page_payloads),
                )
        else:
            stock_eval = summary_stock_eval

        summary_total_asset = self._first_float(summary_output2, ["tot_evlu_amt", "tot_asst_amt", "nass_amt"])
        computed_total_asset = cash_amount + stock_eval
        if summary_total_asset <= 0.0:
            total_asset = computed_total_asset
        else:
            diff = abs(summary_total_asset - computed_total_asset)
            tolerance = max(1.0, max(summary_total_asset, computed_total_asset) * 0.01)
            if computed_total_asset > 0.0 and diff > tolerance:
                logger.warning(
                    "KIS balance total_asset mismatch env=%s summary=%.0f computed=%.0f cash=%.0f stock=%.0f",
                    env_key,
                    summary_total_asset,
                    computed_total_asset,
                    cash_amount,
                    stock_eval,
                )
                total_asset = computed_total_asset
            else:
                total_asset = summary_total_asset

        summary_total_pnl = self._first_float(summary_output2, ["evlu_pfls_smtl_amt", "evlu_pfls_amt"])
        if positions:
            total_pnl = positions_pnl_sum
            diff = abs(summary_total_pnl - positions_pnl_sum)
            tolerance = max(1.0, max(abs(summary_total_pnl), abs(positions_pnl_sum), 1.0) * 0.01)
            if summary_total_pnl != 0.0 and diff > tolerance:
                logger.warning(
                    "KIS balance pnl mismatch env=%s summary=%.0f positions=%.0f",
                    env_key,
                    summary_total_pnl,
                    positions_pnl_sum,
                )
        else:
            total_pnl = summary_total_pnl

        raw_payload: dict[str, Any] = {
            "page_count": len(page_payloads),
            "ctx_last_fk100": ctx_fk100,
            "ctx_last_nk100": ctx_nk100,
            "merged_output1_count": len(merged_rows),
            "last_page": (page_payloads[-1] if page_payloads else {}),
        }

        snapshot = KisBalanceSnapshot(
            cash_amount=cash_amount,
            orderable_cash_amount=orderable_cash,
            stock_eval_amount=stock_eval,
            total_asset_amount=total_asset,
            total_pnl_amount=total_pnl,
            positions=positions,
            raw=raw_payload,
        )
        return KisBalanceResult(ok=True, status_code=200, message="OK", snapshot=snapshot, raw=raw_payload)

    def order_cash(
        self,
        *,
        env: str,
        side: str,
        ticker: str,
        qty: int,
        price: float,
        market_order: bool = False,
    ) -> KisOrderResult:
        env_key = "demo" if env == "demo" else "prod"
        if side not in ("buy", "sell"):
            return KisOrderResult(ok=False, status_code=400, message="INVALID_SIDE")
        if qty <= 0:
            return KisOrderResult(ok=False, status_code=400, message="INVALID_QTY")
        if (not market_order) and price <= 0:
            return KisOrderResult(ok=False, status_code=400, message="INVALID_PRICE")
        if not self.has_required_config(env_key):
            return KisOrderResult(ok=False, status_code=400, message="KIS_CONFIG_MISSING")

        base_url, app_key, app_secret = self._env_conf(env_key)
        try:
            token = self._access_token(env_key)
        except Exception as exc:
            status, msg = self._normalize_token_error(exc)
            logger.warning("KIS order token acquisition failed env=%s status=%s msg=%s", env_key, status, msg)
            return KisOrderResult(ok=False, status_code=status, message=msg)
        tr_id = "TTTC0012U" if side == "buy" else "TTTC0011U"
        if env_key == "demo":
            tr_id = "VTTC0012U" if side == "buy" else "VTTC0011U"

        ord_dvsn = "01" if market_order else "00"
        normalized_price = 0 if market_order else self._normalize_krx_limit_price(price)
        ord_unpr = "0" if market_order else str(normalized_price)
        account_no, account_product_code = self._account_conf(env_key)
        payload = {
            "CANO": account_no,
            "ACNT_PRDT_CD": account_product_code,
            "PDNO": ticker,
            "ORD_DVSN": ord_dvsn,
            "ORD_QTY": str(int(qty)),
            "ORD_UNPR": ord_unpr,
            "EXCG_ID_DVSN_CD": "KRX",
        }
        headers = {
            "Content-Type": "application/json",
            "authorization": f"Bearer {token}",
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": tr_id,
            "custtype": "P",
        }
        hashkey = self._hashkey(env_key, token, payload)
        if hashkey:
            headers["hashkey"] = hashkey
        if not market_order and normalized_price != int(round(price)):
            logger.info(
                "KIS 지정가 호가단위 보정 env=%s side=%s ticker=%s req=%.4f normalized=%s",
                env_key,
                side,
                ticker,
                float(price),
                ord_unpr,
            )

        try:
            resp = requests.post(
                f"{base_url}/uapi/domestic-stock/v1/trading/order-cash",
                headers=headers,
                data=json.dumps(payload, ensure_ascii=False),
                timeout=max(3, settings.kis_order_timeout_sec),
            )
        except Exception as exc:
            logger.exception("KIS order request failed")
            return KisOrderResult(ok=False, status_code=500, message=f"KIS_REQUEST_ERROR: {exc}")

        status = int(resp.status_code)
        data = self._parse_json_payload(resp)
        rt_cd = str(data.get("rt_cd") or "")
        msg_cd = str(data.get("msg_cd") or "").strip()
        msg1 = str(data.get("msg1") or "").strip()
        if (status != 200 or rt_cd != "0") and self._is_token_expired_response(status, msg_cd, msg1):
            logger.warning("KIS order token expired; invalidate and retry env=%s side=%s ticker=%s", env_key, side, ticker)
            self._invalidate_token_cache(env_key)
            try:
                retry_token = self._access_token(env_key)
            except Exception as exc:
                token_status, token_msg = self._normalize_token_error(exc)
                return KisOrderResult(ok=False, status_code=token_status, message=token_msg)
            retry_headers = dict(headers)
            retry_headers["authorization"] = f"Bearer {retry_token}"
            retry_hashkey = self._hashkey(env_key, retry_token, payload)
            if retry_hashkey:
                retry_headers["hashkey"] = retry_hashkey
            elif "hashkey" in retry_headers:
                retry_headers.pop("hashkey", None)
            try:
                resp = requests.post(
                    f"{base_url}/uapi/domestic-stock/v1/trading/order-cash",
                    headers=retry_headers,
                    data=json.dumps(payload, ensure_ascii=False),
                    timeout=max(3, settings.kis_order_timeout_sec),
                )
                status = int(resp.status_code)
                data = self._parse_json_payload(resp)
            except Exception as exc:
                logger.exception("KIS order retry request failed")
                return KisOrderResult(ok=False, status_code=500, message=f"KIS_REQUEST_ERROR: {exc}")
        rt_cd = str(data.get("rt_cd") or "")
        msg_cd = str(data.get("msg_cd") or "").strip()
        msg1 = str(data.get("msg1") or "").strip()
        if msg1 and msg_cd:
            msg = f"{msg1} (코드:{msg_cd})"
        elif msg1:
            msg = msg1
        elif msg_cd:
            msg = f"KIS_ORDER_FAILED({msg_cd})"
        else:
            msg = "KIS_ORDER_FAILED"
        out = data.get("output") if isinstance(data.get("output"), dict) else {}
        order_no = (
            str(out.get("ODNO") or out.get("odno") or "").strip()
            if isinstance(out, dict)
            else None
        )
        ok = (status == 200) and (rt_cd == "0")
        return KisOrderResult(
            ok=ok,
            status_code=status,
            message=msg,
            order_no=order_no or None,
            raw=data,
        )

    def cancel_order(
        self,
        *,
        env: str,
        order_no: str,
        qty: int = 0,
    ) -> KisOrderResult:
        env_key = "demo" if env == "demo" else "prod"
        normalized_order_no = str(order_no or "").strip()
        if not normalized_order_no:
            return KisOrderResult(ok=False, status_code=400, message="BROKER_ORDER_NO_MISSING")
        if not self.has_required_config(env_key):
            return KisOrderResult(ok=False, status_code=400, message="KIS_CONFIG_MISSING")

        base_url, app_key, app_secret = self._env_conf(env_key)
        try:
            token = self._access_token(env_key)
        except Exception as exc:
            status, msg = self._normalize_token_error(exc)
            logger.warning("KIS cancel token acquisition failed env=%s status=%s msg=%s", env_key, status, msg)
            return KisOrderResult(ok=False, status_code=status, message=msg)

        tr_id = "VTTC0803U" if env_key == "demo" else "TTTC0803U"
        account_no, account_product_code = self._account_conf(env_key)
        cancel_all = qty <= 0
        payload = {
            "CANO": account_no,
            "ACNT_PRDT_CD": account_product_code,
            "KRX_FWDG_ORD_ORGNO": "",
            "ORGN_ODNO": normalized_order_no,
            "ORD_DVSN": "00",
            "RVSE_CNCL_DVSN_CD": "02",  # 02: 취소
            "ORD_QTY": "0" if cancel_all else str(int(qty)),
            "ORD_UNPR": "0",
            "QTY_ALL_ORD_YN": "Y" if cancel_all else "N",
        }
        headers = {
            "Content-Type": "application/json",
            "authorization": f"Bearer {token}",
            "appkey": app_key,
            "appsecret": app_secret,
            "tr_id": tr_id,
            "custtype": "P",
        }
        hashkey = self._hashkey(env_key, token, payload)
        if hashkey:
            headers["hashkey"] = hashkey

        endpoint = f"{base_url}/uapi/domestic-stock/v1/trading/order-rvsecncl"

        def _request_once(token_value: str, req_headers: dict[str, str]) -> tuple[int, dict[str, Any]]:
            resp = requests.post(
                endpoint,
                headers=req_headers,
                data=json.dumps(payload, ensure_ascii=False),
                timeout=max(3, settings.kis_order_timeout_sec),
            )
            return int(resp.status_code), self._parse_json_payload(resp)

        try:
            status, data = _request_once(token, headers)
        except Exception as exc:
            logger.exception("KIS cancel request failed")
            return KisOrderResult(ok=False, status_code=500, message=f"KIS_REQUEST_ERROR: {exc}")

        rt_cd = str(data.get("rt_cd") or "")
        msg_cd = str(data.get("msg_cd") or "").strip()
        msg1 = str(data.get("msg1") or "").strip()
        if (status != 200 or rt_cd != "0") and self._is_token_expired_response(status, msg_cd, msg1):
            logger.warning("KIS cancel token expired; invalidate and retry env=%s order_no=%s", env_key, normalized_order_no)
            self._invalidate_token_cache(env_key)
            try:
                retry_token = self._access_token(env_key)
            except Exception as exc:
                token_status, token_msg = self._normalize_token_error(exc)
                return KisOrderResult(ok=False, status_code=token_status, message=token_msg)
            retry_headers = dict(headers)
            retry_headers["authorization"] = f"Bearer {retry_token}"
            retry_hashkey = self._hashkey(env_key, retry_token, payload)
            if retry_hashkey:
                retry_headers["hashkey"] = retry_hashkey
            elif "hashkey" in retry_headers:
                retry_headers.pop("hashkey", None)
            try:
                status, data = _request_once(retry_token, retry_headers)
            except Exception as exc:
                logger.exception("KIS cancel retry request failed")
                return KisOrderResult(ok=False, status_code=500, message=f"KIS_REQUEST_ERROR: {exc}")

        rt_cd = str(data.get("rt_cd") or "")
        msg_cd = str(data.get("msg_cd") or "").strip()
        msg1 = str(data.get("msg1") or "").strip()
        if msg1 and msg_cd:
            msg = f"{msg1} (코드:{msg_cd})"
        elif msg1:
            msg = msg1
        elif msg_cd:
            msg = f"KIS_CANCEL_FAILED({msg_cd})"
        else:
            msg = "KIS_CANCEL_FAILED"
        out = data.get("output") if isinstance(data.get("output"), dict) else {}
        new_order_no = (
            str(out.get("ODNO") or out.get("odno") or "").strip()
            if isinstance(out, dict)
            else None
        )
        ok = (status == 200) and (rt_cd == "0")
        return KisOrderResult(
            ok=ok,
            status_code=status,
            message=msg,
            order_no=new_order_no or None,
            raw=data,
        )
