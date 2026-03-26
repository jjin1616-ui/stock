from __future__ import annotations

import logging
import os
from dataclasses import dataclass
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, time
from threading import Lock, Thread
from zoneinfo import ZoneInfo

import pandas as pd

from app.config import settings
from app.realtime_quotes import fetch_quotes
from engine.krx_api import KrxApiConfig, fetch_daily_market

logger = logging.getLogger("stock.movers2")
SEOUL = ZoneInfo(settings.app_tz)

SESSION_ORDER = ("preopen", "regular", "spike", "closecall", "afterclose", "afterauction")
ACTIVE_SESSION_ORDER = SESSION_ORDER + ("closed",)
SESSION_LABEL = {
    "preopen": "장전(예상체결)",
    "regular": "정규장 급등",
    "spike": "정규장 급등 스파이크",
    "closecall": "장마감(예상체결)",
    "afterclose": "시간외 종가(장후)",
    "afterauction": "시간외 단일가",
}
SESSION_RULES = {
    "preopen": {"min_pct": 2.0, "min_value": 50_000_000.0, "metric_name": "gap_pct"},
    "regular": {"min_pct": max(3.0, float(getattr(settings, "movers_min_chg_pct", 3.0))), "min_value": 500_000_000.0, "metric_name": "chg_pct"},
    "spike": {"min_ratio": 1.2, "min_value": 300_000_000.0, "metric_name": "value_ratio_adj"},
    "closecall": {"min_pct": 2.0, "min_value": 1_000_000_000.0, "metric_name": "close_gap_pct"},
    "afterclose": {"min_pct": 0.8, "min_value": 30_000_000.0, "metric_name": "after_pct"},
    "afterauction": {"min_pct": 1.5, "min_value": 50_000_000.0, "metric_name": "after_auction_pct"},
}


@dataclass
class _CacheEntry:
    expires_at: datetime
    stale_expires_at: datetime
    payload: dict


@dataclass
class _UniverseCacheEntry:
    expires_at: datetime
    bas_dd: str
    universe: pd.DataFrame


@dataclass
class _BasDdCacheEntry:
    expires_at: datetime
    bas_dd: str


@dataclass
class _SessionSnapshotEntry:
    captured_at: datetime
    bas_dd: str
    items: list[dict]


_cache: dict[str, _CacheEntry] = {}
_universe_cache: dict[str, _UniverseCacheEntry] = {}
_bas_dd_cache: _BasDdCacheEntry | None = None
_session_snapshot_cache: dict[str, _SessionSnapshotEntry] = {}
_refreshing: set[str] = set()
_lock = Lock()
_TTL_SECONDS = 8
_STALE_TTL_SECONDS = max(_TTL_SECONDS + 2, min(120, int(os.getenv("MOVERS2_STALE_TTL_SECONDS", "30"))))
_UNIVERSE_TTL_SECONDS = max(30, min(900, int(os.getenv("MOVERS2_UNIVERSE_TTL_SECONDS", "120"))))
_BAS_DD_CACHE_SECONDS = max(30, min(3600, int(os.getenv("MOVERS2_BASDD_CACHE_SECONDS", "300"))))
_KRX_FETCH_WORKERS = max(1, min(4, int(os.getenv("MOVERS2_KRX_FETCH_WORKERS", "2"))))
_SNAPSHOT_MAX_AGE_HOURS = max(2, min(72, int(os.getenv("MOVERS2_SNAPSHOT_MAX_AGE_HOURS", "24"))))
_SNAPSHOT_ITEM_LIMIT = max(50, min(300, int(os.getenv("MOVERS2_SNAPSHOT_ITEM_LIMIT", "180"))))


def _store_cache(cache_key: str, payload: dict, *, now_ts: datetime | None = None) -> None:
    ts = now_ts or datetime.now(tz=SEOUL)
    with _lock:
        _cache[cache_key] = _CacheEntry(
            expires_at=ts + timedelta(seconds=_TTL_SECONDS),
            stale_expires_at=ts + timedelta(seconds=_STALE_TTL_SECONDS),
            payload=payload,
        )


def _normalize_algo_version(value: str | None) -> str:
    v = (value or "").strip().upper()
    return "V1" if v == "V1" else "V2"


