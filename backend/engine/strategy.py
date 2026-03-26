from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
import hashlib
import json
import logging
import os
from typing import Literal

import numpy as np
import pandas as pd

from .costs import round_to_tick, tick_size
from .data_sources import load_benchmark, load_ohlcv
from .krx_api import KrxApiConfig, fetch_daily_market
from .features import compute_value, feat_re, feat_ta, robust_z, winsorize
from .simulate import simulate_trades
from .themes import build_themes
from .universe import filter_universe, load_universe

logger = logging.getLogger("stock.strategy")

Market = Literal["KOSPI", "KOSDAQ"]

DAYTRADE_LIMIT = int(os.getenv("DAYTRADE_LIMIT", "100"))
LONGTERM_LIMIT = int(os.getenv("LONGTERM_LIMIT", "100"))


@dataclass
class StrategySettings:
    algo_version: Literal["V1", "V2"] = "V2"
    risk_preset: str = "ADAPTIVE"
    use_custom_weights: bool = False
    w_ta: float | None = None
    w_re: float | None = None
    w_rs: float | None = None
    theme_cap: int = 2
    max_gap_pct: float = 0.0
    gate_threshold: float = 0.0
    gate_quantile: float | None = None
    gate_lookback: int = 20
    gate_M: int = 200
    min_trades_per_day: int = 10
    min_value_krw: float = 5e9
    long_min_value_krw: float = 2e9
    long_trend_mode: Literal["MA60"] = "MA60"


PRESET_WEIGHTS: dict[str, tuple[float, float, float]] = {
    "DEFENSIVE": (0.30, 0.40, 0.30),
    "ADAPTIVE": (0.40, 0.25, 0.35),
    "AGGRESSIVE": (0.50, 0.20, 0.30),
}


def normalize_algo_version(value: str | None) -> Literal["V1", "V2"]:
    v = (value or "").strip().upper()
    return "V1" if v == "V1" else "V2"


def _resolve_weights(settings: StrategySettings) -> tuple[float, float, float]:
    if settings.use_custom_weights and all(v is not None for v in (settings.w_ta, settings.w_re, settings.w_rs)):
        return float(settings.w_ta), float(settings.w_re), float(settings.w_rs)
    return PRESET_WEIGHTS.get(settings.risk_preset.upper(), PRESET_WEIGHTS["ADAPTIVE"])


def _trading_calendar(bench_kq: pd.Series, bench_ks: pd.Series) -> list[pd.Timestamp]:
    dates = set()
    if bench_kq is not None and not bench_kq.empty:
        dates.update(bench_kq.index.tolist())
    if bench_ks is not None and not bench_ks.empty:
        dates.update(bench_ks.index.tolist())
    return sorted(dates)


def _prev_trading_day(dates: list[pd.Timestamp], d: date) -> pd.Timestamp | None:
    if not dates:
        return None
    target = pd.Timestamp(d)
    prior = [x for x in dates if x < target]
    return prior[-1] if prior else None


def _rolling_trading_days(dates: list[pd.Timestamp], end: pd.Timestamp, n: int) -> list[pd.Timestamp]:
    if not dates:
        return []
    prior = [x for x in dates if x <= end]
    return prior[-n:] if len(prior) >= n else prior


def _theme_cache_path(base_dir: str) -> str:
    return os.path.join(base_dir, "theme_cache.json")


def _load_theme_cache(path: str) -> dict[str, object]:
    if not os.path.exists(path):
        return {}
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        logger.exception("failed to load theme cache")
        return {}


def _save_theme_cache(path: str, payload: dict[str, object]) -> None:
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
    except Exception:
        logger.exception("failed to save theme cache")


def _compute_theme_map(
    returns_df: pd.DataFrame,
    *,
    rebalance_every: int,
    trading_days: list[pd.Timestamp],
    signal_date: pd.Timestamp,
    cache_dir: str,
) -> tuple[pd.Series, float]:
    cache_path = _theme_cache_path(cache_dir)
    cache = _load_theme_cache(cache_path)
    last_rebalance = cache.get("last_rebalance")
    theme_map_raw = cache.get("theme_map")

    need_rebalance = True
    if isinstance(last_rebalance, str):
        try:
            last_dt = pd.Timestamp(last_rebalance)
            window = _rolling_trading_days(trading_days, signal_date, rebalance_every)
            if window and last_dt >= window[0]:
                need_rebalance = False
        except Exception:
            need_rebalance = True

    if not need_rebalance and isinstance(theme_map_raw, dict):
        ser = pd.Series({k: int(v) for k, v in theme_map_raw.items()}, name="theme_id")
        return ser, 0.0

    if returns_df.empty:
        return pd.Series(dtype="int64"), 100.0

    n_clusters = int(round(len(returns_df.columns) / 150.0))
    n_clusters = max(8, min(16, n_clusters))
    try:
        theme_map = build_themes(returns_df, n_clusters)
        payload = {
            "last_rebalance": signal_date.date().isoformat(),
            "theme_map": theme_map.to_dict(),
        }
        _save_theme_cache(cache_path, payload)
        return theme_map, 0.0
    except Exception:
        logger.exception("theme clustering failed; using fallback")
        labels: dict[str, int] = {}
        bucket = max(1, n_clusters)
        for c in returns_df.columns:
            key = str(c).encode("utf-8")
            stable = int(hashlib.md5(key).hexdigest(), 16)
            labels[str(c)] = stable % bucket
        payload = {
            "last_rebalance": signal_date.date().isoformat(),
            "theme_map": labels,
        }
        _save_theme_cache(cache_path, payload)
        return pd.Series(labels, name="theme_id"), 100.0


