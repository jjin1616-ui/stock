from __future__ import annotations

from dataclasses import dataclass
from datetime import timedelta
import os

import numpy as np
import pandas as pd

from .config import Config, Preset
from .data_sources import load_benchmark, load_ohlcv
from .features import compute_value, feat_re, feat_rs, feat_ta, robust_z, winsorize
from .gate import apply_gate, compute_gate, daily_mean_r
from .krx_api import KrxApiConfig, fetch_daily_market
from .kpi import compute_kpi
from .scoring import compute_score
from .simulate import simulate_trades
from .themes import apply_theme_cap_with_rejections, build_themes
from .universe import filter_universe, load_universe


@dataclass
class EngineArtifacts:
    kpi_grid: pd.DataFrame
    top10_daily: pd.DataFrame
    trade_log: pd.DataFrame
    gate_daily: pd.DataFrame
    rejected_candidates: pd.DataFrame


_LAST: EngineArtifacts | None = None


def _window_dates(cfg: Config) -> tuple[pd.Timestamp, pd.Timestamp]:
    end = pd.Timestamp(cfg.end)
    start = end - pd.tseries.offsets.BDay(cfg.lookback_days + cfg.ta_long + cfg.theme_lookback + 5)
    return start.normalize(), end.normalize()

def _prepare_panel_krx_api(cfg: Config, *, start: pd.Timestamp, end: pd.Timestamp) -> tuple[pd.DataFrame, dict[str, pd.DataFrame], pd.Series, pd.Series, int, int]:
    """
    Prepare backtest panel using KRX Data API snapshots (preferred when KRX_API_KEY is set).

    Why: FinanceDataReader/pykrx scraping can fail in headless/cloud environments. KRX Data API is cacheable and stable.
    """
    api_key = os.getenv("KRX_API_KEY", "").strip()
    if not api_key:
        empty_uni = pd.DataFrame(columns=["Code", "Name", "Market", "Marcap"])
        return empty_uni, {}, pd.Series(dtype=float), pd.Series(dtype=float), 0, 0

    data_dir = os.getenv("STOCK_DATA_DIR") or os.getenv("DATA_DIR") or "/var/lib/stock-backend"
    cfg_api = KrxApiConfig(
        api_key=api_key,
        endpoint_kospi=os.getenv("KRX_ENDPOINT_KOSPI", "stk_bydd_trd"),
        endpoint_kosdaq=os.getenv("KRX_ENDPOINT_KOSDAQ", "ksq_bydd_trd"),
        endpoint_konex=os.getenv("KRX_ENDPOINT_KONEX", "knx_bydd_trd"),
        cache_dir=os.path.join(data_dir, "krx_cache"),
    )

    mkts: list[str] = []
    if cfg_api.endpoint_kospi and cfg_api.endpoint_kospi.lower() not in ("none", "null"):
        mkts.append("KOSPI")
    if cfg_api.endpoint_kosdaq and cfg_api.endpoint_kosdaq.lower() not in ("none", "null"):
        mkts.append("KOSDAQ")
    if not mkts:
        mkts = ["KOSPI", "KOSDAQ"]

    # Build a wide panel once (per basDd/market) instead of per-ticker network calls.
    all_rows: dict[str, list[dict]] = {}
    errors_count = 0
    skipped_count = 0
    date_list = pd.bdate_range(start=start, end=end).strftime("%Y%m%d").tolist()
    for bas_dd in date_list:
        for market in mkts:
            try:
                dfm = fetch_daily_market(cfg_api, bas_dd, market)  # cached on disk per basDd/market
            except Exception:
                errors_count += 1
                dfm = pd.DataFrame()
            if dfm is None or dfm.empty:
                continue
            for _, r in dfm.iterrows():
                code = str(r.get("ISU_CD", "")).zfill(6)
                if not (code.isdigit() and len(code) == 6):
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

    panel: dict[str, pd.DataFrame] = {}
    cap_map: dict[str, float] = {}
    name_map: dict[str, str] = {}
    market_map: dict[str, str] = {}

    min_len = max(cfg.ta_long + 5, 30)
    for code, rows in all_rows.items():
        df = pd.DataFrame(rows)
        if df.empty:
            continue
        df["date"] = pd.to_datetime(df["date"], format="%Y%m%d", errors="coerce")
        df = df.dropna(subset=["date"]).sort_values("date")
        df = df.set_index("date")
        if len(df) < min_len:
            skipped_count += 1
            continue

        df = df.rename(columns={"open": "Open", "high": "High", "low": "Low", "close": "Close", "volume": "Volume"})
        # Prefer KRX trading value (ACC_TRDVAL) for TA/value features.
        df["value"] = df["value"].astype(float)
        df["value_ma20"] = df["value"].rolling(20, min_periods=20).mean()
        df["ta"] = feat_ta(df["value"], cfg.ta_short, cfg.ta_long)
        df["re"] = feat_re(df)

        # Keep basic metadata.
        name_map[code] = str(df["name"].iloc[-1]) if "name" in df.columns else code
        market_map[code] = str(df["market"].iloc[-1]) if "market" in df.columns else "KOSDAQ"
        try:
            cap_map[code] = float(df["cap"].iloc[-1]) if "cap" in df.columns else 0.0
        except Exception:
            cap_map[code] = 0.0

        df["code"] = code
        df["name"] = name_map[code]
        df["market"] = market_map[code]
        panel[code] = df

    if not panel:
        empty_uni = pd.DataFrame(columns=["Code", "Name", "Market", "Marcap"])
        return empty_uni, {}, pd.Series(dtype=float), pd.Series(dtype=float), errors_count, skipped_count

    latest_value = []
    for code, df in panel.items():
        lv = float(df["value_ma20"].dropna().iloc[-1]) if df["value_ma20"].notna().any() else 0.0
        latest_value.append((code, lv))
    keep_codes = [c for c, _ in sorted(latest_value, key=lambda x: x[1], reverse=True)[: cfg.max_universe]]
    panel = {c: panel[c] for c in keep_codes}

    uni = pd.DataFrame(
        [
            {"Code": c, "Name": name_map.get(c, c), "Market": market_map.get(c, "KOSDAQ"), "Marcap": cap_map.get(c, 0.0)}
            for c in keep_codes
        ]
    )

    bench_kq = load_benchmark("KOSDAQ", start.date(), (end + timedelta(days=3)).date())
    bench_ks = load_benchmark("KOSPI", start.date(), (end + timedelta(days=3)).date())
    bench_kq_s = bench_kq["Close"].sort_index() if (bench_kq is not None and not bench_kq.empty and "Close" in bench_kq.columns) else pd.Series(dtype=float)
    bench_ks_s = bench_ks["Close"].sort_index() if (bench_ks is not None and not bench_ks.empty and "Close" in bench_ks.columns) else pd.Series(dtype=float)
    return uni, panel, bench_kq_s, bench_ks_s, errors_count, skipped_count


