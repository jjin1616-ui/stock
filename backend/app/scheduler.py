from __future__ import annotations

import json
import logging
import threading
import time
from datetime import datetime
from zoneinfo import ZoneInfo

from apscheduler.schedulers.background import BackgroundScheduler
from sqlalchemy import select

from app.config import settings
from app.models import AutoTradeSetting, EvalDaily, EvalMonthly, Report, StrategySettings, User
from app.autotrade_service import get_or_create_autotrade_setting, run_autotrade_once
from app.autotrade_push import send_autotrade_run_push
from app.broker_credentials import resolve_user_kis_credentials
from app.push import send_and_log
from app.report_generator import dumps, generate_eod, generate_premarket_report
from app.report_cache import premarket_cache_key
from app.schemas import StrategySettingsPayload
from app.us_insiders import compute_us_insider_screen
from app.news_service import enqueue_news_fetch
from app.autotrade_reservation import current_market_phase, process_due_reservations
from engine.strategy import StrategySettings as StrategyConfig, normalize_algo_version
from app.storage import session_scope
from app.ticker_tags import refresh_from_csv, refresh_from_pykrx_sector

SEOUL = ZoneInfo(settings.app_tz)
logger = logging.getLogger("stock.scheduler")
_scheduler: BackgroundScheduler | None = None
_autotrade_engine_lock = threading.Lock()
_autotrade_engine_running_users: set[tuple[int, str]] = set()
_autotrade_engine_last_run_ts: dict[tuple[int, str], float] = {}


def _today():
    return datetime.now(tz=SEOUL).date()


def _acquire_autotrade_engine_user(user_id: int, cooldown_sec: int, phase: str) -> bool:
    now_ts = time.time()
    phase_u = str(phase or "all").strip().lower()
    key = (int(user_id), phase_u)
    with _autotrade_engine_lock:
        if key in _autotrade_engine_running_users:
            return False
        last_ts = float(_autotrade_engine_last_run_ts.get(key, 0.0))
        if (now_ts - last_ts) < float(cooldown_sec):
            return False
        _autotrade_engine_running_users.add(key)
        _autotrade_engine_last_run_ts[key] = now_ts
    return True


def _release_autotrade_engine_user(user_id: int, phase: str) -> None:
    phase_u = str(phase or "all").strip().lower()
    key = (int(user_id), phase_u)
    with _autotrade_engine_lock:
        _autotrade_engine_running_users.discard(key)
        _autotrade_engine_last_run_ts[key] = time.time()


def _run_autotrade_engine_cycle(*, phase_name: str, execution_mode: str, cooldown_sec: int, batch_size: int) -> None:
    if not bool(getattr(settings, "autotrade_engine_enabled", True)):
        return
    phase, _next_open = current_market_phase()
    if phase != "OPEN":
        return

    with session_scope() as session:
        user_ids = [
            int(user_id)
            for (user_id,) in session.execute(
                select(AutoTradeSetting.user_id)
                .join(User, User.id == AutoTradeSetting.user_id)
                .where(AutoTradeSetting.enabled.is_(True), User.status == "active")
                .order_by(AutoTradeSetting.updated_at.desc(), AutoTradeSetting.id.desc())
                .limit(batch_size)
            ).all()
        ]

    processed_users = 0
    executed_users = 0
    created_orders = 0
    submitted_orders = 0
    for user_id in user_ids:
        processed_users += 1
        if not _acquire_autotrade_engine_user(user_id, cooldown_sec=cooldown_sec, phase=phase_name):
            continue
        try:
            with session_scope() as session:
                cfg = get_or_create_autotrade_setting(session, user_id)
                if not bool(getattr(cfg, "enabled", False)):
                    continue
                user_creds, use_user_creds = resolve_user_kis_credentials(session, user_id)
                result = run_autotrade_once(
                    session,
                    user_id=user_id,
                    cfg=cfg,
                    dry_run=False,
                    broker_credentials=(user_creds if use_user_creds else None),
                    record_skipped_orders=False,
                    candidate_profile="initial",
                    execution_mode=execution_mode,
                )
                executed_users += 1
                created = len(result.created_orders)
                submitted = sum(1 for o in result.created_orders if str(getattr(o, "status", "")).upper() != "SKIPPED")
                created_orders += created
                submitted_orders += submitted
                if created > 0:
                    try:
                        send_autotrade_run_push(
                            session,
                            user_id=user_id,
                            environment=str(getattr(cfg, "environment", "demo") or "demo"),
                            run_id=str(result.run_id),
                            source="ENGINE",
                            orders=list(result.created_orders),
                            message=str(result.message or ""),
                        )
                    except Exception:
                        logger.exception("autotrade engine push failed user=%s run_id=%s", user_id, result.run_id)
                if created > 0:
                    logger.info(
                        "autotrade engine user=%s created=%s submitted=%s message=%s",
                        user_id,
                        created,
                        submitted,
                        result.message,
                    )
        except Exception:
            logger.exception("autotrade engine run failed user=%s", user_id)
        finally:
            _release_autotrade_engine_user(user_id, phase=phase_name)

    if processed_users > 0 and (created_orders > 0 or executed_users > 0):
        logger.info(
            "autotrade engine cycle phase=%s mode=%s users=%s executed=%s created=%s submitted=%s",
            phase_name,
            execution_mode,
            processed_users,
            executed_users,
            created_orders,
            submitted_orders,
        )