def _gap_pct(df: pd.DataFrame, loc: int) -> float | None:
    if loc <= 0:
        return None
    try:
        o = float(df["Open"].iloc[loc])
        prev_close = float(df["Close"].iloc[loc - 1])
        if prev_close <= 0:
            return None
        return (o / prev_close - 1.0) * 100.0
    except Exception:
        return None


def _candidate_rows(
    signal_date: pd.Timestamp,
    panel: dict[str, pd.DataFrame],
    bench_kq: pd.Series,
    bench_ks: pd.Series,
    settings: StrategySettings,
) -> tuple[pd.DataFrame, pd.DataFrame, int, int]:
    rows = []
    returns = {}
    errors_count = 0
    skipped_count = 0

    for code, df in panel.items():
        if signal_date not in df.index:
            continue
        loc = df.index.get_loc(signal_date)
        if isinstance(loc, slice) or isinstance(loc, np.ndarray):
            continue
        if loc < 30:
            continue
        row_t = df.iloc[loc]
        value_ma20 = float(row_t["value_ma20"]) if pd.notna(row_t["value_ma20"]) else 0.0
        if value_ma20 < settings.min_value_krw:
            continue

        gap = _gap_pct(df, loc)
        if settings.max_gap_pct > 0 and gap is not None and gap > settings.max_gap_pct:
            continue

        try:
            bench = bench_kq if row_t["market"] == "KOSDAQ" else bench_ks
            if pd.isna(row_t["ta"]) or pd.isna(row_t["re"]):
                continue
            asset_r5 = float(df["Close"].iloc[loc] / df["Close"].iloc[loc - 5] - 1.0)
            bench_r5 = np.nan
            if bench is not None and not bench.empty and signal_date in bench.index:
                try:
                    bench_r5 = float(bench.loc[signal_date] / bench.shift(5).loc[signal_date] - 1.0)
                except Exception:
                    bench_r5 = np.nan
            rs = asset_r5 - bench_r5 if pd.notna(bench_r5) else np.nan
            rows.append(
                {
                    "date": signal_date,
                    "code": code,
                    "name": row_t["name"],
                    "market": row_t["market"],
                    "ta": float(row_t["ta"]),
                    "re": float(row_t["re"]),
                    "rs": float(rs),
                    "value_ma20": value_ma20,
                    "high_t": float(row_t["High"]),
                    "close_t": float(row_t["Close"]),
                }
            )
            rwin = df["Close"].pct_change().iloc[loc - 19 : loc + 1]
            if len(rwin) == 20:
                returns[code] = rwin.values
        except Exception:
            errors_count += 1

    cdf = pd.DataFrame(rows)
    if not cdf.empty and cdf["rs"].isna().any():
        for market in ["KOSPI", "KOSDAQ"]:
            mask = (cdf["market"] == market)
            med = cdf.loc[mask, "rs"].median()
            if pd.isna(med):
                med = 0.0
            cdf.loc[mask & cdf["rs"].isna(), "rs"] = med
    rdf = pd.DataFrame(returns, index=range(20)) if returns else pd.DataFrame()
    return cdf, rdf, errors_count, skipped_count


def _apply_theme_cap(candidates: pd.DataFrame, theme_map: pd.Series, theme_cap: int, limit: int) -> pd.DataFrame:
    if candidates.empty:
        return candidates
    out = []
    counts: dict[int, int] = {}
    for _, row in candidates.sort_values("score", ascending=False).iterrows():
        code = row["code"]
        theme = int(theme_map.get(code, -1))
        used = counts.get(theme, 0)
        if used >= theme_cap:
            continue
        counts[theme] = used + 1
        row2 = row.copy()
        row2["theme_id"] = theme
        out.append(row2)
        if len(out) >= limit:
            break
    return pd.DataFrame(out)


def _compute_gate_daily(
    trading_days: list[pd.Timestamp],
    panel: dict[str, pd.DataFrame],
    settings: StrategySettings,
) -> pd.DataFrame:
    rows = []
    if len(trading_days) < 2:
        return pd.DataFrame(columns=["date", "daily_mean_R"])

    for dt in trading_days:
        next_idx = None
        for i, d in enumerate(trading_days):
            if d == dt and i + 1 < len(trading_days):
                next_idx = trading_days[i + 1]
                break
        if next_idx is None:
            continue

        daily_rows = []
        for code, df in panel.items():
            if dt not in df.index or next_idx not in df.index:
                continue
            loc = df.index.get_loc(dt)
            row_t = df.iloc[loc]
            value_ma20 = float(row_t["value_ma20"]) if pd.notna(row_t["value_ma20"]) else 0.0
            if value_ma20 < settings.min_value_krw:
                continue
            daily_rows.append(
                {
                    "code": code,
                    "date": next_idx.date(),
                    "value_ma20": value_ma20,
                    "entry": float(row_t["High"]) * 1.005,
                    "stop": float(row_t["High"]) * 1.005 * 0.97,
                    "target": float(row_t["High"]) * 1.005 * 1.06,
                    "next_high": float(df.loc[next_idx, "High"]),
                    "next_low": float(df.loc[next_idx, "Low"]),
                    "next_close": float(df.loc[next_idx, "Close"]),
                    "next_open": float(df.loc[next_idx, "Open"]),
                }
            )
        if not daily_rows:
            continue
        daily_df = pd.DataFrame(daily_rows)
        topm = daily_df.sort_values("value_ma20", ascending=False).head(settings.gate_M)
        sim = simulate_trades(
            topm[["date", "code", "entry", "stop", "target", "next_high", "next_low", "next_close", "next_open"]],
            exit_policy="EOD",
            costs="BASE",
        )
        entered = sim[sim["entered"] & sim["R"].notna()]
        if entered.empty or len(entered) < settings.min_trades_per_day:
            rows.append({"date": topm["date"].iloc[0], "daily_mean_R": np.nan})
        else:
            rows.append({"date": topm["date"].iloc[0], "daily_mean_R": float(entered["R"].mean())})
    return pd.DataFrame(rows)


