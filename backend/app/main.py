from __future__ import annotations

import json
import logging
import os
import re
import html
from time import perf_counter
from datetime import datetime, timedelta, date
from concurrent.futures import ThreadPoolExecutor, as_completed
from queue import Queue
from threading import Lock, Thread
from typing import Any
from functools import lru_cache
from zoneinfo import ZoneInfo

from fastapi import FastAPI, Query, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse, FileResponse, HTMLResponse
from sqlalchemy import desc, select, func, or_
from sqlalchemy.exc import OperationalError
import requests

from app.config import settings
from app.models import (
    Alert,
    AutoTradeDailyMetric,
    AutoTradeOrder,
    AutoTradeReservation,
    AutoTradeSetting,
    AutoTradeSymbolRule,
    Device,
    EvalMonthly,
    Report,
    StrategySettings,
    User,
    UserMenuPermission,
    SessionToken,
    LoginEvent,
    AdminAuditLog,
    UserFavorite,
    NewsArticle,
    NewsCluster,
    NewsEntityMention,
)
from app.realtime_quotes import fetch_quotes
from app.movers import compute_movers
from app.movers2 import compute_movers2
from app.us_insiders import compute_us_insider_screen, normalize_transaction_codes
from app.storage import session_scope, init_db, ensure_schema, SessionLocal
from engine.krx_api import KrxApiConfig, fetch_daily_market
from app.schemas import (
    AlertHistoryItem,
    ChartDailyResponse,
    ChartDailyBatchItem,
    ChartDailyBatchRequest,
    ChartDailyBatchResponse,
    ChartPoint,
    DeviceRegisterRequest,
    EodResponse,
    EvalMonthlyResponse,
    InviteCreateRequest,
    InviteCreateResponse,
    InviteMarkSentResponse,
    FirstLoginRequest,
    LoginRequest,
    RefreshTokenRequest,
    LoginResponse,
    ProfileRequest,
    PasswordChangeRequest,
    PasswordResetRequest,
    UserIdentityUpdateRequest,
    UserSummary,
    UserDetail,
    MenuPermissionsPayload,
    MenuPermissionsResponse,
    UsersListResponse,
    InvitedUserSummary,
    MyInvitedUsersResponse,
    LoginEventItem,
    UserLoginLogSummary,
    UserLoginLogsResponse,
    AdminPushSendRequest,
    AdminPushSendResponse,
    AdminPushStatusResponse,
    LogsResponse,
    AdminAuditItem,
    AuditLogsResponse,
    AdminUserAutoTradeOverviewResponse,
    OkResponse,
    PapersSummaryResponse,
    PaperSection,
    PremarketResponse,
    RealtimeQuoteItem,
    RealtimeQuotesResponse,
    StockInvestorDailyItem,
    StockInvestorDailyResponse,
    StockTrendIntradayItem,
    StockTrendIntradayResponse,
    MoversResponse,
    Movers2Response,
    SupplyResponse,
    UsInsiderResponse,
    FavoriteUpsertRequest,
    FavoriteItem,
    FavoritesResponse,
    StockSearchItem,
    StockSearchResponse,
    AutoTradeSettingsPayload,
    AutoTradeSettingsResponse,
    AutoTradeSymbolRuleItem,
    AutoTradeSymbolRuleUpsertPayload,
    AutoTradeSymbolRulesResponse,
    AutoTradeBrokerCredentialPayload,
    AutoTradeBrokerCredentialResponse,
    AutoTradeCandidateItem,
    AutoTradeCandidatesResponse,
    AutoTradeOrderItem,
    AutoTradeReasonDetail,
    AutoTradeOrdersResponse,
    AutoTradePerformanceItem,
    AutoTradePerformanceResponse,
    AutoTradeAccountPosition,
    AutoTradeAccountSnapshotResponse,
    AutoTradeBootstrapResponse,
    AutoTradeRunRequest,
    AutoTradeManualBuyRequest,
    AutoTradeManualSellRequest,
    AutoTradeRunResponse,
    AutoTradeReservationPreviewItem,
    AutoTradeReservationItem,
    AutoTradeReservationsResponse,
    AutoTradeReservationActionResponse,
    AutoTradeOrderCancelResponse,
    AutoTradePendingCancelRequest,
    AutoTradePendingCancelResponse,
    AutoTradeReservationPendingCancelRequest,
    AutoTradeReservationPendingCancelResponse,
    AutoTradeReentryBlockItem,
    AutoTradeReentryBlocksResponse,
    AutoTradeReentryReleaseRequest,
    AutoTradeReentryReleaseResponse,
    StrategySettingsPayload,
    StrategySettingsResponse,
    NewsThemesResponse,
    NewsClustersResponse,
    NewsStocksResponse,
    NewsArticlesResponse,
    NewsClusterResponse,
    NewsIngestRequest,
    NewsIngestResponse,
    NewsMeta,
    NewsThemeCard,
    NewsClusterListItem,
    NewsClusterLite,
    NewsStockHotItem,
    NewsClusterItem,
    NewsArticleItem,
)
from app.push import send_to_devices_and_log, is_push_ready
from app.auth import (
    TokenContext,
    ROLE_MASTER,
    ROLE_USER,
    STATUS_ACTIVE,
    STATUS_BLOCKED,
    STATUS_DELETED,
    INVITE_CREATED,
    INVITE_SENT,
    INVITE_ACTIVATED,
    INVITE_PASSWORD_CHANGED,
    INVITE_ACTIVE,
    REASON_OK,
    REASON_INVALID_CRED,
    REASON_BLOCKED,
    REASON_LOCKED,
    REASON_EXPIRED_TEMP_PASSWORD,
    REASON_FORCE_CHANGE_REQUIRED,
    REASON_DELETED,
    REASON_DEVICE_NOT_ALLOWED,
    REASON_STATUS_INACTIVE,
    get_request_ip,
    get_request_user_agent,
    hash_token,
    issue_token_pair,
    rotate_refresh_token,
    revoke_all_sessions,
    revoke_session,
    log_login_event,
    log_admin_action,
    get_token_context,
    require_active_user,
    require_master,
    now,
)
from app.news_service import (
    RISK_EVENT_TYPES,
    NormalizedArticle,
    backfill_ticker_news_once,
    classify_event_type,
    classify_impact,
    classify_polarity,
    classify_theme_key,
    compute_cluster_key,
    last_news_fetch_status,
    now_kst,
    should_run_ticker_backfill,
    upsert_articles,
)
from app.bootstrap_config import build_bootstrap_manifest
from app.report_generator import generate_premarket_report
from app.scheduler import start_scheduler
from app.report_cache import premarket_cache_key
from app.ticker_tags import TagRow, upsert_tags
from app.ticker_tags import get_tags_map
from engine.universe import load_universe, filter_universe
try:
    from engine.universe import _load_universe_krx_cache as load_universe_krx_cache
except Exception:  # pragma: no cover
    load_universe_krx_cache = None
from app.autotrade_service import (
    _build_pnl_snapshot,
    build_autotrade_candidates,
    get_or_create_autotrade_setting,
    invalidate_autotrade_candidate_build_cache,
    list_active_reentry_blocks,
    recompute_daily_metric,
    release_reentry_blocks,
    run_autotrade_once,
    update_autotrade_setting,
)
from app.autotrade_reservation import (
    cancel_reservation,
    cancel_reservation_preview_item,
    confirm_reservation_execute,
    current_market_phase,
    enqueue_manual_order_reservation,
    enqueue_reservation,
    enqueue_order_cancel_reservation,
    reservation_item_payload,
)
from app.autotrade_reason_codes import normalize_reason_code
from app.broker_credentials import (
    broker_credential_status,
    get_or_create_user_broker_credential,
    resolve_user_kis_credentials,
    update_user_broker_credential,
)
from app.kis_broker import KisBrokerClient
from engine.data_sources import load_ohlcv
from engine.strategy import StrategySettings as StrategyConfig, normalize_algo_version
from passlib.hash import bcrypt
import secrets

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("stock.main")
SEOUL = ZoneInfo(settings.app_tz)
app = FastAPI()
_CHART_DAILY_CACHE: dict[str, tuple[datetime, ChartDailyResponse]] = {}
_CHART_DAILY_CACHE_LOCK = Lock()
_CHART_DAILY_TTL_SECONDS = max(15, min(600, int(os.getenv("CHART_DAILY_TTL_SECONDS", "90"))))
_CHART_BATCH_MAX_CODES = max(1, min(80, int(os.getenv("CHART_BATCH_MAX_CODES", "40"))))
_AUTOTRADE_ACCOUNT_CACHE: dict[str, tuple[datetime, AutoTradeAccountSnapshotResponse]] = {}
_AUTOTRADE_ACCOUNT_CACHE_LOCK = Lock()
_AUTOTRADE_ACCOUNT_TTL_SECONDS = max(3, min(60, int(os.getenv("AUTOTRADE_ACCOUNT_TTL_SECONDS", "15"))))
_AUTOTRADE_CANDIDATES_CACHE: dict[str, tuple[datetime, AutoTradeCandidatesResponse]] = {}
_AUTOTRADE_CANDIDATES_CACHE_LOCK = Lock()
_AUTOTRADE_CANDIDATES_TTL_SECONDS = max(5, min(90, int(os.getenv("AUTOTRADE_CANDIDATES_TTL_SECONDS", "20"))))
_AUTOTRADE_CANDIDATES_INITIAL_TTL_SECONDS = max(
    _AUTOTRADE_CANDIDATES_TTL_SECONDS,
    min(180, int(os.getenv("AUTOTRADE_CANDIDATES_INITIAL_TTL_SECONDS", "45"))),
)
_AUTOTRADE_RUN_GUARD_LOCK = Lock()
_AUTOTRADE_RUN_GUARD_RUNNING: set[tuple[int, str]] = set()
_STOCK_V2_APK_META_FILENAME = "latest.stockv2.json"
_STOCK_V2_STABLE_APK_FILENAME = "stockv2-latest.apk"


@app.get("/bootstrap/config")
def get_bootstrap_config(
    request: Request,
    profile: str = Query("dev_local"),
) -> dict[str, Any]:
    base_url = str(request.base_url).rstrip("/")
    return {
        "profile": profile,
        "manifest": build_bootstrap_manifest(profile=profile, base_url=base_url),
    }


@app.get("/update", response_class=HTMLResponse)
def get_stock_v2_update_page(
    request: Request,
    profile: str = Query("dev_local"),
) -> RedirectResponse:
    return RedirectResponse(
        url=f"/stockv2/apk/install?profile={html.escape(profile)}",
        status_code=302,
    )


def _load_apk_meta(meta_filename: str) -> dict[str, Any]:
    latest_path = os.path.join(settings.apk_dir, meta_filename)
    if not os.path.isfile(latest_path):
        raise HTTPException(status_code=404, detail=f"{meta_filename} 메타데이터가 없습니다.")
    with open(latest_path, "r", encoding="utf-8") as f:
        return json.load(f)


def _resolve_apk_filename(meta_filename: str, stable_filename: str) -> tuple[str, dict[str, Any]]:
    meta = _load_apk_meta(meta_filename)
    fname = str(meta.get("apk_filename") or "").strip()
    if fname.endswith(".apk"):
        fpath = os.path.join(settings.apk_dir, fname)
        if os.path.isfile(fpath):
            return fname, meta
    stable_path = os.path.join(settings.apk_dir, stable_filename)
    if os.path.isfile(stable_path):
        return stable_filename, meta
    raise HTTPException(status_code=404, detail="APK 파일이 없습니다.")


def _apk_download_response(meta_filename: str, stable_filename: str) -> FileResponse | RedirectResponse:
    fname, _ = _resolve_apk_filename(meta_filename=meta_filename, stable_filename=stable_filename)
    fpath = os.path.join(settings.apk_dir, fname)
    if os.path.isfile(fpath):
        return FileResponse(
            path=fpath,
            media_type="application/vnd.android.package-archive",
            filename=fname,
            headers={"Cache-Control": "no-store"},
        )
    return RedirectResponse(url=f"/apk/{fname}", status_code=302)