def job_autotrade_exit_engine() -> None:
    batch_size = max(1, min(300, int(getattr(settings, "autotrade_engine_batch_size", 50))))
    cooldown_sec = max(1, min(120, int(getattr(settings, "autotrade_engine_exit_cooldown_sec", 3))))
    _run_autotrade_engine_cycle(
        phase_name="exit",
        execution_mode="exit_only",
        cooldown_sec=cooldown_sec,
        batch_size=batch_size,
    )


def job_autotrade_entry_engine() -> None:
    batch_size = max(1, min(300, int(getattr(settings, "autotrade_engine_batch_size", 50))))
    cooldown_sec = max(1, min(120, int(getattr(settings, "autotrade_engine_entry_cooldown_sec", getattr(settings, "autotrade_engine_user_cooldown_sec", 8)))))
    _run_autotrade_engine_cycle(
        phase_name="entry",
        execution_mode="entry_only",
        cooldown_sec=cooldown_sec,
        batch_size=batch_size,
    )


def _us_insider_cache_key(
    target_count: int,
    trading_days: int,
    expand_days: int,
    max_candidates: int,
    transaction_codes_key: str,
) -> str:
    return f"{target_count}:{trading_days}:{expand_days}:{max_candidates}:{transaction_codes_key}"


def _upsert_report(session, d, report_type: str, payload: dict, cache_key: str) -> None:
    row = session.scalar(
        select(Report).where(Report.date == d, Report.type == report_type, Report.cache_key == cache_key)
    )
    if row is None:
        # Legacy DB compatibility: old schema may still enforce UNIQUE(date,type).
        row = session.scalar(
            select(Report).where(Report.date == d, Report.type == report_type).order_by(Report.generated_at.desc()).limit(1)
        )
    now = datetime.now(tz=SEOUL)
    if row:
        row.cache_key = cache_key
        row.payload_json = dumps(payload)
        row.generated_at = now
    else:
        session.add(Report(date=d, type=report_type, cache_key=cache_key, payload_json=dumps(payload), generated_at=now))


def _upsert_eval(session, d, daily_mean_r: list[tuple], kpi: dict) -> None:
    now = datetime.now(tz=SEOUL)
    for date_i, r in daily_mean_r[-120:]:
        row = session.scalar(select(EvalDaily).where(EvalDaily.date == date_i))
        if row:
            row.daily_mean_r = r
            row.generated_at = now
        else:
            session.add(EvalDaily(date=date_i, daily_mean_r=r, generated_at=now))

    m = session.scalar(select(EvalMonthly).where(EvalMonthly.end_date == d))
    payload = dumps(kpi)
    if m:
        m.expectancy_r = kpi["expectancy_r"]
        m.win_rate = kpi["win_rate"]
        m.mdd_r = kpi["mdd_r"]
        m.payload_json = payload
        m.generated_at = now
    else:
        session.add(
            EvalMonthly(
                end_date=d,
                expectancy_r=kpi["expectancy_r"],
                win_rate=kpi["win_rate"],
                mdd_r=kpi["mdd_r"],
                payload_json=payload,
                generated_at=now,
            )
        )