def compute_gate_status(
    trading_days: list[pd.Timestamp],
    panel: dict[str, pd.DataFrame],
    settings: StrategySettings,
) -> tuple[bool, pd.DataFrame]:
    daily = _compute_gate_daily(trading_days, panel, settings)
    if daily.empty:
        return False, pd.DataFrame(columns=["date", "daily_mean_R", "gate_metric", "gate_on"])
    daily = daily.sort_values("date").copy()
    # 장중이면 당일 데이터 포함(shift=0), 장외면 기존 shift=1
    from .gate import _is_market_hours
    shift_n = 0 if _is_market_hours() else 1
    metric = daily["daily_mean_R"].rolling(settings.gate_lookback, min_periods=settings.gate_lookback).mean().shift(shift_n)
    daily["gate_metric"] = metric
    if settings.gate_quantile is not None:
        thresh = metric.quantile(settings.gate_quantile)
    else:
        thresh = settings.gate_threshold
    daily["gate_on"] = daily["gate_metric"] >= float(thresh)
    last_metric = daily["gate_metric"].dropna()
    gate_on = bool(daily["gate_on"].iloc[-1]) if not daily.empty else False
    if last_metric.empty:
        gate_on = False
    return gate_on, daily


def _build_longterm(
    signal_date: pd.Timestamp,
    panel: dict[str, pd.DataFrame],
    long_min_value: float,
    exclude_codes: set[str],
    cap_map: dict[str, float],
) -> list[dict[str, object]]:
    rows = []
    for code, df in panel.items():
        if code in exclude_codes:
            continue
        if signal_date not in df.index:
            continue
        loc = df.index.get_loc(signal_date)
        if loc < 20:
            continue
        row_t = df.iloc[loc]
        value_ma20 = float(row_t["value_ma20"]) if pd.notna(row_t["value_ma20"]) else 0.0
        if value_ma20 < long_min_value:
            continue
        ret20 = float(df["Close"].iloc[loc] / df["Close"].iloc[loc - 20] - 1.0) if loc >= 20 else None
        if ret20 is None or ret20 <= 0:
            continue
        close = float(row_t["Close"])
        buy_low = round_to_tick(close * 0.97, "DOWN")
        buy_high = round_to_tick(close * 1.01, "UP")
        target = round_to_tick(close * 1.25, "UP")
        stop = round_to_tick(close * 0.85, "DOWN")
        if not (stop < buy_low < buy_high < target):
            continue
        rows.append(
            {
                "ticker": code,
                "name": row_t["name"],
                "market": row_t["market"],
                "d1_close": close,
                "buy_zone": {"low": buy_low, "high": buy_high},
                "target_12m": target,
                "stop_loss": stop,
                "thesis": "장기 추세 + 유동성 통과",
                "_cap": cap_map.get(code, 0.0),
            }
        )
    rows.sort(key=lambda x: x.get("_cap", 0.0), reverse=True)
    for r in rows:
        r.pop("_cap", None)
    return rows


def _atr_at(df: pd.DataFrame, loc: int, period: int = 14) -> tuple[float, float]:
    if loc < 1 or df.empty:
        return 0.0, 0.0
    high = pd.to_numeric(df["High"], errors="coerce")
    low = pd.to_numeric(df["Low"], errors="coerce")
    close = pd.to_numeric(df["Close"], errors="coerce")
    prev_close = close.shift(1)
    tr = pd.concat(
        [
            (high - low).abs(),
            (high - prev_close).abs(),
            (low - prev_close).abs(),
        ],
        axis=1,
    ).max(axis=1)
    atr = tr.rolling(period, min_periods=period).mean()
    try:
        atr_v = float(atr.iloc[loc]) if pd.notna(atr.iloc[loc]) else 0.0
        close_v = float(close.iloc[loc]) if pd.notna(close.iloc[loc]) else 0.0
    except Exception:
        return 0.0, 0.0
    if atr_v <= 0 or close_v <= 0:
        return 0.0, 0.0
    return atr_v, atr_v / close_v


def _market_regime(bench_ks: pd.Series, signal_date: pd.Timestamp) -> tuple[str, str]:
    """KOSPI 20일/60일 MA 비교로 시장 체제 판단.

    Returns:
        (regime, disclaimer)
        regime: "BULL" | "BEAR"
        disclaimer: 약세장일 때 면책 문구, 강세장이면 빈 문자열
    """
    if bench_ks is None or bench_ks.empty or signal_date not in bench_ks.index:
        return "BEAR", "⚠ 시장 데이터 부족 — 보수적 운용 권장"
    ma20 = bench_ks.rolling(20, min_periods=20).mean()
    ma60 = bench_ks.rolling(60, min_periods=60).mean()
    ma20_v = float(ma20.loc[signal_date]) if pd.notna(ma20.loc[signal_date]) else 0.0
    ma60_v = float(ma60.loc[signal_date]) if pd.notna(ma60.loc[signal_date]) else 0.0
    if ma20_v <= 0 or ma60_v <= 0:
        return "BEAR", "⚠ 시장 이동평균 계산 불가 — 보수적 운용 권장"
    if ma20_v >= ma60_v:
        return "BULL", ""
    return "BEAR", "⚠ KOSPI 약세 구간(MA20<MA60) — 장기 추천 축소, 보수적 운용 권장"