def _to_float(v: object) -> float:
    try:
        if isinstance(v, str):
            return float(v.replace(",", "").strip())
        return float(v)
    except Exception:
        return 0.0


def _naver_logo_urls(ticker: str) -> tuple[str | None, str | None]:
    code = str(ticker or "").strip()
    if len(code) != 6 or not code.isdigit():
        return None, None
    return (
        f"https://ssl.pstatic.net/imgstock/fn/real/logo/stock/Stock{code}.svg",
        f"https://ssl.pstatic.net/imgstock/fn/real/logo/png/stock/Stock{code}.png",
    )


def _cfg() -> KrxApiConfig:
    return KrxApiConfig(
        api_key=settings.krx_api_key,
        endpoint_kospi=settings.krx_endpoint_kospi,
        endpoint_kosdaq=settings.krx_endpoint_kosdaq,
        endpoint_konex=settings.krx_endpoint_konex,
        cache_dir=f"{settings.data_dir}/krx_cache",
    )


def _fetch_market_frames(cfg: KrxApiConfig, bas_dd: str, markets: list[str]) -> list[pd.DataFrame]:
    frames: list[pd.DataFrame] = []
    if not markets:
        return frames
    if len(markets) == 1:
        m = markets[0]
        try:
            df = fetch_daily_market(cfg, bas_dd, m)
        except Exception:
            logger.exception("movers2: krx fetch failed market=%s bas_dd=%s", m, bas_dd)
            df = pd.DataFrame()
        if df is not None and not df.empty:
            frames.append(df)
        return frames

    with ThreadPoolExecutor(max_workers=min(_KRX_FETCH_WORKERS, len(markets))) as pool:
        futs = {pool.submit(fetch_daily_market, cfg, bas_dd, m): m for m in markets}
        for fut in as_completed(futs):
            m = futs[fut]
            try:
                df = fut.result()
            except Exception:
                logger.exception("movers2: krx fetch failed market=%s bas_dd=%s", m, bas_dd)
                continue
            if df is None or df.empty:
                continue
            frames.append(df)
    return frames


def _most_recent_bas_dd(cfg: KrxApiConfig, *, lookback_days: int = 20) -> str:
    now_ts = datetime.now(tz=SEOUL)
    global _bas_dd_cache
    with _lock:
        cached = _bas_dd_cache
        if cached and cached.expires_at > now_ts and cached.bas_dd:
            return cached.bas_dd

    today = now_ts.date()
    resolved = today.strftime("%Y%m%d")
    for i in range(1, max(2, lookback_days) + 1):
        d = today - timedelta(days=i)
        bas_dd = d.strftime("%Y%m%d")
        df = fetch_daily_market(cfg, bas_dd, "KOSPI")
        if df is not None and not df.empty:
            resolved = bas_dd
            break

    with _lock:
        _bas_dd_cache = _BasDdCacheEntry(
            expires_at=now_ts + timedelta(seconds=_BAS_DD_CACHE_SECONDS),
            bas_dd=resolved,
        )
    return resolved


def _active_session(now_ts: datetime, *, algo_version: str) -> str:
    t = now_ts.time()
    if time(8, 30) <= t < time(9, 0):
        return "preopen"
    if time(9, 0) <= t < time(15, 20):
        return "regular"
    if time(15, 20) <= t < time(15, 30):
        return "closecall"
    if time(15, 40) <= t < time(16, 0):
        return "afterclose"
    if time(16, 0) <= t < time(18, 0):
        return "afterauction"
    return "regular" if algo_version == "V1" else "closed"


def _session_progress(now_ts: datetime) -> float:
    start = now_ts.replace(hour=9, minute=0, second=0, microsecond=0)
    end = now_ts.replace(hour=15, minute=30, second=0, microsecond=0)
    if now_ts <= start:
        return 0.05
    if now_ts >= end:
        return 1.0
    total = (end - start).total_seconds()
    if total <= 0:
        return 1.0
    progress = (now_ts - start).total_seconds() / total
    return max(0.05, min(1.0, progress))