def _prepare_panel(cfg: Config) -> tuple[pd.DataFrame, dict[str, pd.DataFrame], pd.Series, pd.Series, int, int]:
    start, end = _window_dates(cfg)
    if os.getenv("KRX_API_KEY"):
        return _prepare_panel_krx_api(cfg, start=start, end=end)
    uni = filter_universe(load_universe())
    panel: dict[str, pd.DataFrame] = {}
    errors_count = 0
    skipped_count = 0

    for _, u in uni.iterrows():
        code = str(u["Code"]).zfill(6)
        market = u.get("Market", "KOSDAQ")
        name = u.get("Name", code)
        try:
            df = load_ohlcv(code, start.date(), (end + timedelta(days=3)).date())
            if df.empty or len(df) < max(cfg.ta_long + 5, 30):
                skipped_count += 1
                continue
            df = df.sort_index().copy()
            df["code"] = code
            df["name"] = name
            df["market"] = market
            df["value"] = compute_value(df)
            df["value_ma20"] = df["value"].rolling(20, min_periods=20).mean()
            df["ta"] = feat_ta(df["value"], cfg.ta_short, cfg.ta_long)
            df["re"] = feat_re(df)
            panel[code] = df
        except Exception:
            errors_count += 1

    if not panel:
        return uni.head(0), {}, pd.Series(dtype=float), pd.Series(dtype=float), errors_count, skipped_count

    latest_value = []
    for code, df in panel.items():
        lv = float(df["value_ma20"].dropna().iloc[-1]) if df["value_ma20"].notna().any() else 0.0
        latest_value.append((code, lv))
    keep_codes = [c for c, _ in sorted(latest_value, key=lambda x: x[1], reverse=True)[: cfg.max_universe]]
    panel = {c: panel[c] for c in keep_codes}
    uni = uni[uni["Code"].astype(str).str.zfill(6).isin(keep_codes)].copy()

    bench_kq = load_benchmark("KOSDAQ", start.date(), (end + timedelta(days=3)).date())["Close"].sort_index()
    bench_ks = load_benchmark("KOSPI", start.date(), (end + timedelta(days=3)).date())["Close"].sort_index()
    return uni, panel, bench_kq, bench_ks, errors_count, skipped_count