def job_premarket() -> None:
    d = _today()
    daytrade_limit = max(3, min(200, int(getattr(settings, "prewarm_daytrade_limit", 10))))
    longterm_limit = max(3, min(200, int(getattr(settings, "prewarm_longterm_limit", 8))))
    with session_scope() as session:
        row = session.get(StrategySettings, 1)
        settings_payload = StrategySettingsPayload(
            algo_version=row.algo_version if row else "V2",
            risk_preset=row.risk_preset if row else "ADAPTIVE",
            use_custom_weights=row.use_custom_weights if row else False,
            w_ta=row.w_ta if row else None,
            w_re=row.w_re if row else None,
            w_rs=row.w_rs if row else None,
            theme_cap=row.theme_cap if row else 2,
            max_gap_pct=row.max_gap_pct if row else 0.0,
            gate_threshold=row.gate_threshold if row else 0.0,
            gate_quantile=row.gate_quantile if row else None,
        )
        settings_payload.algo_version = normalize_algo_version(settings_payload.algo_version)
        settings_hash = settings_payload.compute_hash()
        cache_key = premarket_cache_key(d, settings_hash, daytrade_limit, longterm_limit, settings_payload.algo_version)
        strategy = StrategyConfig(
            algo_version=settings_payload.algo_version,
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
        payload, _diagnostics = generate_premarket_report(
            d,
            strategy,
            daytrade_limit=daytrade_limit,
            longterm_limit=longterm_limit,
        )
        payload.setdefault("status", {})
        payload["status"]["settings_hash"] = settings_hash
        payload["status"]["cache_key"] = cache_key
        payload["status"]["algo_version"] = settings_payload.algo_version
    with session_scope() as session:
        _upsert_report(session, d, "PREMARKET", payload, cache_key=cache_key)
        send_and_log(
            session,
            "PREMARKET",
            f"장전 리포트 {d.isoformat()}",
            "장전 리포트가 도착했습니다.",
            {"type": "PREMARKET", "date": d.isoformat(), "gate_on": payload["daytrade_gate"]["on"]},
        )


def _startup_catchup_premarket_if_missing() -> None:
    if not getattr(settings, "prewarm_enabled", True):
        return
    try:
        now_ts = datetime.now(tz=SEOUL)
        hour = int(getattr(settings, "prewarm_premarket_hour", 8))
        minute = int(getattr(settings, "prewarm_premarket_minute", 20))
        scheduled_today = now_ts.replace(hour=hour, minute=minute, second=0, microsecond=0)
        # Do not pre-generate before the regular prewarm window.
        if now_ts < scheduled_today:
            return

        delay_sec = max(0, min(120, int(getattr(settings, "prewarm_startup_delay_sec", 3))))
        if delay_sec > 0:
            time.sleep(delay_sec)

        d = _today()
        daytrade_limit = max(3, min(200, int(getattr(settings, "prewarm_daytrade_limit", 10))))
        longterm_limit = max(3, min(200, int(getattr(settings, "prewarm_longterm_limit", 8))))

        with session_scope() as session:
            row = session.get(StrategySettings, 1)
            settings_payload = StrategySettingsPayload(
                algo_version=row.algo_version if row else "V2",
                risk_preset=row.risk_preset if row else "ADAPTIVE",
                use_custom_weights=row.use_custom_weights if row else False,
                w_ta=row.w_ta if row else None,
                w_re=row.w_re if row else None,
                w_rs=row.w_rs if row else None,
                theme_cap=row.theme_cap if row else 2,
                max_gap_pct=row.max_gap_pct if row else 0.0,
                gate_threshold=row.gate_threshold if row else 0.0,
                gate_quantile=row.gate_quantile if row else None,
            )
            settings_payload.algo_version = normalize_algo_version(settings_payload.algo_version)
            settings_hash = settings_payload.compute_hash()
            cache_key = premarket_cache_key(d, settings_hash, daytrade_limit, longterm_limit, settings_payload.algo_version)
            existing = session.scalar(
                select(Report.id)
                .where(Report.date == d, Report.type == "PREMARKET", Report.cache_key == cache_key)
                .limit(1)
            )
            if existing is not None:
                logger.info("startup premarket catch-up skipped: cache exists key=%s", cache_key)
                return

            strategy = StrategyConfig(
                algo_version=settings_payload.algo_version,
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

        logger.info("startup premarket catch-up start: key=%s", cache_key)
        payload, _diagnostics = generate_premarket_report(
            d,
            strategy,
            daytrade_limit=daytrade_limit,
            longterm_limit=longterm_limit,
        )
        payload.setdefault("status", {})
        payload["status"]["settings_hash"] = settings_hash
        payload["status"]["cache_key"] = cache_key
        payload["status"]["algo_version"] = settings_payload.algo_version

        with session_scope() as session:
            _upsert_report(session, d, "PREMARKET", payload, cache_key=cache_key)
        logger.info("startup premarket catch-up done: key=%s gate_on=%s", cache_key, payload.get("daytrade_gate", {}).get("on"))
    except Exception:
        logger.exception("startup premarket catch-up failed")


def job_eod() -> None:
    d = _today()
    payload = generate_eod(d)
    with session_scope() as session:
        cache_key = f"EOD:{d.isoformat()}"
        _upsert_report(session, d, "EOD", payload, cache_key=cache_key)
        send_and_log(
            session,
            "EOD",
            f"장후 성적표 {d.isoformat()}",
            "장후 성적표가 도착했습니다.",
            {"type": "EOD", "date": d.isoformat()},
        )


def job_us_insiders_prewarm() -> None:
    target_count = max(1, min(int(getattr(settings, "sec_prewarm_target_count", 10)), 30))
    trading_days = max(3, min(int(getattr(settings, "sec_prewarm_trading_days", 10)), 30))
    expand_days = max(trading_days, min(int(getattr(settings, "sec_prewarm_expand_days", 20)), 60))
    max_candidates = max(20, min(int(getattr(settings, "sec_prewarm_max_candidates", 120)), 300))
    transaction_codes = ("P", "A", "M", "F")
    transaction_codes_key = ",".join(transaction_codes)

    payload = compute_us_insider_screen(
        target_count=target_count,
        trading_days=trading_days,
        expand_days=expand_days,
        max_candidates=max_candidates,
        user_agent=settings.sec_user_agent,
        timeout_sec=settings.sec_timeout_sec,
        github_enrich_enabled=settings.sec_github_enrich_enabled,
        github_enrich_cik_limit=settings.sec_github_enrich_cik_limit,
        github_enrich_max_per_cik=settings.sec_github_enrich_max_per_cik,
        transaction_codes=transaction_codes,
    )

    def _json_default(v):
        if hasattr(v, "isoformat"):
            return v.isoformat()
        raise TypeError(f"not serializable: {type(v)}")

    d = _today()
    cache_key = _us_insider_cache_key(
        target_count,
        trading_days,
        expand_days,
        max_candidates,
        transaction_codes_key,
    )
    now = datetime.now(tz=SEOUL)
    with session_scope() as session:
        row = session.scalar(
            select(Report)
            .where(Report.date == d, Report.type == "US_INSIDERS", Report.cache_key == cache_key)
            .order_by(Report.generated_at.desc())
            .limit(1)
        )
        if row is None:
            row = session.scalar(
                select(Report)
                .where(Report.type == "US_INSIDERS", Report.cache_key == cache_key)
                .order_by(Report.generated_at.desc())
                .limit(1)
            )
        serialized = json.dumps(payload, ensure_ascii=False, default=_json_default)
        if row is None:
            session.add(
                Report(
                    date=d,
                    type="US_INSIDERS",
                    cache_key=cache_key,
                    payload_json=serialized,
                    generated_at=now,
                )
            )
        else:
            row.date = d
            row.type = "US_INSIDERS"
            row.cache_key = cache_key
            row.payload_json = serialized
            row.generated_at = now


def start_scheduler() -> None:
    global _scheduler
    if _scheduler and _scheduler.running:
        return
    _scheduler = BackgroundScheduler(timezone=SEOUL)
    if getattr(settings, "prewarm_enabled", True):
        hour = int(getattr(settings, "prewarm_premarket_hour", 8))
        minute = int(getattr(settings, "prewarm_premarket_minute", 20))
        _scheduler.add_job(job_premarket, "cron", hour=hour, minute=minute, id="premarket")
    if getattr(settings, "ticker_tags_enabled", False):
        th = int(getattr(settings, "ticker_tags_refresh_hour", 6))
        tm = int(getattr(settings, "ticker_tags_refresh_minute", 0))

        def job_refresh_tags():
            with session_scope() as session:
                # Baseline tags from pykrx sector/industry (safe default if no manual mapping exists).
                refresh_from_pykrx_sector(session)
                refresh_from_csv(session)

        _scheduler.add_job(job_refresh_tags, "cron", hour=th, minute=tm, id="ticker_tags_refresh")
    if getattr(settings, "sec_prewarm_enabled", True):
        interval_min = max(5, min(120, int(getattr(settings, "sec_prewarm_interval_min", 20))))
        _scheduler.add_job(
            job_us_insiders_prewarm,
            "interval",
            minutes=interval_min,
            id="us_insiders_prewarm",
            max_instances=1,
            coalesce=True,
        )
    # News fetch (hybrid: DART + RSS) runs via a single in-process worker queue.
    if getattr(settings, "news_enable_dart", True) or getattr(settings, "news_enable_rss", True):
        interval_s = max(60, min(3600, int(getattr(settings, "news_fetch_interval_s", 300))))
        _scheduler.add_job(
            enqueue_news_fetch,
            "interval",
            seconds=interval_s,
            id="news_fetch",
            max_instances=1,
            coalesce=True,
        )
    poll_sec = max(5, min(120, int(getattr(settings, "autotrade_reservation_poll_sec", 20))))
    batch_size = max(1, min(200, int(getattr(settings, "autotrade_reservation_batch_size", 30))))

    def job_autotrade_reservations():
        with session_scope() as session:
            processed = process_due_reservations(session, batch_size=batch_size)
            if processed > 0:
                logger.info("autotrade reservation worker processed=%s", processed)

    _scheduler.add_job(
        job_autotrade_reservations,
        "interval",
        seconds=poll_sec,
        id="autotrade_reservations",
        max_instances=1,
        coalesce=True,
    )
    if bool(getattr(settings, "autotrade_engine_enabled", True)):
        default_poll = max(3, min(120, int(getattr(settings, "autotrade_engine_poll_sec", 20))))
        exit_poll_sec = max(3, min(120, int(getattr(settings, "autotrade_engine_exit_poll_sec", 5))))
        entry_poll_sec = max(3, min(120, int(getattr(settings, "autotrade_engine_entry_poll_sec", default_poll))))
        _scheduler.add_job(
            job_autotrade_exit_engine,
            "interval",
            seconds=exit_poll_sec,
            id="autotrade_engine_exit",
            max_instances=1,
            coalesce=True,
        )
        _scheduler.add_job(
            job_autotrade_entry_engine,
            "interval",
            seconds=entry_poll_sec,
            id="autotrade_engine_entry",
            max_instances=1,
            coalesce=True,
        )
    _scheduler.add_job(job_eod, "cron", hour=15, minute=45, id="eod")
    _scheduler.start()
    if getattr(settings, "prewarm_catchup_on_startup", True):
        threading.Thread(target=_startup_catchup_premarket_if_missing, daemon=True).start()


def stop_scheduler() -> None:
    global _scheduler
    if _scheduler:
        _scheduler.shutdown(wait=False)
        _scheduler = None