def _build_universe(
    base: pd.DataFrame,
    *,
    universe_top_value: int,
    universe_top_chg: int,
) -> pd.DataFrame:
    frame = base.copy()
    frame["ticker"] = frame["ISU_CD"].astype(str).str.zfill(6)
    frame = frame[frame["ticker"].str.match(r"^\d{6}$", na=False)]
    frame["baseline_value"] = frame.get("ACC_TRDVAL", 0.0).astype(float)
    frame["baseline_vol"] = frame.get("ACC_TRDVOL", 0.0).astype(float)
    frame["open"] = frame.get("TDD_OPNPRC", 0.0).astype(float)
    frame["close"] = frame.get("TDD_CLSPRC", 0.0).astype(float)
    frame["proxy_chg_pct"] = frame.apply(
        lambda r: ((float(r["close"]) / float(r["open"]) - 1.0) * 100.0) if float(r["open"] or 0.0) > 0 else 0.0,
        axis=1,
    )
    value_top = frame.sort_values("baseline_value", ascending=False).head(universe_top_value)
    chg_top = frame.assign(abs_proxy=frame["proxy_chg_pct"].abs()).sort_values("abs_proxy", ascending=False).head(universe_top_chg)
    uni = (
        pd.concat([value_top, chg_top], ignore_index=True)
        .drop_duplicates(subset=["ticker"])
        .sort_values("baseline_value", ascending=False)
        .reset_index(drop=True)
    )
    return uni


def _session_price(session: str, q) -> tuple[float, str, str]:
    price = _to_float(getattr(q, "price", 0.0))
    over_price = _to_float(getattr(q, "over_price", 0.0))
    over_status = str(getattr(q, "over_status", "") or "").strip()
    if session in {"regular", "spike"}:
        if price > 0:
            return price, "EXACT", "정규장 현재가 기준"
        return 0.0, "STALE", "정규장 현재가 미수신"
    if session in {"preopen", "closecall", "afterclose", "afterauction"} and over_price > 0:
        reason = f"세션 전용 가격 사용({over_status or 'OVER'})"
        return over_price, "EXACT", reason
    return price, "APPROX", "세션 전용 가격 미확보로 현재가 근사"


def _basis_price(session: str, q) -> tuple[float, str]:
    prev_close = _to_float(getattr(q, "prev_close", 0.0))
    last_price = _to_float(getattr(q, "price", 0.0))
    if session in {"afterclose", "afterauction"} and last_price > 0:
        return last_price, "당일 종가(근사)"
    return prev_close, "전일 종가"


def _session_flow(session: str, q) -> tuple[float, float, str]:
    value = _to_float(getattr(q, "value", 0.0))
    volume = _to_float(getattr(q, "volume", 0.0))
    if session in {"preopen", "closecall", "afterclose", "afterauction"}:
        over_value = _to_float(getattr(q, "over_value", 0.0))
        over_volume = _to_float(getattr(q, "over_volume", 0.0))
        if over_value > 0 or over_volume > 0:
            return over_value, over_volume, "SESSION"
        if value > 0 or volume > 0:
            return value, volume, "REGULAR_FALLBACK"
        return 0.0, 0.0, "MISSING"
    if value > 0 or volume > 0:
        return value, volume, "REGULAR"
    return 0.0, 0.0, "MISSING"


def _sort_items(items: list[dict], *, session_norm: str, direction_norm: str) -> None:
    if session_norm == "spike":
        items.sort(
            key=lambda x: (
                float(x.get("metric_value") or 0.0),
                abs(float(x.get("chg_pct") or 0.0)),
                float(x.get("value") or 0.0),
            ),
            reverse=True,
        )
    elif direction_norm == "down":
        items.sort(key=lambda x: (float(x.get("metric_value") or 0.0), -float(x.get("value") or 0.0)))
    else:
        items.sort(key=lambda x: (float(x.get("metric_value") or 0.0), float(x.get("value") or 0.0)), reverse=True)


def _snapshot_fresh(now_ts: datetime, captured_at: datetime) -> bool:
    max_age = timedelta(hours=_SNAPSHOT_MAX_AGE_HOURS)
    return (now_ts - captured_at) <= max_age