# 약세장 장타 Gate: 최소 score 상향, 추천 수 축소
_BEAR_LONGTERM_MIN_SCORE = 0.15   # 강세장엔 제한 없음
_BEAR_LONGTERM_MAX_PICKS = 5      # 강세장엔 제한 없음


def _build_longterm_v2(
    signal_date: pd.Timestamp,
    panel: dict[str, pd.DataFrame],
    long_min_value: float,
    exclude_codes: set[str],
    cap_map: dict[str, float],
    bench_kq: pd.Series,
    bench_ks: pd.Series,
) -> list[dict[str, object]]:
    # --- 시장 체제 Gate ---
    regime, disclaimer = _market_regime(bench_ks, signal_date)
    is_bear = regime == "BEAR"

    rows: list[dict[str, object]] = []
    for code, df in panel.items():
        if code in exclude_codes:
            continue
        if signal_date not in df.index:
            continue
        loc = df.index.get_loc(signal_date)
        if isinstance(loc, slice) or isinstance(loc, np.ndarray):
            continue
        if loc < 60:
            continue

        row_t = df.iloc[loc]
        value_ma20 = float(row_t["value_ma20"]) if pd.notna(row_t["value_ma20"]) else 0.0
        if value_ma20 < long_min_value:
            continue

        close_s = pd.to_numeric(df["Close"], errors="coerce")
        close = float(close_s.iloc[loc]) if pd.notna(close_s.iloc[loc]) else 0.0
        if close <= 0:
            continue
        ret60 = float(close / close_s.iloc[loc - 60] - 1.0) if loc >= 60 and close_s.iloc[loc - 60] > 0 else -1.0
        ret120 = float(close / close_s.iloc[loc - 120] - 1.0) if loc >= 120 and close_s.iloc[loc - 120] > 0 else ret60
        if ret60 <= 0:
            continue
        if ret120 < -0.05:
            continue

        ma20_s = close_s.rolling(20, min_periods=20).mean()
        ma60_s = close_s.rolling(60, min_periods=60).mean()
        ma20 = float(ma20_s.iloc[loc]) if pd.notna(ma20_s.iloc[loc]) else 0.0
        ma60 = float(ma60_s.iloc[loc]) if pd.notna(ma60_s.iloc[loc]) else 0.0
        if ma20 <= 0 or ma60 <= 0:
            continue
        if ma20 <= ma60 or close < ma60:
            continue

        w60 = close_s.iloc[loc - 59 : loc + 1].dropna() if loc >= 59 else close_s.iloc[: loc + 1].dropna()
        if w60.empty:
            continue
        peak = w60.cummax()
        dd60 = float((w60 / peak - 1.0).min())
        if dd60 < -0.30:
            continue

        bench = bench_kq if row_t["market"] == "KOSDAQ" else bench_ks
        rs60 = 0.0
        if bench is not None and not bench.empty and signal_date in bench.index:
            try:
                b_now = float(bench.loc[signal_date])
                b_prev = float(bench.shift(60).loc[signal_date])
                if b_now > 0 and b_prev > 0:
                    rs60 = ret60 - (b_now / b_prev - 1.0)
            except Exception:
                rs60 = 0.0
        if rs60 < -0.03:
            continue

        atr, atr_pct = _atr_at(df, int(loc), period=14)
        vol_band = atr_pct if atr_pct > 0 else 0.03
        buy_low = round_to_tick(close * (1.0 - max(0.03, min(0.08, vol_band * 2.2))), "DOWN")
        buy_high = round_to_tick(close * (1.0 + min(0.015, max(0.006, vol_band * 0.9))), "UP")
        stop = round_to_tick(close * (1.0 - max(0.12, min(0.25, vol_band * 5.0))), "DOWN")
        target = round_to_tick(close * (1.0 + max(0.22, ret60 * 1.8 + 0.10)), "UP")
        if not (stop < buy_low < buy_high < target):
            continue

        score = (ret60 * 0.45) + (ret120 * 0.20) + (rs60 * 0.20) + ((0.30 + dd60) * 0.15)

        # 약세장: 최소 점수 미달 종목 제외
        if is_bear and score < _BEAR_LONGTERM_MIN_SCORE:
            continue

        thesis_parts = [
            f"3개월 추세 +{ret60 * 100.0:.1f}%",
            f"중기 정렬(MA20>MA60)",
            f"시장 대비 강도 {rs60 * 100.0:+.1f}%",
            f"최근 낙폭 {dd60 * 100.0:.1f}%",
        ]
        # 약세장 면책 문구 추가
        if is_bear and disclaimer:
            thesis_parts.append(disclaimer)
        rows.append(
            {
                "ticker": code,
                "name": row_t["name"],
                "market": row_t["market"],
                "d1_close": close,
                "buy_zone": {"low": buy_low, "high": buy_high},
                "target_12m": target,
                "stop_loss": stop,
                "thesis": " · ".join(thesis_parts[:4]),
                "_score": score,
                "_cap": cap_map.get(code, 0.0),
            }
        )
    rows.sort(key=lambda x: (x.get("_score", -999.0), x.get("_cap", 0.0)), reverse=True)

    # 약세장: 추천 수 축소
    if is_bear:
        rows = rows[:_BEAR_LONGTERM_MAX_PICKS]

    for r in rows:
        r.pop("_score", None)
        r.pop("_cap", None)
    return rows