def _iter_trade_dates(panel: dict[str, pd.DataFrame], end: pd.Timestamp) -> list[pd.Timestamp]:
    dates = set()
    for df in panel.values():
        dates.update(df.index.tolist())
    all_dates = sorted([d for d in dates if d <= end])
    return all_dates


def _daily_candidates(
    dt: pd.Timestamp,
    panel: dict[str, pd.DataFrame],
    bench_kq: pd.Series,
    bench_ks: pd.Series,
    cfg: Config,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    rows = []
    returns = {}

    for code, df in panel.items():
        if dt not in df.index:
            continue
        loc = df.index.get_loc(dt)
        if isinstance(loc, slice) or isinstance(loc, np.ndarray):
            continue
        if loc < max(cfg.ta_long, cfg.rs_k, cfg.theme_lookback):
            continue
        if loc + 1 >= len(df.index):
            continue

        row_t = df.iloc[loc]
        row_n = df.iloc[loc + 1]
        value_ma20 = float(row_t["value_ma20"]) if pd.notna(row_t["value_ma20"]) else 0.0
        if value_ma20 < cfg.min_value_krw:
            continue

        bench = bench_kq if (cfg.benchmark_mode == "KOSDAQ_ONLY" or row_t["market"] == "KOSDAQ") else bench_ks
        if bench is None or getattr(bench, "empty", True):
            # Fallback: if benchmark isn't available, use absolute k-day return as RS proxy.
            rs = float(df["Close"].iloc[loc] / df["Close"].iloc[loc - cfg.rs_k] - 1.0)
        else:
            rs_series = feat_rs(df["Close"], bench, cfg.rs_k)
            rs = float(rs_series.iloc[loc]) if pd.notna(rs_series.iloc[loc]) else np.nan
        ta = float(row_t["ta"]) if pd.notna(row_t["ta"]) else np.nan
        re = float(row_t["re"]) if pd.notna(row_t["re"]) else np.nan
        if np.isnan(ta) or np.isnan(re) or np.isnan(rs):
            continue

        rows.append(
            {
                "date": row_n.name,
                "signal_date": dt,
                "code": code,
                "name": row_t["name"],
                "market": row_t["market"],
                "ta": ta,
                "re": re,
                "rs": rs,
                "value_ma20": value_ma20,
                "high_t": float(row_t["High"]),
                "next_high": float(row_n["High"]),
                "next_low": float(row_n["Low"]),
                "next_close": float(row_n["Close"]),
            }
        )

        rwin = df["Close"].pct_change().iloc[loc - cfg.theme_lookback + 1 : loc + 1]
        if len(rwin) == cfg.theme_lookback:
            returns[code] = rwin.values

    cdf = pd.DataFrame(rows)
    rdf = pd.DataFrame(returns, index=range(cfg.theme_lookback)) if returns else pd.DataFrame()
    return cdf, rdf


def _hhi(themes: pd.Series) -> float:
    if themes.empty:
        return 0.0
    freq = themes.value_counts(normalize=True)
    return float((freq**2).sum())


def _run_combo_base(
    cfg: Config,
    preset: Preset,
    theme_cap: int,
    panel: dict[str, pd.DataFrame],
    bench_kq: pd.Series,
    bench_ks: pd.Series,
    *,
    exit_policy: str = "EOD",
    costs: str = "BASE",
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    top10_rows = []
    top10_trade_rows = []
    topm_trade_rows = []
    rejected_rows = []

    for dt in _iter_trade_dates(panel, pd.Timestamp(cfg.end)):
        candidates, ret_df = _daily_candidates(dt, panel, bench_kq, bench_ks, cfg)
        if candidates.empty:
            continue

        candidates = candidates.copy()
        candidates["ta_w"] = winsorize(candidates["ta"], cfg.winsor_pct)
        candidates["re_w"] = winsorize(candidates["re"], cfg.winsor_pct)
        candidates["rs_w"] = winsorize(candidates["rs"], cfg.winsor_pct)
        candidates["z_ta"] = robust_z(candidates["ta_w"])
        candidates["z_re"] = robust_z(candidates["re_w"])
        candidates["z_rs"] = robust_z(candidates["rs_w"])

        feats = candidates[["z_ta", "z_re", "z_rs"]].copy()
        candidates["score"] = compute_score(feats=feats, preset=preset, value_ma20=candidates["value_ma20"])
        candidates = candidates.sort_values("score", ascending=False)

        theme_map = build_themes(ret_df, cfg.n_themes)
        selected, rejected = apply_theme_cap_with_rejections(candidates=candidates, theme_map=theme_map, theme_cap=theme_cap)
        if selected.empty:
            continue
        if not rejected.empty:
            rejected2 = rejected.copy()
            rejected2["date"] = pd.Timestamp(dt).date()
            rejected_rows.append(
                rejected2[
                    ["date", "code", "name", "score", "reason", "candidate_rank", "theme_id", "value_ma20", "rs", "ta", "re"]
                ]
            )

        selected = selected.copy()
        selected["entry"] = selected["high_t"] * 1.005
        selected["stop"] = selected["entry"] * 0.97
        selected["target"] = selected["entry"] * 1.06
        selected["top10_avg_value_krw"] = float(selected["value_ma20"].mean())
        selected["theme_hhi"] = _hhi(selected["theme_id"]) if "theme_id" in selected.columns else 0.0
        selected["theme_count"] = int(selected["theme_id"].nunique()) if "theme_id" in selected.columns else 0

        top10_rows.append(
            selected[
                [
                    "signal_date",
                    "date",
                    "code",
                    "name",
                    "market",
                    "score",
                    "theme_id",
                    "entry",
                    "target",
                    "stop",
                    "top10_avg_value_krw",
                    "theme_hhi",
                    "theme_count",
                ]
            ]
        )

        sim10 = simulate_trades(
            selected[["date", "code", "entry", "stop", "target", "next_high", "next_low", "next_close"]],
            exit_policy=exit_policy,
            costs=costs,
        )
        top10_trade_rows.append(sim10)

        topm = candidates.head(cfg.gate_M).copy()
        topm["entry"] = topm["high_t"] * 1.005
        topm["stop"] = topm["entry"] * 0.97
        topm["target"] = topm["entry"] * 1.06
        simm = simulate_trades(
            topm[["date", "code", "entry", "stop", "target", "next_high", "next_low", "next_close"]],
            exit_policy=exit_policy,
            costs=costs,
        )
        topm_trade_rows.append(simm)

    top10_daily = pd.concat(top10_rows, ignore_index=True) if top10_rows else pd.DataFrame()
    trade10 = pd.concat(top10_trade_rows, ignore_index=True) if top10_trade_rows else pd.DataFrame()
    tradem = pd.concat(topm_trade_rows, ignore_index=True) if topm_trade_rows else pd.DataFrame()
    rejected = pd.concat(rejected_rows, ignore_index=True) if rejected_rows else pd.DataFrame()
    return top10_daily, trade10, tradem, rejected


def run_grid_with_artifacts(cfg: Config) -> EngineArtifacts:
    uni, panel, bench_kq, bench_ks, errors_count, skipped_count = _prepare_panel(cfg)
    if not panel:
        empty = pd.DataFrame()
        return EngineArtifacts(kpi_grid=empty, top10_daily=empty, trade_log=empty, gate_daily=empty, rejected_candidates=empty)

    kpi_rows = []
    all_top10 = []
    all_trade = []
    all_gate = []
    all_rejected = []

    for pname in cfg.grid.preset_names:
        preset = cfg.presets[pname]
        for theme_cap in cfg.grid.theme_caps:
            for exit_policy in cfg.grid.exit_policies:
                for costs in cfg.grid.costs:
                    top10_daily, trade10, tradem, rejected = _run_combo_base(
                        cfg, preset, theme_cap, panel, bench_kq, bench_ks, exit_policy=exit_policy, costs=costs
                    )
                    if top10_daily.empty:
                        continue

                    if not rejected.empty:
                        rdf = rejected.copy()
                        rdf["preset"] = pname
                        rdf["theme_cap"] = theme_cap
                        rdf["exit_policy"] = exit_policy
                        rdf["costs"] = costs
                        all_rejected.append(rdf)

                    for gu in cfg.grid.gate_universes:
                        base_trade = trade10 if gu == "TOP10_BASED" else tradem
                        daily = daily_mean_r(base_trade.rename(columns={"R_exec": "R"}))
                        for n in cfg.grid.gate_lookbacks:
                            for th in cfg.grid.gate_thresholds:
                                gate_daily = compute_gate(daily=daily, N=n, threshold=th)
                                gated = apply_gate(trade_log=trade10, gate_daily=gate_daily)
                                meta_cols = ["date", "top10_avg_value_krw", "theme_hhi", "theme_count"]
                                meta = top10_daily[meta_cols].drop_duplicates(subset=["date"]) if not top10_daily.empty else pd.DataFrame()
                                kpi = compute_kpi(trade_log=gated, gate_daily=gate_daily, top10_meta=meta)
                                kpi.update(
                                    {
                                        "preset": pname,
                                        "theme_cap": theme_cap,
                                        "gate_lookback_N": n,
                                        "gate_threshold": th,
                                        "gate_universe": gu,
                                        "exit_policy": exit_policy,
                                        "costs": costs,
                                        "lookback_days": cfg.lookback_days,
                                        "errors_count": kpi.get("errors_count", 0) + errors_count,
                                        "skipped_count": kpi.get("skipped_count", 0) + skipped_count,
                                    }
                                )
                                kpi_rows.append(kpi)

                                gdf = gate_daily.copy()
                                gdf["preset"] = pname
                                gdf["theme_cap"] = theme_cap
                                gdf["gate_lookback_N"] = n
                                gdf["gate_threshold"] = th
                                gdf["gate_universe"] = gu
                                gdf["exit_policy"] = exit_policy
                                gdf["costs"] = costs
                                gdf["lookback_days"] = cfg.lookback_days
                                all_gate.append(gdf)

                                tdf = gated.copy()
                                tdf["preset"] = pname
                                tdf["theme_cap"] = theme_cap
                                tdf["gate_lookback_N"] = n
                                tdf["gate_threshold"] = th
                                tdf["gate_universe"] = gu
                                tdf["exit_policy"] = exit_policy
                                tdf["costs"] = costs
                                tdf["lookback_days"] = cfg.lookback_days
                                all_trade.append(tdf)

                                top = top10_daily.copy()
                                top["preset"] = pname
                                top["theme_cap"] = theme_cap
                                top["gate_lookback_N"] = n
                                top["gate_threshold"] = th
                                top["gate_universe"] = gu
                                top["exit_policy"] = exit_policy
                                top["costs"] = costs
                                top["lookback_days"] = cfg.lookback_days
                                all_top10.append(top)

    kpi_grid = pd.DataFrame(kpi_rows)
    top10_df = pd.concat(all_top10, ignore_index=True) if all_top10 else pd.DataFrame()
    trade_df = pd.concat(all_trade, ignore_index=True) if all_trade else pd.DataFrame()
    gate_df = pd.concat(all_gate, ignore_index=True) if all_gate else pd.DataFrame()
    rejected_df = pd.concat(all_rejected, ignore_index=True) if all_rejected else pd.DataFrame()
    return EngineArtifacts(kpi_grid=kpi_grid, top10_daily=top10_df, trade_log=trade_df, gate_daily=gate_df, rejected_candidates=rejected_df)


def run_grid(cfg: Config) -> pd.DataFrame:
    global _LAST
    _LAST = run_grid_with_artifacts(cfg)
    return _LAST.kpi_grid


def get_last_artifacts() -> EngineArtifacts | None:
    return _LAST