def compute_movers2(
    *,
    session: str = "regular",
    direction: str = "up",
    count: int = 100,
    universe_top_value: int = 500,
    universe_top_chg: int = 200,
    markets: list[str] | None = None,
    algo_version: str = "V2",
    _bypass_cache: bool = False,
) -> dict:
    now_ts = datetime.now(tz=SEOUL)
    algo_norm = _normalize_algo_version(algo_version)
    session_norm = (session or "").strip().lower()
    direction_norm = (direction or "").strip().lower()
    if session_norm not in SESSION_ORDER:
        session_norm = "regular"
    if direction_norm not in {"up", "down"}:
        direction_norm = "up"
    count = max(5, min(100, int(count)))
    universe_top_value = max(100, min(2000, int(universe_top_value)))
    universe_top_chg = max(50, min(1000, int(universe_top_chg)))
    mkts = [m.strip().upper() for m in (markets or ["KOSPI", "KOSDAQ"]) if m and m.strip()]
    if not mkts:
        mkts = ["KOSPI", "KOSDAQ"]

    cache_key = f"movers2:{algo_norm}:{session_norm}:{direction_norm}:{count}:{universe_top_value}:{universe_top_chg}:{','.join(mkts)}"
    if not _bypass_cache:
        stale_payload: dict | None = None
        should_refresh = False
        with _lock:
            hit = _cache.get(cache_key)
            if hit and hit.expires_at > now_ts:
                return hit.payload
            if hit and hit.stale_expires_at > now_ts:
                stale_payload = hit.payload
                if cache_key not in _refreshing:
                    _refreshing.add(cache_key)
                    should_refresh = True
        if stale_payload is not None:
            if should_refresh:
                def _refresh() -> None:
                    try:
                        compute_movers2(
                            session=session_norm,
                            direction=direction_norm,
                            count=count,
                            universe_top_value=universe_top_value,
                            universe_top_chg=universe_top_chg,
                            markets=mkts,
                            algo_version=algo_norm,
                            _bypass_cache=True,
                        )
                    except Exception:
                        logger.exception("movers2: stale refresh failed key=%s", cache_key)
                    finally:
                        with _lock:
                            _refreshing.discard(cache_key)
                Thread(target=_refresh, name=f"movers2-refresh-{session_norm}", daemon=True).start()
            return stale_payload

    active_session = _active_session(now_ts, algo_version=algo_norm)
    active_session_label = SESSION_LABEL.get(active_session, active_session)
    notes: list[str] = []
    notes.append(f"현재 활성 세션: {active_session_label}")
    if active_session == "closed":
        notes.append("현재는 정규 세션 비활성 구간입니다. SNAPSHOT/APPROX 품질을 우선 확인하세요.")
    if not settings.krx_api_key:
        payload = {
            "as_of": now_ts.isoformat(),
            "bas_dd": "",
            "session": session_norm,
            "session_label": SESSION_LABEL.get(session_norm, "정규장 급등"),
            "active_session": active_session,
            "direction": direction_norm,
            "data_state": "APPROX",
            "algo_version": algo_norm,
            "snapshot_as_of": None,
            "universe_count": 0,
            "candidate_quotes": 0,
            "session_progress": _session_progress(now_ts),
            "notes": ["KRX_API_KEY가 비어있어 급등 데이터를 생성할 수 없습니다."],
            "items": [],
        }
        _store_cache(cache_key, payload, now_ts=now_ts)
        return payload

    cfg = _cfg()
    bas_dd = _most_recent_bas_dd(cfg)
    universe_cache_key = f"{bas_dd}:{universe_top_value}:{universe_top_chg}:{','.join(mkts)}"
    with _lock:
        uni_hit = _universe_cache.get(universe_cache_key)
    if uni_hit and uni_hit.expires_at > now_ts and not uni_hit.universe.empty:
        universe = uni_hit.universe.copy()
    else:
        frames = _fetch_market_frames(cfg, bas_dd, mkts)
        if not frames:
            payload = {
                "as_of": now_ts.isoformat(),
                "bas_dd": bas_dd,
                "session": session_norm,
                "session_label": SESSION_LABEL.get(session_norm, "정규장 급등"),
                "active_session": active_session,
                "direction": direction_norm,
                "data_state": "APPROX",
                "algo_version": algo_norm,
                "snapshot_as_of": None,
                "universe_count": 0,
                "candidate_quotes": 0,
                "session_progress": _session_progress(now_ts),
                "notes": ["KRX 일간 데이터가 비어 있어 급등 데이터를 계산하지 못했습니다."],
                "items": [],
            }
            _store_cache(cache_key, payload, now_ts=now_ts)
            return payload
        base = pd.concat(frames, ignore_index=True)
        universe = _build_universe(base, universe_top_value=universe_top_value, universe_top_chg=universe_top_chg)
        with _lock:
            _universe_cache[universe_cache_key] = _UniverseCacheEntry(
                expires_at=now_ts + timedelta(seconds=_UNIVERSE_TTL_SECONDS),
                bas_dd=bas_dd,
                universe=universe.copy(),
            )

    tickers = universe["ticker"].astype(str).tolist()
    qmap = {q.ticker: q for q in fetch_quotes(tickers)}
    progress = _session_progress(now_ts)
    notes.append("유니버스: 전일 거래대금 Top + 변동성 프록시(시가/종가) Top 합집합")

    rules = SESSION_RULES[session_norm]
    min_value_base = float(rules.get("min_value", 0.0))
    min_pct_base = float(rules.get("min_pct", 0.0))
    min_ratio_base = float(rules.get("min_ratio", 0.0))
    if algo_norm == "V2":
        min_value_scale = max(0.25, progress) if session_norm in {"regular", "spike"} else 1.0
        min_value_eff = min_value_base * min_value_scale
        min_pct_eff = min_pct_base * (0.8 if session_norm in {"regular", "closecall"} and progress < 0.20 else 1.0)
    else:
        min_value_eff = min_value_base
        min_pct_eff = min_pct_base
    min_ratio_eff = min_ratio_base
    items: list[dict] = []
    soft_candidates: list[dict] = []
    for row in universe.itertuples(index=False):
        ticker = str(getattr(row, "ticker", ""))
        q = qmap.get(ticker)
        if q is None:
            continue
        logo_url, logo_png_url = _naver_logo_urls(ticker)
        price = _to_float(getattr(q, "price", 0.0))
        prev_close = _to_float(getattr(q, "prev_close", 0.0))
        if price <= 0 or prev_close <= 0:
            continue

        session_price, quality, quality_reason = _session_price(session_norm, q)
        if algo_norm == "V2" and session_norm in {"preopen", "closecall", "afterclose", "afterauction"} and quality != "EXACT":
            continue
        basis_price, basis_label = _basis_price(session_norm, q)
        if basis_price <= 0:
            continue
        metric_pct = ((session_price / basis_price) - 1.0) * 100.0
        chg_pct = ((price / prev_close) - 1.0) * 100.0
        value, volume, flow_source = _session_flow(session_norm, q)
        baseline_value = _to_float(getattr(row, "baseline_value", 0.0))
        value_ratio = (value / baseline_value) if (value > 0 and baseline_value > 0) else None
        denom = baseline_value * max(0.05, progress)
        value_ratio_adj = (value / denom) if (value > 0 and denom > 0) else None

        item_payload = {
            "ticker": ticker,
            "name": str(getattr(row, "ISU_NM", "") or ""),
            "market": str(getattr(row, "MKT_NM", "") or ""),
            "logo_url": logo_url,
            "logo_png_url": logo_png_url,
            "tags": [],
            "price": price,
            "prev_close": prev_close,
            "chg_pct": chg_pct,
            "as_of": getattr(q, "as_of", now_ts).isoformat() if getattr(q, "as_of", None) else now_ts.isoformat(),
            "source": str(getattr(q, "source", "") or ""),
            "is_live": bool(getattr(q, "is_live", False)),
            "volume": volume,
            "value": value,
            "baseline_value": baseline_value if baseline_value > 0 else None,
            "value_ratio": value_ratio,
            "session": session_norm,
            "flow_source": flow_source,
            "quality": quality,
            "quality_reason": quality_reason,
            "session_price": session_price,
            "basis_price": basis_price,
            "basis_label": basis_label,
            "over_status": str(getattr(q, "over_status", "") or ""),
        }

        if session_norm == "spike":
            item_payload["metric_name"] = str(rules["metric_name"])
            item_payload["metric_value"] = float(value_ratio_adj or 0.0)
            soft_candidates.append(item_payload)
            if value < min_value_eff:
                continue
            if value_ratio_adj is None or value_ratio_adj < min_ratio_eff:
                continue
            if direction_norm == "up" and chg_pct < 0:
                continue
            if direction_norm == "down" and chg_pct > 0:
                continue
            metric_name = str(rules["metric_name"])
            metric_value = float(value_ratio_adj)
        else:
            item_payload["metric_name"] = str(rules["metric_name"])
            item_payload["metric_value"] = metric_pct
            soft_candidates.append(item_payload)
            if value < min_value_eff:
                continue
            if direction_norm == "up" and metric_pct < min_pct_eff:
                continue
            if direction_norm == "down" and metric_pct > -min_pct_eff:
                continue
            metric_name = str(rules["metric_name"])
            metric_value = metric_pct

        item_payload["metric_name"] = metric_name
        item_payload["metric_value"] = metric_value
        items.append(item_payload)

    _sort_items(items, session_norm=session_norm, direction_norm=direction_norm)

    is_active_session = session_norm == active_session and active_session in SESSION_ORDER
    data_state = "LIVE" if is_active_session else "APPROX"
    snapshot_as_of: str | None = None

    # When the requested session is inactive, prefer the last confirmed session snapshot.
    if not is_active_session:
        snapshot: _SessionSnapshotEntry | None = None
        with _lock:
            snapshot = _session_snapshot_cache.get(session_norm)
        if snapshot and snapshot.items and _snapshot_fresh(now_ts, snapshot.captured_at):
            items = [dict(x) for x in snapshot.items]
            snapshot_as_of = snapshot.captured_at.isoformat()
            data_state = "SNAPSHOT"
            notes.append(
                f"요청 세션({SESSION_LABEL.get(session_norm, session_norm)}) 비활성 구간이라 마지막 확정 스냅샷({snapshot_as_of})을 제공합니다."
            )

    allow_soft_fallback = bool(soft_candidates) and (algo_norm == "V1" or is_active_session)
    if not items and allow_soft_fallback:
        notes.append("엄격 필터 통과 종목이 부족해 완화 정렬(soft fallback)로 상위 후보를 표시합니다.")
        items = list(soft_candidates)
        _sort_items(items, session_norm=session_norm, direction_norm=direction_norm)
        if not is_active_session and data_state != "SNAPSHOT":
            notes.append(
                f"요청 세션({SESSION_LABEL.get(session_norm, session_norm)}) 비활성 구간이라 현재 시점 근사치로 산출했습니다."
            )
    elif not items and soft_candidates and algo_norm == "V2" and not is_active_session:
        notes.append("비활성 세션에서는 soft fallback 노출을 차단합니다. 스냅샷 또는 활성 세션에서 확인하세요.")

    if is_active_session and items:
        snapshot_items = [dict(x) for x in items[:_SNAPSHOT_ITEM_LIMIT]]
        with _lock:
            _session_snapshot_cache[session_norm] = _SessionSnapshotEntry(
                captured_at=now_ts,
                bas_dd=bas_dd,
                items=snapshot_items,
            )

    returned_items = items[:count]
    payload = {
        "as_of": now_ts.isoformat(),
        "bas_dd": bas_dd,
        "session": session_norm,
        "session_label": SESSION_LABEL.get(session_norm, "정규장 급등"),
        "active_session": active_session,
        "direction": direction_norm,
        "data_state": data_state,
        "algo_version": algo_norm,
        "snapshot_as_of": snapshot_as_of,
        "universe_count": int(len(universe)),
        "candidate_quotes": int(len(qmap)),
        "session_progress": progress,
        "notes": notes,
        "items": returned_items,
    }
    missing_flow = sum(1 for x in returned_items if str(x.get("flow_source") or "") == "MISSING")
    if missing_flow > 0:
        notes.append(f"세션 거래대금/거래량 미수신 {missing_flow}건")
    _store_cache(cache_key, payload, now_ts=now_ts)
    return payload