def generate_premarket(
    report_date: date,
    settings: StrategySettings,
    *,
    data_dir: str,
    daytrade_limit: int | None = None,
    longterm_limit: int | None = None,
) -> tuple[dict[str, object], dict[str, object]]:
    algo_version = normalize_algo_version(getattr(settings, "algo_version", None))

    def _clamp_limit(value: int | None, default: int) -> int:
        if value is None:
            return default
        try:
            v = int(value)
        except Exception:
            return default
        min_limit = 3 if algo_version == "V2" else 20
        return max(min_limit, min(200, v))

    daytrade_limit = _clamp_limit(daytrade_limit, DAYTRADE_LIMIT)
    longterm_limit = _clamp_limit(longterm_limit, LONGTERM_LIMIT)
    # Reduce KRX initial fetch window to speed up first-time generation
    end = pd.Timestamp(report_date)
    start = end - pd.tseries.offsets.BDay(180)
    panel: dict[str, pd.DataFrame] = {}
    cap_map: dict[str, float] = {}
    errors_count = 0
    skipped_count = 0

    # Prefer KRX Data API if API key is available
    from app.config import settings as app_settings
    krx_trading_days: list[pd.Timestamp] = []
    if app_settings.krx_api_key:
        # v2 needs a wider history window for 3M+/trend filters, while v1 keeps the old fast path.
        # Keep the window bounded to reduce first-generation latency on live servers.
        start = end - pd.tseries.offsets.BDay(100 if algo_version == "V2" else 60)
        cfg = KrxApiConfig(
            api_key=app_settings.krx_api_key,
            endpoint_kospi=app_settings.krx_endpoint_kospi,
            endpoint_kosdaq=app_settings.krx_endpoint_kosdaq,
            endpoint_konex=app_settings.krx_endpoint_konex,
            cache_dir=os.path.join(data_dir, "krx_cache"),
        )
        markets = []
        if app_settings.krx_endpoint_kospi and app_settings.krx_endpoint_kospi.lower() not in ("none", "null"):
            markets.append("KOSPI")
        if app_settings.krx_endpoint_kosdaq and app_settings.krx_endpoint_kosdaq.lower() not in ("none", "null"):
            markets.append("KOSDAQ")
        if app_settings.krx_endpoint_konex and app_settings.krx_endpoint_konex.lower() not in ("none", "null"):
            markets.append("KONEX")
        all_rows: dict[str, list[dict]] = {}
        date_list = pd.bdate_range(start=start, end=end).strftime("%Y%m%d").tolist()
        for bas_dd in date_list:
            for market in markets:
                dfm = fetch_daily_market(cfg, bas_dd, market)  # may be empty if endpoint invalid
                if dfm is None or dfm.empty:
                    continue
                try:
                    krx_trading_days.append(pd.to_datetime(bas_dd, format="%Y%m%d"))
                except Exception:
                    pass
                for _, r in dfm.iterrows():
                    code = str(r.get("ISU_CD", "")).zfill(6)
                    if not code:
                        continue
                    all_rows.setdefault(code, []).append(
                        {
                            "date": bas_dd,
                            "open": float(r.get("TDD_OPNPRC", 0.0)),
                            "high": float(r.get("TDD_HGPRC", 0.0)),
                            "low": float(r.get("TDD_LWPRC", 0.0)),
                            "close": float(r.get("TDD_CLSPRC", 0.0)),
                            "volume": float(r.get("ACC_TRDVOL", 0.0)),
                            "value": float(r.get("ACC_TRDVAL", 0.0)),
                            "name": str(r.get("ISU_NM", "")),
                            "market": str(r.get("MKT_NM", market)),
                            "cap": float(r.get("MKTCAP", 0.0)),
                        }
                    )

        for code, rows in all_rows.items():
            df = pd.DataFrame(rows)
            if df.empty:
                continue
            df["date"] = pd.to_datetime(df["date"], format="%Y%m%d", errors="coerce")
            df = df.dropna(subset=["date"]).sort_values("date")
            df = df.set_index("date")
            if len(df) < 30:
                skipped_count += 1
                continue
            df = df.rename(
                columns={
                    "open": "Open",
                    "high": "High",
                    "low": "Low",
                    "close": "Close",
                    "volume": "Volume",
                }
            )
            df["value"] = df["value"].astype(float)
            df["value_ma20"] = df["value"].rolling(20, min_periods=20).mean()
            df["ta"] = feat_ta(df["value"], 3, 20)
            df["re"] = feat_re(df)
            df["name"] = df["name"].iloc[-1]
            df["market"] = df["market"].iloc[-1]
            cap_map[code] = float(df["cap"].iloc[-1]) if "cap" in df.columns else 0.0
            panel[code] = df
    else:
        uni = filter_universe(load_universe())
        if uni.empty:
            return (
                {
                    "date": report_date.isoformat(),
                    "generated_at": datetime.now().isoformat(),
                    "status": {"source": "FALLBACK", "message": "유니버스 데이터 없음", "algo_version": algo_version},
                    "daytrade_gate": {"on": False, "lookback_days": settings.gate_lookback, "gate_metric": 0.0, "gate_on_days": 0, "gate_total_days": 0, "reason": ["데이터 부족"]},
                    "daytrade_top": [],
                    "daytrade_primary": [],
                    "daytrade_watch": [],
                    "daytrade_top10": [],
                    "longterm": [],
                    "longterm_top10": [],
                    "overlap_bucket": [],
                    "themes": [],
                    "hard_rules": ["데이터 수집 실패"],
                },
                {"errors_count": 0, "skipped_count": 0, "fallback_rate": 100.0},
            )
        for _, u in uni.iterrows():
            code = str(u["Code"]).zfill(6)
            name = u.get("Name", code)
            market = u.get("Market", "KOSDAQ")
            try:
                cap_map[code] = float(u.get("Marcap", 0.0)) if "Marcap" in u else 0.0
            except Exception:
                cap_map[code] = 0.0
            df = load_ohlcv(code, start.date(), (end + pd.Timedelta(days=3)).date())
            if df is None or df.empty or len(df) < 60:
                skipped_count += 1
                continue
            df = df.sort_index().copy()
            df["name"] = name
            df["market"] = market
            df["value"] = compute_value(df)
            df["value_ma20"] = df["value"].rolling(20, min_periods=20).mean()
            df["ta"] = feat_ta(df["value"], 3, 20)
            df["re"] = feat_re(df)
            panel[code] = df

    def _bench_series(market: str) -> pd.Series:
        dfb = load_benchmark(market, start.date(), (end + pd.Timedelta(days=3)).date())
        if dfb is None or dfb.empty or "Close" not in dfb.columns:
            return pd.Series(dtype=float)
        return dfb["Close"].sort_index()

    bench_kq = _bench_series("KOSDAQ")
    bench_ks = _bench_series("KOSPI")
    trading_days = _trading_calendar(bench_kq, bench_ks)
    if not trading_days and krx_trading_days:
        trading_days = sorted(set(krx_trading_days))
    if not trading_days and panel:
        dates = set()
        for df in panel.values():
            dates.update(df.index.tolist())
        trading_days = sorted(dates)
    signal_date = _prev_trading_day(trading_days, report_date)
    if signal_date is None:
        return (
            {
                "date": report_date.isoformat(),
                "generated_at": datetime.now().isoformat(),
                "status": {"source": "FALLBACK", "message": "거래일 정보 없음", "algo_version": algo_version},
                "daytrade_gate": {"on": False, "lookback_days": settings.gate_lookback, "gate_metric": 0.0, "gate_on_days": 0, "gate_total_days": 0, "reason": ["거래일 미확인"]},
                "daytrade_top": [],
                "daytrade_primary": [],
                "daytrade_watch": [],
                "daytrade_top10": [],
                "longterm": [],
                "longterm_top10": [],
                "overlap_bucket": [],
                "themes": [],
                "hard_rules": ["거래일 확인 실패"],
            },
            {"errors_count": errors_count, "skipped_count": skipped_count, "fallback_rate": 100.0},
        )

    candidates, returns_df, err_cnt, skip_cnt = _candidate_rows(signal_date, panel, bench_kq, bench_ks, settings)
    errors_count += err_cnt
    skipped_count += skip_cnt
    if candidates.empty:
        return (
            {
                "date": report_date.isoformat(),
                "generated_at": datetime.now().isoformat(),
                "status": {"source": "FALLBACK", "message": "후보 없음", "algo_version": algo_version},
                "daytrade_gate": {"on": False, "lookback_days": settings.gate_lookback, "gate_metric": 0.0, "gate_on_days": 0, "gate_total_days": 0, "reason": ["후보 없음"]},
                "daytrade_top": [],
                "daytrade_primary": [],
                "daytrade_watch": [],
                "daytrade_top10": [],
                "longterm": [],
                "longterm_top10": [],
                "overlap_bucket": [],
                "themes": [],
                "hard_rules": ["후보 부족"],
            },
            {"errors_count": errors_count, "skipped_count": skipped_count, "fallback_rate": 100.0},
        )

    w_ta, w_re, w_rs = _resolve_weights(settings)
    candidates["ta_w"] = winsorize(candidates["ta"], 0.02)
    candidates["re_w"] = winsorize(candidates["re"], 0.02)
    candidates["rs_w"] = winsorize(candidates["rs"], 0.02)
    candidates["z_ta"] = robust_z(candidates["ta_w"])
    candidates["z_re"] = robust_z(candidates["re_w"])
    candidates["z_rs"] = robust_z(candidates["rs_w"])

    # 시장 체제 기반 동적 가중치 적용 (벤치마크 있을 때만, 없으면 프리셋 폴백)
    from .scoring import classify_regime, REGIME_WEIGHTS
    _active_bench = bench_kq if not bench_kq.empty else bench_ks
    _regime = classify_regime(_active_bench, window=20) if not _active_bench.empty else None
    if _regime is not None:
        w_ta, w_re, w_rs = REGIME_WEIGHTS[_regime]
        logger.info("시장 체제=%s → 동적 가중치 TA=%.2f RE=%.2f RS=%.2f", _regime.value, w_ta, w_re, w_rs)
    else:
        logger.info("시장 체제 판단 불가 → 프리셋 폴백 가중치 TA=%.2f RE=%.2f RS=%.2f", w_ta, w_re, w_rs)

    candidates["score"] = w_ta * candidates["z_ta"] + w_re * candidates["z_re"] + w_rs * candidates["z_rs"]
    if algo_version == "V2":
        atr_pct_list: list[float] = []
        for _, row in candidates.iterrows():
            code = str(row.get("code") or "")
            df = panel.get(code)
            atr_pct = np.nan
            if df is not None and not df.empty and signal_date in df.index:
                loc = df.index.get_loc(signal_date)
                if not isinstance(loc, slice) and not isinstance(loc, np.ndarray):
                    _, atr_pct = _atr_at(df, int(loc), period=14)
            atr_pct_list.append(float(atr_pct) if pd.notna(atr_pct) else np.nan)
        candidates["atr_pct"] = atr_pct_list
        atr_fill = float(pd.Series(atr_pct_list).dropna().median()) if pd.Series(atr_pct_list).notna().any() else 0.03
        candidates["atr_pct_f"] = candidates["atr_pct"].fillna(atr_fill)
        candidates["liq_log"] = np.log1p(pd.to_numeric(candidates["value_ma20"], errors="coerce").fillna(0.0))
        candidates["z_atr"] = robust_z(candidates["atr_pct_f"])
        candidates["z_liq"] = robust_z(candidates["liq_log"])
        candidates["score"] = candidates["score"] + (0.12 * candidates["z_liq"]) - (0.10 * candidates["z_atr"])
    # --- 동적 z-score 임계값: 시장 변동성(ATR 중앙값) 기반 ---
    # 기본 0.6, 변동성 비례 조정, 범위 0.3~1.2
    _Z_BASE = 0.6
    _Z_MIN, _Z_MAX = 0.3, 1.2
    _NORMAL_VOL = 0.03  # 평균적 시장 ATR% 기준치
    if algo_version == "V2" and "atr_pct_f" in candidates.columns:
        _mkt_vol = float(candidates["atr_pct_f"].median())
        if _mkt_vol > 0 and _NORMAL_VOL > 0:
            # 변동성↑ → 임계값↓(더 많은 종목에 사유 부여), 변동성↓ → 임계값↑(엄선)
            z_threshold = max(_Z_MIN, min(_Z_MAX, _Z_BASE / (_mkt_vol / _NORMAL_VOL)))
        else:
            z_threshold = _Z_BASE
    else:
        z_threshold = _Z_BASE

    # build per-ticker thesis for explainability
    def _build_thesis(row: pd.Series) -> str:
        reasons: list[str] = []
        if row.get("z_ta", 0.0) >= z_threshold:
            reasons.append("수급 가속(TA) 상위")
        if row.get("z_re", 0.0) >= z_threshold:
            reasons.append("캔들 효율(RE) 우수")
        if row.get("z_rs", 0.0) >= z_threshold:
            reasons.append("시장 대비 강세(RS)")
        # add a liquidity hint to diversify reasons
        try:
            if float(row.get("value_ma20", 0.0)) >= settings.min_value_krw * 2:
                reasons.append("유동성 상위")
        except Exception:
            pass
        if algo_version == "V2":
            atr_pct = float(row.get("atr_pct_f", 0.0) or 0.0)
            if atr_pct <= 0.03:
                reasons.append("변동성 안정 구간")
            elif atr_pct >= 0.09:
                reasons.append("고변동(비중 축소)")
        if not reasons:
            reasons.append("점수 상위 + 테마 분산")
        return " · ".join(reasons[:3])

    candidates["thesis"] = candidates.apply(_build_thesis, axis=1)

    theme_map, fallback_rate = _compute_theme_map(
        returns_df,
        rebalance_every=5,
        trading_days=trading_days,
        signal_date=signal_date,
        cache_dir=data_dir,
    )
    # We select a larger pool than the final limit because some candidates are filtered out
    # later when building concrete trade levels (tick rounding constraints etc.).
    #
    # Important: theme_cap is for diversification, but users may request large lists (e.g. 60).
    # A strict theme_cap can cap the result size at roughly (theme_cap * number_of_themes),
    # which looks like "always 16" when theme_cap=2. To avoid that, we:
    # 1) take a diversified prefix via theme_cap
    # 2) then append the remaining high-score candidates without theme cap to fill the requested limit
    pool_limit = max(daytrade_limit * 4, daytrade_limit + 50)
    themed = _apply_theme_cap(candidates, theme_map, settings.theme_cap, limit=pool_limit)
    fallback_pool = candidates.sort_values("score", ascending=False).head(pool_limit).copy()
    if theme_map is not None and not theme_map.empty:
        if "theme_id" not in themed.columns:
            themed["theme_id"] = themed["code"].map(lambda c: int(theme_map.get(str(c), -1)))
        fallback_pool["theme_id"] = fallback_pool["code"].map(lambda c: int(theme_map.get(str(c), -1)))
    else:
        if "theme_id" not in themed.columns:
            themed["theme_id"] = -1
        fallback_pool["theme_id"] = -1

    if themed.empty:
        combined_pool = fallback_pool
    else:
        extra = fallback_pool[~fallback_pool["code"].isin(set(themed["code"].astype(str).tolist()))]
        combined_pool = pd.concat([themed, extra], ignore_index=True)

    gate_on, gate_daily = compute_gate_status(trading_days[-(settings.gate_lookback + 15) :], panel, settings)
    gate_metric_recent = float(gate_daily["gate_metric"].dropna().iloc[-1]) if not gate_daily.empty and gate_daily["gate_metric"].notna().any() else 0.0
    gate_on_days_recent = int(gate_daily.tail(60)["gate_on"].sum()) if not gate_daily.empty else 0
    gate_total_days = int(len(gate_daily.tail(60))) if not gate_daily.empty else 0

    def _to_items(df: pd.DataFrame, max_items: int) -> list[dict[str, object]]:
        out = []
        for _, r in df.iterrows():
            theme_id = None
            try:
                if "theme_id" in r and pd.notna(r["theme_id"]):
                    tid = int(r["theme_id"])
                    theme_id = tid if tid >= 0 else None
            except Exception:
                theme_id = None
            # Build trade levels robustly. With KRX daily sources (and tick rounding),
            # "high" can be missing/zero for some rows; fall back to close.
            try:
                base_ref = float(r.get("high_t", 0.0) or 0.0)
            except Exception:
                base_ref = 0.0
            if base_ref <= 0:
                try:
                    base_ref = float(r.get("close_t", 0.0) or 0.0)
                except Exception:
                    base_ref = 0.0
            if base_ref <= 0:
                continue

            atr = 0.0
            atr_pct = 0.0
            if algo_version == "V2":
                cdf = panel.get(str(r.get("code") or ""))
                if cdf is not None and not cdf.empty and signal_date in cdf.index:
                    loc = cdf.index.get_loc(signal_date)
                    if not isinstance(loc, slice) and not isinstance(loc, np.ndarray):
                        atr, atr_pct = _atr_at(cdf, int(loc), period=14)

            if algo_version == "V2":
                entry_buffer = min(0.008, max(0.0025, atr_pct * 0.35 if atr_pct > 0 else 0.005))
                entry = round_to_tick(base_ref * (1.0 + entry_buffer), "UP")
            else:
                entry = round_to_tick(base_ref * 1.005, "UP")
            if entry <= 0:
                continue
            if algo_version == "V2":
                risk_abs = max(entry * 0.018, atr * 1.6, float(tick_size(entry)))
                target_abs = max(risk_abs * 1.75, atr * 2.4, entry * 0.025)
                stop = round_to_tick(entry - risk_abs, "DOWN")
                target = round_to_tick(entry + target_abs, "UP")
            else:
                stop = round_to_tick(entry * 0.97, "DOWN")
                target = round_to_tick(entry * 1.06, "UP")

            # Ensure strict ordering after tick rounding.
            t = float(tick_size(entry))
            if stop >= entry:
                stop = max(entry - t, 0.0)
            if target <= entry:
                target = entry + t
            if not (stop < entry < target):
                continue
            out.append(
                {
                    "ticker": r["code"],
                    "name": r["name"],
                    "market": r["market"],
                    "theme_id": theme_id,
                    "trigger_buy": entry,
                    "target_1": target,
                    "stop_loss": stop,
                    "thesis": (
                        f"{str(r.get('thesis') or '점수 상위 + 테마 분산')} · ATR적응 레벨"
                        if algo_version == "V2"
                        else (r.get("thesis") or "점수 상위 + 테마 분산")
                    ),
                }
            )
            if len(out) >= max_items:
                break
        return out

    # Build up to the requested limit from the combined pool.
    daytrade_all = _to_items(combined_pool, daytrade_limit)
    primary_items = daytrade_all[:10]
    watch_items = daytrade_all[10:daytrade_limit]
    if not gate_on:
        # Keep top candidates even in gate-off mode, but mark them as conditional entries.
        gate_hint = f"조건부 진입(게이트OFF {gate_metric_recent:+.3f}) · 분할/소액 권장"
        primary_items = daytrade_all[: min(10, len(daytrade_all))]
        watch_items = daytrade_all[10:daytrade_limit]
        for r in primary_items:
            base = str(r.get("thesis") or "").strip()
            r["thesis"] = f"{gate_hint} · {base}" if base else gate_hint
        for r in watch_items:
            base = str(r.get("thesis") or "").strip()
            r["thesis"] = f"후순위 관망 · {base}" if base else "후순위 관망"

    longterm_exclude = set([r.get("ticker") for r in daytrade_all[:10] if r.get("ticker")])
    if algo_version == "V2":
        longterm = _build_longterm_v2(
            signal_date,
            panel,
            settings.long_min_value_krw,
            longterm_exclude,
            cap_map,
            bench_kq,
            bench_ks,
        )
    else:
        longterm = _build_longterm(signal_date, panel, settings.long_min_value_krw, longterm_exclude, cap_map)
    if theme_map is not None and not theme_map.empty:
        for r in longterm:
            try:
                tid = int(theme_map.get(str(r.get("ticker")), -1))
                r["theme_id"] = tid if tid >= 0 else None
            except Exception:
                r["theme_id"] = None

    # Theme summary (for UI filtering). Based on the selected universe (primary+watch).
    themes_summary: list[dict[str, object]] = []
    try:
        counts: dict[int, int] = {}
        for r in (primary_items + watch_items):
            tid = r.get("theme_id")
            if tid is None:
                continue
            try:
                t = int(tid)
            except Exception:
                continue
            if t < 0:
                continue
            counts[t] = counts.get(t, 0) + 1
        items = list(counts.items())
        items.sort(key=lambda x: (-x[1], x[0]))
        for i, (tid, cnt) in enumerate(items[:16], start=1):
            themes_summary.append({"rank": i, "name": f"테마 {tid + 1}", "why": f"{cnt}개"})
    except Exception:
        themes_summary = []

    payload = {
        "date": report_date.isoformat(),
        "generated_at": datetime.now().isoformat(),
        "status": {"source": "LIVE", "algo_version": algo_version},
        "daytrade_gate": {
            "on": bool(gate_on),
            "lookback_days": settings.gate_lookback,
            "gate_metric": gate_metric_recent,
            "gate_on_days": gate_on_days_recent,
            "gate_total_days": gate_total_days,
            "reason": ([f"gate off ({gate_metric_recent:+.3f})", "조건부 진입(분할/소액)"] if not gate_on else ["gate on"]),
        },
        "daytrade_top": primary_items,
        "daytrade_primary": primary_items,
        "daytrade_watch": watch_items,
        "daytrade_top10": primary_items[:10],
        "longterm": longterm[:longterm_limit],
        "longterm_top10": longterm[:10],
        "overlap_bucket": [],
        "themes": themes_summary,
        "hard_rules": [
            "일손실 한도 준수",
            "추격매수 금지",
            "gate off면 보수 모드(분할/소액)",
            ("V2: 변동성 적응형 진입/손절/목표" if algo_version == "V2" else "V1: 고정 비율 진입/손절/목표"),
        ],
    }

    diagnostics = {
        "algo_version": algo_version,
        "errors_count": errors_count,
        "skipped_count": skipped_count,
        "fallback_rate": fallback_rate,
        "gate_trade_days": gate_total_days,
    }
    return payload, diagnostics