def _apk_install_page(
    title: str,
    meta_filename: str,
    stable_filename: str,
    download_path: str,
) -> HTMLResponse:
    fname, meta = _resolve_apk_filename(meta_filename=meta_filename, stable_filename=stable_filename)
    safe_fname = html.escape(fname)
    safe_label = html.escape(str(meta.get("build_label") or "").strip())
    safe_version = html.escape(str(meta.get("version_name") or "").strip())
    safe_notes = html.escape(str(meta.get("notes") or "").strip())
    notes_html = f'<div class="muted" style="margin-top:8px;">{safe_notes}</div>' if safe_notes else ""
    page = f"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{html.escape(title)}</title>
  <style>
    body {{
      margin: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", sans-serif;
      background: #f4f6f8;
      color: #0f172a;
    }}
    .wrap {{
      max-width: 680px;
      margin: 0 auto;
      padding: 24px 16px 40px;
    }}
    .card {{
      background: #fff;
      border-radius: 16px;
      padding: 18px;
      box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
      margin-top: 12px;
    }}
    h1 {{
      font-size: 22px;
      margin: 0 0 8px;
    }}
    .muted {{ color: #64748b; }}
    .btn {{
      display: inline-block;
      text-decoration: none;
      background: #1e3a8a;
      color: #fff;
      border-radius: 999px;
      padding: 10px 16px;
      font-weight: 700;
      margin-right: 8px;
      margin-top: 8px;
    }}
    .btn.sub {{
      background: #e2e8f0;
      color: #0f172a;
    }}
    code {{
      background: #f1f5f9;
      padding: 2px 6px;
      border-radius: 6px;
      word-break: break-all;
    }}
  </style>
</head>
<body>
  <div class="wrap">
    <h1>{html.escape(title)}</h1>
    <div class="muted">다운로드가 안 되면 설치 페이지에서 직접 다시 시도하세요.</div>
    <div class="card">
      <div><strong>현재 배포본</strong>: {safe_label if safe_label else "-"}</div>
      <div class="muted" style="margin-top:6px;">버전: {safe_version if safe_version else "-"}</div>
      <div class="muted" style="margin-top:6px;">파일명: {safe_fname}</div>
      <a class="btn" href="{html.escape(download_path)}">다운로드</a>
      <a class="btn sub" href="/apk/{safe_fname}">버전 파일 직접 열기</a>
    </div>
    <div class="card">
      <strong>설치 실패 시 확인</strong>
      <ul>
        <li>브라우저에서 차단 안내 페이지가 보이면 <strong>Continue</strong>를 누른 뒤 다시 다운로드하세요.</li>
        <li>파일 크기가 수 KB면 APK가 아니라 차단 HTML입니다. 다시 받아야 합니다.</li>
        <li>다운로드 파일명은 <code>{safe_fname}</code> 이어야 합니다.</li>
      </ul>
      {notes_html}
    </div>
  </div>
</body>
</html>"""
    return HTMLResponse(content=page, headers={"Cache-Control": "no-store"})


def _autotrade_account_cache_key(user_id: int, environment: str) -> str:
    return f"{int(user_id)}:{str(environment).strip().lower()}"


def _autotrade_run_guard_acquire(user_id: int, environment: str) -> bool:
    key = (int(user_id), str(environment or "").strip().lower() or "demo")
    with _AUTOTRADE_RUN_GUARD_LOCK:
        if key in _AUTOTRADE_RUN_GUARD_RUNNING:
            return False
        _AUTOTRADE_RUN_GUARD_RUNNING.add(key)
    return True


def _autotrade_run_guard_release(user_id: int, environment: str) -> None:
    key = (int(user_id), str(environment or "").strip().lower() or "demo")
    with _AUTOTRADE_RUN_GUARD_LOCK:
        _AUTOTRADE_RUN_GUARD_RUNNING.discard(key)


def _get_cached_autotrade_account_snapshot(user_id: int, environment: str) -> AutoTradeAccountSnapshotResponse | None:
    cache_key = _autotrade_account_cache_key(user_id, environment)
    with _AUTOTRADE_ACCOUNT_CACHE_LOCK:
        hit = _AUTOTRADE_ACCOUNT_CACHE.get(cache_key)
    if hit is None:
        return None
    cached_at, payload = hit
    age_sec = (datetime.now(tz=SEOUL) - cached_at).total_seconds()
    if age_sec > _AUTOTRADE_ACCOUNT_TTL_SECONDS:
        return None
    return payload.model_copy(deep=True)


def _set_cached_autotrade_account_snapshot(
    user_id: int,
    environment: str,
    payload: AutoTradeAccountSnapshotResponse,
) -> None:
    cache_key = _autotrade_account_cache_key(user_id, environment)
    with _AUTOTRADE_ACCOUNT_CACHE_LOCK:
        _AUTOTRADE_ACCOUNT_CACHE[cache_key] = (datetime.now(tz=SEOUL), payload.model_copy(deep=True))


def _invalidate_autotrade_account_snapshot_cache(user_id: int) -> None:
    prefix = f"{int(user_id)}:"
    with _AUTOTRADE_ACCOUNT_CACHE_LOCK:
        keys = [k for k in _AUTOTRADE_ACCOUNT_CACHE if k.startswith(prefix)]
        for k in keys:
            _AUTOTRADE_ACCOUNT_CACHE.pop(k, None)


def _autotrade_candidates_cache_key(
    user_id: int,
    cfg: AutoTradeSetting,
    limit: int,
    profile: str,
) -> str:
    profile_mode = str(profile or "full").strip().lower()
    if profile_mode not in {"full", "initial"}:
        profile_mode = "full"
    updated = getattr(cfg, "updated_at", None)
    updated_key = (
        updated.replace(microsecond=0).isoformat() if isinstance(updated, datetime) else "na"
    )
    flags = ":".join(
        [
            str(int(bool(getattr(cfg, "include_daytrade", True)))),
            str(int(bool(getattr(cfg, "include_movers", True)))),
            str(int(bool(getattr(cfg, "include_supply", True)))),
            str(int(bool(getattr(cfg, "include_papers", True)))),
            str(int(bool(getattr(cfg, "include_longterm", True)))),
            str(int(bool(getattr(cfg, "include_favorites", True)))),
        ]
    )
    return f"{int(user_id)}:{int(limit)}:{profile_mode}:{updated_key}:{flags}"


def _get_cached_autotrade_candidates(cache_key: str, profile_mode: str) -> AutoTradeCandidatesResponse | None:
    with _AUTOTRADE_CANDIDATES_CACHE_LOCK:
        hit = _AUTOTRADE_CANDIDATES_CACHE.get(cache_key)
    if hit is None:
        return None
    cached_at, payload = hit
    age_sec = (datetime.now(tz=SEOUL) - cached_at).total_seconds()
    ttl_sec = (
        _AUTOTRADE_CANDIDATES_INITIAL_TTL_SECONDS
        if str(profile_mode or "").strip().lower() == "initial"
        else _AUTOTRADE_CANDIDATES_TTL_SECONDS
    )
    if age_sec > ttl_sec:
        return None
    return payload.model_copy(deep=True)


def _set_cached_autotrade_candidates(cache_key: str, payload: AutoTradeCandidatesResponse) -> None:
    with _AUTOTRADE_CANDIDATES_CACHE_LOCK:
        _AUTOTRADE_CANDIDATES_CACHE[cache_key] = (datetime.now(tz=SEOUL), payload.model_copy(deep=True))


def _invalidate_autotrade_candidates_cache(user_id: int) -> None:
    prefix = f"{int(user_id)}:"
    with _AUTOTRADE_CANDIDATES_CACHE_LOCK:
        keys = [k for k in _AUTOTRADE_CANDIDATES_CACHE if k.startswith(prefix)]
        for k in keys:
            _AUTOTRADE_CANDIDATES_CACHE.pop(k, None)
    invalidate_autotrade_candidate_build_cache(user_id)


@app.api_route("/apk/download", methods=["GET", "HEAD"])
def apk_download():
    # nginx proxies this exact path to the backend, while /apk/ is served as static files.
    #
    # Important: We serve the file as an attachment (Content-Disposition) so Android's
    # download manager uses a versioned filename instead of accumulating:
    #   app-latest (2).apk, app-latest (3).apk ...
    latest_path = os.path.join(settings.apk_dir, "latest.json")
    fname = ""
    try:
        with open(latest_path, "r", encoding="utf-8") as f:
            meta = json.load(f)
        fname = str(meta.get("apk_filename") or "").strip()
        if not fname.endswith(".apk"):
            fname = ""
    except Exception:
        fname = ""

    if fname:
        fpath = os.path.join(settings.apk_dir, fname)
        if os.path.isfile(fpath):
            return FileResponse(
                path=fpath,
                media_type="application/vnd.android.package-archive",
                filename=fname,
                headers={"Cache-Control": "no-store"},
            )

    # Fallback: serve the stable symlink name if present, otherwise redirect to /apk/.
    stable_path = os.path.join(settings.apk_dir, "app-latest.apk")
    if os.path.isfile(stable_path):
        return FileResponse(
            path=stable_path,
            media_type="application/vnd.android.package-archive",
            filename="app-latest.apk",
            headers={"Cache-Control": "no-store"},
        )
    return RedirectResponse(url="/apk/app-latest.apk", status_code=302)


@app.get("/apk/install", response_class=HTMLResponse)
def apk_install_page():
    latest_path = os.path.join(settings.apk_dir, "latest.json")
    fname = "app-latest.apk"
    build_label = ""
    try:
        with open(latest_path, "r", encoding="utf-8") as f:
            meta = json.load(f)
        parsed = str(meta.get("apk_filename") or "").strip()
        if parsed.endswith(".apk"):
            fname = parsed
        build_label = str(meta.get("build_label") or "").strip()
    except Exception:
        pass

    safe_fname = html.escape(fname)
    safe_label = html.escape(build_label)
    page = f"""<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>앱 설치 안내</title>
  <style>
    body {{
      margin: 0;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans KR", sans-serif;
      background: #f4f6f8;
      color: #0f172a;
    }}
    .wrap {{
      max-width: 680px;
      margin: 0 auto;
      padding: 24px 16px 40px;
    }}
    .card {{
      background: #fff;
      border-radius: 16px;
      padding: 18px;
      box-shadow: 0 1px 3px rgba(15, 23, 42, 0.08);
      margin-top: 12px;
    }}
    h1 {{
      font-size: 22px;
      margin: 0 0 8px;
    }}
    .muted {{ color: #64748b; }}
    .btn {{
      display: inline-block;
      text-decoration: none;
      background: #1e3a8a;
      color: #fff;
      border-radius: 999px;
      padding: 10px 16px;
      font-weight: 700;
      margin-right: 8px;
      margin-top: 8px;
    }}
    .btn.sub {{
      background: #e2e8f0;
      color: #0f172a;
    }}
    code {{
      background: #f1f5f9;
      padding: 2px 6px;
      border-radius: 6px;
      word-break: break-all;
    }}
  </style>
</head>
<body>
  <div class="wrap">
    <h1>앱 설치 안내</h1>
    <div class="muted">APK 다운로드가 실패하면 아래 순서로 다시 진행하세요.</div>
    <div class="card">
      <div><strong>현재 배포본</strong>: {safe_label if safe_label else "-"}</div>
      <div class="muted" style="margin-top:6px;">파일명: {safe_fname}</div>
      <a class="btn" href="/apk/download">다운로드</a>
      <a class="btn sub" href="/apk/{safe_fname}">버전 파일 직접 열기</a>
    </div>
    <div class="card">
      <strong>설치 실패 시 확인</strong>
      <ul>
        <li>브라우저에서 차단 안내 페이지가 보이면 <strong>Continue</strong>를 누른 뒤 다시 다운로드하세요.</li>
        <li>파일 크기가 수 KB면 APK가 아니라 차단 HTML입니다. 다시 받아야 합니다.</li>
        <li>다운로드 파일명은 <code>{safe_fname}</code> 이어야 합니다.</li>
      </ul>
    </div>
  </div>
</body>
</html>"""
    return HTMLResponse(content=page, headers={"Cache-Control": "no-store"})


@app.get("/stockv2/apk/latest.json")
def stock_v2_apk_latest() -> dict[str, Any]:
    return _load_apk_meta(_STOCK_V2_APK_META_FILENAME)


@app.api_route("/stockv2/apk/download", methods=["GET", "HEAD"])
def stock_v2_apk_download():
    return _apk_download_response(
        meta_filename=_STOCK_V2_APK_META_FILENAME,
        stable_filename=_STOCK_V2_STABLE_APK_FILENAME,
    )


@app.get("/stockv2/apk/install", response_class=HTMLResponse)
def stock_v2_apk_install_page(
    profile: str = Query("dev_local"),
):
    return _apk_install_page(
        title=f"stock_v2 설치 안내 ({profile})",
        meta_filename=_STOCK_V2_APK_META_FILENAME,
        stable_filename=_STOCK_V2_STABLE_APK_FILENAME,
        download_path="/stockv2/apk/download",
    )

@app.on_event("startup")
def _startup():
    init_db()
    ensure_schema()
    _bootstrap_master()
    # Pre-warm caches (e.g., premarket report) for better perceived performance.
    # Safe to call multiple times; scheduler guards against duplicate starts.
    start_scheduler()


def _bootstrap_master() -> None:
    master_code = os.getenv("BOOTSTRAP_MASTER_CODE")
    master_password = os.getenv("BOOTSTRAP_MASTER_PASSWORD")
    if not master_code or not master_password:
        return
    with session_scope() as session:
        existing = session.query(User).filter(User.role == ROLE_MASTER).count()
        if existing > 0:
            return
        now_ts = now()
        session.add(
            User(
                user_code=master_code,
                role=ROLE_MASTER,
                status=STATUS_ACTIVE,
                password_hash=bcrypt.hash(master_password),
                force_password_change=False,
                invite_status=INVITE_ACTIVE,
                expires_at=None,
                memo="bootstrap master",
                created_at=now_ts,
                updated_at=now_ts,
            )
        )

_QUEUE: Queue[tuple[date, StrategySettingsPayload, str, int | None, int | None, str]] = Queue()
_IN_FLIGHT: set[str] = set()
_LOCK = Lock()
_PREMARKET_FAILURES: dict[str, tuple[datetime, str]] = {}
_PREMARKET_RETRY_COOLDOWN = timedelta(seconds=20)
_IP_FAILS: dict[str, list[datetime]] = {}
_US_INSIDER_CACHE: dict[str, tuple[datetime, dict[str, Any]]] = {}
_US_INSIDER_REPORT_TYPE = "US_INSIDERS"
_STOCK_FLOW_CACHE_LOCK = Lock()
_INVESTOR_DAILY_CACHE: dict[str, tuple[datetime, StockInvestorDailyResponse]] = {}
_INTRADAY_TREND_CACHE: dict[str, tuple[datetime, StockTrendIntradayResponse]] = {}
_SUPPLY_CACHE: dict[str, tuple[datetime, SupplyResponse]] = {}
_SUPPLY_LAST_GOOD_CACHE: dict[str, tuple[datetime, SupplyResponse]] = {}
_INVESTOR_DAILY_TTL_SECONDS = max(120, min(3600, int(os.getenv("INVESTOR_DAILY_TTL_SECONDS", "300"))))
_INTRADAY_TREND_TTL_SECONDS = max(5, min(60, int(os.getenv("INTRADAY_TREND_TTL_SECONDS", "12"))))
_SUPPLY_TTL_SECONDS = max(10, min(120, int(os.getenv("SUPPLY_TTL_SECONDS", "40"))))
_SUPPLY_LAST_GOOD_MAX_AGE_SECONDS = max(120, min(3600, int(os.getenv("SUPPLY_LAST_GOOD_MAX_AGE_SECONDS", "900"))))
_SUPPLY_COMPUTE_BUDGET_SECONDS = max(6, min(45, int(os.getenv("SUPPLY_COMPUTE_BUDGET_SECONDS", "12"))))
_SUPPLY_MIN_ITEMS_ON_BUDGET = max(6, min(40, int(os.getenv("SUPPLY_MIN_ITEMS_ON_BUDGET", "12"))))
_SUPPLY_CANDIDATE_LIMIT_MAX = max(40, min(180, int(os.getenv("SUPPLY_CANDIDATE_LIMIT_MAX", "90"))))
_PYKRX_INVESTOR_DISABLE_UNTIL: datetime | None = None


def _touch_ip_fail(ip: str | None) -> bool:
    if not ip:
        return False
    window = settings.auth_ip_window_seconds
    limit = settings.auth_ip_max_failed
    now_ts = datetime.now(tz=SEOUL)
    history = [t for t in _IP_FAILS.get(ip, []) if (now_ts - t).total_seconds() <= window]
    history.append(now_ts)
    _IP_FAILS[ip] = history
    return len(history) >= limit


def _clear_ip_fail(ip: str | None) -> None:
    if not ip:
        return
    _IP_FAILS.pop(ip, None)


def _get_user_by_code(session, user_code: str) -> User | None:
    return session.scalar(select(User).where(User.user_code == user_code))


_USER_CODE_RE = re.compile(r"^[0-9A-Za-z가-힣._-]{2,32}$")
_USER_NAME_RE = re.compile(r"^[A-Za-z가-힣][A-Za-z가-힣\s]{1,39}$")
_PUSH_CONTROL_CHAR_RE = re.compile(r"[\x00-\x1F\x7F]")
_PUSH_ALLOWED_ROUTES = {
    "settings",
    "premarket",
    "autotrade",
    "holdings",
    "supply",
    "movers",
    "movers2",
    "us",
    "news",
    "longterm",
    "papers",
    "eod",
    "alerts",
}


def _is_sqlite_lock_error(exc: Exception) -> bool:
    text = str(exc or "").lower()
    return "database is locked" in text or "database table is locked" in text


def _autotrade_db_lock_message(action: str) -> str:
    return f"{action} 처리 중 데이터베이스 경합이 발생했습니다. 잠시 후 다시 시도해 주세요."


def _is_market_time_block_message(message: str | None) -> bool:
    text = str(message or "").strip().lower()
    if not text:
        return False
    keywords = (
        "장종료",
        "장 시작",
        "장시작",
        "장전",
        "장후",
        "장시간",
        "주문가능시간",
        "시간외",
        "market_closed",
        "market_preopen",
        "market_afterhours",
        "40580000",
        "40581000",
        "40582000",
    )
    return any(key in text for key in keywords)


def _market_phase_user_label(phase: str | None) -> str:
    up = str(phase or "").strip().upper()
    if up == "OPEN":
        return "장중"
    if up == "PREOPEN":
        return "장 시작 전"
    if up == "BREAK":
        return "장중 휴장 구간"
    if up == "CLOSED":
        return "장 마감 후"
    if up == "HOLIDAY":
        return "휴장 시간"
    return "장시간 외"


def _load_order_item_for_user(user_id: int, order_id: int) -> AutoTradeOrderItem | None:
    session = SessionLocal()
    try:
        row = session.get(AutoTradeOrder, int(order_id))
        if row is None or int(row.user_id) != int(user_id):
            return None
        return _autotrade_order_item(row)
    except Exception:
        return None
    finally:
        session.close()


def _load_reservation_item_for_user(user_id: int, reservation_id: int) -> AutoTradeReservationItem | None:
    session = SessionLocal()
    try:
        row = session.get(AutoTradeReservation, int(reservation_id))
        if row is None or int(row.user_id) != int(user_id):
            return None
        return _autotrade_reservation_item(row)
    except Exception:
        return None
    finally:
        session.close()


def _normalize_user_code_input(raw: str | None, *, required: bool = False) -> str | None:
    code = str(raw or "").strip()
    if not code:
        if required:
            raise HTTPException(status_code=400, detail="user_code required")
        return None
    if not _USER_CODE_RE.fullmatch(code):
        raise HTTPException(
            status_code=400,
            detail="INVALID_USER_CODE_FORMAT: 2~32자, 한글/영문/숫자/._-만 허용",
        )
    return code


def _normalize_user_name(raw: str | None, *, required: bool = False) -> str | None:
    name = str(raw or "").strip()
    if not name:
        if required:
            raise HTTPException(status_code=400, detail="name required")
        return None
    if len(name) < 2 or len(name) > 40:
        raise HTTPException(status_code=400, detail="INVALID_NAME_LENGTH")
    if not _USER_NAME_RE.fullmatch(name):
        raise HTTPException(
            status_code=400,
            detail="INVALID_NAME_FORMAT: 2~40자, 한글/영문/공백만 허용",
        )
    return name


def _sanitize_push_text(raw: str | None, *, field: str, max_len: int) -> str:
    text = _PUSH_CONTROL_CHAR_RE.sub(" ", str(raw or ""))
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        raise HTTPException(status_code=400, detail=f"{field.upper()}_REQUIRED")
    if len(text) > max_len:
        raise HTTPException(status_code=400, detail=f"{field.upper()}_TOO_LONG")
    return text


def _sanitize_push_route(raw: str | None) -> str | None:
    route = _PUSH_CONTROL_CHAR_RE.sub("", str(raw or "")).strip()
    if not route:
        return None
    route = route.replace(" ", "")
    route = route.lower()
    if len(route) > 80:
        raise HTTPException(status_code=400, detail="ROUTE_TOO_LONG")
    if route not in _PUSH_ALLOWED_ROUTES:
        raise HTTPException(status_code=400, detail="ROUTE_NOT_ALLOWED")
    return route


def _collect_admin_push_devices(
    session,
    *,
    target: str,
    requester_user_id: int,
    requester_session_id: int,
) -> list[Device]:
    if target == "all":
        return session.query(Device).order_by(Device.updated_at.desc()).all()

    if target == "active_7d":
        since = now() - timedelta(days=7)
        active_user_ids = [
            int(x[0])
            for x in session.query(User.id)
            .filter(User.last_login_at.is_not(None), User.last_login_at >= since)
            .all()
        ]
        if not active_user_ids:
            return []
        active_device_ids = [
            str(x[0]).strip()
            for x in session.query(SessionToken.device_id)
            .filter(
                SessionToken.user_id.in_(active_user_ids),
                SessionToken.device_id.is_not(None),
            )
            .group_by(SessionToken.device_id)
            .all()
            if str(x[0] or "").strip()
        ]
        if not active_device_ids:
            return []
        rows = (
            session.query(Device)
            .filter(Device.device_id.in_(active_device_ids))
            .order_by(Device.updated_at.desc())
            .all()
        )
        by_id = {str(r.device_id): r for r in rows}
        return [by_id[did] for did in active_device_ids if did in by_id]

    # target == "test"
    preferred_device_ids: list[str] = []
    current_session = session.get(SessionToken, requester_session_id)
    if current_session is not None:
        did = str(getattr(current_session, "device_id", "") or "").strip()
        if did:
            preferred_device_ids.append(did)

    recent_session_device_rows = (
        session.query(SessionToken.device_id)
        .filter(
            SessionToken.user_id == requester_user_id,
            SessionToken.device_id.is_not(None),
        )
        .order_by(SessionToken.issued_at.desc())
        .limit(5)
        .all()
    )
    for row in recent_session_device_rows:
        did = str(row[0] or "").strip()
        if did and did not in preferred_device_ids:
            preferred_device_ids.append(did)

    for did in preferred_device_ids:
        device = session.get(Device, did)
        if device is not None:
            return [device]

    fallback = session.query(Device).order_by(Device.updated_at.desc()).limit(1).all()
    return fallback


_MENU_KEY_DAYTRADE = "menu_daytrade"
_MENU_KEY_AUTOTRADE = "menu_autotrade"
_MENU_KEY_HOLDINGS = "menu_holdings"
_MENU_KEY_SUPPLY = "menu_supply"
_MENU_KEY_MOVERS = "menu_movers"
_MENU_KEY_US = "menu_us"
_MENU_KEY_NEWS = "menu_news"
_MENU_KEY_LONGTERM = "menu_longterm"
_MENU_KEY_PAPERS = "menu_papers"
_MENU_KEY_EOD = "menu_eod"
_MENU_KEY_ALERTS = "menu_alerts"

_MENU_PERMISSION_ATTR_MAP = {
    _MENU_KEY_DAYTRADE: "menu_daytrade",
    _MENU_KEY_AUTOTRADE: "menu_autotrade",
    _MENU_KEY_HOLDINGS: "menu_holdings",
    _MENU_KEY_SUPPLY: "menu_supply",
    _MENU_KEY_MOVERS: "menu_movers",
    _MENU_KEY_US: "menu_us",
    _MENU_KEY_NEWS: "menu_news",
    _MENU_KEY_LONGTERM: "menu_longterm",
    _MENU_KEY_PAPERS: "menu_papers",
    _MENU_KEY_EOD: "menu_eod",
    _MENU_KEY_ALERTS: "menu_alerts",
}


def _menu_permissions_is_default(payload: MenuPermissionsPayload) -> bool:
    return all(bool(getattr(payload, attr, True)) for attr in _MENU_PERMISSION_ATTR_MAP.values())


def _get_user_menu_permission_row(session, user_id: int) -> UserMenuPermission | None:
    return session.scalar(
        select(UserMenuPermission)
        .where(UserMenuPermission.user_id == int(user_id))
        .limit(1)
    )


def _menu_permissions_payload(row: UserMenuPermission | None) -> MenuPermissionsPayload:
    if row is None:
        return MenuPermissionsPayload()
    return MenuPermissionsPayload(
        menu_daytrade=bool(getattr(row, "menu_daytrade", True)),
        menu_autotrade=bool(getattr(row, "menu_autotrade", True)),
        menu_holdings=bool(getattr(row, "menu_holdings", True)),
        menu_supply=bool(getattr(row, "menu_supply", True)),
        menu_movers=bool(getattr(row, "menu_movers", True)),
        menu_us=bool(getattr(row, "menu_us", True)),
        menu_news=bool(getattr(row, "menu_news", True)),
        menu_longterm=bool(getattr(row, "menu_longterm", True)),
        menu_papers=bool(getattr(row, "menu_papers", True)),
        menu_eod=bool(getattr(row, "menu_eod", True)),
        menu_alerts=bool(getattr(row, "menu_alerts", True)),
    )


def _resolve_user_menu_permissions(
    session,
    user_id: int,
) -> tuple[MenuPermissionsPayload, datetime | None, bool]:
    row = _get_user_menu_permission_row(session, user_id)
    payload = _menu_permissions_payload(row)
    updated_at = getattr(row, "updated_at", None) if row is not None else None
    inherited_default = row is None
    return payload, updated_at, inherited_default


def _menu_allowed(session, user_id: int, menu_key: str) -> bool:
    payload, _, _ = _resolve_user_menu_permissions(session, user_id)
    key = str(menu_key or "").strip().lower()
    if key == _MENU_KEY_AUTOTRADE:
        # Autotrade endpoints are shared by "자동" and "보유" screens.
        return bool(payload.menu_autotrade) or bool(payload.menu_holdings)
    attr = _MENU_PERMISSION_ATTR_MAP.get(key)
    if attr:
        return bool(getattr(payload, attr, True))
    return True


def _require_menu_allowed(session, user_id: int, menu_key: str) -> None:
    if not _menu_allowed(session, user_id, menu_key):
        raise HTTPException(status_code=403, detail="MENU_FORBIDDEN")


def _require_menu_allowed_for_user(user_id: int, menu_key: str) -> None:
    with session_scope() as session:
        _require_menu_allowed(session, int(user_id), menu_key)


def _apply_premarket_menu_filter(
    payload: dict[str, Any],
    *,
    allow_daytrade: bool,
    allow_longterm: bool,
) -> None:
    if not allow_daytrade:
        for key in (
            "daytrade_top",
            "daytrade_primary",
            "daytrade_watch",
            "daytrade_top10",
            "base_top10",
            "var7_top10",
        ):
            payload[key] = []
    if not allow_longterm:
        payload["longterm"] = []
        payload["longterm_top10"] = []
    if (not allow_daytrade) or (not allow_longterm):
        payload["overlap_bucket"] = []


def _parse_admin_audit_detail(raw: str | None) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _invited_user_codes_by_admin(session, admin_user_id: int) -> dict[str, datetime]:
    rows = (
        session.query(AdminAuditLog)
        .filter(
            AdminAuditLog.admin_user_id == admin_user_id,
            AdminAuditLog.action == "INVITE_CREATE",
        )
        .order_by(AdminAuditLog.ts.desc())
        .all()
    )
    invited: dict[str, datetime] = {}
    for row in rows:
        detail = _parse_admin_audit_detail(row.detail_json)
        code = str(detail.get("user_code") or "").strip()
        if code and code not in invited:
            invited[code] = row.ts
    return invited


def _autotrade_env(raw: str | None) -> str:
    env = str(raw or "demo").strip().lower()
    return env if env in {"demo", "prod"} else "demo"


def _autotrade_account_env(raw: str | None) -> str:
    env = str(raw or "demo").strip().lower()
    return env if env in {"paper", "demo", "prod"} else "demo"


def _masked_account_for_env(broker_status: dict[str, Any], env: str) -> str | None:
    if env == "demo":
        return str(
            broker_status.get("masked_demo_account_no")
            or broker_status.get("masked_account_no")
            or ""
        ).strip() or None
    if env == "prod":
        return str(
            broker_status.get("masked_prod_account_no")
            or broker_status.get("masked_account_no")
            or ""
        ).strip() or None
    return str(broker_status.get("masked_account_no") or "").strip() or None


def _build_local_account_estimate(
    session,
    *,
    user_id: int,
    environment: str,
    account_no_masked: str | None,
    cfg: AutoTradeSetting,
    message: str | None = None,
) -> AutoTradeAccountSnapshotResponse:
    today = datetime.now(tz=SEOUL).date()
    metric = recompute_daily_metric(session, user_id, today)
    pnl_snapshot = _build_pnl_snapshot(session, user_id, today)
    estimate_positions: list[AutoTradeAccountPosition] = []
    for pos in sorted(pnl_snapshot.positions.values(), key=lambda x: x.ticker):
        current_candidates = [
            float(lot.order.current_price)
            for lot in pos.lots
            if (lot.order.current_price or 0.0) > 0.0
        ]
        current_price = current_candidates[0] if current_candidates else float(pos.avg_price)
        eval_amount = current_price * float(pos.qty)
        pnl_amount = (current_price - float(pos.avg_price)) * float(pos.qty)
        pnl_pct = ((current_price / float(pos.avg_price)) - 1.0) * 100.0 if (pos.avg_price or 0.0) > 0.0 else 0.0
        name = next((str(lot.order.name) for lot in pos.lots if str(lot.order.name or "").strip()), pos.ticker)
        estimate_positions.append(
            AutoTradeAccountPosition(
                ticker=str(pos.ticker),
                name=name,
                source_tab=str(pos.source_tab or "UNKNOWN"),
                qty=int(pos.qty),
                avg_price=float(pos.avg_price),
                current_price=float(current_price),
                eval_amount_krw=float(eval_amount),
                pnl_amount_krw=float(pnl_amount),
                pnl_pct=float(pnl_pct),
            )
        )
    estimate_positions.sort(key=lambda p: p.eval_amount_krw, reverse=True)

    seed_krw = float(getattr(cfg, "seed_krw", 0.0) or 0.0)
    stock_eval_krw = sum(float(p.eval_amount_krw) for p in estimate_positions)
    unrealized_pnl_krw = float(metric.unrealized_pnl_krw or 0.0)
    realized_pnl_krw = float(metric.realized_pnl_krw or 0.0)
    cash_krw = max(0.0, seed_krw - stock_eval_krw)
    total_asset_krw = seed_krw + realized_pnl_krw + unrealized_pnl_krw
    if total_asset_krw <= 0.0:
        total_asset_krw = cash_krw + stock_eval_krw

    return AutoTradeAccountSnapshotResponse(
        environment=environment,  # type: ignore[arg-type]
        source="LOCAL_ESTIMATE",
        broker_connected=False,
        account_no_masked=account_no_masked,
        cash_krw=float(cash_krw),
        orderable_cash_krw=float(cash_krw),
        stock_eval_krw=float(stock_eval_krw),
        total_asset_krw=float(total_asset_krw),
        realized_pnl_krw=realized_pnl_krw,
        unrealized_pnl_krw=unrealized_pnl_krw,
        real_eval_pnl_krw=unrealized_pnl_krw,
        real_eval_pnl_pct=((unrealized_pnl_krw / stock_eval_krw) * 100.0) if stock_eval_krw > 0.0 else 0.0,
        asset_change_krw=None,
        asset_change_pct=None,
        positions=estimate_positions,
        message=message,
        updated_at=now(),
    )


def _friendly_autotrade_live_error(raw: str | None) -> str:
    msg = str(raw or "").strip()
    if not msg:
        return "BROKER_BALANCE_UNAVAILABLE"
    up = msg.upper()
    if "EGW00133" in up:
        return "BROKER_RATE_LIMIT_UNAVAILABLE: 브로커 인증 호출 한도(분당)로 계좌 연동이 일시 중단되었습니다. 잠시 후 새로고침하세요."
    if "INVALID" in up and "CREDENTIAL" in up:
        return "BROKER_CREDENTIAL_INVALID_UNAVAILABLE: 계정정보를 다시 확인하세요."
    return msg


def _resolve_autotrade_account_snapshot(
    session,
    *,
    user_id: int,
    cfg: AutoTradeSetting,
    broker_status: dict[str, Any],
    environment: str | None = None,
    allow_live_fetch: bool = True,
) -> AutoTradeAccountSnapshotResponse:
    env = _autotrade_account_env(environment) if environment is not None else _autotrade_account_env(getattr(cfg, "environment", "demo"))
    cached = _get_cached_autotrade_account_snapshot(user_id, env)
    if cached is not None:
        return cached

    account_no_masked = _masked_account_for_env(broker_status, env)
    kis_enabled = bool(broker_status.get("kis_trading_enabled"))
    broker_ready = True
    if env == "demo":
        broker_ready = bool(broker_status.get("demo_ready_effective"))
    elif env == "prod":
        broker_ready = bool(broker_status.get("prod_ready_effective"))
    broker_live_possible = env in {"demo", "prod"} and kis_enabled and broker_ready

    if broker_live_possible and (not bool(allow_live_fetch)):
        return AutoTradeAccountSnapshotResponse(
            environment=env,  # type: ignore[arg-type]
            source="UNAVAILABLE",
            broker_connected=False,
            account_no_masked=account_no_masked,
            cash_krw=None,
            orderable_cash_krw=None,
            stock_eval_krw=None,
            total_asset_krw=None,
            realized_pnl_krw=None,
            unrealized_pnl_krw=None,
            real_eval_pnl_krw=None,
            real_eval_pnl_pct=None,
            asset_change_krw=None,
            asset_change_pct=None,
            positions=[],
            message="BROKER_SYNC_PENDING: 계좌 동기화 중입니다. 잠시 후 자동 갱신됩니다.",
            updated_at=now(),
        )

    if broker_live_possible:
        user_creds, use_user_creds = resolve_user_kis_credentials(session, user_id)
        broker = KisBrokerClient(credentials=(user_creds if use_user_creds else None))
        try:
            bal = broker.inquire_balance(env=("demo" if env == "demo" else "prod"))
            if bal.ok and bal.snapshot is not None:
                live_positions = [
                    AutoTradeAccountPosition(
                        ticker=p.ticker,
                        name=p.name,
                        source_tab=None,
                        qty=int(p.qty),
                        avg_price=float(p.avg_price),
                        current_price=float(p.current_price),
                        eval_amount_krw=float(p.eval_amount),
                        pnl_amount_krw=float(p.pnl_amount),
                        pnl_pct=float(p.pnl_pct),
                    )
                    for p in bal.snapshot.positions
                ]
                response = AutoTradeAccountSnapshotResponse(
                    environment=env,  # type: ignore[arg-type]
                    source="BROKER_LIVE",
                    broker_connected=True,
                    account_no_masked=account_no_masked,
                    cash_krw=float(bal.snapshot.cash_amount),
                    orderable_cash_krw=float(bal.snapshot.orderable_cash_amount),
                    stock_eval_krw=float(bal.snapshot.stock_eval_amount),
                    total_asset_krw=float(bal.snapshot.total_asset_amount),
                    realized_pnl_krw=float(bal.snapshot.realized_pnl_amount or 0.0),
                    unrealized_pnl_krw=float(bal.snapshot.total_pnl_amount),
                    real_eval_pnl_krw=float(
                        bal.snapshot.real_eval_pnl_amount
                        if bal.snapshot.real_eval_pnl_amount is not None
                        else bal.snapshot.total_pnl_amount
                    ),
                    real_eval_pnl_pct=float(
                        bal.snapshot.real_eval_pnl_rate
                        if bal.snapshot.real_eval_pnl_rate is not None
                        else 0.0
                    ),
                    asset_change_krw=(
                        float(bal.snapshot.asset_change_amount)
                        if bal.snapshot.asset_change_amount is not None
                        else None
                    ),
                    asset_change_pct=(
                        float(bal.snapshot.asset_change_rate)
                        if bal.snapshot.asset_change_rate is not None
                        else None
                    ),
                    positions=live_positions,
                    message=None,
                    updated_at=now(),
                )
                _set_cached_autotrade_account_snapshot(user_id, env, response)
                return response
            live_error = _friendly_autotrade_live_error(bal.message)
        except Exception as exc:
            live_error = _friendly_autotrade_live_error(f"BROKER_BALANCE_EXCEPTION_UNAVAILABLE: {exc}")
    else:
        if env == "paper":
            live_error = "PAPER_MODE_ESTIMATE"
        elif not kis_enabled:
            live_error = "KIS_TRADING_DISABLED_UNAVAILABLE"
        else:
            live_error = "BROKER_CREDENTIAL_NOT_READY_UNAVAILABLE"

    if env in {"demo", "prod"}:
        response = AutoTradeAccountSnapshotResponse(
            environment=env,  # type: ignore[arg-type]
            source="UNAVAILABLE",
            broker_connected=False,
            account_no_masked=account_no_masked,
            cash_krw=None,
            orderable_cash_krw=None,
            stock_eval_krw=None,
            total_asset_krw=None,
            realized_pnl_krw=None,
            unrealized_pnl_krw=None,
            real_eval_pnl_krw=None,
            real_eval_pnl_pct=None,
            asset_change_krw=None,
            asset_change_pct=None,
            positions=[],
            message=live_error,
            updated_at=now(),
        )
        _set_cached_autotrade_account_snapshot(user_id, env, response)
        return response

    response = _build_local_account_estimate(
        session,
        user_id=user_id,
        environment=env,
        account_no_masked=account_no_masked,
        cfg=cfg,
        message=live_error,
    )
    _set_cached_autotrade_account_snapshot(user_id, env, response)
    return response


def _generate_temp_password() -> str:
    # Keep temp passwords easy for phone input and verbal sharing.
    # Default: 6-digit numeric PIN (configurable).
    digits_raw = os.getenv("AUTH_TEMP_PASSWORD_DIGITS", "6")
    try:
        digits = int(digits_raw)
    except Exception:
        digits = 6
    digits = max(4, min(10, digits))
    upper = 10 ** digits
    return f"{secrets.randbelow(upper):0{digits}d}"


def _check_locked(user: User) -> bool:
    return user.locked_until is not None and user.locked_until > now()


def _mark_failed_attempt(session, user: User) -> None:
    user.failed_attempts += 1
    if user.failed_attempts >= settings.auth_max_failed:
        user.locked_until = now() + timedelta(minutes=settings.auth_lock_minutes)


def _reset_failed_attempts(session, user: User) -> None:
    user.failed_attempts = 0
    user.locked_until = None


def _ensure_user_active(user: User, reason: str) -> None:
    if user.status == STATUS_BLOCKED:
        raise HTTPException(status_code=403, detail=REASON_BLOCKED)
    if user.status == STATUS_DELETED:
        raise HTTPException(status_code=403, detail=REASON_DELETED)
    if user.status != STATUS_ACTIVE:
        raise HTTPException(status_code=403, detail=reason)


def _settings_from_row(row: StrategySettings | None) -> StrategySettingsPayload:
    if row is None:
        return StrategySettingsPayload(algo_version="V2")
    payload = StrategySettingsPayload(
        algo_version=normalize_algo_version(getattr(row, "algo_version", None)),
        risk_preset=row.risk_preset,
        use_custom_weights=row.use_custom_weights,
        w_ta=row.w_ta,
        w_re=row.w_re,
        w_rs=row.w_rs,
        theme_cap=row.theme_cap,
        max_gap_pct=row.max_gap_pct,
        gate_threshold=row.gate_threshold,
        gate_quantile=row.gate_quantile,
    )
    return payload


def _get_or_create_settings(session) -> StrategySettingsPayload:
    row = session.get(StrategySettings, 1)
    if row is None:
        now = datetime.now(tz=SEOUL)
        row = StrategySettings(
            id=1,
            algo_version="V2",
            risk_preset="ADAPTIVE",
            use_custom_weights=False,
            theme_cap=2,
            max_gap_pct=0.0,
            gate_threshold=0.0,
            gate_quantile=None,
            updated_at=now,
        )
        session.add(row)
        session.flush()
    return _settings_from_row(row)


def _cache_key(
    report_date: date,
    settings_hash: str,
    daytrade_limit: int | None,
    longterm_limit: int | None,
    algo_version: str,
) -> str:
    return premarket_cache_key(report_date, settings_hash, daytrade_limit, longterm_limit, algo_version)


def _us_insider_cache_key(
    target_count: int,
    trading_days: int,
    expand_days: int,
    max_candidates: int,
    transaction_codes_key: str,
    base_date: date | None = None,
) -> str:
    base_key = base_date.isoformat() if base_date is not None else "today"
    return f"{target_count}:{trading_days}:{expand_days}:{max_candidates}:{transaction_codes_key}:{base_key}"


def _load_us_insider_db_cache(cache_key: str) -> tuple[datetime, dict[str, Any]] | None:
    if not settings.sec_cache_db_enabled:
        return None
    with session_scope() as session:
        row = session.scalar(
            select(Report)
            .where(Report.type == _US_INSIDER_REPORT_TYPE, Report.cache_key == cache_key)
            .order_by(desc(Report.generated_at))
            .limit(1)
        )
        if row is None:
            return None
        try:
            payload = json.loads(row.payload_json or "{}")
        except Exception:
            return None
        return row.generated_at, payload


def _load_latest_us_insider_db_positive(limit: int = 20) -> tuple[datetime, dict[str, Any]] | None:
    if not settings.sec_cache_db_enabled:
        return None
    with session_scope() as session:
        rows = session.scalars(
            select(Report)
            .where(Report.type == _US_INSIDER_REPORT_TYPE)
            .order_by(desc(Report.generated_at))
            .limit(max(1, limit))
        ).all()
        for row in rows:
            try:
                payload = json.loads(row.payload_json or "{}")
            except Exception:
                continue
            if int(payload.get("returned_count") or 0) > 0:
                return row.generated_at, payload
    return None


def _upsert_us_insider_db_cache(cache_key: str, payload: dict[str, Any]) -> None:
    if not settings.sec_cache_db_enabled:
        return
    d = datetime.now(tz=SEOUL).date()
    now_ts = datetime.now(tz=SEOUL)
    def _json_default(v: Any) -> str:
        if isinstance(v, (datetime, date)):
            return v.isoformat()
        raise TypeError(f"not serializable: {type(v)}")

    with session_scope() as session:
        row = session.scalar(
            select(Report)
            .where(Report.type == _US_INSIDER_REPORT_TYPE, Report.date == d, Report.cache_key == cache_key)
            .order_by(desc(Report.generated_at))
            .limit(1)
        )
        if row is None:
            row = session.scalar(
                select(Report)
                .where(Report.type == _US_INSIDER_REPORT_TYPE, Report.cache_key == cache_key)
                .order_by(desc(Report.generated_at))
                .limit(1)
            )
        serialized = json.dumps(payload, ensure_ascii=False, default=_json_default)
        if row is None:
            session.add(
                Report(
                    date=d,
                    type=_US_INSIDER_REPORT_TYPE,
                    cache_key=cache_key,
                    payload_json=serialized,
                    generated_at=now_ts,
                )
            )
        else:
            row.date = d
            row.type = _US_INSIDER_REPORT_TYPE
            row.cache_key = cache_key
            row.payload_json = serialized
            row.generated_at = now_ts


def _normalize_ticker(ticker: str) -> str:
    raw = str(ticker or "").strip().upper()
    digits = "".join(ch for ch in raw if ch.isdigit())
    if len(raw) == 6 and len(digits) == 6 and raw.isdigit():
        return digits
    # US/Global style ticker support for 관심 탭 (e.g., AAPL, BRK.B).
    if re.fullmatch(r"[A-Z][A-Z0-9.\-]{0,11}", raw):
        return raw
    raise HTTPException(status_code=400, detail="INVALID_TICKER")


def _is_kr_ticker(ticker: str) -> bool:
    return str(ticker).isdigit() and len(str(ticker)) == 6


def _normalize_kr_ticker_strict(raw_ticker: str) -> str:
    digits = "".join(ch for ch in str(raw_ticker or "") if ch.isdigit())
    if not digits:
        raise HTTPException(status_code=400, detail="INVALID_TICKER")
    ticker = digits[-6:] if len(digits) >= 6 else digits.zfill(6)
    if len(ticker) != 6 or not ticker.isdigit():
        raise HTTPException(status_code=400, detail="INVALID_TICKER")
    return ticker


def _safe_int_from_any(v: Any) -> int:
    try:
        if isinstance(v, str):
            vv = v.replace(",", "").strip()
            if not vv:
                return 0
            return int(float(vv))
        return int(float(v))
    except Exception:
        return 0


def _safe_float_from_any(v: Any) -> float:
    try:
        if isinstance(v, str):
            vv = v.replace(",", "").strip()
            if not vv:
                return 0.0
            return float(vv)
        return float(v)
    except Exception:
        return 0.0


def _fmt_bizdate_to_iso(raw: str) -> str:
    s = str(raw or "").strip()
    if len(s) >= 8 and s[:8].isdigit():
        return f"{s[:4]}-{s[4:6]}-{s[6:8]}"
    return s


def _fetch_naver_investor_daily_rows(ticker: str, days: int) -> list[StockInvestorDailyItem]:
    """
    Fallback source when pykrx/krx scraping is unstable.
    Naver trend API exposes daily net flow for 개인/외국인/기관(요약).
    """
    base = f"https://m.stock.naver.com/api/stock/{ticker}/trend/all"
    headers = {
        "User-Agent": "Mozilla/5.0",
        "Referer": f"https://m.stock.naver.com/domestic/stock/{ticker}/investor",
    }
    out: list[StockInvestorDailyItem] = []
    seen_dates: set[str] = set()
    cursor: str | None = None
    tries = 0

    while len(out) < days and tries < 6:
        page_size = min(60, max(10, days - len(out)))
        params: dict[str, Any] = {"pageSize": page_size}
        if cursor:
            params["bizdate"] = cursor
        resp = requests.get(base, params=params, headers=headers, timeout=5)
        resp.raise_for_status()
        rows = resp.json()
        if not isinstance(rows, list) or not rows:
            break

        appended = 0
        for row in rows:
            if not isinstance(row, dict):
                continue
            d_raw = str(row.get("bizdate") or "").strip()
            if not d_raw or d_raw in seen_dates:
                continue
            seen_dates.add(d_raw)
            individual = _safe_int_from_any(row.get("individualPureBuyQuant"))
            foreigner = _safe_int_from_any(row.get("foreignerPureBuyQuant"))
            institution = _safe_int_from_any(row.get("organPureBuyQuant"))
            out.append(
                StockInvestorDailyItem(
                    date=_fmt_bizdate_to_iso(d_raw),
                    individual_qty=individual,
                    foreign_qty=foreigner,
                    institution_qty=institution,
                    private_fund_qty=0,
                    corporate_qty=0,
                    financial_investment_qty=0,
                    insurance_qty=0,
                    trust_qty=0,
                    pension_qty=0,
                    bank_qty=0,
                    etc_finance_qty=0,
                    other_foreign_qty=0,
                    total_qty=individual + foreigner + institution,
                    individual_value=0.0,
                    foreign_value=0.0,
                    institution_value=0.0,
                    private_fund_value=0.0,
                    corporate_value=0.0,
                    total_value=0.0,
                )
            )
            appended += 1
            if len(out) >= days:
                break

        if appended == 0:
            break
        cursor = str(rows[-1].get("bizdate") or "").strip()
        if not cursor:
            break
        tries += 1

    return out[:days]


def _parse_naver_minute_chart_rows(ticker: str) -> list[tuple[datetime, float, int]]:
    resp = requests.get(
        "https://fchart.stock.naver.com/sise.nhn",
        params={"symbol": ticker, "timeframe": "minute", "count": "400", "requestType": "0"},
        timeout=4,
    )
    resp.raise_for_status()
    text = resp.text
    raw_items = re.findall(r'<item data="([^"]+)"', text)
    parsed: list[tuple[datetime, float, int]] = []
    for raw in raw_items:
        parts = raw.split("|")
        if len(parts) < 6:
            continue
        ts_raw = str(parts[0]).strip()
        if len(ts_raw) < 12 or not ts_raw[:12].isdigit():
            continue
        try:
            ts = datetime.strptime(ts_raw[:12], "%Y%m%d%H%M").replace(tzinfo=SEOUL)
        except Exception:
            continue
        close_v = _safe_float_from_any(parts[4])
        volume_v = max(0, _safe_int_from_any(parts[5]))
        if close_v <= 0.0:
            continue
        parsed.append((ts, close_v, volume_v))

    if not parsed:
        return []

    latest_day = max(x[0].date() for x in parsed)
    out = [x for x in parsed if x[0].date() == latest_day]
    out.sort(key=lambda x: x[0])

    dedup: dict[datetime, tuple[datetime, float, int]] = {}
    for row in out:
        dedup[row[0]] = row
    return list(sorted(dedup.values(), key=lambda x: x[0]))


def _build_stock_trend_intraday_live(ticker: str, limit: int) -> StockTrendIntradayResponse:
    quote = fetch_quotes([ticker], mode="light")
    q = quote[0] if quote else None
    prev_close = float(q.prev_close) if q is not None else 0.0
    name = None

    rows = _parse_naver_minute_chart_rows(ticker)
    items: list[StockTrendIntradayItem] = []
    prev_price: float | None = None
    prev_cum_volume: int | None = None
    est_cum_net_qty = 0

    for ts, price, cum_vol in rows:
        direction = "FLAT"
        if prev_price is not None:
            if price > prev_price:
                direction = "UP"
            elif price < prev_price:
                direction = "DOWN"
        delta_vol = 0
        if prev_cum_volume is not None and cum_vol >= prev_cum_volume:
            delta_vol = cum_vol - prev_cum_volume
        signed = delta_vol if direction == "UP" else (-delta_vol if direction == "DOWN" else 0)
        est_cum_net_qty += signed
        chg_abs = (price - prev_close) if prev_close > 0 else 0.0
        chg_pct = ((price / prev_close) - 1.0) * 100.0 if prev_close > 0 else 0.0
        items.append(
            StockTrendIntradayItem(
                time=ts.strftime("%H:%M"),
                current_price=price,
                change_abs=chg_abs,
                change_pct=chg_pct,
                volume_delta=delta_vol,
                cumulative_volume=cum_vol,
                net_buy_qty_estimate=est_cum_net_qty,
                direction=direction,
            )
        )
        prev_price = price
        prev_cum_volume = cum_vol

    if q is not None and q.price > 0.0:
        now_ts = q.as_of.astimezone(SEOUL)
        cur_price = float(q.price or 0.0)
        cur_cum_vol = max(0, _safe_int_from_any(q.volume))
        direction = "FLAT"
        if prev_price is not None:
            if cur_price > prev_price:
                direction = "UP"
            elif cur_price < prev_price:
                direction = "DOWN"
        delta_vol = 0
        if prev_cum_volume is not None and cur_cum_vol >= prev_cum_volume:
            delta_vol = cur_cum_vol - prev_cum_volume
        signed = delta_vol if direction == "UP" else (-delta_vol if direction == "DOWN" else 0)
        chg_abs = (cur_price - prev_close) if prev_close > 0 else 0.0
        chg_pct = ((cur_price / prev_close) - 1.0) * 100.0 if prev_close > 0 else 0.0
        items.append(
            StockTrendIntradayItem(
                time=now_ts.strftime("%H:%M:%S"),
                current_price=cur_price,
                change_abs=chg_abs,
                change_pct=chg_pct,
                volume_delta=delta_vol,
                cumulative_volume=cur_cum_vol,
                net_buy_qty_estimate=est_cum_net_qty + signed,
                direction=direction,
            )
        )

    if not items and q is not None and q.price > 0.0:
        items.append(
            StockTrendIntradayItem(
                time=q.as_of.astimezone(SEOUL).strftime("%H:%M:%S"),
                current_price=float(q.price),
                change_abs=(float(q.price) - prev_close) if prev_close > 0 else 0.0,
                change_pct=((float(q.price) / prev_close) - 1.0) * 100.0 if prev_close > 0 else 0.0,
                volume_delta=0,
                cumulative_volume=max(0, _safe_int_from_any(q.volume)),
                net_buy_qty_estimate=0,
                direction="FLAT",
            )
        )

    items_latest = list(reversed(items))[: max(1, min(limit, 240))]
    window_minutes = 0
    if len(items) >= 2:
        latest_ts = rows[-1][0] if rows else datetime.now(tz=SEOUL)
        first_ts = rows[0][0] if rows else latest_ts
        window_minutes = max(0, int((latest_ts - first_ts).total_seconds() // 60))

    return StockTrendIntradayResponse(
        ticker=ticker,
        name=name,
        as_of=datetime.now(tz=SEOUL),
        prev_close=prev_close,
        source="LIVE",
        message="순매수 수량은 분당 체결량과 가격방향 기반 추정치입니다.",
        window_minutes=window_minutes,
        items=items_latest,
    )


def _build_stock_investor_daily_live(ticker: str, days: int) -> StockInvestorDailyResponse:
    now_ts = datetime.now(tz=SEOUL)
    items: list[StockInvestorDailyItem] = []

    def _num(df, idx, col: str) -> float:
        if df is None or df.empty or col not in df.columns:
            return 0.0
        try:
            return _safe_float_from_any(df.loc[idx, col])
        except Exception:
            return 0.0

    global _PYKRX_INVESTOR_DISABLE_UNTIL
    pykrx_blocked = (
        _PYKRX_INVESTOR_DISABLE_UNTIL is not None and now_ts < _PYKRX_INVESTOR_DISABLE_UNTIL
    )

    # 1) Primary: pykrx detail feed (개인/외국인/기관 + 세부 주체 + 금액)
    if not pykrx_blocked:
        try:
            from pykrx import stock as krx_stock  # type: ignore

            end = now_ts.strftime("%Y%m%d")
            start = (now_ts - timedelta(days=max(30, days * 5))).strftime("%Y%m%d")
            qty_df = krx_stock.get_market_trading_volume_by_date(start, end, ticker, on="순매수", detail=True)
            if qty_df is None or qty_df.empty:
                raise RuntimeError("pykrx quantity dataframe empty")

            val_df = krx_stock.get_market_trading_value_by_date(start, end, ticker, on="순매수", detail=True)
            if val_df is None or val_df.empty:
                raise RuntimeError("pykrx value dataframe empty")

            qty_df = qty_df.sort_index(ascending=False).head(days)
            for idx in qty_df.index:
                fin = _num(qty_df, idx, "금융투자")
                ins = _num(qty_df, idx, "보험")
                trust = _num(qty_df, idx, "투신")
                private_fund = _num(qty_df, idx, "사모")
                bank = _num(qty_df, idx, "은행")
                etc_fin = _num(qty_df, idx, "기타금융")
                pension = _num(qty_df, idx, "연기금")
                institution = _num(qty_df, idx, "기관합계")
                if institution == 0.0:
                    institution = fin + ins + trust + private_fund + bank + etc_fin + pension

                foreign_total = _num(qty_df, idx, "외국인합계")
                if foreign_total == 0.0:
                    foreign_total = _num(qty_df, idx, "외국인") + _num(qty_df, idx, "기타외국인")

                items.append(
                    StockInvestorDailyItem(
                        date=(idx.strftime("%Y-%m-%d") if hasattr(idx, "strftime") else str(idx)[:10]),
                        individual_qty=_safe_int_from_any(_num(qty_df, idx, "개인")),
                        foreign_qty=_safe_int_from_any(foreign_total),
                        institution_qty=_safe_int_from_any(institution),
                        private_fund_qty=_safe_int_from_any(private_fund),
                        corporate_qty=_safe_int_from_any(_num(qty_df, idx, "기타법인")),
                        financial_investment_qty=_safe_int_from_any(fin),
                        insurance_qty=_safe_int_from_any(ins),
                        trust_qty=_safe_int_from_any(trust),
                        pension_qty=_safe_int_from_any(pension),
                        bank_qty=_safe_int_from_any(bank),
                        etc_finance_qty=_safe_int_from_any(etc_fin),
                        other_foreign_qty=_safe_int_from_any(_num(qty_df, idx, "기타외국인")),
                        total_qty=_safe_int_from_any(_num(qty_df, idx, "전체")),
                        individual_value=_num(val_df, idx, "개인"),
                        foreign_value=_num(val_df, idx, "외국인합계")
                        if _num(val_df, idx, "외국인합계") != 0.0
                        else (_num(val_df, idx, "외국인") + _num(val_df, idx, "기타외국인")),
                        institution_value=_num(val_df, idx, "기관합계")
                        if _num(val_df, idx, "기관합계") != 0.0
                        else (
                            _num(val_df, idx, "금융투자")
                            + _num(val_df, idx, "보험")
                            + _num(val_df, idx, "투신")
                            + _num(val_df, idx, "사모")
                            + _num(val_df, idx, "은행")
                            + _num(val_df, idx, "기타금융")
                            + _num(val_df, idx, "연기금")
                        ),
                        private_fund_value=_num(val_df, idx, "사모"),
                        corporate_value=_num(val_df, idx, "기타법인"),
                        total_value=_num(val_df, idx, "전체"),
                    )
                )
        except Exception as exc:
            # pykrx frequently fails with KRX anti-bot/JSON decode issues on cloud IPs.
            _PYKRX_INVESTOR_DISABLE_UNTIL = now_ts + timedelta(minutes=30)
            logger.warning(
                "stock investor daily pykrx primary failed ticker=%s reason=%s; disable pykrx until %s",
                ticker,
                str(exc)[:120],
                _PYKRX_INVESTOR_DISABLE_UNTIL.isoformat(),
                exc_info=True,
            )

    if items:
        return StockInvestorDailyResponse(
            ticker=ticker,
            as_of=now_ts,
            source="LIVE",
            days=days,
            items=items,
        )

    # 2) Fallback: Naver mobile trend API (개인/외국인/기관계 summary)
    naver_rows = _fetch_naver_investor_daily_rows(ticker, days)
    if naver_rows:
        return StockInvestorDailyResponse(
            ticker=ticker,
            as_of=now_ts,
            source="LIVE",
            message="일부 항목은 네이버 수급 요약(개인/외국인/기관) 기준으로 제공합니다.",
            days=days,
            items=naver_rows,
        )

    return StockInvestorDailyResponse(
        ticker=ticker,
        as_of=now_ts,
        source="FALLBACK",
        message="투자자 수급 데이터를 가져오지 못했습니다.",
        days=days,
        items=[],
    )


def _supply_logo_urls(ticker: str) -> tuple[str | None, str | None]:
    code = str(ticker or "").strip()
    if len(code) != 6 or not code.isdigit():
        return None, None
    return (
        f"https://ssl.pstatic.net/imgstock/fn/real/logo/stock/Stock{code}.svg",
        f"https://ssl.pstatic.net/imgstock/fn/real/logo/png/stock/Stock{code}.png",
    )


def _supply_cfg() -> KrxApiConfig:
    return KrxApiConfig(
        api_key=settings.krx_api_key,
        endpoint_kospi=settings.krx_endpoint_kospi,
        endpoint_kosdaq=settings.krx_endpoint_kosdaq,
        endpoint_konex=settings.krx_endpoint_konex,
        cache_dir=f"{settings.data_dir}/krx_cache",
    )


def _supply_recent_bas_dd(cfg: KrxApiConfig, lookback_days: int = 20) -> str:
    today = datetime.now(tz=SEOUL).date()
    resolved = today.strftime("%Y%m%d")
    for i in range(1, max(2, lookback_days) + 1):
        d = today - timedelta(days=i)
        bas_dd = d.strftime("%Y%m%d")
        df = fetch_daily_market(cfg, bas_dd, "KOSPI")
        if df is not None and not df.empty:
            resolved = bas_dd
            break
    return resolved


def _supply_market_frames(cfg: KrxApiConfig, bas_dd: str, markets: list[str]) -> list[Any]:
    frames: list[Any] = []
    normalized = [m.strip().upper() for m in markets if str(m).strip()]
    if not normalized:
        return frames
    workers = max(1, min(4, len(normalized)))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futs = {
            pool.submit(fetch_daily_market, cfg, bas_dd, market): market
            for market in normalized
        }
        for fut in as_completed(futs):
            try:
                df = fut.result()
            except Exception:
                logger.exception("supply krx fetch failed market=%s bas_dd=%s", futs[fut], bas_dd)
                continue
            if df is None or df.empty:
                continue
            frames.append(df)
    return frames


def _supply_universe_rows(
    frames: list[Any],
    *,
    universe_top_value: int,
    universe_top_chg: int,
) -> list[dict[str, Any]]:
    if not frames:
        return []
    try:
        import pandas as pd  # type: ignore
    except Exception:
        return []
    base = pd.concat(frames, ignore_index=True)
    base["ticker"] = base["ISU_CD"].astype(str).str.zfill(6)
    base = base[base["ticker"].str.match(r"^\d{6}$", na=False)]
    if base.empty:
        return []
    base["baseline_value"] = base.get("ACC_TRDVAL", 0.0).astype(float)
    base["baseline_vol"] = base.get("ACC_TRDVOL", 0.0).astype(float)
    base["open"] = base.get("TDD_OPNPRC", 0.0).astype(float)
    base["close"] = base.get("TDD_CLSPRC", 0.0).astype(float)
    base["proxy_chg_pct"] = base.apply(
        lambda r: ((float(r["close"]) / float(r["open"]) - 1.0) * 100.0) if float(r["open"] or 0.0) > 0 else 0.0,
        axis=1,
    )
    value_top = base.sort_values("baseline_value", ascending=False).head(universe_top_value)
    chg_top = base.assign(abs_proxy=base["proxy_chg_pct"].abs()).sort_values("abs_proxy", ascending=False).head(universe_top_chg)
    uni = (
        pd.concat([value_top, chg_top], ignore_index=True)
        .drop_duplicates(subset=["ticker"])
        .sort_values("baseline_value", ascending=False)
        .reset_index(drop=True)
    )
    return uni.to_dict(orient="records")


def _get_stock_investor_daily_cached_or_live(ticker: str, days: int) -> StockInvestorDailyResponse:
    cache_key = f"{ticker}:{days}"
    now_ts = datetime.now(tz=SEOUL)
    with _STOCK_FLOW_CACHE_LOCK:
        hit = _INVESTOR_DAILY_CACHE.get(cache_key)
        if hit is not None and (now_ts - hit[0]).total_seconds() <= _INVESTOR_DAILY_TTL_SECONDS:
            return hit[1]
    try:
        live = _build_stock_investor_daily_live(ticker, days)
        with _STOCK_FLOW_CACHE_LOCK:
            _INVESTOR_DAILY_CACHE[cache_key] = (datetime.now(tz=SEOUL), live)
        return live
    except Exception as exc:
        logger.exception("supply investor fetch failed ticker=%s days=%s", ticker, days)
        with _STOCK_FLOW_CACHE_LOCK:
            stale = _INVESTOR_DAILY_CACHE.get(cache_key)
        if stale is not None:
            cached = stale[1].model_copy(deep=True)
            cached.source = "CACHE"
            cached.message = f"실시간 조회 실패로 캐시 표시: {str(exc)[:120]}"
            cached.as_of = datetime.now(tz=SEOUL)
            return cached
        return StockInvestorDailyResponse(
            ticker=ticker,
            as_of=datetime.now(tz=SEOUL),
            source="FALLBACK",
            message=f"투자자 수급 조회 실패: {str(exc)[:160]}",
            days=days,
            items=[],
        )


def _clamp(v: float, lo: float, hi: float) -> float:
    if v < lo:
        return lo
    if v > hi:
        return hi
    return v


def _sum_investor_qty(rows: list[StockInvestorDailyItem], n: int, field: str) -> int:
    target = rows[: max(1, n)]
    total = 0
    for row in target:
        total += _safe_int_from_any(getattr(row, field, 0))
    return total


def _supply_flow_label(foreign_3d: int, institution_3d: int, individual_3d: int, net_3d: int) -> str:
    if individual_3d < 0 and net_3d > 0:
        return "개인 역추세"
    if foreign_3d > 0 and institution_3d > 0:
        return "동반 매수"
    if foreign_3d >= institution_3d:
        return "외국인 주도"
    return "기관 주도"


def _supply_confidence(source: str, investor_days: int) -> str:
    src = str(source or "").upper()
    if src == "LIVE" and investor_days >= 10:
        return "HIGH"
    if src in {"LIVE", "CACHE"} and investor_days >= 5:
        return "MID"
    return "LOW"


def _compute_supply_live(
    *,
    count: int,
    days: int,
    universe_top_value: int,
    universe_top_chg: int,
    markets: list[str],
    include_contrarian: bool,
) -> SupplyResponse:
    now_ts = datetime.now(tz=SEOUL)
    started_at = perf_counter()
    if not settings.krx_api_key:
        return SupplyResponse(
            as_of=now_ts,
            bas_dd="",
            source="FALLBACK",
            message="KRX_API_KEY가 비어 있어 수급 데이터를 생성할 수 없습니다.",
            notes=["서버 환경변수 KRX_API_KEY 확인 필요"],
            items=[],
        )

    cfg = _supply_cfg()
    bas_dd = _supply_recent_bas_dd(cfg)
    frames = _supply_market_frames(cfg, bas_dd, markets)
    if not frames:
        return SupplyResponse(
            as_of=now_ts,
            bas_dd=bas_dd,
            source="FALLBACK",
            message="KRX 일간 데이터가 비어 있어 수급 계산을 진행하지 못했습니다.",
            notes=["KRX 일간 데이터 수신 실패"],
            items=[],
        )

    universe_rows = _supply_universe_rows(
        frames,
        universe_top_value=universe_top_value,
        universe_top_chg=universe_top_chg,
    )
    if not universe_rows:
        return SupplyResponse(
            as_of=now_ts,
            bas_dd=bas_dd,
            source="FALLBACK",
            message="수급 유니버스 구성이 비어 있습니다.",
            notes=["유니버스 후보 생성 실패"],
            items=[],
        )

    tickers = [str(row.get("ticker") or "") for row in universe_rows if str(row.get("ticker") or "")]
    qmap = {q.ticker: q for q in fetch_quotes(tickers)}
    pre_candidates: list[tuple[dict[str, Any], Any, float, float]] = []
    for row in universe_rows:
        ticker = str(row.get("ticker") or "")
        if not ticker:
            continue
        q = qmap.get(ticker)
        if q is None:
            continue
        price = float(q.price or 0.0)
        prev_close = float(q.prev_close or 0.0)
        if price <= 0.0 or prev_close <= 0.0:
            continue
        baseline_value = _safe_float_from_any(row.get("baseline_value"))
        baseline_vol = _safe_float_from_any(row.get("baseline_vol"))
        if baseline_value <= 0.0 or baseline_vol <= 0.0:
            continue
        value_now = float(q.value or 0.0)
        if value_now <= 0.0:
            continue
        min_live_value = max(70_000_000.0, baseline_value * 0.025)
        if value_now < min_live_value:
            continue
        chg_pct = ((price / prev_close) - 1.0) * 100.0
        value_ratio = (value_now / baseline_value) if baseline_value > 0 else 0.0
        pre_candidates.append((row, q, chg_pct, value_ratio))

    pre_candidates.sort(
        key=lambda x: (
            float(getattr(x[1], "value", 0.0) or 0.0),
            abs(float(x[2] or 0.0)),
            float(x[0].get("baseline_value") or 0.0),
        ),
        reverse=True,
    )
    candidate_limit = max(40, min(_SUPPLY_CANDIDATE_LIMIT_MAX, max(count * 2, 60)))
    narrowed = pre_candidates[:candidate_limit]

    items: list[dict[str, Any]] = []
    fallback_rows = 0
    low_conf_rows = 0
    budget_exhausted = False
    supply_unit = "value"  # 금액 우선, qty 폴백 시 변경
    for row, q, chg_pct, value_ratio in narrowed:
        elapsed_sec = perf_counter() - started_at
        if elapsed_sec >= _SUPPLY_COMPUTE_BUDGET_SECONDS and len(items) >= min(count, _SUPPLY_MIN_ITEMS_ON_BUDGET):
            budget_exhausted = True
            break
        ticker = str(row.get("ticker") or "")
        investor = _get_stock_investor_daily_cached_or_live(ticker=ticker, days=days)
        investor_rows = investor.items or []
        if not investor_rows:
            continue
        investor_source = str(investor.source or "FALLBACK").upper()
        if investor_source == "FALLBACK":
            fallback_rows += 1
        investor_days = len(investor_rows)
        if investor_source == "FALLBACK" and investor_days < 3:
            continue

        # 금액(value) 우선, 없으면 수량(qty) 폴백 (네이버 fallback은 value=0)
        _has_value = any(getattr(r, "foreign_value", 0) != 0 or getattr(r, "institution_value", 0) != 0 for r in investor_rows[:3])
        if not _has_value:
            supply_unit = "qty"
        _f_key = "foreign_value" if _has_value else "foreign_qty"
        _i_key = "institution_value" if _has_value else "institution_qty"
        _d_key = "individual_value" if _has_value else "individual_qty"
        foreign_3d = _sum_investor_qty(investor_rows, 3, _f_key)
        institution_3d = _sum_investor_qty(investor_rows, 3, _i_key)
        individual_3d = _sum_investor_qty(investor_rows, 3, _d_key)
        net_3d = foreign_3d + institution_3d
        net_5d = _sum_investor_qty(investor_rows, 5, _f_key) + _sum_investor_qty(investor_rows, 5, _i_key)
        if net_3d <= 0 and net_5d <= 0:
            continue

        flow_label = _supply_flow_label(foreign_3d, institution_3d, individual_3d, net_3d)
        if (not include_contrarian) and flow_label == "개인 역추세":
            continue

        buy_streak_days = 0
        for investor_row in investor_rows:
            day_net = _safe_int_from_any(investor_row.foreign_qty) + _safe_int_from_any(investor_row.institution_qty)
            if day_net > 0:
                buy_streak_days += 1
            else:
                break

        baseline_value = _safe_float_from_any(row.get("baseline_value"))
        baseline_vol = _safe_float_from_any(row.get("baseline_vol"))
        flow_ratio_3d = float(net_3d) / max(1.0, baseline_vol * 0.06)
        flow_ratio_5d = float(net_5d) / max(1.0, baseline_vol * 0.10)
        score = 50.0
        score += _clamp(flow_ratio_3d, -1.0, 3.0) * 15.0
        score += _clamp(flow_ratio_5d, -0.8, 2.5) * 10.0
        score += min(4, buy_streak_days) * 5.0
        score += _clamp(value_ratio, 0.0, 3.0) * 6.0
        score += _clamp(chg_pct, -6.0, 8.0) * 1.1
        if foreign_3d > 0 and institution_3d > 0:
            score += 8.0
        if flow_label == "개인 역추세":
            score += 6.0
        if investor_source == "LIVE":
            score += 8.0
        elif investor_source == "CACHE":
            score += 3.0
        else:
            score -= 10.0
        if investor_days < 5:
            score -= 8.0

        confidence = _supply_confidence(investor_source, investor_days)
        if confidence == "LOW":
            low_conf_rows += 1

        logo_url, logo_png_url = _supply_logo_urls(ticker)
        items.append(
            {
                "ticker": ticker,
                "name": str(row.get("ISU_NM") or ""),
                "market": str(row.get("MKT_NM") or ""),
                "logo_url": logo_url,
                "logo_png_url": logo_png_url,
                "tags": [],
                "price": float(q.price or 0.0),
                "prev_close": float(q.prev_close or 0.0),
                "chg_pct": chg_pct,
                "as_of": (q.as_of if isinstance(q.as_of, datetime) else now_ts),
                "source": str(q.source or ""),
                "is_live": bool(q.is_live),
                "volume": float(q.volume or 0.0),
                "value": float(q.value or 0.0),
                "baseline_value": baseline_value if baseline_value > 0 else None,
                "value_ratio": value_ratio if value_ratio > 0 else None,
                "flow_label": flow_label,
                "confidence": confidence,
                "investor_source": investor_source if investor_source in {"LIVE", "CACHE", "FALLBACK"} else "FALLBACK",
                "investor_message": investor.message,
                "investor_days": investor_days,
                "foreign_3d": foreign_3d,
                "institution_3d": institution_3d,
                "individual_3d": individual_3d,
                "net_3d": net_3d,
                "net_5d": net_5d,
                "buy_streak_days": buy_streak_days,
                "flow_score": round(score, 3),
            }
        )

    items.sort(
        key=lambda x: (
            float(x.get("flow_score") or 0.0),
            float(x.get("net_3d") or 0.0),
            float(x.get("value") or 0.0),
        ),
        reverse=True,
    )

    strong = [it for it in items if float(it.get("flow_score") or 0.0) >= 46.0]
    selected = (strong if len(strong) >= max(10, count // 2) else items)[:count]
    notes = [
        "유니버스: 전일 거래대금 Top + 변동성 프록시 Top 합집합",
        "필터: 외국인/기관 3일 순매수 + 실시간 유동성 기준",
    ]
    if fallback_rows > 0:
        notes.append(f"투자자 데이터 FALLBACK 포함 {fallback_rows}건")
    if low_conf_rows > 0:
        notes.append(f"저신뢰(LOW) {low_conf_rows}건 포함")
    if budget_exhausted:
        notes.append(f"응답 안정화를 위해 계산 시간({_SUPPLY_COMPUTE_BUDGET_SECONDS}s) 예산 내에서 결과를 반환했습니다.")

    elapsed_ms = int((perf_counter() - started_at) * 1000.0)
    logger.info(
        "market supply built elapsed_ms=%s universe=%s pre_candidates=%s narrowed=%s selected=%s budget_hit=%s fallback_rows=%s low_conf_rows=%s",
        elapsed_ms,
        len(universe_rows),
        len(pre_candidates),
        len(narrowed),
        len(selected),
        budget_exhausted,
        fallback_rows,
        low_conf_rows,
    )

    return SupplyResponse.model_validate(
        {
            "as_of": now_ts,
            "bas_dd": bas_dd,
            "source": "LIVE",
            "message": None if selected else "조건을 충족하는 수급 후보가 없습니다.",
            "unit": supply_unit,
            "universe_count": len(universe_rows),
            "candidate_quotes": len(qmap),
            "notes": notes,
            "items": selected,
        }
    )


def _autotrade_settings_payload(row: AutoTradeSetting) -> AutoTradeSettingsPayload:
    return AutoTradeSettingsPayload(
        enabled=bool(row.enabled),
        environment=_autotrade_env(str(row.environment or "demo")),
        include_daytrade=bool(row.include_daytrade),
        include_movers=bool(row.include_movers),
        include_supply=bool(getattr(row, "include_supply", True)),
        include_papers=bool(row.include_papers),
        include_longterm=bool(row.include_longterm),
        include_favorites=bool(row.include_favorites),
        order_budget_krw=float(row.order_budget_krw or 0.0),
        max_orders_per_run=int(row.max_orders_per_run or 0),
        max_daily_loss_pct=float(row.max_daily_loss_pct or 0.0),
        seed_krw=float(row.seed_krw or 0.0),
        take_profit_pct=float(row.take_profit_pct or 0.0),
        stop_loss_pct=float(row.stop_loss_pct or 0.0),
        stoploss_reentry_policy=(
            str(getattr(row, "stoploss_reentry_policy", "cooldown") or "cooldown").strip().lower()
            if str(getattr(row, "stoploss_reentry_policy", "cooldown") or "cooldown").strip().lower()
            in {"immediate", "cooldown", "day_block", "manual_block"}
            else "cooldown"
        ),
        stoploss_reentry_cooldown_min=max(
            1,
            min(1440, int(getattr(row, "stoploss_reentry_cooldown_min", 30) or 30)),
        ),
        takeprofit_reentry_policy=(
            str(getattr(row, "takeprofit_reentry_policy", "cooldown") or "cooldown").strip().lower()
            if str(getattr(row, "takeprofit_reentry_policy", "cooldown") or "cooldown").strip().lower()
            in {"immediate", "cooldown", "day_block", "manual_block"}
            else "cooldown"
        ),
        takeprofit_reentry_cooldown_min=max(
            1,
            min(1440, int(getattr(row, "takeprofit_reentry_cooldown_min", 30) or 30)),
        ),
        allow_market_order=bool(row.allow_market_order),
        offhours_reservation_enabled=bool(getattr(row, "offhours_reservation_enabled", True)),
        offhours_reservation_mode=(
            "confirm"
            if str(getattr(row, "offhours_reservation_mode", "auto") or "").strip().lower() == "confirm"
            else "auto"
        ),
        offhours_confirm_timeout_min=max(
            1,
            min(30, int(getattr(row, "offhours_confirm_timeout_min", 3) or 3)),
        ),
        offhours_confirm_timeout_action=(
            "auto"
            if str(getattr(row, "offhours_confirm_timeout_action", "cancel") or "").strip().lower() == "auto"
            else "cancel"
        ),
    )


def _autotrade_symbol_rule_item(row: AutoTradeSymbolRule) -> AutoTradeSymbolRuleItem:
    return AutoTradeSymbolRuleItem(
        ticker=str(row.ticker or ""),
        name=(str(row.name) if row.name is not None else None),
        take_profit_pct=float(row.take_profit_pct or 0.0),
        stop_loss_pct=float(row.stop_loss_pct or 0.0),
        enabled=bool(row.enabled),
        updated_at=row.updated_at,
    )


def _autotrade_broker_payload(raw: dict[str, Any]) -> AutoTradeBrokerCredentialResponse:
    source = str(raw.get("source") or "SERVER_ENV")
    if source not in {"USER", "SERVER_ENV"}:
        source = "SERVER_ENV"
    return AutoTradeBrokerCredentialResponse(
        kis_trading_enabled=bool(raw.get("kis_trading_enabled")),
        use_user_credentials=bool(raw.get("use_user_credentials")),
        has_demo_app_key=bool(raw.get("has_demo_app_key")),
        has_demo_app_secret=bool(raw.get("has_demo_app_secret")),
        has_prod_app_key=bool(raw.get("has_prod_app_key")),
        has_prod_app_secret=bool(raw.get("has_prod_app_secret")),
        has_account_no=bool(raw.get("has_account_no")),
        has_demo_account_no=bool(raw.get("has_demo_account_no")),
        has_prod_account_no=bool(raw.get("has_prod_account_no")),
        masked_account_no=(str(raw.get("masked_account_no")) if raw.get("masked_account_no") is not None else None),
        masked_demo_account_no=(str(raw.get("masked_demo_account_no")) if raw.get("masked_demo_account_no") is not None else None),
        masked_prod_account_no=(str(raw.get("masked_prod_account_no")) if raw.get("masked_prod_account_no") is not None else None),
        masked_prod_app_key=(str(raw.get("masked_prod_app_key")) if raw.get("masked_prod_app_key") is not None else None),
        masked_prod_app_secret=(str(raw.get("masked_prod_app_secret")) if raw.get("masked_prod_app_secret") is not None else None),
        account_product_code=str(raw.get("account_product_code") or "01"),
        account_product_code_demo=str(raw.get("account_product_code_demo") or "01"),
        account_product_code_prod=str(raw.get("account_product_code_prod") or "01"),
        demo_ready_user=bool(raw.get("demo_ready_user")),
        prod_ready_user=bool(raw.get("prod_ready_user")),
        demo_ready_server=bool(raw.get("demo_ready_server")),
        prod_ready_server=bool(raw.get("prod_ready_server")),
        demo_ready_effective=bool(raw.get("demo_ready_effective")),
        prod_ready_effective=bool(raw.get("prod_ready_effective")),
        source=source,
        updated_at=raw.get("updated_at") if isinstance(raw.get("updated_at"), datetime) else datetime.now(tz=SEOUL),
    )


def _parse_autotrade_order_meta(row: AutoTradeOrder) -> dict[str, Any]:
    raw_meta = str(getattr(row, "metadata_json", "") or "").strip()
    if not raw_meta:
        return {}
    try:
        payload = json.loads(raw_meta)
        return payload if isinstance(payload, dict) else {}
    except Exception:
        return {}


def _to_float_or_none(v: Any) -> float | None:
    try:
        if v is None:
            return None
        return float(v)
    except Exception:
        return None


def _fmt_krw(v: Any) -> str:
    fv = _to_float_or_none(v)
    if fv is None:
        return "-"
    return f"{fv:,.0f}원"


def _fmt_qty(v: Any) -> str:
    fv = _to_float_or_none(v)
    if fv is None:
        return "-"
    return f"{int(round(fv))}주"


def _fmt_pct(v: Any) -> str:
    fv = _to_float_or_none(v)
    if fv is None:
        return "-"
    return f"{fv:+.2f}%"


def _normalize_order_reason_code(status: str, reason: str) -> str:
    return normalize_reason_code(status, reason)


def _autotrade_reason_detail(row: AutoTradeOrder, meta: dict[str, Any]) -> AutoTradeReasonDetail:
    status = str(getattr(row, "status", "") or "").strip()
    reason = str(getattr(row, "reason", "") or "").strip()
    code = _normalize_order_reason_code(status, reason)
    evidence: dict[str, str] = {}

    if code == "SEED_LIMIT_EXCEEDED":
        evidence["현재노출"] = _fmt_krw(meta.get("current_exposure_krw"))
        evidence["필요주문금액"] = _fmt_krw(meta.get("required_cash"))
        evidence["시드한도"] = _fmt_krw(meta.get("seed_limit_krw"))
        return AutoTradeReasonDetail(
            conclusion="총 시드 한도 초과로 이번 매수를 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="총 시드를 상향하거나 종목당 예산을 낮추세요.",
        )
    if code == "ORDERABLE_CASH_LIMIT":
        evidence["주문가능현금"] = _fmt_krw(meta.get("available_orderable_cash"))
        evidence["필요주문금액"] = _fmt_krw(meta.get("required_cash"))
        evidence["요청수량"] = _fmt_qty(getattr(row, "qty", None))
        return AutoTradeReasonDetail(
            conclusion="주문가능현금이 부족해 주문이 전송되지 않았습니다.",
            reason_code=code,
            evidence=evidence,
            action="수량 자동재계산을 사용하거나 종목당 예산을 줄이세요.",
        )
    if code == "ALREADY_OPEN_POSITION":
        evidence["보유수량"] = _fmt_qty(meta.get("open_qty"))
        evidence["평균단가"] = _fmt_krw(meta.get("open_avg_price"))
        return AutoTradeReasonDetail(
            conclusion="이미 보유 중인 종목이라 중복 진입을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="분할매수를 원하면 해당 옵션을 활성화하세요.",
        )
    if code == "ENTRY_BLOCKED_MANUAL":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        return AutoTradeReasonDetail(
            conclusion="사용자 차단 설정으로 이번 진입을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="종목 규칙에서 차단 상태를 해제하세요.",
        )
    if code == "PENDING_BUY_ORDER":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        evidence["보호시간"] = f"{int(_to_float_or_none(meta.get('pending_guard_sec')) or 0)}초"
        if int(_to_float_or_none(meta.get("existing_order_id")) or 0) > 0:
            evidence["기존주문ID"] = str(int(_to_float_or_none(meta.get("existing_order_id")) or 0))
        existing_order_no = str(meta.get("existing_broker_order_no") or "").strip()
        if existing_order_no:
            evidence["기존주문번호"] = existing_order_no
        return AutoTradeReasonDetail(
            conclusion="동일 종목 매수 주문이 접수 대기 중이라 중복 진입을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="접수 주문 체결/거부 반영 후 다시 시도하세요.",
        )
    if code == "PENDING_SELL_ORDER":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        evidence["보호시간"] = f"{int(_to_float_or_none(meta.get('pending_guard_sec')) or 0)}초"
        evidence["매도가능수량"] = _fmt_qty(meta.get("sellable_qty"))
        if int(_to_float_or_none(meta.get("existing_order_id")) or 0) > 0:
            evidence["기존주문ID"] = str(int(_to_float_or_none(meta.get("existing_order_id")) or 0))
        existing_order_no = str(meta.get("existing_broker_order_no") or "").strip()
        if existing_order_no:
            evidence["기존주문번호"] = existing_order_no
        return AutoTradeReasonDetail(
            conclusion="동일 종목 매도 주문이 접수 대기 중이라 중복 청산을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="기존 매도 주문 상태를 확인한 뒤 재실행하세요.",
        )
    if code == "QTY_ZERO":
        evidence["종목당예산"] = _fmt_krw(meta.get("order_budget_krw"))
        evidence["요청가격"] = _fmt_krw(meta.get("requested_price"))
        evidence["계산수량"] = _fmt_qty(meta.get("computed_qty"))
        return AutoTradeReasonDetail(
            conclusion="종목당 예산 대비 주문 수량이 0주라 진입을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="종목당 예산을 올리거나 더 낮은 가격대 종목을 선택하세요.",
        )
    if code == "STOPLOSS_REENTRY_COOLDOWN":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        evidence["재진입정책"] = "손절 후 재진입 대기시간"
        evidence["대기시간"] = f"{int(_to_float_or_none(meta.get('stoploss_reentry_cooldown_min')) or 0)}분"
        return AutoTradeReasonDetail(
            conclusion="해당 종목은 손절 직후 재진입 대기시간 구간이라 진입을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="재진입 대기시간 종료 후 자동 재평가됩니다. 즉시 재진입이 필요하면 정책을 변경하세요.",
        )
    if code == "STOPLOSS_REENTRY_BLOCKED_TODAY":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        evidence["재진입정책"] = "당일 재진입 금지"
        return AutoTradeReasonDetail(
            conclusion="해당 종목은 손절 당일 재진입 금지 정책으로 오늘은 진입하지 않습니다.",
            reason_code=code,
            evidence=evidence,
            action="다음 거래일에 자동 재평가되며, 필요 시 정책을 완화하세요.",
        )
    if code == "STOPLOSS_REENTRY_BLOCKED_MANUAL":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        evidence["재진입정책"] = "수동 해제 전 차단"
        return AutoTradeReasonDetail(
            conclusion="해당 종목은 손절 후 수동 해제 전 차단 상태라 진입을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="설정에서 차단 종목을 해제하면 다음 사이클부터 재평가됩니다.",
        )
    if code == "TAKEPROFIT_REENTRY_COOLDOWN":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        evidence["재진입정책"] = "익절 후 재진입 대기시간"
        evidence["대기시간"] = f"{int(_to_float_or_none(meta.get('takeprofit_reentry_cooldown_min')) or 0)}분"
        return AutoTradeReasonDetail(
            conclusion="해당 종목은 익절 직후 재진입 대기시간 구간이라 진입을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="재진입 대기시간 종료 후 자동 재평가됩니다. 즉시 재진입이 필요하면 정책을 변경하세요.",
        )
    if code == "TAKEPROFIT_REENTRY_BLOCKED_TODAY":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        evidence["재진입정책"] = "당일 재진입 금지"
        return AutoTradeReasonDetail(
            conclusion="해당 종목은 익절 당일 재진입 금지 정책으로 오늘은 진입하지 않습니다.",
            reason_code=code,
            evidence=evidence,
            action="다음 거래일에 자동 재평가되며, 필요 시 정책을 완화하세요.",
        )
    if code == "TAKEPROFIT_REENTRY_BLOCKED_MANUAL":
        evidence["종목"] = str(getattr(row, "ticker", "") or "-")
        evidence["재진입정책"] = "수동 해제 전 차단"
        return AutoTradeReasonDetail(
            conclusion="해당 종목은 익절 후 수동 해제 전 차단 상태라 진입을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="설정에서 차단 종목을 해제하면 다음 사이클부터 재평가됩니다.",
        )
    if code == "SELLABLE_QTY_ZERO":
        evidence["보유수량"] = _fmt_qty(meta.get("holding_qty"))
        evidence["매도가능수량"] = _fmt_qty(meta.get("sellable_qty"))
        evidence["트리거"] = str(meta.get("trigger") or "-")
        return AutoTradeReasonDetail(
            conclusion="보유수량은 있지만 매도가능수량이 0주라 주문을 건너뛰었습니다.",
            reason_code=code,
            evidence=evidence,
            action="증권사 매도가능수량을 재조회한 뒤 다시 실행하세요.",
        )
    if code == "MARKET_CLOSED":
        evidence["현재장상태"] = "장시간 외"
        return AutoTradeReasonDetail(
            conclusion="장시간 외라 즉시 주문이 거부되었습니다.",
            reason_code=code,
            evidence=evidence,
            action="예약 주문을 등록하거나 장중에 다시 실행하세요.",
        )
    if code == "BROKER_NO_HOLDING":
        evidence["요청수량"] = _fmt_qty(getattr(row, "qty", None))
        evidence["보유수량"] = _fmt_qty(meta.get("holding_qty"))
        evidence["매도가능수량"] = _fmt_qty(meta.get("sellable_qty"))
        return AutoTradeReasonDetail(
            conclusion="증권사 보유잔고가 없어 매도 주문이 거부되었습니다.",
            reason_code=code,
            evidence=evidence,
            action="보유수량을 재조회한 뒤 수량을 맞춰 재시도하세요.",
        )
    if code == "BROKER_CREDENTIAL_MISSING":
        evidence["실행환경"] = str(meta.get("environment") or "-")
        return AutoTradeReasonDetail(
            conclusion="증권사 계정정보가 준비되지 않아 주문을 실행하지 못했습니다.",
            reason_code=code,
            evidence=evidence,
            action="자동매매 설정에서 증권사 키/계좌 정보를 확인하세요.",
        )
    if code == "BROKER_BALANCE_UNAVAILABLE":
        evidence["원문"] = reason or "-"
        return AutoTradeReasonDetail(
            conclusion="증권사 잔고 조회 실패로 주문을 중단했습니다.",
            reason_code=code,
            evidence=evidence,
            action="잠시 후 재시도하고, 반복되면 계정 연동 상태를 점검하세요.",
        )
    if code == "TAKE_PROFIT":
        evidence["현재손익률"] = _fmt_pct(meta.get("pnl_pct"))
        evidence["익절기준"] = _fmt_pct(meta.get("take_profit_pct"))
        return AutoTradeReasonDetail(
            conclusion="익절 기준 도달로 자동 매도가 실행되었습니다.",
            reason_code=code,
            evidence=evidence,
            action="기준을 조정하려면 자동매매 익절 설정을 변경하세요.",
        )
    if code == "STOP_LOSS":
        evidence["현재손익률"] = _fmt_pct(meta.get("pnl_pct"))
        evidence["손절기준"] = _fmt_pct(-abs(float(_to_float_or_none(meta.get("stop_loss_pct")) or 0.0)))
        return AutoTradeReasonDetail(
            conclusion="손절 기준 도달로 자동 매도가 실행되었습니다.",
            reason_code=code,
            evidence=evidence,
            action="기준을 조정하려면 자동매매 손절 설정을 변경하세요.",
        )
    if str(status).upper() == "BROKER_REJECTED":
        evidence["증권사원문"] = reason or "사유 미수신"
        return AutoTradeReasonDetail(
            conclusion="증권사에서 주문을 거부했습니다.",
            reason_code=code,
            evidence=evidence,
            action="증권사 원문 사유를 확인 후 수량/가격/시간을 조정해 재시도하세요.",
        )
    status_u = str(status or "").strip().upper()
    fallback_code = str(code or status_u or "UNKNOWN").strip().upper()
    if fallback_code == "UNKNOWN":
        fallback_code = status_u or "UNKNOWN"
    if not re.fullmatch(r"[A-Z0-9_]+", fallback_code):
        fallback_code = status_u or "UNKNOWN"

    if status_u == "BROKER_SUBMITTED":
        evidence["요청수량"] = _fmt_qty(getattr(row, "qty", None))
        evidence["요청가격"] = _fmt_krw(getattr(row, "requested_price", None))
        evidence["증권사주문번호"] = str(getattr(row, "broker_order_no", "") or "-")
        return AutoTradeReasonDetail(
            conclusion="증권사 주문이 접수되었습니다. 체결 대기 중입니다.",
            reason_code=fallback_code,
            evidence=evidence,
            action="진행중/미체결 섹션에서 체결·거부·취소 상태를 확인하세요.",
        )
    if status_u in {"BROKER_FILLED", "PAPER_FILLED"}:
        evidence["체결수량"] = _fmt_qty(getattr(row, "qty", None))
        evidence["체결가격"] = _fmt_krw(getattr(row, "filled_price", None) or getattr(row, "requested_price", None))
        return AutoTradeReasonDetail(
            conclusion="주문이 체결 완료되었습니다.",
            reason_code=fallback_code,
            evidence=evidence,
            action="체결내역과 보유 포지션을 확인하세요.",
        )
    if status_u in {"BROKER_CANCELED", "BROKER_CLOSED"}:
        evidence["증권사주문번호"] = str(getattr(row, "broker_order_no", "") or "-")
        if reason:
            evidence["증권사원문"] = reason
        return AutoTradeReasonDetail(
            conclusion="접수취소가 반영되어 취소 대상에서 정리되었습니다.",
            reason_code=fallback_code,
            evidence=evidence,
            action="진행중 목록에서 제외되었는지 확인하세요.",
        )
    if status_u == "ERROR":
        evidence["원문"] = reason or "에러 사유 미수신"
        return AutoTradeReasonDetail(
            conclusion="주문 처리 중 오류가 발생했습니다.",
            reason_code=fallback_code,
            evidence=evidence,
            action="잠시 후 재시도하고 반복되면 로그를 확인하세요.",
        )

    if reason:
        evidence["원문"] = reason
    return AutoTradeReasonDetail(
        conclusion="주문 조건 미충족으로 이번 주문을 건너뛰었습니다.",
        reason_code=fallback_code,
        evidence=evidence,
        action="코드/근거를 확인해 설정(시드·예산·재진입·차단)을 조정하세요.",
    )


def _autotrade_order_item(row: AutoTradeOrder) -> AutoTradeOrderItem:
    inferred_env: str | None = None
    meta = _parse_autotrade_order_meta(row)
    if meta:
        for key in ("environment", "mode", "env"):
            cand = str(meta.get(key) or "").strip().lower()
            if cand in {"paper", "demo", "prod"}:
                inferred_env = cand
                break
    if inferred_env is None:
        status_upper = str(getattr(row, "status", "") or "").strip().upper()
        reason_upper = str(getattr(row, "reason", "") or "").strip().upper()
        hint_upper = f"{reason_upper} {str(getattr(row, 'broker_order_no', '') or '').upper()}"
        if status_upper == "PAPER_FILLED" or "PAPER" in hint_upper:
            inferred_env = "paper"
        elif ("_DEMO_" in hint_upper) or ("VTS" in hint_upper) or ("모의투자" in str(getattr(row, "reason", "") or "")):
            inferred_env = "demo"
        elif ("_PROD_" in hint_upper) or ("실전투자" in str(getattr(row, "reason", "") or "")):
            inferred_env = "prod"

    side = "SELL" if str(row.side).upper() == "SELL" else "BUY"
    return AutoTradeOrderItem(
        id=int(row.id),
        run_id=str(row.run_id),
        source_tab=str(row.source_tab),
        environment=inferred_env,  # type: ignore[arg-type]
        ticker=str(row.ticker),
        name=row.name,
        side=side,
        qty=int(row.qty or 0),
        requested_price=float(row.requested_price or 0.0),
        filled_price=(float(row.filled_price) if row.filled_price is not None else None),
        current_price=(float(row.current_price) if row.current_price is not None else None),
        pnl_pct=(float(row.pnl_pct) if row.pnl_pct is not None else None),
        status=str(row.status),
        broker_order_no=row.broker_order_no,
        reason=row.reason,
        reason_detail=_autotrade_reason_detail(row, meta),
        requested_at=row.requested_at,
        filled_at=row.filled_at,
    )


def _autotrade_order_environment(row: AutoTradeOrder) -> str | None:
    env = _autotrade_order_item(row).environment
    normalized = str(env or "").strip().lower()
    if normalized in {"paper", "demo", "prod"}:
        return normalized
    return None


def _find_recent_pending_order(
    session,
    *,
    user_id: int,
    environment: str,
    side: str,
    ticker: str,
    within_sec: int,
) -> AutoTradeOrder | None:
    env = str(environment or "").strip().lower()
    side_u = "SELL" if str(side or "").strip().upper() == "SELL" else "BUY"
    tk = _normalize_kr_ticker_strict(ticker)
    sec = max(1, int(within_sec))
    cutoff = datetime.now(tz=SEOUL) - timedelta(seconds=sec)
    rows = (
        session.query(AutoTradeOrder)
        .filter(
            AutoTradeOrder.user_id == int(user_id),
            AutoTradeOrder.status == "BROKER_SUBMITTED",
            AutoTradeOrder.side == side_u,
            AutoTradeOrder.ticker == tk,
            AutoTradeOrder.requested_at >= cutoff,
        )
        .order_by(AutoTradeOrder.requested_at.desc(), AutoTradeOrder.id.desc())
        .limit(20)
        .all()
    )
    for row in rows:
        row_env = _autotrade_order_environment(row)
        if row_env == env:
            return row
    return None


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


def _cancel_broker_submitted_order(
    session,
    *,
    user_id: int,
    order_row: AutoTradeOrder,
    fallback_environment: str | None = None,
) -> tuple[str, str]:
    if str(getattr(order_row, "status", "") or "").strip().upper() != "BROKER_SUBMITTED":
        return "skipped", "NOT_PENDING_ORDER"

    env = _autotrade_order_environment(order_row)
    if env not in {"demo", "prod"}:
        fallback = str(fallback_environment or "").strip().lower()
        env = fallback if fallback in {"demo", "prod"} else None
    if env not in {"demo", "prod"}:
        return "failed", "ORDER_ENVIRONMENT_UNRESOLVED"

    broker_order_no = str(getattr(order_row, "broker_order_no", "") or "").strip()
    if not broker_order_no:
        order_row.status = "BROKER_CLOSED"
        order_row.reason = "증권사 주문번호가 없어 접수 상태를 종료 처리했습니다."
        order_row.filled_at = datetime.now(tz=SEOUL)
        meta = _parse_autotrade_order_meta(order_row)
        meta["cancel_last"] = {
            "ok": False,
            "status_code": 400,
            "message": "BROKER_ORDER_NO_MISSING",
            "requested_at": datetime.now(tz=SEOUL).isoformat(),
            "environment": env,
            "normalized_result": "CLOSED_WITHOUT_ORDER_NO",
        }
        order_row.metadata_json = json.dumps(meta, ensure_ascii=False)
        session.flush()
        return "closed", "이미 처리된 주문으로 확인되어 접수 상태를 종료 처리했습니다."

    user_creds, use_user_creds = resolve_user_kis_credentials(session, user_id)
    broker = KisBrokerClient(credentials=(user_creds if use_user_creds else None))
    result = broker.cancel_order(
        env=env,
        order_no=broker_order_no,
        qty=max(0, int(getattr(order_row, "qty", 0) or 0)),
    )

    meta = _parse_autotrade_order_meta(order_row)
    meta["cancel_last"] = {
        "ok": bool(result.ok),
        "status_code": int(result.status_code),
        "message": str(result.message or ""),
        "requested_at": datetime.now(tz=SEOUL).isoformat(),
        "environment": env,
    }
    if result.raw is not None:
        meta["cancel_last"]["raw"] = result.raw

    if result.ok:
        order_row.status = "BROKER_CANCELED"
        order_row.reason = str(result.message or "사용자 요청으로 접수 취소")
        order_row.filled_at = datetime.now(tz=SEOUL)
        meta["cancel_last"]["normalized_result"] = "CANCELED"
        order_row.metadata_json = json.dumps(meta, ensure_ascii=False)
        session.flush()
        return "canceled", order_row.reason or "접수 취소 완료"

    if _is_cancel_target_missing_message(result.message):
        order_row.status = "BROKER_CLOSED"
        order_row.reason = str(result.message or "취소 대상 주문이 존재하지 않습니다.")
        order_row.filled_at = datetime.now(tz=SEOUL)
        meta["cancel_last"]["normalized_result"] = "CLOSED_NOT_FOUND"
        order_row.metadata_json = json.dumps(meta, ensure_ascii=False)
        session.flush()
        return "closed", "이미 체결/취소되어 접수취소 대상이 아닙니다. 증권사 상태로 동기화했습니다."

    order_row.metadata_json = json.dumps(meta, ensure_ascii=False)
    session.flush()
    return "failed", str(result.message or "증권사 접수 취소 실패")


def _autotrade_reservation_item(row: AutoTradeReservation) -> AutoTradeReservationItem:
    payload = reservation_item_payload(row)
    return AutoTradeReservationItem.model_validate(payload)


def _autotrade_metric_item(row: AutoTradeDailyMetric) -> AutoTradePerformanceItem:
    return AutoTradePerformanceItem(
        ymd=row.ymd.isoformat(),
        orders_total=int(row.orders_total or 0),
        filled_total=int(row.filled_total or 0),
        buy_amount_krw=float(row.buy_amount_krw or 0.0),
        eval_amount_krw=float(row.eval_amount_krw or 0.0),
        realized_pnl_krw=float(row.realized_pnl_krw or 0.0),
        unrealized_pnl_krw=float(row.unrealized_pnl_krw or 0.0),
        roi_pct=float(row.roi_pct or 0.0),
        win_rate=float(row.win_rate or 0.0),
        mdd_pct=float(row.mdd_pct or 0.0),
        updated_at=row.updated_at,
    )


def _is_real_filled_order_status(status: str | None) -> bool:
    return str(status or "").strip().upper() in {"PAPER_FILLED", "BROKER_FILLED"}


def _build_autotrade_performance_items_from_orders(
    session,
    *,
    user_id: int,
    since: date,
    environment: str | None = None,
    seed_krw: float = 0.0,
) -> list[AutoTradePerformanceItem]:
    env_filter = str(environment or "").strip().lower()
    if env_filter and env_filter not in {"demo", "prod"}:
        env_filter = ""
    allowed_envs = {env_filter} if env_filter else {"demo", "prod"}

    source_rows = (
        session.query(AutoTradeOrder)
        .filter(AutoTradeOrder.user_id == int(user_id))
        .order_by(AutoTradeOrder.requested_at.asc(), AutoTradeOrder.id.asc())
        .all()
    )
    if not source_rows:
        return []
    rows: list[AutoTradeOrder] = []
    for row in source_rows:
        row_env = _autotrade_order_environment(row) or ""
        if row_env not in allowed_envs:
            continue
        rows.append(row)
    if not rows:
        return []

    today = datetime.now(tz=SEOUL).date()
    if since > today:
        return []
    lots_by_ticker: dict[str, list[dict[str, float | int]]] = {}
    items_asc: list[AutoTradePerformanceItem] = []
    cumulative_realized_pnl = 0.0
    cumulative_realized_cost = 0.0
    cash_balance = float(seed_krw or 0.0)
    prev_total_asset: float | None = None
    twr_factor = 1.0

    day_orders_total = 0
    day_filled_total = 0
    day_closed_pnl_pct: list[float] = []
    day_updated_at: datetime | None = None

    def _reset_day_accumulator() -> None:
        nonlocal day_orders_total, day_filled_total, day_closed_pnl_pct, day_updated_at
        day_orders_total = 0
        day_filled_total = 0
        day_closed_pnl_pct = []
        day_updated_at = None

    def _finalize_day(day: date) -> None:
        nonlocal prev_total_asset, twr_factor
        open_pnl_pcts: list[float] = []
        buy_amount = 0.0
        eval_amount = 0.0
        unrealized = 0.0
        for ticker, lots in lots_by_ticker.items():
            active_lots = [lot for lot in lots if int(lot.get("qty") or 0) > 0]
            if not active_lots:
                continue
            total_qty = sum(int(lot.get("qty") or 0) for lot in active_lots)
            if total_qty <= 0:
                continue
            total_cost = sum(float(lot.get("entry_price") or 0.0) * int(lot.get("qty") or 0) for lot in active_lots)
            if total_cost <= 0.0:
                continue
            avg_price = total_cost / float(total_qty)
            current_candidates = [float(lot.get("current_price") or 0.0) for lot in active_lots if float(lot.get("current_price") or 0.0) > 0.0]
            current_price = current_candidates[-1] if current_candidates else avg_price
            pnl_pct = ((current_price / avg_price) - 1.0) * 100.0 if avg_price > 0.0 else 0.0
            open_pnl_pcts.append(pnl_pct)
            buy_amount += total_cost
            eval_amount += current_price * float(total_qty)
            unrealized += (current_price - avg_price) * float(total_qty)

        pnl_samples = open_pnl_pcts + day_closed_pnl_pct
        wins = sum(1 for x in pnl_samples if x > 0.0)
        win_rate = (wins / len(pnl_samples)) if pnl_samples else 0.0
        min_pnl_pct = min(pnl_samples) if pnl_samples else 0.0
        mdd_pct = abs(min(0.0, min_pnl_pct))
        base_amount = buy_amount + cumulative_realized_cost
        total_pnl = cumulative_realized_pnl + unrealized
        roi_pct = ((total_pnl / base_amount) * 100.0) if base_amount > 0.0 else 0.0
        total_asset = cash_balance + eval_amount
        if prev_total_asset is not None and prev_total_asset > 0.0:
            today_pnl = total_asset - prev_total_asset
            daily_return_pct = (today_pnl / prev_total_asset) * 100.0
            twr_factor *= (1.0 + (daily_return_pct / 100.0))
        else:
            today_pnl = 0.0
            daily_return_pct = 0.0
        twr_cum_pct = (twr_factor - 1.0) * 100.0
        holding_pnl = unrealized
        holding_pnl_pct = ((holding_pnl / buy_amount) * 100.0) if buy_amount > 0.0 else 0.0
        updated_at = _as_seoul_datetime(day_updated_at) or datetime.now(tz=SEOUL)
        items_asc.append(
            AutoTradePerformanceItem(
                ymd=day.isoformat(),
                orders_total=int(day_orders_total),
                filled_total=int(day_filled_total),
                buy_amount_krw=float(buy_amount),
                eval_amount_krw=float(eval_amount),
                realized_pnl_krw=float(cumulative_realized_pnl),
                unrealized_pnl_krw=float(unrealized),
                roi_pct=float(roi_pct),
                win_rate=float(win_rate),
                mdd_pct=float(mdd_pct),
                total_asset_krw=None,
                daily_return_pct=None,
                twr_cum_pct=None,
                holding_pnl_krw=float(holding_pnl),
                holding_pnl_pct=float(holding_pnl_pct),
                today_pnl_krw=None,
                today_pnl_pct=None,
                updated_at=updated_at,
            )
        )
        prev_total_asset = total_asset

    def _append_buy_lot(ticker: str, qty: int, entry_price: float, current_price: float) -> None:
        if qty <= 0 or entry_price <= 0.0 or not ticker:
            return
        lots = lots_by_ticker.setdefault(ticker, [])
        lots.append(
            {
                "qty": int(qty),
                "entry_price": float(entry_price),
                "current_price": float(current_price if current_price > 0.0 else entry_price),
            }
        )

    def _apply_sell_lot(ticker: str, qty: int, exit_price: float, *, collect_day_stat: bool) -> None:
        nonlocal cumulative_realized_pnl, cumulative_realized_cost, day_closed_pnl_pct
        if qty <= 0 or exit_price <= 0.0 or not ticker:
            return
        lots = lots_by_ticker.get(ticker) or []
        close_qty = int(qty)
        while close_qty > 0 and lots:
            lot = lots[0]
            lot_qty = int(lot.get("qty") or 0)
            entry_price = float(lot.get("entry_price") or 0.0)
            if lot_qty <= 0 or entry_price <= 0.0:
                lots.pop(0)
                continue
            matched = min(close_qty, lot_qty)
            pnl_krw = (exit_price - entry_price) * float(matched)
            cumulative_realized_pnl += pnl_krw
            cumulative_realized_cost += entry_price * float(matched)
            if collect_day_stat:
                close_pct = ((exit_price / entry_price) - 1.0) * 100.0 if entry_price > 0.0 else 0.0
                day_closed_pnl_pct.append(close_pct)
            remaining = lot_qty - matched
            lot["qty"] = int(remaining)
            close_qty -= matched
            if remaining <= 0:
                lots.pop(0)
        if lots:
            lots_by_ticker[ticker] = lots
        else:
            lots_by_ticker.pop(ticker, None)

    row_index = 0
    row_count = len(rows)
    while row_index < row_count:
        row = rows[row_index]
        requested_at = getattr(row, "requested_at", None)
        if not isinstance(requested_at, datetime):
            row_index += 1
            continue
        row_day = requested_at.date()
        if row_day >= since:
            break
        side = str(getattr(row, "side", "BUY") or "BUY").strip().upper()
        status = str(getattr(row, "status", "") or "").strip().upper()
        is_real_fill = _is_real_filled_order_status(status)
        qty = max(0, int(getattr(row, "qty", 0) or 0))
        price = float(getattr(row, "filled_price", None) or getattr(row, "requested_price", None) or 0.0)
        ticker = _normalize_kr_ticker_strict(getattr(row, "ticker", ""))
        current_seed = float(getattr(row, "current_price", None) or getattr(row, "filled_price", None) or getattr(row, "requested_price", None) or price)
        if is_real_fill and qty > 0 and price > 0.0 and ticker:
            if side == "BUY":
                _append_buy_lot(ticker=ticker, qty=qty, entry_price=price, current_price=current_seed)
                cash_balance -= price * float(qty)
            elif side == "SELL":
                _apply_sell_lot(ticker=ticker, qty=qty, exit_price=price, collect_day_stat=False)
                cash_balance += price * float(qty)
        row_index += 1

    cursor = since
    while cursor <= today:
        _reset_day_accumulator()
        while row_index < row_count:
            row = rows[row_index]
            requested_at = getattr(row, "requested_at", None)
            if not isinstance(requested_at, datetime):
                row_index += 1
                continue
            row_day = requested_at.date()
            if row_day < cursor:
                row_index += 1
                continue
            if row_day > cursor:
                break
            day_orders_total += 1
            if day_updated_at is None or requested_at > day_updated_at:
                day_updated_at = requested_at

            side = str(getattr(row, "side", "BUY") or "BUY").strip().upper()
            status = str(getattr(row, "status", "") or "").strip().upper()
            is_real_fill = _is_real_filled_order_status(status)
            qty = max(0, int(getattr(row, "qty", 0) or 0))
            price = float(getattr(row, "filled_price", None) or getattr(row, "requested_price", None) or 0.0)
            ticker = _normalize_kr_ticker_strict(getattr(row, "ticker", ""))
            current_seed = float(getattr(row, "current_price", None) or getattr(row, "filled_price", None) or getattr(row, "requested_price", None) or price)

            if is_real_fill:
                day_filled_total += 1
                if qty > 0 and price > 0.0 and ticker:
                    if side == "BUY":
                        _append_buy_lot(ticker=ticker, qty=qty, entry_price=price, current_price=current_seed)
                        cash_balance -= price * float(qty)
                    elif side == "SELL":
                        _apply_sell_lot(ticker=ticker, qty=qty, exit_price=price, collect_day_stat=True)
                        cash_balance += price * float(qty)
            row_index += 1
        _finalize_day(cursor)
        cursor += timedelta(days=1)

    return list(reversed(items_asc))


def _as_seoul_datetime(value: datetime | None) -> datetime | None:
    if not isinstance(value, datetime):
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=SEOUL)
    return value.astimezone(SEOUL)


def _autotrade_performance_summary(items: list[AutoTradePerformanceItem]) -> AutoTradePerformanceItem | None:
    if not items:
        return None
    latest = items[0]
    orders_total = sum(int(r.orders_total or 0) for r in items)
    filled_total = sum(int(r.filled_total or 0) for r in items)
    buy_amount = float(latest.buy_amount_krw or 0.0)
    eval_amount = float(latest.eval_amount_krw or 0.0)
    realized = float(latest.realized_pnl_krw or 0.0)
    unrealized = float(latest.unrealized_pnl_krw or 0.0)
    roi_pct = float(latest.roi_pct or 0.0)
    win_rate = float(latest.win_rate or 0.0)
    mdd_pct = max(float(r.mdd_pct or 0.0) for r in items)
    total_asset = getattr(latest, "total_asset_krw", None)
    daily_return_pct = getattr(latest, "daily_return_pct", None)
    twr_cum_pct = getattr(latest, "twr_cum_pct", None)
    holding_pnl = getattr(latest, "holding_pnl_krw", None)
    holding_pnl_pct = getattr(latest, "holding_pnl_pct", None)
    today_pnl = getattr(latest, "today_pnl_krw", None)
    today_pnl_pct = getattr(latest, "today_pnl_pct", None)
    updated_candidates = [_as_seoul_datetime(r.updated_at) for r in items]
    updated_candidates = [ts for ts in updated_candidates if ts is not None]
    latest_updated = max(updated_candidates) if updated_candidates else datetime.now(tz=SEOUL)
    return AutoTradePerformanceItem(
        ymd="summary",
        orders_total=orders_total,
        filled_total=filled_total,
        buy_amount_krw=buy_amount,
        eval_amount_krw=eval_amount,
        realized_pnl_krw=realized,
        unrealized_pnl_krw=unrealized,
        roi_pct=roi_pct,
        win_rate=win_rate,
        mdd_pct=mdd_pct,
        total_asset_krw=(float(total_asset) if total_asset is not None else None),
        daily_return_pct=(float(daily_return_pct) if daily_return_pct is not None else None),
        twr_cum_pct=(float(twr_cum_pct) if twr_cum_pct is not None else None),
        holding_pnl_krw=(float(holding_pnl) if holding_pnl is not None else None),
        holding_pnl_pct=(float(holding_pnl_pct) if holding_pnl_pct is not None else None),
        today_pnl_krw=(float(today_pnl) if today_pnl is not None else None),
        today_pnl_pct=(float(today_pnl_pct) if today_pnl_pct is not None else None),
        updated_at=latest_updated,
    )


def _is_meaningful_performance_item(item: AutoTradePerformanceItem | None) -> bool:
    if item is None:
        return False
    if int(getattr(item, "filled_total", 0) or 0) > 0:
        return True
    numeric_fields = (
        "buy_amount_krw",
        "eval_amount_krw",
        "realized_pnl_krw",
        "unrealized_pnl_krw",
        "total_asset_krw",
        "holding_pnl_krw",
        "today_pnl_krw",
    )
    for field in numeric_fields:
        try:
            value = float(getattr(item, field, 0.0) or 0.0)
        except Exception:
            value = 0.0
        if abs(value) > 1e-9:
            return True
    return False


def _has_meaningful_history_before(items: list[AutoTradePerformanceItem], target_ymd: str) -> bool:
    for item in items:
        if str(getattr(item, "ymd", "") or "") == target_ymd:
            continue
        if _is_meaningful_performance_item(item):
            return True
    return False


def _build_live_account_performance_item(
    *,
    snapshots: list[AutoTradeAccountSnapshotResponse],
    ymd: date,
    orders_total: int,
    filled_total: int,
) -> AutoTradePerformanceItem | None:
    live_snaps = [s for s in snapshots if str(getattr(s, "source", "") or "").strip().upper() == "BROKER_LIVE"]
    if not live_snaps:
        return None
    buy_amount = 0.0
    eval_amount = 0.0
    realized = 0.0
    unrealized = 0.0
    total_asset = 0.0
    holding_pnl_sum = 0.0
    today_pnl_sum = 0.0
    prev_asset_sum = 0.0
    pnl_samples: list[float] = []
    updated_candidates: list[datetime] = []

    for snap in live_snaps:
        positions = list(getattr(snap, "positions", []) or [])
        buy_amount += sum(max(0.0, float(getattr(p, "avg_price", 0.0) or 0.0)) * max(0, int(getattr(p, "qty", 0) or 0)) for p in positions)
        if getattr(snap, "stock_eval_krw", None) is not None:
            eval_amount += max(0.0, float(getattr(snap, "stock_eval_krw", 0.0) or 0.0))
        else:
            eval_amount += sum(max(0.0, float(getattr(p, "eval_amount_krw", 0.0) or 0.0)) for p in positions)
        if getattr(snap, "total_asset_krw", None) is not None:
            total_asset += max(0.0, float(getattr(snap, "total_asset_krw", 0.0) or 0.0))
        snap_realized = getattr(snap, "realized_pnl_krw", None)
        snap_unrealized = getattr(snap, "unrealized_pnl_krw", None)
        realized += float(snap_realized or 0.0)
        if snap_unrealized is not None:
            unrealized += float(snap_unrealized or 0.0)
        else:
            unrealized += sum(float(getattr(p, "pnl_amount_krw", 0.0) or 0.0) for p in positions)
        snap_holding_pnl = getattr(snap, "real_eval_pnl_krw", None)
        if snap_holding_pnl is not None:
            holding_pnl_sum += float(snap_holding_pnl or 0.0)
        else:
            holding_pnl_sum += float(snap_unrealized or 0.0)
        snap_today_pnl = getattr(snap, "asset_change_krw", None)
        snap_total_asset = getattr(snap, "total_asset_krw", None)
        if snap_today_pnl is not None:
            today_pnl_sum += float(snap_today_pnl or 0.0)
            if snap_total_asset is not None:
                prev_asset_sum += max(0.0, float(snap_total_asset or 0.0) - float(snap_today_pnl or 0.0))
        for pos in positions:
            try:
                pnl_samples.append(float(getattr(pos, "pnl_pct", 0.0) or 0.0))
            except Exception:
                continue
        updated = _as_seoul_datetime(getattr(snap, "updated_at", None))
        if updated is not None:
            updated_candidates.append(updated)

    total_pnl = realized + unrealized
    roi_pct = ((total_pnl / buy_amount) * 100.0) if buy_amount > 0.0 else 0.0
    holding_pnl_pct = ((holding_pnl_sum / buy_amount) * 100.0) if buy_amount > 0.0 else 0.0
    wins = sum(1 for p in pnl_samples if p > 0.0)
    win_rate = (wins / len(pnl_samples)) if pnl_samples else 0.0
    mdd_pct = abs(min(0.0, min(pnl_samples) if pnl_samples else 0.0))
    phase, _next_open = current_market_phase(datetime.now(tz=SEOUL))
    market_holiday = str(phase or "").strip().upper() == "HOLIDAY"
    effective_today_pnl = 0.0 if market_holiday else float(today_pnl_sum)
    effective_today_pnl_pct = (
        0.0
        if market_holiday
        else ((effective_today_pnl / prev_asset_sum * 100.0) if prev_asset_sum > 0.0 else 0.0)
    )
    updated_at = max(updated_candidates) if updated_candidates else datetime.now(tz=SEOUL)
    return AutoTradePerformanceItem(
        ymd=ymd.isoformat(),
        orders_total=max(0, int(orders_total)),
        filled_total=max(0, int(filled_total)),
        buy_amount_krw=float(buy_amount),
        eval_amount_krw=float(eval_amount),
        realized_pnl_krw=float(realized),
        unrealized_pnl_krw=float(unrealized),
        roi_pct=float(roi_pct),
        win_rate=float(win_rate),
        mdd_pct=float(mdd_pct),
        total_asset_krw=float(total_asset),
        daily_return_pct=float(effective_today_pnl_pct),
        twr_cum_pct=float(effective_today_pnl_pct),
        holding_pnl_krw=float(holding_pnl_sum),
        holding_pnl_pct=float(holding_pnl_pct),
        today_pnl_krw=float(effective_today_pnl),
        today_pnl_pct=float(effective_today_pnl_pct),
        updated_at=updated_at,
    )


def _select_report_row(session, report_date: date, report_type: str, cache_key: str) -> Report | None:
    """
    Read report row with cache_key-first semantics, but remain compatible with legacy
    DBs that still enforce UNIQUE(date,type) and only keep one row per date/type.
    Important: never cross-read another non-empty cache_key (prevents V1/V2 mixing).
    """
    row = session.scalar(
        select(Report)
        .where(Report.type == report_type, Report.date == report_date, Report.cache_key == cache_key)
        .order_by(desc(Report.generated_at))
        .limit(1)
    )
    if row is not None:
        return row
    return session.scalar(
        select(Report)
        .where(
            Report.type == report_type,
            Report.date == report_date,
            or_(Report.cache_key.is_(None), Report.cache_key == ""),
        )
        .order_by(desc(Report.generated_at))
        .limit(1)
    )


def _empty_premarket(report_date: date, status: dict) -> dict:
    return {
        "date": report_date.isoformat(),
        "generated_at": datetime.now(tz=SEOUL).isoformat(),
        "status": status,
        "daytrade_gate": {"on": False, "lookback_days": 20, "gate_metric": 0.0, "gate_on_days": 0, "gate_total_days": 0, "reason": ["대기"]},
        "daytrade_top": [],
        "daytrade_primary": [],
        "daytrade_watch": [],
        "daytrade_top10": [],
        "longterm": [],
        "longterm_top10": [],
        "overlap_bucket": [],
        "themes": [],
        "hard_rules": ["서버에서 보고서 생성 중입니다."],
    }


def _summarize_worker_error(exc: Exception) -> str:
    raw = f"{exc.__class__.__name__}: {exc}".strip()
    raw = re.sub(r"\s+", " ", raw)
    if not raw:
        return "UNKNOWN_ERROR"
    if len(raw) > 180:
        return f"{raw[:177]}..."
    return raw


def _apply_ticker_tags(session, payload: dict) -> None:
    """
    Inject ticker tags at *read-time* so mapping updates don't require regenerating the report.
    Safe to call on any premarket-like payload dict.
    """
    try:
        tickers = [
            x.get("ticker")
            for x in (payload.get("daytrade_top", []) or [])
            + (payload.get("daytrade_watch", []) or [])
            + (payload.get("daytrade_primary", []) or [])
            + (payload.get("daytrade_top10", []) or [])
            + (payload.get("longterm", []) or [])
            + (payload.get("longterm_top10", []) or [])
            if isinstance(x, dict)
        ]
        tmap = get_tags_map(session, [t for t in tickers if isinstance(t, str)])
        for k in ("daytrade_top", "daytrade_watch", "daytrade_primary", "daytrade_top10", "longterm", "longterm_top10"):
            for it in payload.get(k, []) or []:
                if isinstance(it, dict):
                    it["tags"] = tmap.get(str(it.get("ticker") or ""), [])
    except Exception:
        # Tag injection must never break the report path.
        pass


def _has_premarket_recommendations(payload: dict[str, Any] | None) -> bool:
    if not isinstance(payload, dict):
        return False
    for key in ("daytrade_top", "daytrade_primary", "daytrade_watch", "daytrade_top10", "longterm", "longterm_top10"):
        items = payload.get(key)
        if isinstance(items, list) and len(items) > 0:
            return True
    return False


def _latest_nonempty_premarket_payload(session, report_date: date, max_scan: int = 40) -> dict | None:
    rows = session.scalars(
        select(Report)
        .where(Report.type == "PREMARKET", Report.date <= report_date)
        .order_by(desc(Report.date), desc(Report.generated_at))
        .limit(max_scan)
    ).all()
    for row in rows:
        try:
            payload = json.loads(row.payload_json or "{}")
        except Exception:
            continue
        if _has_premarket_recommendations(payload):
            return payload
    return None


def _build_snapshot_fallback_response(
    *,
    report_date: date,
    status_message: str,
    key: str,
    settings_hash: str,
    resolved_algo: str,
) -> PremarketResponse | None:
    with session_scope() as session:
        payload = _latest_nonempty_premarket_payload(session, report_date)
        if payload is None:
            return None
        snapshot_date = str(payload.get("date") or "")
        _apply_ticker_tags(session, payload)

    payload.setdefault("status", {})
    payload["status"]["source"] = "CACHE"
    payload["status"]["message"] = status_message
    payload["status"]["cache_key"] = key
    payload["status"]["settings_hash"] = settings_hash
    payload["status"]["algo_version"] = resolved_algo
    if snapshot_date:
        payload["status"]["snapshot_date"] = snapshot_date
    return PremarketResponse.model_validate(payload)


def _run_worker() -> None:
    while True:
        report_date, settings_payload, settings_hash, daytrade_limit, longterm_limit, algo_version = _QUEUE.get()
        key = _cache_key(report_date, settings_hash, daytrade_limit, longterm_limit, algo_version)
        try:
            strategy = StrategyConfig(
                algo_version=algo_version,
                risk_preset=settings_payload.risk_preset,
                use_custom_weights=settings_payload.use_custom_weights,
                w_ta=settings_payload.w_ta,
                w_re=settings_payload.w_re,
                w_rs=settings_payload.w_rs,
                theme_cap=settings_payload.theme_cap,
                max_gap_pct=settings_payload.max_gap_pct,
                gate_threshold=settings_payload.gate_threshold,
                gate_quantile=settings_payload.gate_quantile,
                gate_lookback=settings.gate_lookback_trading_days,
                gate_M=settings.gate_universe_top_m,
                min_trades_per_day=settings.gate_min_trades_per_day,
                min_value_krw=settings.min_value_krw,
                long_min_value_krw=settings.long_min_value_krw,
            )
            payload, diagnostics = generate_premarket_report(
                report_date,
                strategy,
                daytrade_limit=daytrade_limit,
                longterm_limit=longterm_limit,
            )
            payload.setdefault("status", {})
            payload["status"]["source"] = "LIVE"
            payload["status"]["settings_hash"] = settings_hash
            payload["status"]["cache_key"] = key
            payload["status"]["algo_version"] = algo_version
            with session_scope() as session:
                # cache_key-first lookup + legacy(date,type) fallback for older DBs.
                row = _select_report_row(session, report_date, "PREMARKET", key)
                now_ts = datetime.now(tz=SEOUL)
                if row:
                    row.cache_key = key
                    row.payload_json = json.dumps(payload, ensure_ascii=False)
                    row.generated_at = now_ts
                else:
                    session.add(Report(date=report_date, type="PREMARKET", cache_key=key, payload_json=json.dumps(payload, ensure_ascii=False), generated_at=now_ts))
            with _LOCK:
                _PREMARKET_FAILURES.pop(key, None)
        except Exception as exc:
            err_msg = _summarize_worker_error(exc)
            logger.exception("premarket worker failed: key=%s error=%s", key, err_msg)
            with _LOCK:
                _PREMARKET_FAILURES[key] = (datetime.now(tz=SEOUL), err_msg)
        finally:
            with _LOCK:
                _IN_FLIGHT.discard(key)
            _QUEUE.task_done()


Thread(target=_run_worker, daemon=True).start()


@app.post("/auth/invite", response_model=InviteCreateResponse)
def create_invite(payload: InviteCreateRequest, request: Request, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    requested_code = _normalize_user_code_input(payload.user_code, required=False)
    requested_name = _normalize_user_name(payload.name, required=False)
    with session_scope() as session:
        user_code = requested_code or secrets.token_hex(4)
        while session.scalar(select(User).where(User.user_code == user_code)) is not None:
            user_code = secrets.token_hex(4)
        if payload.password_mode == "MANUAL":
            if not payload.initial_password:
                raise HTTPException(status_code=400, detail="initial_password required")
            initial_password = payload.initial_password
        else:
            initial_password = _generate_temp_password()
        now_ts = now()
        expires_at = now_ts + timedelta(days=max(1, payload.expires_in_days))
        user = User(
            user_code=user_code,
            role=payload.role,
            status=STATUS_ACTIVE,
            password_hash=bcrypt.hash(initial_password),
            force_password_change=True,
            invite_status=INVITE_CREATED,
            expires_at=expires_at,
            memo=payload.memo,
            name=requested_name,
            created_at=now_ts,
            updated_at=now_ts,
            device_binding_enabled=payload.device_binding_enabled,
        )
        session.add(user)
    log_admin_action(
        admin_user_id=admin.id,
        action="INVITE_CREATE",
        detail={"mode": payload.password_mode, "user_code": user_code, "name": requested_name},
    )
    return InviteCreateResponse(user_code=user_code, initial_password=initial_password, expires_at=expires_at, invite_status=INVITE_CREATED)


@app.post("/auth/invite/{user_code}/mark_sent", response_model=InviteMarkSentResponse)
def mark_invite_sent(user_code: str, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        user.invite_status = INVITE_SENT
        user.updated_at = now()
    log_admin_action(admin_user_id=admin.id, action="INVITE_MARK_SENT", detail={"user_code": user_code})
    return InviteMarkSentResponse(invite_status=INVITE_SENT)


def _safe_log_login_event(**kwargs: Any) -> None:
    try:
        log_login_event(**kwargs)
    except OperationalError as exc:
        if _is_sqlite_lock_error(exc):
            logger.warning(
                "login_event_skip_locked user_code=%s result=%s reason=%s",
                kwargs.get("user_code"),
                kwargs.get("result"),
                kwargs.get("reason"),
            )
            return
        raise
    except Exception:
        logger.warning(
            "login_event_skip_error user_code=%s result=%s reason=%s",
            kwargs.get("user_code"),
            kwargs.get("result"),
            kwargs.get("reason"),
            exc_info=True,
        )


@app.post("/auth/login/first", response_model=LoginResponse)
def first_login(payload: FirstLoginRequest, request: Request):
    ip = get_request_ip(request)
    user_agent = get_request_user_agent(request)
    with session_scope() as session:
        user = _get_user_by_code(session, payload.user_code)
        if not user:
            _safe_log_login_event(user=None, user_code=payload.user_code, result="fail", reason=REASON_INVALID_CRED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=401, detail=REASON_INVALID_CRED)
        if user.status == STATUS_BLOCKED:
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_BLOCKED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=403, detail=REASON_BLOCKED)
        if user.status == STATUS_DELETED:
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_DELETED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=403, detail=REASON_DELETED)
        if _check_locked(user):
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_LOCKED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=403, detail=REASON_LOCKED)
        if user.expires_at and user.expires_at < now():
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_EXPIRED_TEMP_PASSWORD, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=403, detail=REASON_EXPIRED_TEMP_PASSWORD)
        if user.device_binding_enabled:
            # device binding enabled -> device_id is mandatory
            if not payload.device_id:
                _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_DEVICE_NOT_ALLOWED, ip=ip, device_id=None, app_version=payload.app_version)
                raise HTTPException(status_code=403, detail=REASON_DEVICE_NOT_ALLOWED)
            if user.bound_device_id and user.bound_device_id != payload.device_id:
                _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_DEVICE_NOT_ALLOWED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
                raise HTTPException(status_code=403, detail=REASON_DEVICE_NOT_ALLOWED)
            if not user.bound_device_id:
                user.bound_device_id = payload.device_id
        if not bcrypt.verify(payload.initial_password, user.password_hash):
            _mark_failed_attempt(session, user)
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_INVALID_CRED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            if _touch_ip_fail(ip):
                raise HTTPException(status_code=429, detail="TOO_MANY_ATTEMPTS")
            raise HTTPException(status_code=401, detail=REASON_INVALID_CRED)
        _reset_failed_attempts(session, user)
        user.last_login_at = now()
        user.updated_at = now()
        _safe_log_login_event(user=user, user_code=user.user_code, result="success", reason=REASON_OK, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
    _clear_ip_fail(ip)
    token_pair = issue_token_pair(
        user,
        device_id=payload.device_id,
        app_version=payload.app_version,
        ip=ip,
        user_agent=user_agent,
    )
    return LoginResponse(
        token=token_pair.access_token,
        expires_at=token_pair.access_expires_at,
        refresh_token=token_pair.refresh_token,
        refresh_expires_at=token_pair.refresh_expires_at,
        force_password_change=True,
        invite_status=user.invite_status,
        role=user.role,
    )


@app.post("/auth/login", response_model=LoginResponse)
def login(payload: LoginRequest, request: Request):
    ip = get_request_ip(request)
    user_agent = get_request_user_agent(request)
    with session_scope() as session:
        user = _get_user_by_code(session, payload.user_code)
        if not user:
            _safe_log_login_event(user=None, user_code=payload.user_code, result="fail", reason=REASON_INVALID_CRED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=401, detail=REASON_INVALID_CRED)
        if user.status == STATUS_BLOCKED:
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_BLOCKED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=403, detail=REASON_BLOCKED)
        if user.status == STATUS_DELETED:
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_DELETED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=403, detail=REASON_DELETED)
        if _check_locked(user):
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_LOCKED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=403, detail=REASON_LOCKED)
        # Temporary password expiry should also block regular login while force_password_change=true.
        if user.force_password_change and user.expires_at and user.expires_at < now():
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_EXPIRED_TEMP_PASSWORD, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            raise HTTPException(status_code=403, detail=REASON_EXPIRED_TEMP_PASSWORD)
        if user.device_binding_enabled:
            if not payload.device_id:
                _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_DEVICE_NOT_ALLOWED, ip=ip, device_id=None, app_version=payload.app_version)
                raise HTTPException(status_code=403, detail=REASON_DEVICE_NOT_ALLOWED)
            if user.bound_device_id and user.bound_device_id != payload.device_id:
                _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_DEVICE_NOT_ALLOWED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
                raise HTTPException(status_code=403, detail=REASON_DEVICE_NOT_ALLOWED)
            if not user.bound_device_id:
                user.bound_device_id = payload.device_id
        if not bcrypt.verify(payload.password, user.password_hash):
            _mark_failed_attempt(session, user)
            _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_INVALID_CRED, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
            if _touch_ip_fail(ip):
                raise HTTPException(status_code=429, detail="TOO_MANY_ATTEMPTS")
            raise HTTPException(status_code=401, detail=REASON_INVALID_CRED)
        _reset_failed_attempts(session, user)
        user.last_login_at = now()
        user.updated_at = now()
        _safe_log_login_event(user=user, user_code=user.user_code, result="success", reason=REASON_OK, ip=ip, device_id=payload.device_id, app_version=payload.app_version)
    _clear_ip_fail(ip)
    token_pair = issue_token_pair(
        user,
        device_id=payload.device_id,
        app_version=payload.app_version,
        ip=ip,
        user_agent=user_agent,
    )
    return LoginResponse(
        token=token_pair.access_token,
        expires_at=token_pair.access_expires_at,
        refresh_token=token_pair.refresh_token,
        refresh_expires_at=token_pair.refresh_expires_at,
        force_password_change=user.force_password_change,
        invite_status=user.invite_status,
        role=user.role,
    )


@app.post("/auth/refresh", response_model=LoginResponse)
def refresh_login(payload: RefreshTokenRequest, request: Request):
    ip = get_request_ip(request)
    user_agent = get_request_user_agent(request)
    token_pair = rotate_refresh_token(
        payload.refresh_token,
        device_id=payload.device_id,
        app_version=payload.app_version,
        ip=ip,
        user_agent=user_agent,
    )
    with session_scope() as session:
        user = session.get(User, token_pair.user_id)
        if user is None:
            raise HTTPException(status_code=401, detail="TOKEN_INVALID")
        if user.status == STATUS_BLOCKED:
            raise HTTPException(status_code=403, detail=REASON_BLOCKED)
        if user.status == STATUS_DELETED:
            raise HTTPException(status_code=403, detail=REASON_DELETED)
        if _check_locked(user):
            raise HTTPException(status_code=403, detail=REASON_LOCKED)
    return LoginResponse(
        token=token_pair.access_token,
        expires_at=token_pair.access_expires_at,
        refresh_token=token_pair.refresh_token,
        refresh_expires_at=token_pair.refresh_expires_at,
        force_password_change=bool(user.force_password_change),
        invite_status=str(user.invite_status),
        role=str(user.role),
    )


@app.get("/auth/me", response_model=UserDetail)
def auth_me(ctx=Depends(get_token_context)):
    user = require_active_user(ctx, allow_force_change=True)
    return UserDetail(
        user_code=user.user_code,
        name=user.name,
        phone=user.phone,
        role=user.role,
        status=user.status,
        last_login_at=user.last_login_at,
        failed_attempts=user.failed_attempts,
        locked_until=user.locked_until,
        invite_status=user.invite_status,
        created_at=user.created_at,
        memo=user.memo,
        force_password_change=user.force_password_change,
        expires_at=user.expires_at,
        device_binding_enabled=user.device_binding_enabled,
        bound_device_id=user.bound_device_id,
    )


@app.get("/auth/menu_permissions", response_model=MenuPermissionsResponse)
def auth_menu_permissions(ctx=Depends(get_token_context)):
    user = require_active_user(ctx, allow_force_change=True)
    with session_scope() as session:
        perms, updated_at, inherited_default = _resolve_user_menu_permissions(session, user.id)
    return MenuPermissionsResponse(
        user_code=user.user_code,
        permissions=perms,
        updated_at=updated_at,
        inherited_default=inherited_default,
    )


@app.post("/device/register", response_model=OkResponse)
def register_device(payload: DeviceRegisterRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx, allow_force_change=True)
    device_id = str(payload.device_id or "").strip()
    if not device_id:
        raise HTTPException(status_code=400, detail="DEVICE_ID_REQUIRED")

    push_pref = payload.pref.model_dump() if payload.pref is not None else {}
    pref_payload = {
        "push_premarket": bool(push_pref.get("push_premarket", True)),
        "push_eod": bool(push_pref.get("push_eod", True)),
        "push_triggers": bool(push_pref.get("push_triggers", True)),
        "user_id": int(user.id),
        "user_code": str(user.user_code),
    }
    token = str(payload.fcm_token or "").strip() or None

    with session_scope() as session:
        row = session.get(Device, device_id)
        now_ts = now()
        if row is None:
            row = Device(
                device_id=device_id,
                fcm_token=token,
                pref_json=json.dumps(pref_payload, ensure_ascii=False),
                created_at=now_ts,
                updated_at=now_ts,
            )
            session.add(row)
        else:
            row.pref_json = json.dumps(pref_payload, ensure_ascii=False)
            # null 토큰 호출(앱 재진입) 시 기존 토큰을 덮어쓰지 않는다.
            if token is not None:
                row.fcm_token = token
            row.updated_at = now_ts
    return OkResponse()


@app.post("/auth/profile", response_model=OkResponse)
def update_profile(payload: ProfileRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx, allow_force_change=True)
    if not payload.consent:
        raise HTTPException(status_code=400, detail="CONSENT_REQUIRED")
    with session_scope() as session:
        row = session.get(User, user.id)
        if not row:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        row.name = payload.name
        row.phone = payload.phone
        if row.invite_status in (INVITE_CREATED, INVITE_SENT):
            row.invite_status = INVITE_ACTIVATED
        row.updated_at = now()
    return OkResponse()


@app.post("/auth/password/change", response_model=LoginResponse)
def change_password(payload: PasswordChangeRequest, request: Request, ctx=Depends(get_token_context)):
    user = require_active_user(ctx, allow_force_change=True)
    ip = get_request_ip(request)
    user_agent = get_request_user_agent(request)
    if not payload.current_password:
        raise HTTPException(status_code=400, detail="CURRENT_PASSWORD_REQUIRED")
    if not bcrypt.verify(payload.current_password, user.password_hash):
        _safe_log_login_event(user=user, user_code=user.user_code, result="fail", reason=REASON_INVALID_CRED, ip=ip, device_id=None, app_version=None)
        raise HTTPException(status_code=401, detail=REASON_INVALID_CRED)
    with session_scope() as session:
        row = session.get(User, user.id)
        if not row:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        row.password_hash = bcrypt.hash(payload.new_password)
        row.force_password_change = False
        # Password is now permanent; clear temp expiry and mark as fully active.
        row.invite_status = INVITE_ACTIVE
        row.expires_at = None
        row.updated_at = now()
    revoke_all_sessions(user.id)
    token_pair = issue_token_pair(
        user,
        device_id=None,
        app_version=None,
        ip=ip,
        user_agent=user_agent,
    )
    return LoginResponse(
        token=token_pair.access_token,
        expires_at=token_pair.access_expires_at,
        refresh_token=token_pair.refresh_token,
        refresh_expires_at=token_pair.refresh_expires_at,
        force_password_change=False,
        invite_status=INVITE_ACTIVE,
        role=user.role,
    )


@app.post("/auth/logout", response_model=OkResponse)
def logout(ctx=Depends(get_token_context)):
    revoke_session(ctx.token_hash)
    return OkResponse()


@app.get("/admin/users", response_model=UsersListResponse)
def list_users(page: int = 1, size: int = 50, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    offset = max(0, (page - 1) * size)
    with session_scope() as session:
        total = session.query(User).count()
        rows = session.query(User).order_by(User.created_at.desc()).offset(offset).limit(size).all()
    items = [
        UserSummary(
            user_code=r.user_code,
            name=r.name,
            phone=r.phone,
            role=r.role,
            status=r.status,
            last_login_at=r.last_login_at,
            failed_attempts=r.failed_attempts,
            locked_until=r.locked_until,
            invite_status=r.invite_status,
            created_at=r.created_at,
        )
        for r in rows
    ]
    return UsersListResponse(items=items, total=total)


@app.get("/admin/users/{user_code}", response_model=UserDetail)
def get_user(user_code: str, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
    return UserDetail(
        user_code=user.user_code,
        name=user.name,
        phone=user.phone,
        role=user.role,
        status=user.status,
        last_login_at=user.last_login_at,
        failed_attempts=user.failed_attempts,
        locked_until=user.locked_until,
        invite_status=user.invite_status,
        created_at=user.created_at,
        memo=user.memo,
        force_password_change=user.force_password_change,
        expires_at=user.expires_at,
        device_binding_enabled=user.device_binding_enabled,
        bound_device_id=user.bound_device_id,
    )


@app.get("/admin/users/{user_code}/menu_permissions", response_model=MenuPermissionsResponse)
def admin_get_user_menu_permissions(user_code: str, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        perms, updated_at, inherited_default = _resolve_user_menu_permissions(session, user.id)
        target_user_code = user.user_code
    return MenuPermissionsResponse(
        user_code=target_user_code,
        permissions=perms,
        updated_at=updated_at,
        inherited_default=inherited_default,
    )


@app.post("/admin/users/{user_code}/menu_permissions", response_model=MenuPermissionsResponse)
def admin_update_user_menu_permissions(
    user_code: str,
    payload: MenuPermissionsPayload,
    ctx=Depends(get_token_context),
):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        before_payload, _, before_inherited_default = _resolve_user_menu_permissions(session, user.id)
        row = _get_user_menu_permission_row(session, user.id)
        now_ts = now()
        if _menu_permissions_is_default(payload):
            if row is not None:
                session.delete(row)
            after_payload = MenuPermissionsPayload()
            updated_at = None
            inherited_default = True
        else:
            if row is None:
                row = UserMenuPermission(
                    user_id=user.id,
                    menu_daytrade=bool(payload.menu_daytrade),
                    menu_autotrade=bool(payload.menu_autotrade),
                    menu_holdings=bool(payload.menu_holdings),
                    menu_supply=bool(payload.menu_supply),
                    menu_movers=bool(payload.menu_movers),
                    menu_us=bool(payload.menu_us),
                    menu_news=bool(payload.menu_news),
                    menu_longterm=bool(payload.menu_longterm),
                    menu_papers=bool(payload.menu_papers),
                    menu_eod=bool(payload.menu_eod),
                    menu_alerts=bool(payload.menu_alerts),
                    created_at=now_ts,
                    updated_at=now_ts,
                )
                session.add(row)
            else:
                row.menu_daytrade = bool(payload.menu_daytrade)
                row.menu_autotrade = bool(payload.menu_autotrade)
                row.menu_holdings = bool(payload.menu_holdings)
                row.menu_supply = bool(payload.menu_supply)
                row.menu_movers = bool(payload.menu_movers)
                row.menu_us = bool(payload.menu_us)
                row.menu_news = bool(payload.menu_news)
                row.menu_longterm = bool(payload.menu_longterm)
                row.menu_papers = bool(payload.menu_papers)
                row.menu_eod = bool(payload.menu_eod)
                row.menu_alerts = bool(payload.menu_alerts)
                row.updated_at = now_ts
            session.flush()
            after_payload = _menu_permissions_payload(row)
            updated_at = row.updated_at
            inherited_default = False
        target_user_code = user.user_code

    log_admin_action(
        admin_user_id=admin.id,
        action="USER_MENU_PERMISSIONS_UPDATE",
        detail={
            "target_user_code": target_user_code,
            "before": before_payload.model_dump(),
            "after": after_payload.model_dump(),
            "before_inherited_default": before_inherited_default,
        },
    )
    return MenuPermissionsResponse(
        user_code=target_user_code,
        permissions=after_payload,
        updated_at=updated_at,
        inherited_default=inherited_default,
    )


@app.get("/admin/my/invited_users", response_model=MyInvitedUsersResponse)
def list_my_invited_users(page: int = 1, size: int = 50, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    page = max(1, int(page))
    size = max(1, min(int(size), 200))
    offset = max(0, (page - 1) * size)

    with session_scope() as session:
        invited_map = _invited_user_codes_by_admin(session, admin.id)
        ordered_codes = list(invited_map.keys())
        paged_codes = ordered_codes[offset: offset + size]
        rows = (
            session.query(User)
            .filter(User.user_code.in_(paged_codes))
            .all()
            if paged_codes
            else []
        )
    by_code = {str(u.user_code): u for u in rows}
    items: list[InvitedUserSummary] = []
    for code in paged_codes:
        user = by_code.get(code)
        if user is None:
            continue
        items.append(
            InvitedUserSummary(
                user_code=user.user_code,
                name=user.name,
                phone=user.phone,
                role=user.role,
                status=user.status,
                last_login_at=user.last_login_at,
                failed_attempts=user.failed_attempts,
                locked_until=user.locked_until,
                invite_status=user.invite_status,
                created_at=user.created_at,
                invited_at=invited_map.get(code),
            )
        )
    return MyInvitedUsersResponse(items=items, total=len(ordered_codes))


@app.post("/admin/users/{user_code}/identity", response_model=UserDetail)
def update_user_identity(user_code: str, payload: UserIdentityUpdateRequest, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    requested_code = _normalize_user_code_input(payload.user_code, required=False)
    requested_name = _normalize_user_name(payload.name, required=False)
    requested_memo = payload.memo.strip() if payload.memo is not None else None
    requested_phone = payload.phone.strip() if payload.phone is not None else None

    if requested_code is None and requested_name is None and requested_memo is None and requested_phone is None:
        raise HTTPException(status_code=400, detail="NO_FIELDS_TO_UPDATE")

    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        before = {
            "user_code": user.user_code,
            "name": user.name,
            "memo": user.memo,
            "phone": user.phone,
        }
        if requested_code and requested_code != user.user_code:
            exists = session.scalar(
                select(User).where(User.user_code == requested_code).limit(1)
            )
            if exists is not None:
                raise HTTPException(status_code=409, detail="USER_CODE_ALREADY_EXISTS")
            user.user_code = requested_code
        if requested_name is not None:
            user.name = requested_name
        if requested_memo is not None:
            user.memo = requested_memo or None
        if requested_phone is not None:
            user.phone = requested_phone or None
        user.updated_at = now()
        session.flush()

        updated = UserDetail(
            user_code=user.user_code,
            name=user.name,
            phone=user.phone,
            role=user.role,
            status=user.status,
            last_login_at=user.last_login_at,
            failed_attempts=user.failed_attempts,
            locked_until=user.locked_until,
            invite_status=user.invite_status,
            created_at=user.created_at,
            memo=user.memo,
            force_password_change=user.force_password_change,
            expires_at=user.expires_at,
            device_binding_enabled=user.device_binding_enabled,
            bound_device_id=user.bound_device_id,
        )

    log_admin_action(
        admin_user_id=admin.id,
        action="USER_IDENTITY_UPDATE",
        detail={
            "target_user_code": user_code,
            "before": before,
            "after": {
                "user_code": updated.user_code,
                "name": updated.name,
                "memo": updated.memo,
                "phone": updated.phone,
            },
        },
    )
    return updated


@app.post("/admin/users/{user_code}/revoke_sessions", response_model=OkResponse)
def revoke_user_sessions(user_code: str, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        target_id = int(user.id)
    revoke_all_sessions(target_id)
    log_admin_action(admin_user_id=admin.id, action="USER_SESSIONS_REVOKE", detail={"user_code": user_code})
    return OkResponse()


@app.get("/admin/users/{user_code}/login_logs", response_model=UserLoginLogsResponse)
def user_login_logs(
    user_code: str,
    page: int = 1,
    size: int = 50,
    ctx=Depends(get_token_context),
):
    admin = require_master(ctx)
    page = max(1, int(page))
    size = max(1, min(int(size), 300))
    offset = max(0, (page - 1) * size)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")

        total = (
            session.query(func.count(LoginEvent.id))
            .filter(LoginEvent.user_code == user.user_code)
            .scalar()
        ) or 0
        rows = (
            session.query(LoginEvent)
            .filter(LoginEvent.user_code == user.user_code)
            .order_by(LoginEvent.ts.desc())
            .offset(offset)
            .limit(size)
            .all()
        )
        success_count = (
            session.query(func.count(LoginEvent.id))
            .filter(LoginEvent.user_code == user.user_code, LoginEvent.result == "success")
            .scalar()
        ) or 0
        fail_count = (
            session.query(func.count(LoginEvent.id))
            .filter(LoginEvent.user_code == user.user_code, LoginEvent.result == "fail")
            .scalar()
        ) or 0
        reason_rows = (
            session.query(LoginEvent.reason_code, func.count(LoginEvent.id))
            .filter(LoginEvent.user_code == user.user_code)
            .group_by(LoginEvent.reason_code)
            .all()
        )
        last_success_at = session.query(func.max(LoginEvent.ts)).filter(
            LoginEvent.user_code == user.user_code,
            LoginEvent.result == "success",
        ).scalar()
        last_fail_at = session.query(func.max(LoginEvent.ts)).filter(
            LoginEvent.user_code == user.user_code,
            LoginEvent.result == "fail",
        ).scalar()
        active_session_count = (
            session.query(func.count(SessionToken.id))
            .filter(
                SessionToken.user_id == user.id,
                SessionToken.revoked_at.is_(None),
                SessionToken.expires_at > now(),
            )
            .scalar()
        ) or 0

        user_summary = UserSummary(
            user_code=user.user_code,
            name=user.name,
            phone=user.phone,
            role=user.role,
            status=user.status,
            last_login_at=user.last_login_at,
            failed_attempts=user.failed_attempts,
            locked_until=user.locked_until,
            invite_status=user.invite_status,
            created_at=user.created_at,
        )
        items = [
            LoginEventItem(
                timestamp=r.ts,
                user_code=r.user_code,
                result=r.result,
                reason_code=r.reason_code,
                ip=r.ip,
                device_id=r.device_id,
                app_version=r.app_version,
            )
            for r in rows
        ]
        summary = UserLoginLogSummary(
            success_count=int(success_count),
            fail_count=int(fail_count),
            reason_counts={str(k): int(v) for k, v in reason_rows},
            last_success_at=last_success_at,
            last_fail_at=last_fail_at,
            active_session_count=int(active_session_count),
        )
        return UserLoginLogsResponse(
            user=user_summary,
            total=int(total),
            items=items,
            summary=summary,
        )


@app.get("/admin/users/{user_code}/autotrade_overview", response_model=AdminUserAutoTradeOverviewResponse)
def user_autotrade_overview(
    user_code: str,
    days: int = Query(30, ge=1, le=365),
    order_size: int = Query(50, ge=1, le=200),
    ctx=Depends(get_token_context),
):
    admin = require_master(ctx)
    since = datetime.now(tz=SEOUL).date() - timedelta(days=max(1, int(days)) - 1)

    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")

        cfg = get_or_create_autotrade_setting(session, user.id)
        settings_payload = _autotrade_settings_payload(cfg)
        broker_status = broker_credential_status(session, user.id)
        broker_payload = _autotrade_broker_payload(broker_status)
        env = _autotrade_env(getattr(cfg, "environment", "paper"))
        account_no_masked = _masked_account_for_env(broker_status, env)
        account = _build_local_account_estimate(
            session,
            user_id=user.id,
            environment=env,
            account_no_masked=account_no_masked,
            cfg=cfg,
            message="ADMIN_VIEW_LOCAL_ESTIMATE",
        )

        perf_rows = (
            session.query(AutoTradeDailyMetric)
            .filter(
                AutoTradeDailyMetric.user_id == user.id,
                AutoTradeDailyMetric.ymd >= since,
            )
            .order_by(AutoTradeDailyMetric.ymd.desc())
            .all()
        )
        performance_items = [_autotrade_metric_item(r) for r in perf_rows]
        performance_summary = None
        if perf_rows:
            orders_total = sum(int(r.orders_total or 0) for r in perf_rows)
            filled_total = sum(int(r.filled_total or 0) for r in perf_rows)
            buy_amount = sum(float(r.buy_amount_krw or 0.0) for r in perf_rows)
            eval_amount = sum(float(r.eval_amount_krw or 0.0) for r in perf_rows)
            realized = sum(float(r.realized_pnl_krw or 0.0) for r in perf_rows)
            unrealized = sum(float(r.unrealized_pnl_krw or 0.0) for r in perf_rows)
            weighted_win_num = sum(float(r.win_rate or 0.0) * max(1, int(r.filled_total or 0)) for r in perf_rows)
            weighted_win_den = sum(max(1, int(r.filled_total or 0)) for r in perf_rows)
            roi_pct = ((realized + unrealized) / buy_amount) * 100.0 if buy_amount > 0.0 else 0.0
            win_rate = weighted_win_num / weighted_win_den if weighted_win_den > 0 else 0.0
            mdd_pct = max(float(r.mdd_pct or 0.0) for r in perf_rows)
            latest_updated = max(r.updated_at for r in perf_rows)
            performance_summary = AutoTradePerformanceItem(
                ymd="summary",
                orders_total=orders_total,
                filled_total=filled_total,
                buy_amount_krw=buy_amount,
                eval_amount_krw=eval_amount,
                realized_pnl_krw=realized,
                unrealized_pnl_krw=unrealized,
                roi_pct=roi_pct,
                win_rate=win_rate,
                mdd_pct=mdd_pct,
                updated_at=latest_updated,
            )

        rules = (
            session.query(AutoTradeSymbolRule)
            .filter(AutoTradeSymbolRule.user_id == user.id)
            .order_by(AutoTradeSymbolRule.updated_at.desc(), AutoTradeSymbolRule.id.desc())
            .limit(50)
            .all()
        )
        symbol_rules = [_autotrade_symbol_rule_item(r) for r in rules]

        recent_orders_total = (
            session.query(func.count(AutoTradeOrder.id))
            .filter(AutoTradeOrder.user_id == user.id)
            .scalar()
        ) or 0
        order_rows = (
            session.query(AutoTradeOrder)
            .filter(AutoTradeOrder.user_id == user.id)
            .order_by(AutoTradeOrder.requested_at.desc())
            .limit(order_size)
            .all()
        )
        recent_orders = [_autotrade_order_item(r) for r in order_rows]

        source_rows = (
            session.query(AutoTradeOrder.source_tab, func.count(AutoTradeOrder.id))
            .filter(AutoTradeOrder.user_id == user.id)
            .group_by(AutoTradeOrder.source_tab)
            .all()
        )
        status_rows = (
            session.query(AutoTradeOrder.status, func.count(AutoTradeOrder.id))
            .filter(AutoTradeOrder.user_id == user.id)
            .group_by(AutoTradeOrder.status)
            .all()
        )

        user_detail = UserDetail(
            user_code=user.user_code,
            name=user.name,
            phone=user.phone,
            role=user.role,
            status=user.status,
            last_login_at=user.last_login_at,
            failed_attempts=user.failed_attempts,
            locked_until=user.locked_until,
            invite_status=user.invite_status,
            created_at=user.created_at,
            memo=user.memo,
            force_password_change=user.force_password_change,
            expires_at=user.expires_at,
            device_binding_enabled=user.device_binding_enabled,
            bound_device_id=user.bound_device_id,
        )

    return AdminUserAutoTradeOverviewResponse(
        user=user_detail,
        settings=settings_payload,
        broker=broker_payload,
        account=account,
        performance_days=int(days),
        performance_summary=performance_summary,
        performance_items=performance_items,
        symbol_rules=symbol_rules,
        recent_orders_total=int(recent_orders_total),
        recent_orders=recent_orders,
        source_counts={str(k or "UNKNOWN"): int(v) for k, v in source_rows},
        status_counts={str(k or "UNKNOWN"): int(v) for k, v in status_rows},
    )


@app.post("/admin/users/{user_code}/block", response_model=OkResponse)
def block_user(user_code: str, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        user.status = STATUS_BLOCKED
        user.updated_at = now()
    revoke_all_sessions(user.id)
    log_admin_action(admin_user_id=admin.id, action="USER_BLOCK", detail={"user_code": user_code})
    return OkResponse()


@app.post("/admin/users/{user_code}/unblock", response_model=OkResponse)
def unblock_user(user_code: str, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        user.status = STATUS_ACTIVE
        user.updated_at = now()
    log_admin_action(admin_user_id=admin.id, action="USER_UNBLOCK", detail={"user_code": user_code})
    return OkResponse()


@app.post("/admin/users/{user_code}/reset_password", response_model=InviteCreateResponse)
def reset_password(user_code: str, payload: PasswordResetRequest, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        if payload.password_mode == "MANUAL":
            if not payload.initial_password:
                raise HTTPException(status_code=400, detail="initial_password required")
            initial_password = payload.initial_password
        else:
            initial_password = _generate_temp_password()
        user.password_hash = bcrypt.hash(initial_password)
        user.force_password_change = True
        user.invite_status = INVITE_CREATED
        user.expires_at = now() + timedelta(days=max(1, payload.expires_in_days))
        user.updated_at = now()
        expires_at = user.expires_at
    revoke_all_sessions(user.id)
    log_admin_action(admin_user_id=admin.id, action="PASSWORD_RESET", detail={"user_code": user_code, "mode": payload.password_mode})
    return InviteCreateResponse(user_code=user_code, initial_password=initial_password, expires_at=expires_at, invite_status=INVITE_CREATED)


@app.post("/admin/users/{user_code}/extend_expiry", response_model=OkResponse)
def extend_expiry(user_code: str, days: int = 7, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        user.expires_at = now() + timedelta(days=max(1, days))
        user.updated_at = now()
    log_admin_action(admin_user_id=admin.id, action="EXPIRES_EXTEND", detail={"user_code": user_code, "days": days})
    return OkResponse()


@app.post("/admin/users/{user_code}/role", response_model=OkResponse)
def change_role(user_code: str, role: str = "USER", ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    if role not in (ROLE_USER, ROLE_MASTER):
        raise HTTPException(status_code=400, detail="INVALID_ROLE")
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        if user.role == ROLE_MASTER and role != ROLE_MASTER:
            # minimal protection: keep at least one master
            masters = session.query(User).filter(User.role == ROLE_MASTER).count()
            if masters <= 1:
                raise HTTPException(status_code=400, detail="MIN_MASTER_REQUIRED")
        user.role = role
        user.updated_at = now()
    log_admin_action(admin_user_id=admin.id, action="ROLE_CHANGE", detail={"user_code": user_code, "role": role})
    return OkResponse()


@app.post("/admin/users/{user_code}/device_binding", response_model=OkResponse)
def toggle_device_binding(user_code: str, enabled: bool = False, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    with session_scope() as session:
        user = _get_user_by_code(session, user_code)
        if not user:
            raise HTTPException(status_code=404, detail="NOT_FOUND")
        user.device_binding_enabled = enabled
        if not enabled:
            user.bound_device_id = None
        user.updated_at = now()
    log_admin_action(admin_user_id=admin.id, action="DEVICE_BIND_TOGGLE", detail={"user_code": user_code, "enabled": enabled})
    return OkResponse()


@app.get("/admin/logs/login", response_model=LogsResponse)
def login_logs(page: int = 1, size: int = 50, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    offset = max(0, (page - 1) * size)
    with session_scope() as session:
        total = session.query(LoginEvent).count()
        rows = session.query(LoginEvent).order_by(LoginEvent.ts.desc()).offset(offset).limit(size).all()
    items = [
        LoginEventItem(
            timestamp=r.ts,
            user_code=r.user_code,
            result=r.result,
            reason_code=r.reason_code,
            ip=r.ip,
            device_id=r.device_id,
            app_version=r.app_version,
        )
        for r in rows
    ]
    return LogsResponse(items=items, total=total)


@app.get("/admin/logs/audit", response_model=AuditLogsResponse)
def audit_logs(page: int = 1, size: int = 50, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    offset = max(0, (page - 1) * size)
    with session_scope() as session:
        total = session.query(AdminAuditLog).count()
        rows = session.query(AdminAuditLog).order_by(AdminAuditLog.ts.desc()).offset(offset).limit(size).all()
    items = [
        AdminAuditItem(
            timestamp=r.ts,
            admin_user_id=r.admin_user_id,
            action=r.action,
            detail=json.loads(r.detail_json or "{}"),
        )
        for r in rows
    ]
    return AuditLogsResponse(items=items, total=total)


@app.get("/admin/push/status", response_model=AdminPushStatusResponse)
def admin_push_status(ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    del admin
    with session_scope() as session:
        all_devices = session.query(Device).order_by(Device.updated_at.desc()).all()
        active_7d_devices = _collect_admin_push_devices(
            session,
            target="active_7d",
            requester_user_id=0,
            requester_session_id=0,
        )
    all_token_count = sum(1 for d in all_devices if str(getattr(d, "fcm_token", "") or "").strip())
    active_token_count = sum(1 for d in active_7d_devices if str(getattr(d, "fcm_token", "") or "").strip())
    return AdminPushStatusResponse(
        push_ready=is_push_ready(),
        all_device_count=len(all_devices),
        all_token_count=all_token_count,
        active_7d_device_count=len(active_7d_devices),
        active_7d_token_count=active_token_count,
    )


@app.post("/admin/push/send", response_model=AdminPushSendResponse)
def admin_push_send(payload: AdminPushSendRequest, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    title = _sanitize_push_text(payload.title, field="title", max_len=60)
    body = _sanitize_push_text(payload.body, field="body", max_len=300)
    route = _sanitize_push_route(payload.route)
    target = str(payload.target or "all").strip().lower()
    alert_type = str(payload.alert_type or "ADMIN").strip().upper()
    dry_run = bool(payload.dry_run)

    if target not in {"all", "active_7d", "test"}:
        raise HTTPException(status_code=400, detail="INVALID_TARGET")
    if alert_type not in {"ADMIN", "UPDATE"}:
        raise HTTPException(status_code=400, detail="INVALID_ALERT_TYPE")

    push_ready = is_push_ready()
    try:
        with session_scope() as session:
            devices = _collect_admin_push_devices(
                session,
                target=target,
                requester_user_id=int(admin.id),
                requester_session_id=int(ctx.session_id),
            )
            push_payload: dict[str, Any] = {
                "type": alert_type,
                "source": "ADMIN_MANUAL",
                "target": target,
            }
            if route:
                push_payload["route"] = route

            dispatch = send_to_devices_and_log(
                session,
                devices=devices,
                alert_type=alert_type,
                title=title,
                body=body,
                payload=push_payload,
                dry_run=dry_run,
                respect_preferences=False,
            )
    except Exception:
        logger.exception("admin push send failed before dispatch")
        return AdminPushSendResponse(
            ok=False,
            target_count=0,
            token_count=0,
            sent_count=0,
            failed_count=0,
            skipped_count=0,
            skipped_no_token_count=0,
            skipped_pref_count=0,
            push_ready=push_ready,
            message="PUSH_SEND_INTERNAL_ERROR",
            sample_tokens_masked=[],
        )

    if dry_run:
        if dispatch.target_count == 0:
            message = "DRY_RUN_NO_TARGET_DEVICE"
            ok = False
        elif dispatch.token_count == 0:
            message = "DRY_RUN_NO_TARGET_TOKEN"
            ok = False
        elif not push_ready:
            message = f"DRY_RUN_FIREBASE_NOT_READY target={dispatch.target_count} token={dispatch.token_count}"
            ok = False
        else:
            message = f"DRY_RUN_OK target={dispatch.target_count} token={dispatch.token_count}"
            ok = True
    elif dispatch.target_count == 0:
        message = "NO_TARGET_DEVICE"
        ok = False
    elif dispatch.token_count == 0:
        message = "NO_TARGET_TOKEN"
        ok = False
    elif not push_ready:
        message = "FIREBASE_NOT_READY"
        ok = False
    elif dispatch.failed_count == 0 and dispatch.sent_count > 0:
        message = "SEND_OK"
        ok = True
    elif dispatch.sent_count > 0:
        message = "SEND_PARTIAL_FAIL"
        ok = False
    else:
        message = "SEND_FAILED"
        ok = False

    log_admin_action(
        admin_user_id=int(admin.id),
        action="ADMIN_PUSH_SEND",
        detail={
            "target": target,
            "alert_type": alert_type,
            "route": route,
            "dry_run": dry_run,
            "title_len": len(title),
            "body_len": len(body),
            "push_ready": push_ready,
            "target_count": dispatch.target_count,
            "token_count": dispatch.token_count,
            "sent_count": dispatch.sent_count,
            "failed_count": dispatch.failed_count,
            "skipped_count": dispatch.skipped_count,
            "skipped_no_token_count": dispatch.skipped_no_token_count,
            "skipped_pref_count": dispatch.skipped_pref_count,
            "sample_tokens_masked": dispatch.sample_tokens_masked[:3],
        },
    )
    return AdminPushSendResponse(
        ok=ok,
        target_count=dispatch.target_count,
        token_count=dispatch.token_count,
        sent_count=dispatch.sent_count,
        failed_count=dispatch.failed_count,
        skipped_count=dispatch.skipped_count,
        skipped_no_token_count=dispatch.skipped_no_token_count,
        skipped_pref_count=dispatch.skipped_pref_count,
        push_ready=push_ready,
        message=message,
        sample_tokens_masked=dispatch.sample_tokens_masked[:3],
    )


@app.get("/reports/premarket", response_model=PremarketResponse)
def get_premarket(
    date: str = Query(...),
    force: bool = Query(False),
    daytrade_limit: int | None = Query(None),
    longterm_limit: int | None = Query(None),
    algo_version: str | None = Query(None),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    report_date = datetime.strptime(date, "%Y-%m-%d").date()
    cached_payload_json = None
    resolved_algo = "V2"
    settings_hash = ""
    key = ""
    allow_daytrade = True
    allow_longterm = True
    with session_scope() as session:
        allow_daytrade = _menu_allowed(session, user.id, _MENU_KEY_DAYTRADE)
        allow_longterm = _menu_allowed(session, user.id, _MENU_KEY_LONGTERM)
        if (not allow_daytrade) and (not allow_longterm):
            raise HTTPException(status_code=403, detail="MENU_FORBIDDEN")
        base_settings = _get_or_create_settings(session)
        resolved_algo = normalize_algo_version(algo_version or base_settings.algo_version)
        settings_payload = base_settings.model_copy(deep=True)
        settings_payload.algo_version = resolved_algo
        settings_hash = settings_payload.compute_hash()
        key = _cache_key(report_date, settings_hash, daytrade_limit, longterm_limit, resolved_algo)
        row = _select_report_row(session, report_date, "PREMARKET", key)
        if row is not None:
            cached_payload_json = row.payload_json
        if row and not force:
            payload = json.loads(row.payload_json)
            _apply_ticker_tags(session, payload)
            _apply_premarket_menu_filter(
                payload,
                allow_daytrade=allow_daytrade,
                allow_longterm=allow_longterm,
            )
            payload.setdefault("status", {})
            payload["status"]["source"] = "CACHE"
            payload["status"]["settings_hash"] = settings_hash
            payload["status"]["cache_key"] = key
            payload["status"]["algo_version"] = resolved_algo
            return PremarketResponse.model_validate(payload)

    status = {
        "source": "FALLBACK",
        "queued": False,
        "message": "보고서 생성 중",
        "cache_key": key,
        "settings_hash": settings_hash,
        "algo_version": resolved_algo,
    }
    with _LOCK:
        failed = _PREMARKET_FAILURES.get(key)
        if (not force) and failed is not None:
            failed_at, fail_msg = failed
            age = datetime.now(tz=SEOUL) - failed_at
            if age < _PREMARKET_RETRY_COOLDOWN:
                wait_sec = max(1, int((_PREMARKET_RETRY_COOLDOWN - age).total_seconds()))
                status = {
                    "source": "FALLBACK",
                    "queued": False,
                    "message": f"보고서 생성 실패: {fail_msg} (재시도 {wait_sec}s 후)",
                    "cache_key": key,
                    "settings_hash": settings_hash,
                    "algo_version": resolved_algo,
                }
            else:
                if key not in _IN_FLIGHT:
                    _IN_FLIGHT.add(key)
                    _PREMARKET_FAILURES.pop(key, None)
                    _QUEUE.put((report_date, settings_payload, settings_hash, daytrade_limit, longterm_limit, resolved_algo))
                    status = {
                        "source": "FALLBACK",
                        "queued": True,
                        "message": "보고서 생성 대기",
                        "cache_key": key,
                        "settings_hash": settings_hash,
                        "algo_version": resolved_algo,
                    }
                else:
                    status = {
                        "source": "FALLBACK",
                        "queued": False,
                        "message": "보고서 생성 중",
                        "cache_key": key,
                        "settings_hash": settings_hash,
                        "algo_version": resolved_algo,
                    }
        elif key not in _IN_FLIGHT:
            _IN_FLIGHT.add(key)
            _PREMARKET_FAILURES.pop(key, None)
            _QUEUE.put((report_date, settings_payload, settings_hash, daytrade_limit, longterm_limit, resolved_algo))
            status = {
                "source": "FALLBACK",
                "queued": True,
                "message": "보고서 생성 대기",
                "cache_key": key,
                "settings_hash": settings_hash,
                "algo_version": resolved_algo,
            }
        else:
            status = {
                "source": "FALLBACK",
                "queued": False,
                "message": "보고서 생성 중",
                "cache_key": key,
                "settings_hash": settings_hash,
                "algo_version": resolved_algo,
            }

    if cached_payload_json is not None:
        payload = json.loads(cached_payload_json)
        # Even in force-refresh fallback, keep tags consistent.
        with session_scope() as session:
            _apply_ticker_tags(session, payload)
        _apply_premarket_menu_filter(
            payload,
            allow_daytrade=allow_daytrade,
            allow_longterm=allow_longterm,
        )
        payload.setdefault("status", {})
        payload["status"]["source"] = "CACHE"
        payload["status"]["message"] = "재생성 요청됨"
        payload["status"]["settings_hash"] = settings_hash
        payload["status"]["cache_key"] = key
        payload["status"]["algo_version"] = resolved_algo
        return PremarketResponse.model_validate(payload)

    snapshot = _build_snapshot_fallback_response(
        report_date=report_date,
        status_message=f"{status.get('message') or '보고서 생성 중'} · 최근 스냅샷 표시",
        key=key,
        settings_hash=settings_hash,
        resolved_algo=resolved_algo,
    )
    if snapshot is not None:
        filtered_payload = snapshot.model_dump()
        _apply_premarket_menu_filter(
            filtered_payload,
            allow_daytrade=allow_daytrade,
            allow_longterm=allow_longterm,
        )
        return PremarketResponse.model_validate(filtered_payload)

    payload = _empty_premarket(report_date, status)
    _apply_premarket_menu_filter(
        payload,
        allow_daytrade=allow_daytrade,
        allow_longterm=allow_longterm,
    )
    return PremarketResponse.model_validate(payload)

@app.get("/eval/monthly", response_model=EvalMonthlyResponse)
def get_eval_monthly(end: str = Query(...), ctx=Depends(get_token_context)):
    require_active_user(ctx)
    with session_scope() as session:
        m = session.scalar(select(EvalMonthly).order_by(desc(EvalMonthly.end_date)).limit(1))
        if not m: return EvalMonthlyResponse(end=end, trades_total=0, win_rate=0.0, avg_r=0.0, expectancy_r=0.0, mdd_r=0.0, gate_on_days_recent=0, gate_metric_recent=0.0, payload={})
        p = json.loads(m.payload_json)
        return EvalMonthlyResponse(end=m.end_date.isoformat(), trades_total=int(p.get("trades_total", 0)), win_rate=float(p.get("win_rate", 0)), avg_r=float(p.get("avg_r", 0)), expectancy_r=float(p.get("expectancy_r", m.expectancy_r)), mdd_r=float(p.get("mdd_r", m.mdd_r)), gate_on_days_recent=int(p.get("gate_on_days_recent", 0)), gate_metric_recent=float(p.get("gate_metric_recent", 0)), payload=p)

@app.get("/quotes/realtime", response_model=RealtimeQuotesResponse)
def get_realtime_quotes(
    tickers: str = Query(...),
    mode: str = Query("full", pattern="^(full|light)$"),
    ctx=Depends(get_token_context),
):
    require_active_user(ctx)
    mode_norm = "light" if str(mode).lower() == "light" else "full"
    quotes = fetch_quotes([x.strip() for x in tickers.split(",") if x.strip()], mode=mode_norm)
    return RealtimeQuotesResponse(as_of=datetime.now(tz=SEOUL), items=[RealtimeQuoteItem(ticker=q.ticker, price=q.price, prev_close=q.prev_close, chg_pct=q.chg_pct, as_of=q.as_of, source=q.source, is_live=q.is_live) for q in quotes])


@app.get("/stock/investor/daily", response_model=StockInvestorDailyResponse)
def get_stock_investor_daily(
    ticker: str = Query(...),
    days: int = Query(60, ge=5, le=180),
    ctx=Depends(get_token_context),
):
    require_active_user(ctx)
    ticker_i = _normalize_kr_ticker_strict(ticker)
    cache_key = f"{ticker_i}:{days}"
    now_ts = datetime.now(tz=SEOUL)
    with _STOCK_FLOW_CACHE_LOCK:
        hit = _INVESTOR_DAILY_CACHE.get(cache_key)
        if hit is not None and (now_ts - hit[0]).total_seconds() <= _INVESTOR_DAILY_TTL_SECONDS:
            return hit[1]

    try:
        live = _build_stock_investor_daily_live(ticker_i, days)
        with _STOCK_FLOW_CACHE_LOCK:
            _INVESTOR_DAILY_CACHE[cache_key] = (datetime.now(tz=SEOUL), live)
        return live
    except Exception as exc:
        logger.exception("stock investor daily fetch failed ticker=%s days=%s", ticker_i, days)
        with _STOCK_FLOW_CACHE_LOCK:
            stale = _INVESTOR_DAILY_CACHE.get(cache_key)
        if stale is not None:
            cached = stale[1].model_copy(deep=True)
            cached.source = "CACHE"
            cached.message = f"실시간 조회 실패로 캐시 표시: {str(exc)[:120]}"
            cached.as_of = datetime.now(tz=SEOUL)
            return cached
        return StockInvestorDailyResponse(
            ticker=ticker_i,
            as_of=datetime.now(tz=SEOUL),
            source="FALLBACK",
            message=f"투자자 수급 조회 실패: {str(exc)[:160]}",
            days=days,
            items=[],
        )


@app.get("/stock/trend/intraday", response_model=StockTrendIntradayResponse)
def get_stock_trend_intraday(
    ticker: str = Query(...),
    limit: int = Query(80, ge=10, le=240),
    ctx=Depends(get_token_context),
):
    require_active_user(ctx)
    ticker_i = _normalize_kr_ticker_strict(ticker)
    cache_key = f"{ticker_i}:{limit}"
    now_ts = datetime.now(tz=SEOUL)
    with _STOCK_FLOW_CACHE_LOCK:
        hit = _INTRADAY_TREND_CACHE.get(cache_key)
        if hit is not None and (now_ts - hit[0]).total_seconds() <= _INTRADAY_TREND_TTL_SECONDS:
            return hit[1]

    try:
        live = _build_stock_trend_intraday_live(ticker_i, limit)
        with _STOCK_FLOW_CACHE_LOCK:
            _INTRADAY_TREND_CACHE[cache_key] = (datetime.now(tz=SEOUL), live)
        return live
    except Exception as exc:
        logger.exception("stock intraday trend fetch failed ticker=%s limit=%s", ticker_i, limit)
        with _STOCK_FLOW_CACHE_LOCK:
            stale = _INTRADAY_TREND_CACHE.get(cache_key)
        if stale is not None:
            cached = stale[1].model_copy(deep=True)
            cached.source = "CACHE"
            cached.message = f"실시간 조회 실패로 캐시 표시: {str(exc)[:120]}"
            cached.as_of = datetime.now(tz=SEOUL)
            return cached
        return StockTrendIntradayResponse(
            ticker=ticker_i,
            as_of=datetime.now(tz=SEOUL),
            source="FALLBACK",
            message=f"시간별 거래동향 조회 실패: {str(exc)[:160]}",
            window_minutes=0,
            items=[],
        )


@app.get("/market/supply", response_model=SupplyResponse)
def get_market_supply(
    count: int = Query(60, ge=10, le=120),
    days: int = Query(20, ge=10, le=90),
    universe_top_value: int = Query(450, ge=150, le=2000),
    universe_top_chg: int = Query(220, ge=80, le=1000),
    markets: str = Query("KOSPI,KOSDAQ"),
    include_contrarian: bool = Query(True),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_SUPPLY)
    mkts = [x.strip() for x in markets.split(",") if x.strip()]
    cache_key = f"{count}:{days}:{universe_top_value}:{universe_top_chg}:{','.join(mkts)}:{int(include_contrarian)}"
    now_ts = datetime.now(tz=SEOUL)
    with _STOCK_FLOW_CACHE_LOCK:
        hit = _SUPPLY_CACHE.get(cache_key)
        if hit is not None and (now_ts - hit[0]).total_seconds() <= _SUPPLY_TTL_SECONDS:
            return hit[1]

    try:
        payload = _compute_supply_live(
            count=count,
            days=days,
            universe_top_value=universe_top_value,
            universe_top_chg=universe_top_chg,
            markets=mkts,
            include_contrarian=include_contrarian,
        )
        try:
            with session_scope() as session_db:
                tickers = [it.ticker for it in payload.items if str(it.ticker or "").strip()]
                tmap = get_tags_map(session_db, [t for t in tickers if isinstance(t, str)])
                for it in payload.items:
                    it.tags = tmap.get(str(it.ticker or ""), [])
        except Exception:
            pass
        last_good_hit: tuple[datetime, SupplyResponse] | None = None
        now_cached = datetime.now(tz=SEOUL)
        with _STOCK_FLOW_CACHE_LOCK:
            _SUPPLY_CACHE[cache_key] = (now_cached, payload)
            if payload.items:
                _SUPPLY_LAST_GOOD_CACHE[cache_key] = (now_cached, payload.model_copy(deep=True))
            else:
                last_good_hit = _SUPPLY_LAST_GOOD_CACHE.get(cache_key)
        if (not payload.items) and last_good_hit is not None:
            age_sec = (now_cached - last_good_hit[0]).total_seconds()
            if age_sec <= _SUPPLY_LAST_GOOD_MAX_AGE_SECONDS and last_good_hit[1].items:
                cached = last_good_hit[1].model_copy(deep=True)
                cached.source = "CACHE"
                cached.message = payload.message or "실시간 후보가 비어 최근 정상 스냅샷을 표시합니다."
                cached.as_of = now_cached
                fallback_note = "실시간 후보 비어 최근 정상 스냅샷으로 대체"
                notes = list(cached.notes or [])
                if fallback_note not in notes:
                    notes.append(fallback_note)
                cached.notes = notes
                logger.warning(
                    "market supply live-empty fallback to last-good cache key=%s age_sec=%.1f",
                    cache_key,
                    age_sec,
                )
                return cached
        return payload
    except Exception as exc:
        logger.exception("market supply fetch failed")
        with _STOCK_FLOW_CACHE_LOCK:
            last_good = _SUPPLY_LAST_GOOD_CACHE.get(cache_key)
            stale = _SUPPLY_CACHE.get(cache_key)
        if last_good is not None:
            age_sec = (datetime.now(tz=SEOUL) - last_good[0]).total_seconds()
            if age_sec <= _SUPPLY_LAST_GOOD_MAX_AGE_SECONDS and last_good[1].items:
                cached = last_good[1].model_copy(deep=True)
                cached.source = "CACHE"
                cached.message = f"실시간 수급 계산 실패로 최근 정상 스냅샷 표시: {str(exc)[:140]}"
                cached.as_of = datetime.now(tz=SEOUL)
                fallback_note = "실시간 계산 실패로 최근 정상 스냅샷 사용"
                notes = list(cached.notes or [])
                if fallback_note not in notes:
                    notes.append(fallback_note)
                cached.notes = notes
                return cached
        if stale is not None:
            cached = stale[1].model_copy(deep=True)
            cached.source = "CACHE"
            cached.message = f"실시간 수급 계산 실패로 캐시 표시: {str(exc)[:140]}"
            cached.as_of = datetime.now(tz=SEOUL)
            return cached
        return SupplyResponse(
            as_of=datetime.now(tz=SEOUL),
            bas_dd="",
            source="FALLBACK",
            message=f"수급 데이터 조회 실패: {str(exc)[:160]}",
            notes=["서버 로그 확인 필요"],
            items=[],
        )


@app.get("/market/movers", response_model=MoversResponse)
def get_market_movers(
    mode: str = Query("chg"),
    period: str = Query("1d"),
    count: int = Query(100, ge=5, le=100),
    pool_size: int = Query(120, ge=30, le=400),
    markets: str = Query("KOSPI,KOSDAQ"),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_MOVERS)
    mkts = [x.strip() for x in markets.split(",") if x.strip()]
    payload = compute_movers(mode=mode, period=period, count=count, pool_size=pool_size, markets=mkts)
    # Attach per-ticker tags if present.
    try:
        with session_scope() as session:
            tickers = [x.get("ticker") for x in payload.get("items", []) if isinstance(x, dict)]
            tmap = get_tags_map(session, [t for t in tickers if isinstance(t, str)])
            for it in payload.get("items", []) or []:
                if isinstance(it, dict):
                    it["tags"] = tmap.get(str(it.get("ticker") or ""), [])
    except Exception:
        pass
    return MoversResponse.model_validate(payload)


@app.get("/market/movers2", response_model=Movers2Response)
def get_market_movers2(
    session: str = Query("regular"),
    direction: str = Query("up"),
    count: int = Query(100, ge=5, le=100),
    universe_top_value: int = Query(500, ge=100, le=2000),
    universe_top_chg: int = Query(200, ge=50, le=1000),
    markets: str = Query("KOSPI,KOSDAQ"),
    fields: str = Query("basic,chart"),
    algo_version: str | None = Query(None),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_MOVERS)
    _ = fields  # reserved for forward-compatible response shaping
    mkts = [x.strip() for x in markets.split(",") if x.strip()]
    with session_scope() as session_db:
        settings_payload = _get_or_create_settings(session_db)
        resolved_algo = normalize_algo_version(algo_version or settings_payload.algo_version)
    payload = compute_movers2(
        session=session,
        direction=direction,
        count=count,
        universe_top_value=universe_top_value,
        universe_top_chg=universe_top_chg,
        markets=mkts,
        algo_version=resolved_algo,
    )
    # Attach per-ticker tags if present.
    try:
        with session_scope() as session_db:
            tickers = [x.get("ticker") for x in payload.get("items", []) if isinstance(x, dict)]
            tmap = get_tags_map(session_db, [t for t in tickers if isinstance(t, str)])
            for it in payload.get("items", []) or []:
                if isinstance(it, dict):
                    it["tags"] = tmap.get(str(it.get("ticker") or ""), [])
    except Exception:
        pass
    return Movers2Response.model_validate(payload)


@app.get("/market/us-insiders", response_model=UsInsiderResponse)
def get_us_insiders(
    target_count: int = Query(10, ge=1, le=30),
    trading_days: int = Query(10, ge=3, le=30),
    expand_days: int = Query(20, ge=3, le=45),
    max_candidates: int = Query(120, ge=20, le=300),
    transaction_codes: str = Query("ALL"),
    base_date: date | None = Query(None),
    force: bool = Query(False),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_US)
    historical_mode = base_date is not None
    effective_expand_days = max(expand_days, trading_days)
    normalized_codes = normalize_transaction_codes(transaction_codes)
    tx_codes_key = ",".join(normalized_codes)
    allow_cross_key_fallback = tx_codes_key == "P,A,M,F"
    ckey = _us_insider_cache_key(
        target_count,
        trading_days,
        effective_expand_days,
        max_candidates,
        tx_codes_key,
        base_date,
    )
    now_ts = datetime.now(tz=SEOUL)

    def _norm_ts(ts: datetime) -> datetime:
        if ts.tzinfo is None:
            return ts.replace(tzinfo=SEOUL)
        return ts

    def _clone_with_note(cached_payload: dict[str, Any], note: str) -> dict[str, Any]:
        replay = dict(cached_payload)
        notes = list(replay.get("notes") or [])
        notes.append(note)
        replay["notes"] = notes
        return replay

    fresh_ttl = timedelta(seconds=max(60, settings.sec_cache_ttl_sec))
    stale_ttl = timedelta(seconds=max(300, settings.sec_cache_stale_sec))

    with _LOCK:
        mem_cached = _US_INSIDER_CACHE.get(ckey)
    db_cached = _load_us_insider_db_cache(ckey)

    if not force:
        for source_name, cached in (("메모리", mem_cached), ("DB", db_cached)):
            if cached is None:
                continue
            cached_at, cached_payload = cached
            cached_at = _norm_ts(cached_at)
            if now_ts - cached_at <= fresh_ttl:
                replay = _clone_with_note(cached_payload, f"캐시 스냅샷({source_name}) 표시 중입니다.")
                with _LOCK:
                    _US_INSIDER_CACHE[ckey] = (cached_at, cached_payload)
                return UsInsiderResponse.model_validate(replay)

    payload = compute_us_insider_screen(
        target_count=target_count,
        trading_days=trading_days,
        expand_days=effective_expand_days,
        max_candidates=max_candidates,
        user_agent=settings.sec_user_agent,
        timeout_sec=settings.sec_timeout_sec,
        github_enrich_enabled=settings.sec_github_enrich_enabled,
        github_enrich_cik_limit=settings.sec_github_enrich_cik_limit,
        github_enrich_max_per_cik=settings.sec_github_enrich_max_per_cik,
        transaction_codes=normalized_codes,
        base_date=base_date,
    )
    returned = int(payload.get("returned_count") or 0)
    shortage = str(payload.get("shortage_reason") or "")
    sec_blocked = ("SEC 요청 제한" in shortage) or ("EDGAR 수집 실패" in shortage)

    # Persist latest scan snapshot regardless of count (for deterministic cache behavior).
    with _LOCK:
        _US_INSIDER_CACHE[ckey] = (datetime.now(tz=SEOUL), payload)
    _upsert_us_insider_db_cache(ckey, payload)

    if returned > 0:
        return UsInsiderResponse.model_validate(payload)

    # If latest scan is blocked/sparse, reuse recent successful cache from memory/DB.
    best_positive: tuple[str, datetime, dict[str, Any]] | None = None
    for source_name, cached in (("메모리", mem_cached), ("DB", db_cached)):
        if cached is None:
            continue
        cached_at, cached_payload = cached
        cached_at = _norm_ts(cached_at)
        if now_ts - cached_at > stale_ttl:
            continue
        cached_returned = int(cached_payload.get("returned_count") or 0)
        if cached_returned <= 0:
            continue
        if best_positive is None or cached_at > best_positive[1]:
            best_positive = (source_name, cached_at, cached_payload)

    # Secondary fallback: allow cross-key recent success snapshots (same endpoint,
    # different query params) to avoid a completely empty screen during sparse periods.
    if (not historical_mode) and allow_cross_key_fallback and best_positive is None:
        with _LOCK:
            mem_all = list(_US_INSIDER_CACHE.values())
        for cached_at, cached_payload in mem_all:
            cached_at = _norm_ts(cached_at)
            if now_ts - cached_at > stale_ttl:
                continue
            if int(cached_payload.get("returned_count") or 0) <= 0:
                continue
            if best_positive is None or cached_at > best_positive[1]:
                best_positive = ("메모리-교차키", cached_at, cached_payload)
        db_any_positive = _load_latest_us_insider_db_positive(limit=20)
        if db_any_positive is not None:
            cached_at, cached_payload = db_any_positive
            cached_at = _norm_ts(cached_at)
            if now_ts - cached_at <= stale_ttl and int(cached_payload.get("returned_count") or 0) > 0:
                if best_positive is None or cached_at > best_positive[1]:
                    best_positive = ("DB-교차키", cached_at, cached_payload)

    if best_positive is not None and (sec_blocked or returned == 0):
        source_name, cached_at, cached_payload = best_positive
        replay = _clone_with_note(cached_payload, f"최신 스캔 결과가 부족해 최근 성공 스냅샷({source_name})을 임시 표시합니다.")
        replay["shortage_reason"] = "최신 스캔 결과 부족으로 최근 성공 스냅샷을 표시합니다."
        with _LOCK:
            _US_INSIDER_CACHE[ckey] = (cached_at, cached_payload)
        return UsInsiderResponse.model_validate(replay)

    return UsInsiderResponse.model_validate(payload)


@app.get("/favorites", response_model=FavoritesResponse)
def get_favorites(ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_EOD)
    with session_scope() as session:
        rows = (
            session.query(UserFavorite)
            .filter(UserFavorite.user_id == user.id)
            .order_by(UserFavorite.favorited_at.desc())
            .all()
        )
    if not rows:
        return FavoritesResponse(items=[])

    kr_tickers = [str(r.ticker).zfill(6) for r in rows if _is_kr_ticker(str(r.ticker))]
    qmap = {q.ticker: q for q in fetch_quotes(kr_tickers)}
    items: list[FavoriteItem] = []
    for r in rows:
        ticker_raw = str(r.ticker)
        ticker = ticker_raw.zfill(6) if _is_kr_ticker(ticker_raw) else ticker_raw
        q = qmap.get(ticker) if _is_kr_ticker(ticker) else None
        current_price = float(q.price) if (q is not None and (q.price or 0.0) > 0.0) else None
        change_pct = None
        if current_price is not None and float(r.baseline_price or 0.0) > 0.0:
            change_pct = ((current_price / float(r.baseline_price)) - 1.0) * 100.0
        items.append(
            FavoriteItem(
                ticker=ticker,
                name=r.name,
                baseline_price=float(r.baseline_price),
                favorited_at=r.favorited_at,
                source_tab=r.source_tab,
                current_price=current_price,
                change_since_favorite_pct=change_pct,
                as_of=(q.as_of if q is not None else None),
                source=(q.source if q is not None else None),
                is_live=(q.is_live if q is not None else None),
            )
        )
    return FavoritesResponse(items=items)


@app.get("/alerts/history", response_model=list[AlertHistoryItem])
def get_alert_history(
    limit: int = Query(50, ge=1, le=100),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_ALERTS)
    with session_scope() as session:
        rows = (
            session.query(Alert)
            .order_by(Alert.ts.desc(), Alert.id.desc())
            .limit(limit)
            .all()
        )
    items: list[AlertHistoryItem] = []
    for row in rows:
        payload_json = row.payload_json or "{}"
        try:
            payload = json.loads(payload_json)
        except Exception:
            payload = {}
        items.append(
            AlertHistoryItem(
                ts=row.ts,
                type=str(row.type or "TRIGGER"),
                title=str(row.title or ""),
                body=str(row.body or ""),
                payload=payload if isinstance(payload, dict) else {},
            )
        )
    return items


@app.get("/stocks/search", response_model=StockSearchResponse)
def search_stocks(
    q: str = Query("", min_length=1, max_length=50),
    limit: int = Query(50, ge=1, le=300),
    ctx=Depends(get_token_context),
):
    require_active_user(ctx)
    keyword = str(q or "").strip().lower()
    if not keyword:
        return StockSearchResponse(count=0, items=[])
    try:
        uni = filter_universe(load_universe())
    except Exception:
        logger.exception("stocks search universe load failed")
        return StockSearchResponse(count=0, items=[])

    items: list[StockSearchItem] = []
    tickers: list[str] = []
    for _, row in uni.iterrows():
        ticker = str(row.get("Code") or "").strip().zfill(6)
        name = str(row.get("Name") or "").strip()
        market = str(row.get("Market") or "").strip().upper()
        if len(ticker) != 6 or not ticker.isdigit() or not name:
            continue
        if keyword not in ticker.lower() and keyword not in name.lower():
            continue
        items.append(StockSearchItem(ticker=ticker, name=name, market=market))
        tickers.append(ticker)
        if len(items) >= limit:
            break
    qmap = {q.ticker: q for q in fetch_quotes(tickers)} if tickers else {}
    enriched: list[StockSearchItem] = []
    for it in items:
        q = qmap.get(it.ticker)
        enriched.append(
            StockSearchItem(
                ticker=it.ticker,
                name=it.name,
                market=it.market,
                current_price=(float(q.price) if q is not None and (q.price or 0.0) > 0.0 else None),
                chg_pct=(float(q.chg_pct) if q is not None and q.chg_pct is not None else None),
            )
        )
    return StockSearchResponse(count=len(enriched), items=enriched)


@app.post("/favorites", response_model=FavoriteItem)
def upsert_favorite(payload: FavoriteUpsertRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_EOD)
    ticker = _normalize_ticker(payload.ticker)
    baseline_price = float(payload.baseline_price or 0.0)
    if baseline_price <= 0.0:
        raise HTTPException(status_code=400, detail="INVALID_BASELINE_PRICE")

    now_ts = now()
    favorited_at = payload.favorited_at or now_ts
    with session_scope() as session:
        row = session.scalar(
            select(UserFavorite).where(UserFavorite.user_id == user.id, UserFavorite.ticker == ticker).limit(1)
        )
        if row is None:
            row = UserFavorite(
                user_id=user.id,
                ticker=ticker,
                name=payload.name,
                baseline_price=baseline_price,
                favorited_at=favorited_at,
                source_tab=payload.source_tab,
                updated_at=now_ts,
            )
            session.add(row)
        else:
            row.name = payload.name or row.name
            row.baseline_price = baseline_price
            row.favorited_at = favorited_at
            row.source_tab = payload.source_tab or row.source_tab
            row.updated_at = now_ts
    _invalidate_autotrade_candidates_cache(user.id)

    q = fetch_quotes([ticker]) if _is_kr_ticker(ticker) else []
    quote = q[0] if q else None
    current_price = float(quote.price) if (quote is not None and (quote.price or 0.0) > 0.0) else None
    change_pct = None
    if current_price is not None and baseline_price > 0.0:
        change_pct = ((current_price / baseline_price) - 1.0) * 100.0
    return FavoriteItem(
        ticker=ticker,
        name=payload.name,
        baseline_price=baseline_price,
        favorited_at=favorited_at,
        source_tab=payload.source_tab,
        current_price=current_price,
        change_since_favorite_pct=change_pct,
        as_of=(quote.as_of if quote is not None else None),
        source=(quote.source if quote is not None else None),
        is_live=(quote.is_live if quote is not None else None),
    )


@app.delete("/favorites/{ticker}", response_model=OkResponse)
def delete_favorite(ticker: str, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_EOD)
    tk = _normalize_ticker(ticker)
    with session_scope() as session:
        row = session.scalar(
            select(UserFavorite).where(UserFavorite.user_id == user.id, UserFavorite.ticker == tk).limit(1)
        )
        if row is not None:
            session.delete(row)
    _invalidate_autotrade_candidates_cache(user.id)
    return OkResponse()


@app.get("/autotrade/settings", response_model=AutoTradeSettingsResponse)
def get_autotrade_settings(ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        row = get_or_create_autotrade_setting(session, user.id)
        return AutoTradeSettingsResponse(
            settings=_autotrade_settings_payload(row),
            updated_at=row.updated_at,
        )


@app.post("/autotrade/settings", response_model=AutoTradeSettingsResponse)
def save_autotrade_settings(payload: AutoTradeSettingsPayload, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    payload = AutoTradeSettingsPayload.validate_ranges(payload)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        row = update_autotrade_setting(session, user.id, payload.model_dump())
        _invalidate_autotrade_account_snapshot_cache(user.id)
        _invalidate_autotrade_candidates_cache(user.id)
        return AutoTradeSettingsResponse(
            settings=_autotrade_settings_payload(row),
            updated_at=row.updated_at,
        )


@app.get("/autotrade/symbol-rules", response_model=AutoTradeSymbolRulesResponse)
def get_autotrade_symbol_rules(ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        rows = (
            session.query(AutoTradeSymbolRule)
            .filter(AutoTradeSymbolRule.user_id == user.id)
            .order_by(AutoTradeSymbolRule.updated_at.desc(), AutoTradeSymbolRule.id.desc())
            .all()
        )
        items = [_autotrade_symbol_rule_item(r) for r in rows]
        return AutoTradeSymbolRulesResponse(count=len(items), items=items)


@app.post("/autotrade/symbol-rules", response_model=AutoTradeSymbolRuleItem)
def upsert_autotrade_symbol_rule(payload: AutoTradeSymbolRuleUpsertPayload, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    ticker = _normalize_ticker(payload.ticker)
    take_profit_pct = float(payload.take_profit_pct)
    stop_loss_pct = abs(float(payload.stop_loss_pct))
    if not (1.0 <= take_profit_pct <= 30.0):
        raise HTTPException(status_code=400, detail="take_profit_pct out of range")
    if not (0.5 <= stop_loss_pct <= 30.0):
        raise HTTPException(status_code=400, detail="stop_loss_pct out of range")
    name = str(payload.name).strip() if payload.name is not None else None
    if name == "":
        name = None
    now_ts = datetime.now(tz=SEOUL)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        row = session.scalar(
            select(AutoTradeSymbolRule)
            .where(
                AutoTradeSymbolRule.user_id == user.id,
                AutoTradeSymbolRule.ticker == ticker,
            )
            .limit(1)
        )
        if row is None:
            row = AutoTradeSymbolRule(
                user_id=user.id,
                ticker=ticker,
                name=name,
                take_profit_pct=take_profit_pct,
                stop_loss_pct=stop_loss_pct,
                enabled=bool(payload.enabled),
                created_at=now_ts,
                updated_at=now_ts,
            )
            session.add(row)
            session.flush()
        else:
            row.name = name
            row.take_profit_pct = take_profit_pct
            row.stop_loss_pct = stop_loss_pct
            row.enabled = bool(payload.enabled)
            row.updated_at = now_ts
        return _autotrade_symbol_rule_item(row)


@app.delete("/autotrade/symbol-rules/{ticker}", response_model=OkResponse)
def delete_autotrade_symbol_rule(ticker: str, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    tk = _normalize_ticker(ticker)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        row = session.scalar(
            select(AutoTradeSymbolRule)
            .where(
                AutoTradeSymbolRule.user_id == user.id,
                AutoTradeSymbolRule.ticker == tk,
            )
            .limit(1)
        )
        if row is not None:
            session.delete(row)
    return OkResponse()


@app.get("/autotrade/broker", response_model=AutoTradeBrokerCredentialResponse)
def get_autotrade_broker_credential(ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        get_or_create_user_broker_credential(session, user.id)
        payload = broker_credential_status(session, user.id)
        return _autotrade_broker_payload(payload)


@app.get("/autotrade/bootstrap", response_model=AutoTradeBootstrapResponse)
def get_autotrade_bootstrap(
    fast: bool = Query(True),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    generated_at = datetime.now(tz=SEOUL)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        cfg = get_or_create_autotrade_setting(session, user.id)

        settings_payload = AutoTradeSettingsResponse(
            settings=_autotrade_settings_payload(cfg),
            updated_at=cfg.updated_at,
        )

        rules_rows = (
            session.query(AutoTradeSymbolRule)
            .filter(AutoTradeSymbolRule.user_id == user.id)
            .order_by(AutoTradeSymbolRule.updated_at.desc(), AutoTradeSymbolRule.id.desc())
            .all()
        )
        symbol_rules = AutoTradeSymbolRulesResponse(
            count=len(rules_rows),
            items=[_autotrade_symbol_rule_item(r) for r in rules_rows],
        )

        broker_status = broker_credential_status(session, user.id)
        broker_payload = _autotrade_broker_payload(broker_status)
        account_payload = _resolve_autotrade_account_snapshot(
            session,
            user_id=user.id,
            cfg=cfg,
            broker_status=broker_status,
            allow_live_fetch=not bool(fast),
        )

        recent_order_size = 80
        total = (
            session.query(func.count(AutoTradeOrder.id))
            .filter(AutoTradeOrder.user_id == user.id)
            .scalar()
        ) or 0
        order_rows = (
            session.query(AutoTradeOrder)
            .filter(AutoTradeOrder.user_id == user.id)
            .order_by(AutoTradeOrder.requested_at.desc())
            .limit(recent_order_size)
            .all()
        )
        orders_payload = AutoTradeOrdersResponse(
            total=int(total),
            items=[_autotrade_order_item(r) for r in order_rows],
        )

        max_orders = int(getattr(cfg, "max_orders_per_run", 20) or 20)
        candidates_prefetch_limit = max(40, min(100, max_orders * 4))
        return AutoTradeBootstrapResponse(
            generated_at=generated_at,
            settings=settings_payload,
            symbol_rules=symbol_rules,
            broker=broker_payload,
            account=account_payload,
            orders=orders_payload,
            candidates_prefetch_limit=candidates_prefetch_limit,
            deferred_sections=["candidates", "performance"],
        )


@app.post("/autotrade/broker", response_model=AutoTradeBrokerCredentialResponse)
def save_autotrade_broker_credential(payload: AutoTradeBrokerCredentialPayload, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        update_user_broker_credential(session, user.id, payload.model_dump())
        status = broker_credential_status(session, user.id)
        _invalidate_autotrade_account_snapshot_cache(user.id)
        return _autotrade_broker_payload(status)


@app.get("/autotrade/candidates", response_model=AutoTradeCandidatesResponse)
def get_autotrade_candidates(
    limit: int = Query(50, ge=1, le=300),
    profile: str = Query("full"),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    diagnostics: dict[str, Any] = {}
    profile_mode = str(profile or "full").strip().lower()
    if profile_mode not in {"full", "initial"}:
        profile_mode = "full"
    effective_limit = limit if profile_mode == "full" else min(limit, 80)

    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        cfg = get_or_create_autotrade_setting(session, user.id)
        cache_key = _autotrade_candidates_cache_key(
            user_id=user.id,
            cfg=cfg,
            limit=effective_limit,
            profile=profile_mode,
        )
        cached = _get_cached_autotrade_candidates(cache_key, profile_mode=profile_mode)
        if cached is not None:
            return cached
        items_raw = build_autotrade_candidates(
            session,
            user.id,
            cfg,
            limit=effective_limit,
            diagnostics=diagnostics,
            profile=profile_mode,
        )
    items: list[AutoTradeCandidateItem] = []
    for x in items_raw:
        source_tab = str(x.get("source_tab") or "UNKNOWN")
        if source_tab not in {"DAYTRADE", "MOVERS", "SUPPLY", "PAPERS", "LONGTERM", "FAVORITES"}:
            source_tab = "UNKNOWN"
        items.append(
            AutoTradeCandidateItem(
                ticker=str(x.get("ticker") or ""),
                name=(str(x.get("name")) if x.get("name") is not None else None),
                source_tab=source_tab,
                signal_price=(float(x.get("signal_price")) if x.get("signal_price") is not None else None),
                current_price=(float(x.get("current_price")) if x.get("current_price") is not None else None),
                chg_pct=(float(x.get("chg_pct")) if x.get("chg_pct") is not None else None),
                note=(str(x.get("note")) if x.get("note") is not None else None),
            )
        )
    response = AutoTradeCandidatesResponse(
        generated_at=datetime.now(tz=SEOUL),
        count=len(items),
        items=items,
        source_counts={str(k): int(v) for k, v in (diagnostics.get("source_counts") or {}).items()},
        warnings=[str(x) for x in (diagnostics.get("warnings") or [])],
    )
    _set_cached_autotrade_candidates(cache_key, response)
    return response


@app.post("/autotrade/run", response_model=AutoTradeRunResponse)
def run_autotrade(payload: AutoTradeRunRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        cfg = get_or_create_autotrade_setting(session, user.id)
        run_env = _autotrade_env(getattr(cfg, "environment", "demo"))
        guard_acquired = False
        if not bool(payload.dry_run):
            guard_acquired = _autotrade_run_guard_acquire(user.id, run_env)
            if not guard_acquired:
                return AutoTradeRunResponse(
                    run_id=secrets.token_hex(6),
                    message="RUN_ALREADY_IN_PROGRESS",
                    queued=False,
                    reservation_id=None,
                    reservation_status=None,
                    reservation_preview_count=0,
                    reservation_preview_items=[],
                    requested_count=0,
                    submitted_count=0,
                    filled_count=0,
                    skipped_count=0,
                    orders=[],
                    metric=None,
                )
        try:
            max_orders = max(1, min(int(payload.limit or cfg.max_orders_per_run), 100))
            preview_limit = max(20, max_orders * 4)
            reservation_preview: list[AutoTradeReservationPreviewItem] = []
            reservation_preview_count = 0
            try:
                preview_candidates = build_autotrade_candidates(
                    session,
                    user.id,
                    cfg,
                    limit=preview_limit,
                    profile="initial",
                )
                reservation_preview_count = len(preview_candidates)
                order_budget = max(0.0, float(getattr(cfg, "order_budget_krw", 0.0) or 0.0))
                order_type = "MARKET" if bool(getattr(cfg, "allow_market_order", False)) else "LIMIT"
                for row in preview_candidates:
                    source_tab = str(row.get("source_tab") or "UNKNOWN").strip().upper()
                    if source_tab not in {"DAYTRADE", "MOVERS", "SUPPLY", "PAPERS", "LONGTERM", "FAVORITES", "RECENT"}:
                        source_tab = "UNKNOWN"
                    plan_price = float(row.get("current_price") or row.get("signal_price") or 0.0)
                    plan_qty = int(order_budget // plan_price) if plan_price > 0.0 else 0
                    plan_amount = (plan_qty * plan_price) if plan_qty > 0 and plan_price > 0.0 else None
                    reservation_preview.append(
                        AutoTradeReservationPreviewItem(
                            ticker=str(row.get("ticker") or ""),
                            name=(str(row.get("name")) if row.get("name") is not None else None),
                            source_tab=source_tab,
                            signal_price=(float(row.get("signal_price")) if row.get("signal_price") is not None else None),
                            current_price=(float(row.get("current_price")) if row.get("current_price") is not None else None),
                            chg_pct=(float(row.get("chg_pct")) if row.get("chg_pct") is not None else None),
                            planned_qty=plan_qty,
                            planned_price=(plan_price if plan_price > 0.0 else None),
                            planned_amount_krw=plan_amount,
                            order_type=order_type,
                            merged_count=1,
                        )
                    )
            except Exception as exc:
                logger.warning("autotrade reservation preview build failed user_id=%s err=%s", user.id, exc)
            if not bool(payload.dry_run):
                phase, _next_open = current_market_phase()
                if phase != "OPEN":
                    if not bool(getattr(cfg, "offhours_reservation_enabled", True)):
                        return AutoTradeRunResponse(
                            run_id=secrets.token_hex(6),
                            message=f"MARKET_{phase}_BLOCKED",
                            queued=False,
                            reservation_id=None,
                            reservation_status=None,
                            reservation_preview_count=reservation_preview_count,
                            reservation_preview_items=reservation_preview,
                            requested_count=0,
                            submitted_count=0,
                            filled_count=0,
                            skipped_count=0,
                            orders=[],
                            metric=None,
                        )
                    if not bool(payload.reserve_if_closed):
                        return AutoTradeRunResponse(
                            run_id=secrets.token_hex(6),
                            message=f"MARKET_{phase}_RESERVATION_AVAILABLE",
                            queued=False,
                            reservation_id=None,
                            reservation_status=None,
                            reservation_preview_count=reservation_preview_count,
                            reservation_preview_items=reservation_preview,
                            requested_count=reservation_preview_count,
                            submitted_count=0,
                            filled_count=0,
                            skipped_count=0,
                            orders=[],
                            metric=None,
                        )
                    reservation, merge_info = enqueue_reservation(
                        session,
                        user_id=user.id,
                        environment=run_env,
                        mode=str(getattr(cfg, "offhours_reservation_mode", "auto") or "auto"),
                        timeout_action=str(getattr(cfg, "offhours_confirm_timeout_action", "cancel") or "cancel"),
                        timeout_min=max(1, min(30, int(getattr(cfg, "offhours_confirm_timeout_min", 3) or 3))),
                        limit=payload.limit,
                        trigger_phase=phase,
                        preview_count=reservation_preview_count,
                        preview_items=[item.model_dump() for item in reservation_preview],
                    )
                    reservation_item = _autotrade_reservation_item(reservation)
                    reserved_message = f"MARKET_{phase}_RESERVED"
                    if bool(merge_info.get("merged")):
                        reserved_message = f"MARKET_{phase}_RESERVATION_MERGED"
                    return AutoTradeRunResponse(
                        run_id=f"reservation-{int(reservation.id)}",
                        message=reserved_message,
                        queued=True,
                        reservation_id=int(reservation.id),
                        reservation_status=str(reservation.status),
                        reservation_merged=bool(merge_info.get("merged")),
                        reservation_merge_requests=int(merge_info.get("merge_request_count") or 1),
                        reservation_preview_count=int(reservation_item.preview_count or 0),
                        reservation_preview_items=list(reservation_item.preview_items or []),
                        requested_count=int(reservation_item.preview_count or 0),
                        submitted_count=0,
                        filled_count=0,
                        skipped_count=0,
                        orders=[],
                        metric=None,
                    )
            user_creds, use_user_creds = resolve_user_kis_credentials(session, user.id)
            result = run_autotrade_once(
                session,
                user_id=user.id,
                cfg=cfg,
                dry_run=bool(payload.dry_run),
                limit=payload.limit,
                broker_credentials=(user_creds if use_user_creds else None),
            )
            orders = [_autotrade_order_item(x) for x in result.created_orders]
            submitted_count = sum(1 for o in result.created_orders if str(o.status) != "SKIPPED")
            filled_count = sum(1 for o in result.created_orders if str(o.status) in {"PAPER_FILLED", "BROKER_FILLED"})
            skipped_count = sum(1 for o in result.created_orders if str(o.status) == "SKIPPED")
            metric = _autotrade_metric_item(result.metric) if result.metric is not None else None
            _invalidate_autotrade_account_snapshot_cache(user.id)
            _invalidate_autotrade_candidates_cache(user.id)
            return AutoTradeRunResponse(
                run_id=result.run_id,
                message=result.message,
                queued=False,
                reservation_id=None,
                reservation_status=None,
                requested_count=len(result.candidates),
                submitted_count=submitted_count,
                filled_count=filled_count,
                skipped_count=skipped_count,
                orders=orders,
                metric=metric,
            )
        finally:
            if guard_acquired:
                _autotrade_run_guard_release(user.id, run_env)


@app.get("/autotrade/reservations", response_model=AutoTradeReservationsResponse)
def get_autotrade_reservations(
    status: str | None = Query(None),
    limit: int = Query(30, ge=1, le=200),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        q = session.query(AutoTradeReservation).filter(AutoTradeReservation.user_id == user.id)
        status_norm = str(status or "").strip().upper()
        if status_norm:
            q = q.filter(AutoTradeReservation.status == status_norm)
        total_count = int(q.count())
        rows = q.order_by(AutoTradeReservation.requested_at.desc(), AutoTradeReservation.id.desc()).limit(limit).all()
        return AutoTradeReservationsResponse(
            total=total_count,
            items=[_autotrade_reservation_item(r) for r in rows],
        )


@app.post("/autotrade/reservations/pending-cancel", response_model=AutoTradeReservationPendingCancelResponse)
def cancel_autotrade_pending_reservations(payload: AutoTradeReservationPendingCancelRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    target_env_raw = str(payload.environment or "").strip().lower()
    if target_env_raw and target_env_raw not in {"paper", "demo", "prod"}:
        raise HTTPException(status_code=400, detail="INVALID_ENVIRONMENT")
    target_env = _autotrade_env(target_env_raw) if target_env_raw else None
    max_count = max(1, min(300, int(payload.max_count or 30)))
    try:
        with session_scope() as session:
            _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
            q = (
                session.query(AutoTradeReservation)
                .filter(
                    AutoTradeReservation.user_id == user.id,
                    AutoTradeReservation.status.in_(["QUEUED", "WAIT_CONFIRM", "RUNNING"]),
                )
            )
            if target_env:
                q = q.filter(AutoTradeReservation.environment == target_env)
            rows = (
                q.order_by(AutoTradeReservation.requested_at.desc(), AutoTradeReservation.id.desc())
                .limit(max_count)
                .all()
            )
            if not rows:
                return AutoTradeReservationPendingCancelResponse(
                    ok=True,
                    requested_count=0,
                    canceled_count=0,
                    failed_count=0,
                    skipped_count=0,
                    canceled_reservation_ids=[],
                    failed_reservation_ids=[],
                    message="취소할 예약 주문이 없습니다.",
                )

            canceled_ids: list[int] = []
            failed_ids: list[int] = []
            skipped_count = 0
            for row in rows:
                reservation_id = int(row.id or 0)
                if reservation_id <= 0:
                    skipped_count += 1
                    continue
                try:
                    before_status = str(row.status or "").upper()
                    result_row = cancel_reservation(session, user_id=user.id, reservation_id=reservation_id)
                    after_status = str(result_row.status or "").upper()
                    if after_status == "CANCELED" and before_status != "CANCELED":
                        canceled_ids.append(reservation_id)
                    else:
                        skipped_count += 1
                except ValueError:
                    failed_ids.append(reservation_id)

            if canceled_ids:
                _invalidate_autotrade_candidates_cache(user.id)
            return AutoTradeReservationPendingCancelResponse(
                ok=True,
                requested_count=len(rows),
                canceled_count=len(canceled_ids),
                failed_count=len(failed_ids),
                skipped_count=int(skipped_count),
                canceled_reservation_ids=canceled_ids,
                failed_reservation_ids=failed_ids,
                message=(
                    f"예약 취소 요청 {len(rows)}건 중 취소 {len(canceled_ids)}건, "
                    f"실패 {len(failed_ids)}건, 스킵 {int(skipped_count)}건"
                ),
            )
    except OperationalError as exc:
        if not _is_sqlite_lock_error(exc):
            raise
        logger.warning(
            "autotrade reservation pending cancel lock user_id=%s env=%s",
            user.id,
            target_env,
            exc_info=True,
        )
        return AutoTradeReservationPendingCancelResponse(
            ok=False,
            requested_count=0,
            canceled_count=0,
            failed_count=0,
            skipped_count=0,
            canceled_reservation_ids=[],
            failed_reservation_ids=[],
            message=_autotrade_db_lock_message("예약 일괄 취소"),
        )


@app.post("/autotrade/reservations/{reservation_id}/confirm", response_model=AutoTradeReservationActionResponse)
def confirm_autotrade_reservation(reservation_id: int, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        try:
            row, run_result = confirm_reservation_execute(session, user_id=user.id, reservation_id=reservation_id)
        except ValueError:
            raise HTTPException(status_code=404, detail="NOT_FOUND")

        response_run = None
        if run_result is not None:
            response_run = AutoTradeRunResponse(
                run_id=str(run_result.get("run_id") or ""),
                message=str(run_result.get("message") or "RESERVATION_EXECUTED"),
                queued=False,
                reservation_id=int(row.id),
                reservation_status=str(row.status),
                requested_count=int(run_result.get("requested_count") or 0),
                submitted_count=int(run_result.get("submitted_count") or 0),
                filled_count=int(run_result.get("filled_count") or 0),
                skipped_count=int(run_result.get("skipped_count") or 0),
                orders=[],
                metric=None,
            )
        _invalidate_autotrade_account_snapshot_cache(user.id)
        _invalidate_autotrade_candidates_cache(user.id)
        return AutoTradeReservationActionResponse(
            ok=True,
            reservation=_autotrade_reservation_item(row),
            run_result=response_run,
        )


@app.post("/autotrade/reservations/{reservation_id}/cancel", response_model=AutoTradeReservationActionResponse)
def cancel_autotrade_reservation(reservation_id: int, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    try:
        with session_scope() as session:
            _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
            try:
                row = cancel_reservation(session, user_id=user.id, reservation_id=reservation_id)
            except ValueError:
                raise HTTPException(status_code=404, detail="NOT_FOUND")
            return AutoTradeReservationActionResponse(
                ok=True,
                reservation=_autotrade_reservation_item(row),
                run_result=None,
                message="예약 주문을 취소했습니다.",
            )
    except OperationalError as exc:
        if not _is_sqlite_lock_error(exc):
            raise
        logger.warning(
            "autotrade reservation cancel lock user_id=%s reservation_id=%s",
            user.id,
            reservation_id,
            exc_info=True,
        )
        return AutoTradeReservationActionResponse(
            ok=False,
            reservation=_load_reservation_item_for_user(user.id, reservation_id),
            run_result=None,
            message=_autotrade_db_lock_message("예약 취소"),
        )


@app.post("/autotrade/reservations/{reservation_id}/items/{ticker}/cancel", response_model=AutoTradeReservationActionResponse)
def cancel_autotrade_reservation_item(reservation_id: int, ticker: str, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    try:
        with session_scope() as session:
            _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
            try:
                row, removed_count = cancel_reservation_preview_item(
                    session,
                    user_id=user.id,
                    reservation_id=reservation_id,
                    ticker=ticker,
                )
            except ValueError as exc:
                code = str(exc).upper()
                if code == "NOT_FOUND":
                    raise HTTPException(status_code=404, detail="NOT_FOUND")
                if code == "STATUS_NOT_CANCELABLE":
                    return AutoTradeReservationActionResponse(
                        ok=False,
                        reservation=_load_reservation_item_for_user(user.id, reservation_id),
                        run_result=None,
                        message="현재 상태에서는 개별 취소가 불가합니다. 예약 대기 상태에서만 취소할 수 있습니다.",
                    )
                if code == "ITEM_NOT_FOUND":
                    return AutoTradeReservationActionResponse(
                        ok=False,
                        reservation=_load_reservation_item_for_user(user.id, reservation_id),
                        run_result=None,
                        message="예약 목록에서 해당 종목을 찾지 못했습니다.",
                    )
                return AutoTradeReservationActionResponse(
                    ok=False,
                    reservation=_load_reservation_item_for_user(user.id, reservation_id),
                    run_result=None,
                    message="예약 종목 개별 취소에 실패했습니다.",
                )
            return AutoTradeReservationActionResponse(
                ok=True,
                reservation=_autotrade_reservation_item(row),
                run_result=None,
                message=f"예약 종목 {int(removed_count)}건을 취소했습니다.",
            )
    except OperationalError as exc:
        if not _is_sqlite_lock_error(exc):
            raise
        logger.warning(
            "autotrade reservation item cancel lock user_id=%s reservation_id=%s ticker=%s",
            user.id,
            reservation_id,
            ticker,
            exc_info=True,
        )
        return AutoTradeReservationActionResponse(
            ok=False,
            reservation=_load_reservation_item_for_user(user.id, reservation_id),
            run_result=None,
            message=_autotrade_db_lock_message("예약 종목 취소"),
        )


@app.get("/autotrade/reentry-blocks", response_model=AutoTradeReentryBlocksResponse)
def get_autotrade_reentry_blocks(
    environment: str | None = Query(None),
    trigger_reason: str | None = Query(None),
    limit: int = Query(200, ge=1, le=500),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        rows = list_active_reentry_blocks(
            session,
            user_id=user.id,
            environment=environment,
            trigger_reason=trigger_reason,
            limit=limit,
        )
        items = [
            AutoTradeReentryBlockItem(
                id=int(row.id),
                environment=str(row.environment or "demo"),
                ticker=str(row.ticker or ""),
                trigger_reason=str(row.trigger_reason or "STOP_LOSS"),
                blocked_at=row.blocked_at or datetime.now(tz=SEOUL),
                released_at=row.released_at,
                note=(str(row.note) if row.note is not None else None),
            )
            for row in rows
        ]
        return AutoTradeReentryBlocksResponse(total=len(items), items=items)


@app.post("/autotrade/reentry-blocks/release", response_model=AutoTradeReentryReleaseResponse)
def release_autotrade_reentry_blocks(payload: AutoTradeReentryReleaseRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        released = release_reentry_blocks(
            session,
            user_id=user.id,
            environment=payload.environment,
            ticker=payload.ticker,
            trigger_reason=payload.trigger_reason,
            release_all=bool(payload.release_all),
        )
        if released > 0:
            _invalidate_autotrade_candidates_cache(user.id)
        return AutoTradeReentryReleaseResponse(
            ok=True,
            released_count=int(released),
            message=("재진입 차단 해제 완료" if released > 0 else "해제할 차단 항목이 없습니다."),
        )


@app.post("/autotrade/manual-buy", response_model=AutoTradeRunResponse)
def run_autotrade_manual_buy(payload: AutoTradeManualBuyRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    ticker = _normalize_kr_ticker_strict(payload.ticker)
    requested_mode = str(payload.mode or "").strip().lower()
    mode = requested_mode if requested_mode in {"demo", "prod"} else "demo"

    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        cfg = get_or_create_autotrade_setting(session, user.id)
        run_id = secrets.token_hex(6)
        now_ts = now()

        quote_list = fetch_quotes([ticker], mode="full")
        quote_map = {str(q.ticker): q for q in quote_list}
        quote = quote_map.get(ticker)
        quote_price = float(quote.price) if quote is not None and float(quote.price or 0.0) > 0.0 else 0.0

        market_order = bool(payload.market_order) if payload.market_order is not None else bool(cfg.allow_market_order)
        input_price = float(payload.request_price) if payload.request_price is not None else 0.0
        request_price = quote_price if market_order else (input_price if input_price > 0.0 else 0.0)
        order_type = "MARKET" if market_order else "LIMIT"

        budget_krw = float(payload.budget_krw) if payload.budget_krw is not None else float(cfg.order_budget_krw or 0.0)
        budget_krw = max(0.0, budget_krw)
        qty = int(payload.qty) if payload.qty is not None else (int(budget_krw // request_price) if request_price > 0.0 else 0)
        qty = max(0, qty)

        if bool(payload.dry_run):
            return AutoTradeRunResponse(
                run_id=run_id,
                message=(
                    f"DRY_RUN_MANUAL_BUY mode={mode} ticker={ticker} "
                    f"qty={qty} price={int(round(request_price))} order_type={order_type}"
                ),
                requested_count=1,
                submitted_count=0,
                filled_count=0,
                skipped_count=0,
                orders=[],
                metric=None,
            )

        status = "SKIPPED"
        reason = None
        broker_order_no = None
        filled_price = None
        filled_at = None
        metadata: dict[str, Any] = {
            "kind": "MANUAL_BUY",
            "source": "DETAIL_CARD",
            "requested_mode": requested_mode or None,
            "mode": mode,
            "environment": mode,
            "ticker": ticker,
            "quote_price": quote_price,
            "request_price": request_price,
            "input_price": input_price if input_price > 0.0 else None,
            "order_type": order_type,
            "market_order": market_order,
            "budget_krw": budget_krw,
            "qty": qty,
        }

        if not market_order and input_price <= 0.0:
            reason = "REQUEST_PRICE_REQUIRED"
        elif request_price <= 0.0:
            reason = "PRICE_UNAVAILABLE"
        elif qty <= 0:
            reason = "QTY_ZERO"
        else:
            pending_guard_sec = max(30, min(3600, int(getattr(settings, "autotrade_pending_order_guard_sec", 300))))
            pending_row = _find_recent_pending_order(
                session,
                user_id=user.id,
                environment=mode,
                side="BUY",
                ticker=ticker,
                within_sec=pending_guard_sec,
            )
            if pending_row is not None:
                reason = "PENDING_BUY_ORDER"
                metadata["pending_guard_sec"] = int(pending_guard_sec)
                metadata["existing_order_id"] = int(getattr(pending_row, "id", 0) or 0)
                metadata["existing_broker_order_no"] = str(getattr(pending_row, "broker_order_no", "") or "")
            else:
                phase, _next_open = current_market_phase(datetime.now(tz=SEOUL))
                if phase != "OPEN":
                    if not bool(getattr(cfg, "offhours_reservation_enabled", True)):
                        reason = f"MARKET_{phase}_BLOCKED"
                    else:
                        reservation = enqueue_manual_order_reservation(
                            session,
                            user_id=user.id,
                            environment=mode,
                            side="BUY",
                            source_tab="DETAIL_CARD",
                            ticker=ticker,
                            name=(str(payload.name).strip() if payload.name is not None and str(payload.name).strip() else None),
                            qty=qty,
                            request_price=request_price,
                            market_order=market_order,
                            order_type=order_type,
                            trigger_phase=phase,
                            timeout_action="auto",
                        )
                        preview_item = AutoTradeReservationPreviewItem(
                            ticker=ticker,
                            name=(str(payload.name).strip() if payload.name is not None and str(payload.name).strip() else None),
                            source_tab="DETAIL_CARD",
                            signal_price=request_price if request_price > 0.0 else None,
                            current_price=request_price if request_price > 0.0 else None,
                            chg_pct=None,
                            planned_qty=qty,
                            planned_price=request_price if request_price > 0.0 else None,
                            planned_amount_krw=(request_price * qty if request_price > 0.0 and qty > 0 else None),
                            order_type=order_type,
                            merged_count=1,
                        )
                        return AutoTradeRunResponse(
                            run_id=f"reservation-{int(reservation.id)}",
                            message=f"MANUAL_BUY_{mode.upper()}:{order_type}:MARKET_{phase}_RESERVED",
                            queued=True,
                            reservation_id=int(reservation.id),
                            reservation_status=str(reservation.status),
                            reservation_merged=False,
                            reservation_merge_requests=1,
                            reservation_preview_count=1,
                            reservation_preview_items=[preview_item],
                            requested_count=1,
                            submitted_count=0,
                            filled_count=0,
                            skipped_count=0,
                            orders=[],
                            metric=None,
                        )
            if reason is None:
                if not settings.kis_trading_enabled:
                    reason = "KIS_TRADING_DISABLED"
                else:
                    user_creds, use_user_creds = resolve_user_kis_credentials(session, user.id)
                    broker = KisBrokerClient(credentials=(user_creds if use_user_creds else None))
                    broker_env = "demo" if mode == "demo" else "prod"
                    if not broker.has_required_config(broker_env):
                        reason = "BROKER_CREDENTIAL_MISSING"
                    else:
                        result = broker.order_cash(
                            env=broker_env,
                            side="buy",
                            ticker=ticker,
                            qty=qty,
                            price=request_price,
                            market_order=market_order,
                        )
                        metadata["broker"] = {
                            "ok": bool(result.ok),
                            "status_code": int(result.status_code),
                            "message": str(result.message or ""),
                        }
                        if result.ok:
                            status = "BROKER_SUBMITTED"
                            broker_order_no = result.order_no
                            reason = str(result.message or "MANUAL_BUY_PROD")
                        else:
                            status = "BROKER_REJECTED"
                            reason = str(result.message or "BROKER_ORDER_FAILED")

        row = AutoTradeOrder(
            user_id=user.id,
            run_id=run_id,
            source_tab="DETAIL_CARD",
            ticker=ticker,
            name=(str(payload.name).strip() if payload.name is not None and str(payload.name).strip() else None),
            side="BUY",
            qty=qty,
            requested_price=request_price,
            filled_price=filled_price,
            current_price=(quote_price if quote_price > 0.0 else (request_price if request_price > 0.0 else None)),
            pnl_pct=None,
            status=status,
            broker_order_no=broker_order_no,
            reason=reason,
            metadata_json=json.dumps(metadata, ensure_ascii=False),
            requested_at=now_ts,
            filled_at=filled_at,
        )
        session.add(row)
        session.flush()

        metric = recompute_daily_metric(session, user.id, datetime.now().date())
        submitted_count = 0 if status == "SKIPPED" else 1
        filled_count = 1 if status in {"PAPER_FILLED", "BROKER_FILLED"} else 0
        skipped_count = 1 if status == "SKIPPED" else 0
        msg_reason = str(reason or status)
        _invalidate_autotrade_account_snapshot_cache(user.id)
        _invalidate_autotrade_candidates_cache(user.id)
        return AutoTradeRunResponse(
            run_id=run_id,
            message=f"MANUAL_BUY_{mode.upper()}:{order_type}:{msg_reason}",
            requested_count=1,
            submitted_count=submitted_count,
            filled_count=filled_count,
            skipped_count=skipped_count,
            orders=[_autotrade_order_item(row)],
            metric=_autotrade_metric_item(metric) if metric is not None else None,
        )


@app.post("/autotrade/manual-sell", response_model=AutoTradeRunResponse)
def run_autotrade_manual_sell(payload: AutoTradeManualSellRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    ticker = _normalize_kr_ticker_strict(payload.ticker)
    requested_mode = str(payload.mode or "").strip().lower()
    mode = requested_mode if requested_mode in {"demo", "prod"} else "demo"

    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        cfg = get_or_create_autotrade_setting(session, user.id)
        run_id = secrets.token_hex(6)
        now_ts = now()

        quote_list = fetch_quotes([ticker], mode="full")
        quote_map = {str(q.ticker): q for q in quote_list}
        quote = quote_map.get(ticker)
        quote_price = float(quote.price) if quote is not None and float(quote.price or 0.0) > 0.0 else 0.0

        market_order = bool(payload.market_order) if payload.market_order is not None else bool(cfg.allow_market_order)
        input_price = float(payload.request_price) if payload.request_price is not None else 0.0
        request_price = quote_price if market_order else (input_price if input_price > 0.0 else quote_price)
        order_type = "MARKET" if market_order else "LIMIT"

        qty = int(payload.qty) if payload.qty is not None else 0
        qty = max(0, qty)

        if bool(payload.dry_run):
            return AutoTradeRunResponse(
                run_id=run_id,
                message=(
                    f"DRY_RUN_MANUAL_SELL mode={mode} ticker={ticker} "
                    f"qty={qty} price={int(round(request_price))} order_type={order_type}"
                ),
                requested_count=1,
                submitted_count=0,
                filled_count=0,
                skipped_count=0,
                orders=[],
                metric=None,
            )

        status = "SKIPPED"
        reason = None
        broker_order_no = None
        filled_price = None
        filled_at = None
        metadata: dict[str, Any] = {
            "kind": "MANUAL_SELL",
            "source": "HOLDINGS",
            "requested_mode": requested_mode or None,
            "mode": mode,
            "environment": mode,
            "ticker": ticker,
            "quote_price": quote_price,
            "request_price": request_price,
            "input_price": input_price if input_price > 0.0 else None,
            "order_type": order_type,
            "market_order": market_order,
            "qty": qty,
        }

        if request_price <= 0.0:
            reason = "PRICE_UNAVAILABLE"
        elif qty <= 0:
            reason = "QTY_ZERO"
        else:
            pending_guard_sec = max(30, min(3600, int(getattr(settings, "autotrade_pending_order_guard_sec", 300))))
            pending_row = _find_recent_pending_order(
                session,
                user_id=user.id,
                environment=mode,
                side="SELL",
                ticker=ticker,
                within_sec=pending_guard_sec,
            )
            if pending_row is not None:
                reason = "PENDING_SELL_ORDER"
                metadata["pending_guard_sec"] = int(pending_guard_sec)
                metadata["existing_order_id"] = int(getattr(pending_row, "id", 0) or 0)
                metadata["existing_broker_order_no"] = str(getattr(pending_row, "broker_order_no", "") or "")
            else:
                phase, _next_open = current_market_phase(datetime.now(tz=SEOUL))
                if phase != "OPEN":
                    if not bool(getattr(cfg, "offhours_reservation_enabled", True)):
                        reason = f"MARKET_{phase}_BLOCKED"
                    else:
                        reservation = enqueue_manual_order_reservation(
                            session,
                            user_id=user.id,
                            environment=mode,
                            side="SELL",
                            source_tab="HOLDINGS",
                            ticker=ticker,
                            name=(str(payload.name).strip() if payload.name is not None and str(payload.name).strip() else None),
                            qty=qty,
                            request_price=request_price,
                            market_order=market_order,
                            order_type=order_type,
                            trigger_phase=phase,
                            timeout_action="auto",
                        )
                        preview_item = AutoTradeReservationPreviewItem(
                            ticker=ticker,
                            name=(str(payload.name).strip() if payload.name is not None and str(payload.name).strip() else None),
                            source_tab="HOLDINGS",
                            signal_price=request_price if request_price > 0.0 else None,
                            current_price=request_price if request_price > 0.0 else None,
                            chg_pct=None,
                            planned_qty=qty,
                            planned_price=request_price if request_price > 0.0 else None,
                            planned_amount_krw=(request_price * qty if request_price > 0.0 and qty > 0 else None),
                            order_type=order_type,
                            merged_count=1,
                        )
                        return AutoTradeRunResponse(
                            run_id=f"reservation-{int(reservation.id)}",
                            message=f"MANUAL_SELL_{mode.upper()}:{order_type}:MARKET_{phase}_RESERVED",
                            queued=True,
                            reservation_id=int(reservation.id),
                            reservation_status=str(reservation.status),
                            reservation_merged=False,
                            reservation_merge_requests=1,
                            reservation_preview_count=1,
                            reservation_preview_items=[preview_item],
                            requested_count=1,
                            submitted_count=0,
                            filled_count=0,
                            skipped_count=0,
                            orders=[],
                            metric=None,
                        )
            if reason is None:
                if not settings.kis_trading_enabled:
                    reason = "KIS_TRADING_DISABLED"
                else:
                    user_creds, use_user_creds = resolve_user_kis_credentials(session, user.id)
                    broker = KisBrokerClient(credentials=(user_creds if use_user_creds else None))
                    broker_env = "demo" if mode == "demo" else "prod"
                    if not broker.has_required_config(broker_env):
                        reason = "BROKER_CREDENTIAL_MISSING"
                    else:
                        result = broker.order_cash(
                            env=broker_env,
                            side="sell",
                            ticker=ticker,
                            qty=qty,
                            price=request_price,
                            market_order=market_order,
                        )
                        metadata["broker"] = {
                            "ok": bool(result.ok),
                            "status_code": int(result.status_code),
                            "message": str(result.message or ""),
                        }
                        if result.ok:
                            status = "BROKER_SUBMITTED"
                            broker_order_no = result.order_no
                            reason = str(result.message or "MANUAL_SELL_PROD")
                        else:
                            status = "BROKER_REJECTED"
                            reason = str(result.message or "BROKER_ORDER_FAILED")

        row = AutoTradeOrder(
            user_id=user.id,
            run_id=run_id,
            source_tab="HOLDINGS",
            ticker=ticker,
            name=(str(payload.name).strip() if payload.name is not None and str(payload.name).strip() else None),
            side="SELL",
            qty=qty,
            requested_price=request_price,
            filled_price=filled_price,
            current_price=(quote_price if quote_price > 0.0 else (request_price if request_price > 0.0 else None)),
            pnl_pct=None,
            status=status,
            broker_order_no=broker_order_no,
            reason=reason,
            metadata_json=json.dumps(metadata, ensure_ascii=False),
            requested_at=now_ts,
            filled_at=filled_at,
        )
        session.add(row)
        session.flush()

        metric = recompute_daily_metric(session, user.id, datetime.now().date())
        submitted_count = 0 if status == "SKIPPED" else 1
        filled_count = 1 if status in {"PAPER_FILLED", "BROKER_FILLED"} else 0
        skipped_count = 1 if status == "SKIPPED" else 0
        msg_reason = str(reason or status)
        _invalidate_autotrade_account_snapshot_cache(user.id)
        _invalidate_autotrade_candidates_cache(user.id)
        return AutoTradeRunResponse(
            run_id=run_id,
            message=f"MANUAL_SELL_{mode.upper()}:{order_type}:{msg_reason}",
            requested_count=1,
            submitted_count=submitted_count,
            filled_count=filled_count,
            skipped_count=skipped_count,
            orders=[_autotrade_order_item(row)],
            metric=_autotrade_metric_item(metric) if metric is not None else None,
        )


@app.post("/autotrade/orders/{order_id}/cancel", response_model=AutoTradeOrderCancelResponse)
def cancel_autotrade_order(
    order_id: int,
    environment: str | None = Query(None),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    fallback_env_raw = str(environment or "").strip().lower()
    if fallback_env_raw and fallback_env_raw not in {"paper", "demo", "prod"}:
        raise HTTPException(status_code=400, detail="INVALID_ENVIRONMENT")
    fallback_env = _autotrade_env(fallback_env_raw) if fallback_env_raw else None
    try:
        with session_scope() as session:
            _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
            seed_row = session.get(AutoTradeOrder, int(order_id))
            if seed_row is None or int(seed_row.user_id) != int(user.id):
                raise HTTPException(status_code=404, detail="NOT_FOUND")

            target_env = _autotrade_order_environment(seed_row) or fallback_env or "demo"
            target_ticker = str(getattr(seed_row, "ticker", "") or "").strip()
            target_side = str(getattr(seed_row, "side", "BUY") or "BUY").strip().upper()
            if target_side not in {"BUY", "SELL"}:
                target_side = "BUY"

            candidate_rows = (
                session.query(AutoTradeOrder)
                .filter(
                    AutoTradeOrder.user_id == user.id,
                    AutoTradeOrder.status == "BROKER_SUBMITTED",
                    AutoTradeOrder.ticker == target_ticker,
                    AutoTradeOrder.side == target_side,
                )
                .order_by(AutoTradeOrder.requested_at.desc(), AutoTradeOrder.id.desc())
                .all()
            )
            target_rows: list[AutoTradeOrder] = []
            target_row_ids: set[int] = set()
            for cand in candidate_rows:
                cand_env = _autotrade_order_environment(cand) or target_env
                if cand_env != target_env:
                    continue
                cid = int(getattr(cand, "id", 0) or 0)
                if cid <= 0 or cid in target_row_ids:
                    continue
                target_rows.append(cand)
                target_row_ids.add(cid)

            seed_id = int(getattr(seed_row, "id", 0) or 0)
            if seed_id > 0 and seed_id not in target_row_ids:
                seed_env = _autotrade_order_environment(seed_row) or target_env
                if (
                    str(getattr(seed_row, "status", "") or "").strip().upper() == "BROKER_SUBMITTED"
                    and seed_env == target_env
                ):
                    target_rows.insert(0, seed_row)
                    target_row_ids.add(seed_id)

            reservation_id: int | None = None
            reservation_status: str | None = None
            canceled_items: list[AutoTradeOrderItem] = []
            failed_items: list[AutoTradeOrderItem] = []
            skipped_count = 0
            closed_count = 0
            reserved_count = 0
            canceled_ids: list[int] = []
            closed_ids: list[int] = []
            market_block_message: str | None = None
            reservation_message: str | None = None
            cancel_sync_limit = max(1, min(200, int(getattr(settings, "autotrade_cancel_sync_limit", 8))))
            cancel_sync_budget_sec = max(5, min(180, int(getattr(settings, "autotrade_cancel_sync_budget_sec", 35))))
            cancel_started_at = perf_counter()

            for index, row in enumerate(target_rows):
                elapsed_sec = perf_counter() - cancel_started_at
                if index >= cancel_sync_limit or elapsed_sec >= float(cancel_sync_budget_sec):
                    reserve_targets = target_rows[index:]
                    if reserve_targets:
                        phase, _next_open = current_market_phase(datetime.now(tz=SEOUL))
                        reserved = enqueue_order_cancel_reservation(
                            session,
                            user_id=user.id,
                            environment=target_env,
                            order_rows=reserve_targets,
                            trigger_phase=phase,
                            timeout_action="cancel",
                        )
                        if reserved is not None:
                            reserved_count += len(reserve_targets)
                            reservation_id = int(reserved.id)
                            reservation_status = str(reserved.status or "QUEUED")
                            reservation_message = (
                                f"대량 접수취소로 즉시 {index}건 처리 후 나머지 {len(reserve_targets)}건을 장중 자동 예약했습니다. "
                                f"예약 #{reservation_id}"
                            )
                        else:
                            for remain in reserve_targets:
                                failed_items.append(_autotrade_order_item(remain))
                    break
                outcome, message = _cancel_broker_submitted_order(
                    session,
                    user_id=user.id,
                    order_row=row,
                    fallback_environment=target_env,
                )
                if outcome == "canceled":
                    canceled_items.append(_autotrade_order_item(row))
                    rid = int(getattr(row, "id", 0) or 0)
                    if rid > 0:
                        canceled_ids.append(rid)
                    continue
                if outcome == "closed":
                    closed_count += 1
                    rid = int(getattr(row, "id", 0) or 0)
                    if rid > 0:
                        closed_ids.append(rid)
                    continue
                if outcome == "skipped" or str(message or "").strip().upper() in {"NOT_PENDING_ORDER"}:
                    skipped_count += 1
                    continue
                if _is_market_time_block_message(message):
                    market_block_message = str(message or "").strip()
                    reserve_targets = [row] + target_rows[index + 1 :]
                    phase, _next_open = current_market_phase(datetime.now(tz=SEOUL))
                    reserved = enqueue_order_cancel_reservation(
                        session,
                        user_id=user.id,
                        environment=target_env,
                        order_rows=reserve_targets,
                        trigger_phase=phase,
                        timeout_action="cancel",
                    )
                    if reserved is not None:
                        reserved_count = len(reserve_targets)
                        reservation_id = int(reserved.id)
                        reservation_status = str(reserved.status or "QUEUED")
                        phase_label = _market_phase_user_label(phase)
                        reservation_message = (
                            f"{phase_label}이라 접수취소 {reserved_count}건을 장중 자동 예약했습니다. 예약 #{reservation_id}"
                        )
                    else:
                        failed_items.append(_autotrade_order_item(row))
                        for remain in target_rows[index + 1 :]:
                            failed_items.append(_autotrade_order_item(remain))
                    break
                failed_items.append(_autotrade_order_item(row))

            if canceled_items or closed_count > 0:
                _invalidate_autotrade_account_snapshot_cache(user.id)
                _invalidate_autotrade_candidates_cache(user.id)

            message_parts: list[str] = []
            if reservation_message:
                message_parts.append(reservation_message)
            elif market_block_message:
                message_parts.append(market_block_message)
            message_parts.append(
                f"{target_ticker} 접수취소 요청 {len(target_rows)}건 중 취소 {len(canceled_items)}건, 상태정리 {closed_count}건, 예약 {reserved_count}건, 실패 {len(failed_items)}건, 스킵 {int(skipped_count)}건"
            )
            ok = len(failed_items) == 0

            return AutoTradeOrderCancelResponse(
                ok=bool(ok),
                order=_autotrade_order_item(seed_row),
                scope="symbol",
                requested_count=len(target_rows),
                canceled_count=len(canceled_items),
                closed_count=int(closed_count),
                reserved_count=int(reserved_count),
                failed_count=len(failed_items),
                skipped_count=int(skipped_count),
                canceled_order_ids=canceled_ids,
                closed_order_ids=closed_ids,
                reservation_id=reservation_id,
                reservation_status=reservation_status,
                message=" ".join(part for part in message_parts if str(part or "").strip()).strip(),
            )
    except OperationalError as exc:
        if not _is_sqlite_lock_error(exc):
            raise
        logger.warning(
            "autotrade order cancel lock user_id=%s order_id=%s",
            user.id,
            order_id,
            exc_info=True,
        )
        return AutoTradeOrderCancelResponse(
            ok=False,
            order=_load_order_item_for_user(user.id, order_id),
            scope="symbol",
            requested_count=0,
            canceled_count=0,
            closed_count=0,
            reserved_count=0,
            failed_count=0,
            skipped_count=0,
            canceled_order_ids=[],
            closed_order_ids=[],
            reservation_id=None,
            reservation_status=None,
            message=_autotrade_db_lock_message("접수 취소"),
        )


@app.post("/autotrade/orders/pending-cancel", response_model=AutoTradePendingCancelResponse)
def cancel_autotrade_pending_orders(payload: AutoTradePendingCancelRequest, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    target_env_raw = str(payload.environment or "").strip().lower()
    if target_env_raw and target_env_raw not in {"paper", "demo", "prod"}:
        raise HTTPException(status_code=400, detail="INVALID_ENVIRONMENT")
    target_env = _autotrade_env(target_env_raw) if target_env_raw else None
    max_count = max(1, min(300, int(payload.max_count or 20)))
    try:
        with session_scope() as session:
            _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
            rows = (
                session.query(AutoTradeOrder)
                .filter(
                    AutoTradeOrder.user_id == user.id,
                    AutoTradeOrder.status == "BROKER_SUBMITTED",
                )
                .order_by(AutoTradeOrder.requested_at.desc(), AutoTradeOrder.id.desc())
                .limit(max_count * 6)
                .all()
            )
            target_rows: list[AutoTradeOrder] = []
            for row in rows:
                env = _autotrade_order_environment(row)
                if target_env and env != target_env:
                    continue
                target_rows.append(row)
                if len(target_rows) >= max_count:
                    break

            canceled_items: list[AutoTradeOrderItem] = []
            failed_items: list[AutoTradeOrderItem] = []
            skipped_count = 0
            closed_count = 0
            reserved_count = 0
            reservation_id: int | None = None
            db_lock_abort = False
            market_block_message: str | None = None
            reservation_message: str | None = None
            cancel_sync_limit = max(1, min(200, int(getattr(settings, "autotrade_cancel_sync_limit", 8))))
            cancel_sync_budget_sec = max(5, min(180, int(getattr(settings, "autotrade_cancel_sync_budget_sec", 35))))
            cancel_started_at = perf_counter()
            for index, row in enumerate(target_rows):
                elapsed_sec = perf_counter() - cancel_started_at
                if index >= cancel_sync_limit or elapsed_sec >= float(cancel_sync_budget_sec):
                    reserve_targets = target_rows[index:]
                    if reserve_targets:
                        reserve_env = target_env or _autotrade_order_environment(row) or "demo"
                        phase, _next_open = current_market_phase(datetime.now(tz=SEOUL))
                        reserved = enqueue_order_cancel_reservation(
                            session,
                            user_id=user.id,
                            environment=reserve_env,
                            order_rows=reserve_targets,
                            trigger_phase=phase,
                            timeout_action="cancel",
                        )
                        if reserved is not None:
                            reserved_count += len(reserve_targets)
                            reservation_id = int(reserved.id)
                            phase_label = _market_phase_user_label(phase)
                            reservation_message = (
                                f"대량 접수취소로 즉시 {index}건 처리 후 나머지 {len(reserve_targets)}건을 "
                                f"{phase_label} 자동 예약했습니다. 예약 #{reservation_id}"
                            )
                        else:
                            for remain in reserve_targets:
                                failed_items.append(_autotrade_order_item(remain))
                    break
                try:
                    outcome, _message = _cancel_broker_submitted_order(
                        session,
                        user_id=user.id,
                        order_row=row,
                        fallback_environment=target_env,
                    )
                except OperationalError as exc:
                    if not _is_sqlite_lock_error(exc):
                        raise
                    session.rollback()
                    db_lock_abort = True
                    logger.warning(
                        "autotrade pending cancel lock user_id=%s order_id=%s",
                        user.id,
                        row.id,
                        exc_info=True,
                    )
                    break
                if outcome == "canceled":
                    canceled_items.append(_autotrade_order_item(row))
                elif outcome == "closed":
                    closed_count += 1
                else:
                    row_item = _autotrade_order_item(row)
                    if outcome == "skipped" or str(_message or "").strip().upper() in {"NOT_PENDING_ORDER"}:
                        skipped_count += 1
                    else:
                        if _is_market_time_block_message(_message):
                            market_block_message = str(_message or "").strip()
                            reserve_targets = [row] + target_rows[index + 1 :]
                            reserve_env = target_env or _autotrade_order_environment(row) or "demo"
                            phase, _next_open = current_market_phase(datetime.now(tz=SEOUL))
                            reserved = enqueue_order_cancel_reservation(
                                session,
                                user_id=user.id,
                                environment=reserve_env,
                                order_rows=reserve_targets,
                                trigger_phase=phase,
                                timeout_action="cancel",
                            )
                            if reserved is not None:
                                reserved_count = len(reserve_targets)
                                reservation_id = int(reserved.id)
                                phase_label = _market_phase_user_label(phase)
                                reservation_message = (
                                    f"{phase_label}이라 접수취소 {reserved_count}건을 장중 자동 예약했습니다. 예약 #{reservation_id}"
                                )
                            else:
                                failed_items.append(row_item)
                                for remain in target_rows[index + 1 :]:
                                    failed_items.append(_autotrade_order_item(remain))
                            break
                        failed_items.append(row_item)

            if db_lock_abort:
                return AutoTradePendingCancelResponse(
                    ok=False,
                    requested_count=len(target_rows),
                    canceled_count=0,
                    closed_count=0,
                    reserved_count=0,
                    reservation_id=None,
                    failed_count=0,
                    skipped_count=0,
                    canceled_orders=[],
                    failed_orders=[],
                    message=_autotrade_db_lock_message("일괄 접수 취소"),
                )

            if canceled_items or closed_count > 0:
                _invalidate_autotrade_account_snapshot_cache(user.id)
                _invalidate_autotrade_candidates_cache(user.id)

            message_parts: list[str] = []
            if reservation_message:
                message_parts.append(reservation_message)
            elif market_block_message:
                message_parts.append(market_block_message)
            message_parts.append(
                f"접수취소 요청 {len(target_rows)}건 중 취소 {len(canceled_items)}건, 상태정리 {closed_count}건, 예약 {reserved_count}건, 실패 {len(failed_items)}건, 스킵 {int(skipped_count)}건"
            )
            return AutoTradePendingCancelResponse(
                ok=True,
                requested_count=len(target_rows),
                canceled_count=len(canceled_items),
                closed_count=int(closed_count),
                reserved_count=int(reserved_count),
                reservation_id=reservation_id,
                failed_count=len(failed_items),
                skipped_count=int(skipped_count),
                canceled_orders=canceled_items,
                failed_orders=failed_items,
                message=" ".join(part for part in message_parts if str(part or "").strip()).strip(),
            )
    except OperationalError as exc:
        if not _is_sqlite_lock_error(exc):
            raise
        logger.warning(
            "autotrade pending cancel lock user_id=%s env=%s",
            user.id,
            target_env,
            exc_info=True,
        )
        return AutoTradePendingCancelResponse(
            ok=False,
            requested_count=0,
            canceled_count=0,
            closed_count=0,
            reserved_count=0,
            reservation_id=None,
            failed_count=0,
            skipped_count=0,
            canceled_orders=[],
            failed_orders=[],
            message=_autotrade_db_lock_message("일괄 접수 취소"),
        )


@app.get("/autotrade/orders", response_model=AutoTradeOrdersResponse)
def get_autotrade_orders(
    page: int = Query(1, ge=1, le=1000),
    size: int = Query(50, ge=1, le=300),
    environment: str | None = Query(None),
    status: str | None = Query(None),
    ticker: str | None = Query(None),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    offset = max(0, (page - 1) * size)
    env_filter = str(environment or "").strip().lower()
    if env_filter and env_filter not in {"paper", "demo", "prod"}:
        raise HTTPException(status_code=400, detail="INVALID_ENVIRONMENT")
    status_filter = str(status or "").strip().upper()
    ticker_filter = "".join(ch for ch in str(ticker or "").strip() if ch.isdigit())
    if ticker_filter:
        ticker_filter = ticker_filter.zfill(6)[-6:]
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        base_query = (
            session.query(AutoTradeOrder)
            .filter(AutoTradeOrder.user_id == user.id)
        )
        if status_filter:
            base_query = base_query.filter(AutoTradeOrder.status == status_filter)
        if ticker_filter:
            base_query = base_query.filter(AutoTradeOrder.ticker == ticker_filter)
        ordered_rows = base_query.order_by(AutoTradeOrder.requested_at.desc(), AutoTradeOrder.id.desc()).all()
        if env_filter:
            ordered_rows = [row for row in ordered_rows if (_autotrade_order_environment(row) or "") == env_filter]
        total = len(ordered_rows)
        rows = ordered_rows[offset : offset + size]
        items = [_autotrade_order_item(r) for r in rows]
        return AutoTradeOrdersResponse(total=int(total), items=items)


@app.get("/autotrade/performance", response_model=AutoTradePerformanceResponse)
def get_autotrade_performance(
    days: int = Query(30, ge=1, le=365),
    environment: str | None = Query(None),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    env_filter = str(environment or "").strip().lower()
    if env_filter and env_filter not in {"demo", "prod"}:
        raise HTTPException(status_code=400, detail="INVALID_ENVIRONMENT")
    since = datetime.now(tz=SEOUL).date() - timedelta(days=max(1, days) - 1)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        cfg = get_or_create_autotrade_setting(session, user.id)
        items = _build_autotrade_performance_items_from_orders(
            session,
            user_id=int(user.id),
            since=since,
            environment=(env_filter or None),
            seed_krw=float(getattr(cfg, "seed_krw", 0.0) or 0.0),
        )
        summary = _autotrade_performance_summary(items)
        needs_live_fallback = (
            summary is None
            or (
                float(summary.buy_amount_krw or 0.0) <= 0.0
                and float(summary.eval_amount_krw or 0.0) <= 0.0
                and float(summary.unrealized_pnl_krw or 0.0) == 0.0
            )
        )
        if needs_live_fallback:
            broker_status = broker_credential_status(session, user.id)
            target_envs = [env_filter] if env_filter in {"demo", "prod"} else ["demo", "prod"]
            snapshots: list[AutoTradeAccountSnapshotResponse] = []
            for env in target_envs:
                snapshots.append(
                    _resolve_autotrade_account_snapshot(
                        session,
                        user_id=user.id,
                        cfg=cfg,
                        broker_status=broker_status,
                        environment=env,
                    )
                )
            today = datetime.now(tz=SEOUL).date()
            today_key = today.isoformat()
            today_item = next((it for it in items if str(it.ymd) == today_key), None)
            live_item = _build_live_account_performance_item(
                snapshots=snapshots,
                ymd=today,
                orders_total=int(getattr(today_item, "orders_total", 0) or 0),
                filled_total=int(getattr(today_item, "filled_total", 0) or 0),
            )
            if live_item is not None:
                if not _has_meaningful_history_before(items, today_key):
                    # Avoid misleading period deltas when only today's live snapshot exists.
                    items = [live_item]
                else:
                    replaced = False
                    merged: list[AutoTradePerformanceItem] = []
                    for item in items:
                        if str(item.ymd) == today_key:
                            merged.append(live_item)
                            replaced = True
                        else:
                            merged.append(item)
                    if not replaced:
                        max_items = max(1, len(items) or int(days))
                        merged = ([live_item] + items)[:max_items]
                    items = merged
                summary = _autotrade_performance_summary(items)
        if summary is None:
            summary = _autotrade_performance_summary(items)
    return AutoTradePerformanceResponse(days=days, summary=summary, items=items)


@app.get("/autotrade/account", response_model=AutoTradeAccountSnapshotResponse)
def get_autotrade_account_snapshot(
    environment: str | None = Query(None),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    with session_scope() as session:
        _require_menu_allowed(session, user.id, _MENU_KEY_AUTOTRADE)
        cfg = get_or_create_autotrade_setting(session, user.id)
        broker_status = broker_credential_status(session, user.id)
        env = _autotrade_account_env(environment) if environment is not None else _autotrade_account_env(getattr(cfg, "environment", "demo"))
        return _resolve_autotrade_account_snapshot(
            session,
            user_id=user.id,
            cfg=cfg,
            broker_status=broker_status,
            environment=env,
        )


@app.post("/admin/ticker_tags/bulk", response_model=OkResponse)
def admin_upsert_ticker_tags(payload: dict, ctx=Depends(get_token_context)):
    admin = require_master(ctx)
    items = payload.get("items") if isinstance(payload, dict) else None
    if not isinstance(items, list):
        raise HTTPException(status_code=400, detail="items required")
    rows = []
    for it in items:
        if not isinstance(it, dict):
            continue
        ticker = str(it.get("ticker") or "").strip()
        tags = it.get("tags") or []
        if isinstance(tags, str):
            tags = [x.strip() for x in tags.replace("|", ",").replace(";", ",").split(",") if x.strip()]
        if not isinstance(tags, list):
            tags = []
        rows.append(TagRow(ticker=ticker, tags=[str(x) for x in tags if str(x).strip()], source=str(it.get("source") or "manual")))
    with session_scope() as session:
        upsert_tags(session, rows)
    log_admin_action(admin_user_id=admin.id, action="TICKER_TAGS_UPSERT", detail={"count": len(rows)})
    return OkResponse()

@app.get("/papers/summary", response_model=PapersSummaryResponse)
def get_papers_summary(ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_PAPERS)
    return PapersSummaryResponse(
        updated_at=datetime.now(tz=SEOUL),
        title="KoreaStockDash 논문/가설 요약",
        sections=[
            PaperSection(title="단타 엔진", bullets=["TA/RE/RS 기반 점수", "게이트 ON에서만 실행"]),
            PaperSection(title="장투 엔진", bullets=["유동성+추세 필터", "단타와 분리"]),
            PaperSection(title="리스크", bullets=["gate off 시 진입 금지", "손실 한도 준수"]),
        ],
    )

def _chart_cache_key(code: str, days: int) -> str:
    return f"{str(code).zfill(6)}:{int(days)}:{settings.krx_chart_mode}"


def _build_chart_daily_response(code: str, days: int) -> ChartDailyResponse:
    normalized_code = str(code).zfill(6)
    target_days = max(1, min(int(days), 2000))
    cache_key = _chart_cache_key(normalized_code, target_days)
    now_ts = datetime.now(tz=SEOUL)
    with _CHART_DAILY_CACHE_LOCK:
        hit = _CHART_DAILY_CACHE.get(cache_key)
        if hit is not None:
            cached_at, payload = hit
            if (now_ts - cached_at).total_seconds() <= _CHART_DAILY_TTL_SECONDS:
                return payload

    end = now_ts.date()
    start = end - timedelta(days=target_days * 2)  # enough for business days
    df = None
    name = normalized_code
    # Prefer KRX Data API only when explicitly enabled (per-day calls are slow)
    if settings.krx_api_key and settings.krx_chart_mode == "krx_per_day":
        try:
            import pandas as pd
            from engine.krx_api import KrxApiConfig, fetch_daily_market

            cfg = KrxApiConfig(
                api_key=settings.krx_api_key,
                endpoint_kospi=settings.krx_endpoint_kospi,
                endpoint_kosdaq=settings.krx_endpoint_kosdaq,
                endpoint_konex=settings.krx_endpoint_konex,
                cache_dir=os.path.join(settings.data_dir, "krx_cache"),
            )
            dates = pd.bdate_range(start=start, end=end).strftime("%Y%m%d").tolist()
            rows = []
            for bas_dd in dates:
                dfm = fetch_daily_market(cfg, bas_dd, "KOSPI")
                if dfm is None or dfm.empty:
                    continue
                hit = dfm[dfm["ISU_CD"].astype(str).str.zfill(6) == normalized_code]
                if hit.empty:
                    continue
                r = hit.iloc[0]
                name = str(r.get("ISU_NM") or name)
                rows.append(
                    {
                        "date": bas_dd,
                        "Open": float(r.get("TDD_OPNPRC", 0.0)),
                        "High": float(r.get("TDD_HGPRC", 0.0)),
                        "Low": float(r.get("TDD_LWPRC", 0.0)),
                        "Close": float(r.get("TDD_CLSPRC", 0.0)),
                        "Volume": float(r.get("ACC_TRDVOL", 0.0)),
                    }
                )
            if rows:
                df = pd.DataFrame(rows)
                df["date"] = pd.to_datetime(df["date"], format="%Y%m%d", errors="coerce")
                df = df.dropna(subset=["date"]).set_index("date").sort_index()
        except Exception:
            logger.exception("KRX chart fetch failed; fallback to pykrx")
    if df is None:
        df = load_ohlcv(normalized_code, start, end)

    points: list[ChartPoint] = []
    if df is not None and not df.empty:
        df = df.tail(target_days)
        for idx, row in df.iterrows():
            d = idx.date().isoformat() if hasattr(idx, "date") else str(idx)
            try:
                close = float(row["Close"])
            except Exception:
                close = 0.0

            def _safe(col: str) -> float | None:
                try:
                    return float(row[col])
                except Exception:
                    return None

            points.append(
                ChartPoint(
                    date=d,
                    open=_safe("Open"),
                    high=_safe("High"),
                    low=_safe("Low"),
                    close=close,
                    volume=_safe("Volume"),
                )
            )

    payload = ChartDailyResponse(code=normalized_code, name=name, points=points)
    with _CHART_DAILY_CACHE_LOCK:
        _CHART_DAILY_CACHE[cache_key] = (datetime.now(tz=SEOUL), payload)
    return payload


@app.get("/chart/daily", response_model=ChartDailyResponse)
def get_chart_daily(
    code: str = Query(...),
    days: int = Query(180, ge=1, le=2000),
    ctx=Depends(get_token_context),
):
    require_active_user(ctx)
    return _build_chart_daily_response(code, days)


@app.post("/chart/daily/batch", response_model=ChartDailyBatchResponse)
def get_chart_daily_batch(payload: ChartDailyBatchRequest, ctx=Depends(get_token_context)):
    require_active_user(ctx)
    req_days = max(1, min(int(payload.days), 2000))
    codes: list[str] = []
    seen: set[str] = set()
    for raw in payload.tickers:
        code = str(raw or "").strip()
        if not code:
            continue
        norm = code.zfill(6)
        if norm in seen:
            continue
        seen.add(norm)
        codes.append(norm)
        if len(codes) >= _CHART_BATCH_MAX_CODES:
            break

    if not codes:
        return ChartDailyBatchResponse(as_of=datetime.now(tz=SEOUL), items=[])

    max_workers = min(6, len(codes))
    by_code: dict[str, ChartDailyBatchItem] = {}
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futs = {pool.submit(_build_chart_daily_response, code, req_days): code for code in codes}
        for fut in as_completed(futs):
            code = futs[fut]
            try:
                chart = fut.result()
                by_code[code] = ChartDailyBatchItem(
                    code=code,
                    name=chart.name,
                    points=chart.points,
                    error=None,
                )
            except Exception as exc:
                logger.exception("chart batch fetch failed code=%s", code)
                by_code[code] = ChartDailyBatchItem(
                    code=code,
                    name=code,
                    points=[],
                    error=str(exc),
                )

    items = [by_code[c] for c in codes if c in by_code]
    return ChartDailyBatchResponse(as_of=datetime.now(tz=SEOUL), items=items)

@app.get("/health")
def health(): return {"ok": True}


@app.get("/api/settings", response_model=StrategySettingsResponse)
def get_settings(ctx=Depends(get_token_context)):
    require_master(ctx)
    with session_scope() as session:
        settings_payload = _get_or_create_settings(session)
        settings_hash = settings_payload.compute_hash()
        return StrategySettingsResponse(settings=settings_payload, settings_hash=settings_hash)


@app.post("/api/settings", response_model=StrategySettingsResponse)
def update_settings(payload: StrategySettingsPayload, ctx=Depends(get_token_context)):
    require_master(ctx)
    payload = StrategySettingsPayload.validate_ranges(payload)
    provided = set(payload.model_fields_set or set())
    with session_scope() as session:
        row = session.get(StrategySettings, 1)
        if row is None:
            row = StrategySettings(id=1, updated_at=datetime.now(tz=SEOUL))
            session.add(row)
        resolved_algo = normalize_algo_version(payload.algo_version or getattr(row, "algo_version", None))
        row.algo_version = resolved_algo
        payload.algo_version = resolved_algo
        if "risk_preset" in provided:
            row.risk_preset = payload.risk_preset
        else:
            payload.risk_preset = row.risk_preset
        if "use_custom_weights" in provided:
            row.use_custom_weights = payload.use_custom_weights
        else:
            payload.use_custom_weights = row.use_custom_weights
        if "w_ta" in provided:
            row.w_ta = payload.w_ta
        else:
            payload.w_ta = row.w_ta
        if "w_re" in provided:
            row.w_re = payload.w_re
        else:
            payload.w_re = row.w_re
        if "w_rs" in provided:
            row.w_rs = payload.w_rs
        else:
            payload.w_rs = row.w_rs
        if "theme_cap" in provided:
            row.theme_cap = payload.theme_cap
        else:
            payload.theme_cap = row.theme_cap
        if "max_gap_pct" in provided:
            row.max_gap_pct = payload.max_gap_pct
        else:
            payload.max_gap_pct = row.max_gap_pct
        if "gate_threshold" in provided:
            row.gate_threshold = payload.gate_threshold
        else:
            payload.gate_threshold = row.gate_threshold
        if "gate_quantile" in provided:
            row.gate_quantile = payload.gate_quantile
        else:
            payload.gate_quantile = row.gate_quantile
        row.updated_at = datetime.now(tz=SEOUL)
        settings_hash = payload.compute_hash()
        return StrategySettingsResponse(settings=payload, settings_hash=settings_hash)


def _parse_news_window(window: str) -> timedelta:
    w = (window or "").strip().lower()
    if w == "10m":
        return timedelta(minutes=10)
    if w == "1h":
        return timedelta(hours=1)
    if w == "24h":
        return timedelta(hours=24)
    if w == "7d":
        return timedelta(days=7)

    # Extended format support for detail-card range binding (e.g., 30d, 365d, 1825d).
    m = re.fullmatch(r"(\d+)([mhd])", w)
    if m:
        amount = int(m.group(1))
        unit = m.group(2)
        if unit == "m" and 1 <= amount <= 1440:
            return timedelta(minutes=amount)
        if unit == "h" and 1 <= amount <= 24 * 3650:
            return timedelta(hours=amount)
        if unit == "d" and 1 <= amount <= 3650:
            return timedelta(days=amount)
    raise HTTPException(status_code=400, detail="INVALID_WINDOW")


def _news_time_filter(window: str, ymd: int | None) -> tuple[datetime | None, int | None]:
    if ymd is not None:
        if ymd < 20000101 or ymd > 29991231:
            raise HTTPException(status_code=400, detail="INVALID_YMD")
        return None, int(ymd)
    delta = _parse_news_window(window)
    since = now_kst() - delta
    return since, None


_NEWS_EVENT_TYPES = {
    "earnings",
    "contract",
    "buyback",
    "offering",
    "mna",
    "regulation",
    "lawsuit",
    "report",
    "community",
    "misc",
}


def _normalize_news_source(source: str) -> str:
    s = (source or "all").strip().lower()
    if s in ("all", "dart", "rss"):
        return s
    raise HTTPException(status_code=400, detail="INVALID_SOURCE")


def _normalize_news_event_type(event_type: str | None) -> str | None:
    if event_type is None:
        return None
    ev = event_type.strip().lower()
    if not ev:
        return None
    if ev not in _NEWS_EVENT_TYPES:
        raise HTTPException(status_code=400, detail="INVALID_EVENT_TYPE")
    return ev


def _normalize_news_theme_key(theme_key: str | None) -> str | None:
    if theme_key is None:
        return None
    t = theme_key.strip().upper()
    return t or None


def _normalize_news_sort(sort: str, *, allowed: set[str], default: str) -> str:
    s = (sort or "").strip().lower()
    if not s:
        return default
    if s not in allowed:
        raise HTTPException(status_code=400, detail="INVALID_SORT")
    return s


def _normalize_news_limit(limit: int, *, minimum: int = 1, maximum: int = 500, default: int = 200) -> int:
    if limit is None:
        return default
    try:
        parsed = int(limit)
    except Exception as exc:
        raise HTTPException(status_code=400, detail="INVALID_LIMIT") from exc
    return max(minimum, min(maximum, parsed))


def _theme_score_bias(theme_key: str) -> float:
    # Keep ETC visible, but de-prioritize against named market themes.
    return -120.0 if (theme_key or "").upper() == "ETC" else 0.0


def _safe_json_str_list(raw: str | None) -> list[str]:
    if not raw:
        return []
    try:
        parsed = json.loads(raw)
    except Exception:
        return []
    if not isinstance(parsed, list):
        return []
    return [str(x).strip() for x in parsed if str(x).strip()]


def _normalize_news_search_text(raw: str) -> str:
    return re.sub(r"[^0-9A-Za-z가-힣]", "", str(raw or "")).lower()


_TICKER_NAME_WORD_CHARS = "0-9A-Za-z가-힣"
_TICKER_NAME_PARTICLE_RE = (
    r"(?:은|는|이|가|을|를|와|과|의|에|도|로|으로|만|부터|까지|께서|에서|에게|한테)?"
)


def _contains_ticker_name_strict(text: str, ticker_name: str) -> bool:
    text_i = str(text or "")
    name_i = str(ticker_name or "").strip()
    if not text_i or len(name_i) < 3:
        return False
    pat = rf"(?<![{_TICKER_NAME_WORD_CHARS}]){re.escape(name_i)}{_TICKER_NAME_PARTICLE_RE}(?![{_TICKER_NAME_WORD_CHARS}])"
    if re.search(pat, text_i):
        return True
    # Allow spacing variants while keeping full-name matching only.
    norm_text = _normalize_news_search_text(re.sub(r"\s+", "", text_i))
    norm_name = _normalize_news_search_text(re.sub(r"\s+", "", name_i))
    return bool(len(norm_name) >= 4 and norm_name and norm_name in norm_text)


def _filter_rows_by_ticker_name(rows: list[NewsArticle], ticker_name: str, limit: int) -> list[NewsArticle]:
    out: list[NewsArticle] = []
    seen: set[str] = set()
    for row in rows:
        combined = f"{row.title or ''} {row.summary or ''}".strip()
        if not _contains_ticker_name_strict(combined, ticker_name):
            continue
        key = f"{row.source}|{row.source_uid}"
        if key in seen:
            continue
        seen.add(key)
        out.append(row)
        if len(out) >= limit:
            break
    return out


def _apply_news_article_filters(
    query,
    *,
    since: datetime | None,
    ymd_i: int | None,
    source: str,
    event_type: str | None,
    hide_risk: bool,
    apply_relevance_filter: bool = True,
):
    if ymd_i is not None:
        query = query.filter(NewsArticle.published_ymd == ymd_i)
    else:
        query = query.filter(NewsArticle.published_at >= since)
    if source == "dart":
        query = query.filter(NewsArticle.source == "dart")
    elif source == "rss":
        query = query.filter(NewsArticle.source != "dart")
    if event_type:
        query = query.filter(NewsArticle.event_type == event_type)
    if hide_risk:
        query = query.filter(~NewsArticle.event_type.in_(list(RISK_EVENT_TYPES)))
    if apply_relevance_filter and getattr(settings, "news_rss_market_relevance_filter", True):
        # Hide low-signal generic RSS rows (existing DB rows included), while preserving DART.
        query = query.filter(
            or_(
                NewsArticle.source == "dart",
                NewsArticle.event_type != "misc",
                NewsArticle.theme_key != "ETC",
                func.coalesce(NewsArticle.tickers_json, "[]") != "[]",
            )
        )
    return query


@lru_cache(maxsize=1)
def _ticker_name_map() -> dict[str, str]:
    out: dict[str, str] = {}
    try:
        df = None
        if callable(load_universe_krx_cache):
            try:
                df_cache = load_universe_krx_cache()
                if df_cache is not None and not df_cache.empty:
                    df = df_cache
            except Exception:
                logger.exception("ticker name map cache load failed; fallback to load_universe")
        if df is None:
            df = load_universe()
        if df is not None and not df.empty:
            for _, row in df.iterrows():
                code = str(row.get("Code") or "").strip()
                name = str(row.get("Name") or "").strip()
                if re.fullmatch(r"\d{6}", code) and name:
                    out[code] = name
    except Exception:
        logger.exception("ticker name map load failed")
    return out


def _to_news_article_item(a: NewsArticle) -> NewsArticleItem:
    return NewsArticleItem(
        source=a.source,
        source_uid=a.source_uid,
        url=a.url,
        title=a.title,
        summary=a.summary,
        published_at=a.published_at,
        published_ymd=int(a.published_ymd),
        event_type=a.event_type,
        polarity=a.polarity,
        impact=int(a.impact),
        theme_key=a.theme_key,
        tickers=_safe_json_str_list(a.tickers_json),
    )


def _aggregate_clusters_from_articles(articles: list[NewsArticle]) -> list[dict[str, Any]]:
    grouped: dict[str, dict[str, Any]] = {}
    for a in articles:
        ckey = compute_cluster_key(a.theme_key, a.event_type, int(a.published_ymd), a.title)
        row = grouped.get(ckey)
        if row is None:
            row = {
                "cluster_key": ckey,
                "theme_key": a.theme_key,
                "event_type": a.event_type,
                "title": a.title,
                "summary": a.summary,
                "published_start": a.published_at,
                "published_end": a.published_at,
                "published_ymd": int(a.published_ymd),
                "article_count": 0,
                "impact_sum": 0,
                "risk_count": 0,
                "ticker_freq": {},
            }
            grouped[ckey] = row

        row["article_count"] += 1
        row["impact_sum"] += int(a.impact or 0)
        if a.event_type in RISK_EVENT_TYPES:
            row["risk_count"] += 1
        if a.published_at < row["published_start"]:
            row["published_start"] = a.published_at
        if a.published_at > row["published_end"]:
            row["published_end"] = a.published_at
        if (not row["summary"]) and (a.summary or "").strip():
            row["summary"] = a.summary

        freq: dict[str, int] = row["ticker_freq"]
        for t in _safe_json_str_list(a.tickers_json):
            freq[t] = freq.get(t, 0) + 1

    out: list[dict[str, Any]] = []
    for row in grouped.values():
        article_count = int(row["article_count"])
        impact_sum = int(row["impact_sum"])
        risk_count = int(row["risk_count"])
        risk_ratio = (risk_count / max(1, article_count)) if article_count else 0.0
        risk_penalty = risk_ratio * (0.20 * impact_sum + 4.0 * article_count)
        hot_score = float(impact_sum + (0.5 * article_count) - risk_penalty + _theme_score_bias(str(row["theme_key"] or "ETC")))
        freq: dict[str, int] = row["ticker_freq"]
        top_tickers = [t for t, _ in sorted(freq.items(), key=lambda x: (-x[1], x[0]))[:5]]
        out.append(
            {
                "cluster_key": str(row["cluster_key"]),
                "theme_key": str(row["theme_key"] or "ETC"),
                "event_type": str(row["event_type"] or "misc"),
                "title": str(row["title"] or ""),
                "summary": row["summary"],
                "top_tickers": top_tickers,
                "published_start": row["published_start"],
                "published_end": row["published_end"],
                "published_ymd": int(row["published_ymd"]),
                "article_count": article_count,
                "impact_sum": impact_sum,
                "hot_score": hot_score,
            }
        )
    return out


def _load_cluster_ids_by_key(session, cluster_keys: list[str]) -> dict[str, int]:
    if not cluster_keys:
        return {}
    out: dict[str, int] = {}
    # Keep IN list size bounded for sqlite.
    for i in range(0, len(cluster_keys), 800):
        chunk = cluster_keys[i : i + 800]
        rows = (
            session.query(NewsCluster.cluster_key, NewsCluster.id)
            .filter(NewsCluster.cluster_key.in_(chunk))
            .all()
        )
        for key, cid in rows:
            out[str(key)] = int(cid)
    return out


def _optional_master_ctx(request: Request) -> TokenContext | None:
    authz = (request.headers.get("authorization") or "").strip()
    if not authz.lower().startswith("bearer "):
        return None
    token = authz.split(None, 1)[1].strip() if " " in authz else ""
    if not token:
        return None
    token_hash = hash_token(token)
    with session_scope() as session:
        row = session.execute(
            select(SessionToken, User)
            .join(User, User.id == SessionToken.user_id)
            .where(SessionToken.token_hash == token_hash)
        ).first()
        if row is None:
            return None
        session_token, user = row
        if session_token.revoked_at is not None:
            return None
        if session_token.expires_at <= now():
            return None
        return TokenContext(user=user, token_hash=token_hash, session_id=session_token.id)


@app.get("/api/news/themes", response_model=NewsThemesResponse)
def get_news_themes(
    window: str = Query("24h"),
    ymd: int | None = Query(None),
    source: str = Query("all"),
    event_type: str | None = Query(None),
    hide_risk: bool = Query(False),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_NEWS)
    since, ymd_i = _news_time_filter(window, ymd)
    source_i = _normalize_news_source(source)
    event_type_i = _normalize_news_event_type(event_type)
    params = {
        "window": window,
        "ymd": ymd_i,
        "source": source_i,
        "event_type": event_type_i,
        "hide_risk": hide_risk,
    }

    with session_scope() as session:
        aq = session.query(NewsArticle)
        aq = _apply_news_article_filters(
            aq,
            since=since,
            ymd_i=ymd_i,
            source=source_i,
            event_type=event_type_i,
            hide_risk=hide_risk,
        )
        articles = aq.all()

        if not articles:
            meta = NewsMeta(
                source="LIVE",
                status="MISS",
                message=last_news_fetch_status().get("message"),
                generated_at=datetime.now(tz=SEOUL),
                params=params,
            )
            return NewsThemesResponse(meta=meta, themes=[])

        clusters = _aggregate_clusters_from_articles(articles)
        cluster_keys = [str(c["cluster_key"]) for c in clusters]
        cluster_id_map = _load_cluster_ids_by_key(session, cluster_keys)

        theme_stats: dict[str, dict[str, Any]] = {}
        for a in articles:
            theme_key = str(a.theme_key or "ETC")
            row = theme_stats.get(theme_key)
            if row is None:
                row = {
                    "article_count": 0,
                    "impact_sum": 0,
                    "risk_count": 0,
                    "latest_published_at": a.published_at,
                }
                theme_stats[theme_key] = row
            row["article_count"] += 1
            row["impact_sum"] += int(a.impact or 0)
            if a.event_type in RISK_EVENT_TYPES:
                row["risk_count"] += 1
            if a.published_at > row["latest_published_at"]:
                row["latest_published_at"] = a.published_at

        clusters_by_theme: dict[str, list[dict[str, Any]]] = {}
        for c in clusters:
            tk = str(c["theme_key"] or "ETC")
            clusters_by_theme.setdefault(tk, []).append(c)

        themes: list[NewsThemeCard] = []
        for theme_key, row in theme_stats.items():
            article_count = int(row["article_count"])
            impact_sum = int(row["impact_sum"])
            risk_count = int(row["risk_count"])
            latest = row["latest_published_at"]
            cluster_rows = clusters_by_theme.get(theme_key, [])
            cluster_count = len(cluster_rows)
            risk_ratio = (risk_count / max(1, article_count)) if article_count else 0.0
            # Penalize themes dominated by negative-risk events without fully zeroing them out.
            risk_penalty = risk_ratio * (0.35 * impact_sum + 8.0 * article_count)
            hot_score = float(
                impact_sum
                + (3.0 * cluster_count)
                + (0.5 * article_count)
                - risk_penalty
                + _theme_score_bias(theme_key)
            )

            top = sorted(
                cluster_rows,
                key=lambda c: (
                    c["published_end"],
                    c["article_count"],
                    c["impact_sum"],
                ),
                reverse=True,
            )[:6]
            top_clusters: list[NewsClusterLite] = []
            for c in top:
                top_clusters.append(
                    NewsClusterLite(
                        id=int(cluster_id_map.get(str(c["cluster_key"]), 0)),
                        theme_key=str(c["theme_key"]),
                        event_type=str(c["event_type"]),
                        title=str(c["title"]),
                        summary=c["summary"],
                        top_tickers=list(c["top_tickers"]),
                        published_start=c["published_start"],
                        published_end=c["published_end"],
                        published_ymd=int(c["published_ymd"]),
                        article_count=int(c["article_count"]),
                    )
                )

            themes.append(
                NewsThemeCard(
                    theme_key=theme_key,
                    hot_score=hot_score,
                    cluster_count=cluster_count,
                    article_count=article_count,
                    impact_sum=impact_sum,
                    latest_published_at=latest,
                    top_clusters=top_clusters,
                )
            )

        themes.sort(key=lambda x: x.hot_score, reverse=True)
        meta = NewsMeta(
            source="LIVE",
            status="OK",
            message=last_news_fetch_status().get("message"),
            generated_at=datetime.now(tz=SEOUL),
            params=params,
        )
        return NewsThemesResponse(meta=meta, themes=themes)


@app.get("/api/news/stocks", response_model=NewsStocksResponse)
def get_news_stocks(
    window: str = Query("24h"),
    ymd: int | None = Query(None),
    theme_key: str | None = Query(None),
    source: str = Query("all"),
    event_type: str | None = Query(None),
    hide_risk: bool = Query(False),
    sort: str = Query("hot"),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_NEWS)
    since, ymd_i = _news_time_filter(window, ymd)
    theme_key_i = _normalize_news_theme_key(theme_key)
    source_i = _normalize_news_source(source)
    event_type_i = _normalize_news_event_type(event_type)
    sort_i = _normalize_news_sort(sort, allowed={"hot", "latest", "impact", "count"}, default="hot")
    params = {
        "window": window,
        "ymd": ymd_i,
        "theme_key": theme_key_i,
        "source": source_i,
        "event_type": event_type_i,
        "hide_risk": hide_risk,
        "sort": sort_i,
    }

    with session_scope() as session:
        q = session.query(
            NewsEntityMention.ticker.label("ticker"),
            func.count(NewsEntityMention.id).label("mention_count"),
            func.coalesce(func.sum(NewsEntityMention.impact), 0).label("impact_sum"),
            func.max(NewsEntityMention.published_at).label("latest_published_at"),
        ).join(NewsArticle, NewsArticle.id == NewsEntityMention.article_id)
        if theme_key_i:
            q = q.filter(NewsArticle.theme_key == theme_key_i)
        if ymd_i is not None:
            q = q.filter(NewsArticle.published_ymd == ymd_i)
        else:
            q = q.filter(NewsArticle.published_at >= since)
        if source_i == "dart":
            q = q.filter(NewsArticle.source == "dart")
        elif source_i == "rss":
            q = q.filter(NewsArticle.source != "dart")
        if event_type_i:
            q = q.filter(NewsArticle.event_type == event_type_i)
        if hide_risk:
            q = q.filter(~NewsArticle.event_type.in_(list(RISK_EVENT_TYPES)))
        q = q.group_by(NewsEntityMention.ticker)
        rows = q.all()

    items: list[NewsStockHotItem] = []
    for r in rows:
        ticker = str(r.ticker or "").strip()
        if not ticker:
            continue
        mention_count = int(r.mention_count or 0)
        impact_sum = int(r.impact_sum or 0)
        latest = r.latest_published_at
        hot_score = float(impact_sum + (0.5 * mention_count))
        items.append(
            NewsStockHotItem(
                ticker=ticker,
                hot_score=hot_score,
                mention_count=mention_count,
                impact_sum=impact_sum,
                latest_published_at=latest,
                name=None,
            )
        )

    if sort_i == "latest":
        items.sort(key=lambda x: x.latest_published_at or datetime(1970, 1, 1), reverse=True)
    elif sort_i == "impact":
        items.sort(key=lambda x: x.impact_sum, reverse=True)
    elif sort_i == "count":
        items.sort(key=lambda x: x.mention_count, reverse=True)
    else:
        items.sort(key=lambda x: x.hot_score, reverse=True)

    meta_status = "OK" if items else "MISS"
    meta = NewsMeta(
        source="LIVE",
        status=meta_status,
        message=last_news_fetch_status().get("message"),
        generated_at=datetime.now(tz=SEOUL),
        params=params,
    )
    return NewsStocksResponse(meta=meta, stocks=items)


@app.get("/api/news/clusters", response_model=NewsClustersResponse)
def get_news_clusters(
    window: str = Query("24h"),
    ymd: int | None = Query(None),
    theme_key: str | None = Query(None),
    source: str = Query("all"),
    event_type: str | None = Query(None),
    hide_risk: bool = Query(False),
    sort: str = Query("hot"),
    limit: int = Query(200),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_NEWS)
    since, ymd_i = _news_time_filter(window, ymd)
    theme_key_i = _normalize_news_theme_key(theme_key)
    source_i = _normalize_news_source(source)
    event_type_i = _normalize_news_event_type(event_type)
    sort_i = _normalize_news_sort(sort, allowed={"hot", "latest", "impact", "count"}, default="hot")
    limit_i = _normalize_news_limit(limit, minimum=1, maximum=500, default=200)
    params = {
        "window": window,
        "ymd": ymd_i,
        "theme_key": theme_key_i,
        "source": source_i,
        "event_type": event_type_i,
        "hide_risk": hide_risk,
        "sort": sort_i,
        "limit": limit_i,
    }

    with session_scope() as session:
        aq = session.query(NewsArticle)
        aq = _apply_news_article_filters(
            aq,
            since=since,
            ymd_i=ymd_i,
            source=source_i,
            event_type=event_type_i,
            hide_risk=hide_risk,
        )
        if theme_key_i:
            aq = aq.filter(NewsArticle.theme_key == theme_key_i)
        articles = aq.all()

        clusters = _aggregate_clusters_from_articles(articles)
        if theme_key_i:
            clusters = [c for c in clusters if str(c["theme_key"]).upper() == theme_key_i]
        if not clusters:
            meta = NewsMeta(
                source="LIVE",
                status="MISS",
                message=last_news_fetch_status().get("message"),
                generated_at=datetime.now(tz=SEOUL),
                params=params,
            )
            return NewsClustersResponse(meta=meta, clusters=[])

        if sort_i == "latest":
            clusters.sort(key=lambda c: c["published_end"], reverse=True)
        elif sort_i == "impact":
            clusters.sort(key=lambda c: c["impact_sum"], reverse=True)
        elif sort_i == "count":
            clusters.sort(key=lambda c: c["article_count"], reverse=True)
        else:
            clusters.sort(
                key=lambda c: (
                    c["hot_score"],
                    c["published_end"],
                ),
                reverse=True,
            )
        clusters = clusters[:limit_i]
        cluster_id_map = _load_cluster_ids_by_key(session, [str(c["cluster_key"]) for c in clusters])

    items: list[NewsClusterListItem] = []
    for c in clusters:
        items.append(
            NewsClusterListItem(
                id=int(cluster_id_map.get(str(c["cluster_key"]), 0)),
                cluster_key=str(c["cluster_key"]),
                theme_key=str(c["theme_key"]),
                event_type=str(c["event_type"]),
                title=str(c["title"]),
                summary=c["summary"],
                top_tickers=list(c["top_tickers"]),
                published_start=c["published_start"],
                published_end=c["published_end"],
                published_ymd=int(c["published_ymd"]),
                article_count=int(c["article_count"]),
                impact_sum=int(c["impact_sum"]),
                hot_score=float(c["hot_score"]),
            )
        )

    meta = NewsMeta(
        source="LIVE",
        status="OK",
        message=last_news_fetch_status().get("message"),
        generated_at=datetime.now(tz=SEOUL),
        params=params,
    )
    return NewsClustersResponse(meta=meta, clusters=items)


@app.get("/api/news/articles", response_model=NewsArticlesResponse)
def get_news_articles(
    window: str = Query("24h"),
    ymd: int | None = Query(None),
    theme_key: str | None = Query(None),
    source: str = Query("all"),
    event_type: str | None = Query(None),
    hide_risk: bool = Query(False),
    ticker: str | None = Query(None),
    q: str | None = Query(None),
    sort: str = Query("latest"),
    limit: int = Query(200),
    ctx=Depends(get_token_context),
):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_NEWS)
    since, ymd_i = _news_time_filter(window, ymd)
    theme_key_i = _normalize_news_theme_key(theme_key)
    source_i = _normalize_news_source(source)
    event_type_i = _normalize_news_event_type(event_type)
    ticker_i = str(ticker or "").strip()
    if ticker_i:
        digits = "".join(ch for ch in ticker_i if ch.isdigit())
        ticker_i = digits.zfill(6) if len(digits) <= 6 else digits[:6]
        if not re.fullmatch(r"\d{6}", ticker_i):
            raise HTTPException(status_code=400, detail="INVALID_TICKER")
    q_i = str(q or "").strip()
    sort_i = _normalize_news_sort(sort, allowed={"latest", "impact"}, default="latest")
    limit_i = _normalize_news_limit(limit, minimum=1, maximum=500, default=200)
    params = {
        "window": window,
        "ymd": ymd_i,
        "theme_key": theme_key_i,
        "source": source_i,
        "event_type": event_type_i,
        "hide_risk": hide_risk,
        "ticker": ticker_i or None,
        "q": q_i or None,
        "sort": sort_i,
        "limit": limit_i,
    }

    with session_scope() as session:
        q = session.query(NewsArticle)
        q = _apply_news_article_filters(
            q,
            since=since,
            ymd_i=ymd_i,
            source=source_i,
            event_type=event_type_i,
            hide_risk=hide_risk,
            apply_relevance_filter=not bool(ticker_i),
        )
        if theme_key_i:
            q = q.filter(NewsArticle.theme_key == theme_key_i)
        if ticker_i:
            q = q.join(NewsEntityMention, NewsEntityMention.article_id == NewsArticle.id)
            q = q.filter(NewsEntityMention.ticker == ticker_i)
        if q_i:
            like = f"%{q_i}%"
            q = q.filter(or_(NewsArticle.title.ilike(like), NewsArticle.summary.ilike(like)))
        if sort_i == "impact":
            q = q.order_by(NewsArticle.impact.desc(), NewsArticle.published_at.desc())
        else:
            q = q.order_by(NewsArticle.published_at.desc(), NewsArticle.impact.desc())
        fetch_limit = limit_i
        if q_i:
            fetch_limit = min(5000, max(limit_i * 5, 500))
        rows = q.limit(fetch_limit).all()

    needs_community_backfill = bool(
        ticker_i and event_type_i == "community" and len(rows) < min(20, limit_i)
    )
    if ticker_i and (not rows or needs_community_backfill):
        name_map = _ticker_name_map()
        ticker_name = str(name_map.get(ticker_i, "")).strip()
        if ticker_name and not rows:
            with session_scope() as session:
                q_fb = session.query(NewsArticle)
                q_fb = _apply_news_article_filters(
                    q_fb,
                    since=since,
                    ymd_i=ymd_i,
                    source=source_i,
                    event_type=event_type_i,
                    hide_risk=hide_risk,
                    apply_relevance_filter=False,
                )
                like_name = f"%{ticker_name}%"
                q_fb = q_fb.filter(or_(NewsArticle.title.ilike(like_name), NewsArticle.summary.ilike(like_name)))
                if sort_i == "impact":
                    q_fb = q_fb.order_by(NewsArticle.impact.desc(), NewsArticle.published_at.desc())
                else:
                    q_fb = q_fb.order_by(NewsArticle.published_at.desc(), NewsArticle.impact.desc())
                candidate_limit = min(3000, max(limit_i * 20, 600))
                rows = _filter_rows_by_ticker_name(
                    q_fb.limit(candidate_limit).all(),
                    ticker_name,
                    limit_i,
                )
        if not rows or needs_community_backfill:
            try:
                if should_run_ticker_backfill(ticker_i, cooldown_s=180):
                    allow_community_backfill = event_type_i == "community"
                    _, backfill_msgs = backfill_ticker_news_once(
                        ticker_i,
                        per_feed_limit=max(20, min(60, limit_i)),
                        max_elapsed_s=8.0,
                        rss_timeout_sec=4,
                        naver_timeout_sec=5,
                        include_naver_search=False,
                        include_naver_finance_community=allow_community_backfill,
                    )
                    if backfill_msgs:
                        params["backfill"] = "; ".join(backfill_msgs[:4])
                else:
                    params["backfill"] = "cooldown_skip"
            except Exception:
                logger.exception("ticker backfill failed: %s", ticker_i)
            with session_scope() as session:
                q_retry = session.query(NewsArticle)
                q_retry = _apply_news_article_filters(
                    q_retry,
                    since=since,
                    ymd_i=ymd_i,
                    source=source_i,
                    event_type=event_type_i,
                    hide_risk=hide_risk,
                    apply_relevance_filter=False,
                )
                if theme_key_i:
                    q_retry = q_retry.filter(NewsArticle.theme_key == theme_key_i)
                q_retry = q_retry.join(NewsEntityMention, NewsEntityMention.article_id == NewsArticle.id)
                q_retry = q_retry.filter(NewsEntityMention.ticker == ticker_i)
                if q_i:
                    like = f"%{q_i}%"
                    q_retry = q_retry.filter(or_(NewsArticle.title.ilike(like), NewsArticle.summary.ilike(like)))
                if sort_i == "impact":
                    q_retry = q_retry.order_by(NewsArticle.impact.desc(), NewsArticle.published_at.desc())
                else:
                    q_retry = q_retry.order_by(NewsArticle.published_at.desc(), NewsArticle.impact.desc())
                rows = q_retry.limit(fetch_limit).all()
            if (not rows) and ticker_name:
                with session_scope() as session:
                    q_fb = session.query(NewsArticle)
                    q_fb = _apply_news_article_filters(
                        q_fb,
                        since=since,
                        ymd_i=ymd_i,
                        source=source_i,
                        event_type=event_type_i,
                        hide_risk=hide_risk,
                        apply_relevance_filter=False,
                    )
                    like_name = f"%{ticker_name}%"
                    q_fb = q_fb.filter(or_(NewsArticle.title.ilike(like_name), NewsArticle.summary.ilike(like_name)))
                    if sort_i == "impact":
                        q_fb = q_fb.order_by(NewsArticle.impact.desc(), NewsArticle.published_at.desc())
                    else:
                        q_fb = q_fb.order_by(NewsArticle.published_at.desc(), NewsArticle.impact.desc())
                    candidate_limit = min(3000, max(limit_i * 20, 600))
                    rows = _filter_rows_by_ticker_name(
                        q_fb.limit(candidate_limit).all(),
                        ticker_name,
                        limit_i,
                    )

    if q_i:
        q_i_norm = _normalize_news_search_text(q_i)
        q_i_lower = q_i.lower()
        filtered_rows: list[NewsArticle] = []
        seen_keys: set[str] = set()
        for row in rows:
            title = str(row.title or "")
            summary = str(row.summary or "")
            combined = f"{title} {summary}"
            combined_lower = combined.lower()
            combined_norm = _normalize_news_search_text(combined)
            if (
                q_i_lower in combined_lower
                or (q_i_norm and q_i_norm in combined_norm)
            ):
                key = f"{row.source}|{row.source_uid}"
                if key not in seen_keys:
                    seen_keys.add(key)
                    filtered_rows.append(row)
            if len(filtered_rows) >= limit_i:
                break
        rows = filtered_rows

    if not rows:
        meta = NewsMeta(
            source="LIVE",
            status="MISS",
            message=last_news_fetch_status().get("message"),
            generated_at=datetime.now(tz=SEOUL),
            params=params,
        )
        return NewsArticlesResponse(meta=meta, articles=[])

    items = [_to_news_article_item(a) for a in rows]
    meta = NewsMeta(
        source="LIVE",
        status="OK",
        message=last_news_fetch_status().get("message"),
        generated_at=datetime.now(tz=SEOUL),
        params=params,
    )
    return NewsArticlesResponse(meta=meta, articles=items)


@app.get("/api/news/cluster/{cluster_id}", response_model=NewsClusterResponse)
def get_news_cluster(cluster_id: int, ctx=Depends(get_token_context)):
    user = require_active_user(ctx)
    _require_menu_allowed_for_user(user.id, _MENU_KEY_NEWS)
    params = {"cluster_id": cluster_id}
    with session_scope() as session:
        c = session.get(NewsCluster, cluster_id)
        if c is None:
            raise HTTPException(status_code=404, detail="NOT_FOUND")

        try:
            top_tickers = json.loads(c.top_tickers_json or "[]")
            if not isinstance(top_tickers, list):
                top_tickers = []
            top_tickers = [str(x) for x in top_tickers if str(x).strip()]
        except Exception:
            top_tickers = []

        cluster = NewsClusterItem(
            id=int(c.id),
            cluster_key=c.cluster_key,
            theme_key=c.theme_key,
            event_type=c.event_type,
            title=c.title,
            summary=c.summary,
            top_tickers=top_tickers,
            published_start=c.published_start,
            published_end=c.published_end,
            published_ymd=int(c.published_ymd),
            article_count=int(c.article_count),
        )

        candidates = (
            session.query(NewsArticle)
            .filter(
                NewsArticle.theme_key == c.theme_key,
                NewsArticle.event_type == c.event_type,
                NewsArticle.published_ymd == c.published_ymd,
            )
            .all()
        )
        articles: list[NewsArticleItem] = []
        for a in candidates:
            if compute_cluster_key(a.theme_key, a.event_type, a.published_ymd, a.title) != c.cluster_key:
                continue
            try:
                tickers = json.loads(a.tickers_json or "[]")
                if not isinstance(tickers, list):
                    tickers = []
                tickers = [str(x) for x in tickers if str(x).strip()]
            except Exception:
                tickers = []
            articles.append(
                NewsArticleItem(
                    source=a.source,
                    source_uid=a.source_uid,
                    url=a.url,
                    title=a.title,
                    summary=a.summary,
                    published_at=a.published_at,
                    published_ymd=int(a.published_ymd),
                    event_type=a.event_type,
                    polarity=a.polarity,
                    impact=int(a.impact),
                    theme_key=a.theme_key,
                    tickers=tickers,
                )
            )
        articles.sort(key=lambda x: x.published_at, reverse=True)

    meta = NewsMeta(
        source="LIVE",
        status="OK",
        message=None,
        generated_at=datetime.now(tz=SEOUL),
        params=params,
    )
    return NewsClusterResponse(meta=meta, cluster=cluster, articles=articles)


@app.post("/api/news/ingest", response_model=NewsIngestResponse)
def news_ingest(payload: NewsIngestRequest, request: Request):
    secret = (settings.news_ingest_token or "").strip()
    header = (request.headers.get("x-ingest-token") or "").strip()

    if secret:
        if header != secret:
            raise HTTPException(status_code=403, detail="FORBIDDEN")
    else:
        ctx = _optional_master_ctx(request)
        if ctx is None:
            raise HTTPException(status_code=401, detail="UNAUTHORIZED")
        require_master(ctx)

    articles_in: list[NormalizedArticle] = []
    for a in payload.articles or []:
        source = str(a.source or "").strip()
        source_uid = str(a.source_uid or "").strip()
        url = str(a.url or "").strip()
        title = str(a.title or "").strip()
        if not source or not source_uid or not url or not title:
            continue
        summary = (a.summary or "").strip() or None
        published_at = a.published_at if a.published_at is not None else now_kst()
        if published_at.tzinfo is not None:
            published_at = published_at.astimezone(SEOUL).replace(tzinfo=None)
        published_ymd = int(published_at.strftime("%Y%m%d"))

        combined = f"{title} {summary or ''}".strip()
        event_type = (a.event_type or "").strip() or classify_event_type(combined)
        theme_key = (a.theme_key or "").strip() or classify_theme_key(combined)
        polarity = (a.polarity or "").strip() or classify_polarity(event_type)
        impact = int(a.impact) if a.impact is not None else classify_impact(event_type)
        tickers = a.tickers or []
        tickers = [str(x).strip() for x in tickers if str(x).strip()]

        articles_in.append(
            NormalizedArticle(
                source=source,
                source_uid=source_uid,
                url=url,
                title=title,
                summary=summary,
                published_at=published_at,
                published_ymd=published_ymd,
                event_type=event_type,
                polarity=polarity,
                impact=impact,
                theme_key=theme_key,
                tickers=tickers,
            )
        )

    stats, msgs = upsert_articles(articles_in)
    meta = NewsMeta(
        source="LIVE",
        status="OK",
        message="; ".join(msgs[:6]) if msgs else None,
        generated_at=datetime.now(tz=SEOUL),
        params={"articles": len(payload.articles or [])},
    )
    return NewsIngestResponse(
        meta=meta,
        inserted=stats.inserted,
        updated=stats.updated,
        clusters_updated=stats.clusters_updated,
        mentions_inserted=stats.mentions_inserted,
    )


# ═══════════════════════════════════════════════════
# 홈 화면 추가 API
# ═══════════════════════════════════════════════════

from app.schemas import TradeFeedResponse, TradeFeedItem, PnlCalendarResponse, PnlCalendarDay


@app.get("/autotrade/feed", response_model=TradeFeedResponse)
def get_autotrade_feed(
    limit: int = Query(20, ge=1, le=100),
    ctx=Depends(get_token_context),
):
    """오늘의 자동매매 실시간 피드 (최근 체결 내역)."""
    user = require_active_user(ctx)
    today = datetime.now(tz=SEOUL).date()
    with session_scope() as session:
        rows = (
            session.query(AutoTradeOrder)
            .filter(
                AutoTradeOrder.user_id == user.id,
                AutoTradeOrder.status.in_(["FILLED", "PARTIAL_FILLED"]),
            )
            .order_by(AutoTradeOrder.filled_at.desc(), AutoTradeOrder.id.desc())
            .limit(limit)
            .all()
        )
        items = []
        for r in rows:
            filled_at = getattr(r, "filled_at", None) or getattr(r, "requested_at", None)
            time_str = filled_at.strftime("%H:%M") if filled_at else None
            pnl = None
            if str(getattr(r, "side", "")).upper() == "SELL":
                pnl = getattr(r, "realized_pnl_krw", None)
            items.append(TradeFeedItem(
                time=time_str,
                ticker=getattr(r, "ticker", None),
                name=getattr(r, "name", None) or getattr(r, "ticker", None),
                side=str(getattr(r, "side", "")).upper(),
                qty=getattr(r, "filled_qty", None) or getattr(r, "qty", None),
                price=getattr(r, "filled_price", None) or getattr(r, "price", None),
                pnl=float(pnl) if pnl is not None else None,
            ))
        return TradeFeedResponse(items=items, total=len(items))


@app.get("/autotrade/pnl-calendar", response_model=PnlCalendarResponse)
def get_autotrade_pnl_calendar(
    year: int = Query(..., ge=2024, le=2030),
    month: int = Query(..., ge=1, le=12),
    ctx=Depends(get_token_context),
):
    """월별 일간 수익 캘린더."""
    user = require_active_user(ctx)
    from calendar import monthrange
    _, last_day = monthrange(year, month)
    start_date = date(year, month, 1)
    end_date = date(year, month, last_day)
    with session_scope() as session:
        rows = (
            session.query(AutoTradeOrder)
            .filter(
                AutoTradeOrder.user_id == user.id,
                AutoTradeOrder.status.in_(["FILLED", "PARTIAL_FILLED"]),
            )
            .order_by(AutoTradeOrder.filled_at.asc())
            .all()
        )
        daily: dict[str, dict] = {}
        for r in rows:
            filled_at = getattr(r, "filled_at", None) or getattr(r, "requested_at", None)
            if filled_at is None:
                continue
            d = filled_at.date() if hasattr(filled_at, "date") else filled_at
            if d < start_date or d > end_date:
                continue
            ds = d.isoformat()
            if ds not in daily:
                daily[ds] = {"pnl": 0.0, "count": 0}
            pnl = getattr(r, "realized_pnl_krw", None)
            if pnl is not None:
                daily[ds]["pnl"] += float(pnl)
            daily[ds]["count"] += 1
        days = [PnlCalendarDay(date=k, pnl=v["pnl"], trade_count=v["count"]) for k, v in sorted(daily.items())]
        month_total = sum(d.pnl for d in days)
        month_count = sum(d.trade_count for d in days)
        return PnlCalendarResponse(days=days, month_total_pnl=month_total, month_trade_count=month_count)
