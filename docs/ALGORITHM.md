# KoreaStockDash — Algorithm Deep Dive (Daytrade / Longterm / Papers)

This document is a code-grounded explanation of the current algorithm rules.

Primary implementation: `backend/engine/strategy.py`

## Daytrade (단타)

### 1) Data + Universe
- Source:
  - Prefer KRX Data API (if `KRX_API_KEY` is set): `backend/engine/krx_api.py`
  - Fallback: `FinanceDataReader` / `pykrx` via `backend/engine/data_sources.py`
- Universe:
  - Loaded and filtered via `backend/engine/universe.py` (market/universe hygiene).

### 2) Liquidity + Gap Filters
- Liquidity proxy: `value = Close * Volume` and `value_ma20 = rolling mean(20)`
- Candidate filter:
  - `value_ma20 >= min_value_krw` (default comes from backend settings)
  - Optional gap filter: if `max_gap_pct > 0`, drop tickers where today's open gap exceeds this limit.

### 3) Features
- TA: `feat_ta(value, 3, 20)` (short/long value MA ratio, log)
- RE: `feat_re(df)` (|Close-Open| / |High-Low| : candle “efficiency”)
- RS: 5-day relative strength vs benchmark (KOSPI/KOSDAQ), computed in candidate loop.

Implementation: `backend/engine/features.py`, `backend/engine/strategy.py:_candidate_rows`

### 4) Scoring (Risk Preset Weights)
- Winsorize each feature at 2% tails.
- Robust Z-score:
  - `robust_z = (x - median) / (1.4826 * MAD)`
  - If MAD=0 → returns zeros (warning logged).
- Score:
  - `score = wTA*z(TA) + wRE*z(RE) + wRS*z(RS)`

Weights:
- `DEFENSIVE = (0.30, 0.40, 0.30)`
- `ADAPTIVE  = (0.40, 0.25, 0.35)`
- `AGGRESSIVE= (0.50, 0.20, 0.30)`
- Or custom weights when `use_custom_weights=true` and all weights provided.

Implementation: `backend/engine/strategy.py:_resolve_weights`

### 5) Theme Clustering + Theme Cap (분산)
- Cluster tickers by return correlation into ~8–16 “themes”.
- Selection applies `theme_cap` per theme while filling up to `daytrade_limit`.
- Cached to `theme_cache.json` in the server data dir; rebalanced every 5 trading days.

Implementation:
- `backend/engine/themes.py`
- `backend/engine/strategy.py:_compute_theme_map`, `_apply_theme_cap`

### 6) Gate (진입 가능/불가)
Purpose: block entries when the strategy edge is not present.

- Build per-day synthetic trades on top-M liquidity universe (`gate_M`, default 200).
- Simulate next-day outcomes (entry/stop/target, exit policy EOD).
- Compute `daily_mean_R` for each day when enough trades exist.
- Compute:
  - `gate_metric = rolling_mean(gate_lookback).shift(1)` (leakage-safe)
  - `gate_on = gate_metric >= threshold` (or quantile threshold)

If `gate_on == false`:
- Keep `top/primary` list but relabel as conditional entries:
  - `조건부 진입(게이트OFF metric) · 분할/소액 권장`
- Keep lower ranks as watch list:
  - `후순위 관망`

Implementation: `backend/engine/strategy.py:compute_gate_status`

### 7) Daytrade Trade Params (신호)
For each selected ticker:
- `entry = High_t * 1.005` (tick-rounded UP)
- `target = entry * 1.06` (tick-rounded UP)
- `stop = entry * 0.97` (tick-rounded DOWN)

Implementation: `backend/engine/strategy.py:_to_items`

## Longterm (장투)

Rules (current):
- Exclude tickers already in daytrade primary.
- Liquidity: `value_ma20 >= long_min_value_krw`
- Trend: 20-day return must be positive.
- Construct a simple buy zone and risk box:
  - `buy_low = close * 0.97`
  - `buy_high = close * 1.01`
  - `target_12m = close * 1.25`
  - `stop = close * 0.85`
- Sort by market cap (descending) to favor large caps first.

Implementation: `backend/engine/strategy.py:_build_longterm`

## Papers (논문)

Current behavior:
- Backend `/papers/summary` is a *spec summary* (not a full backtest report).
- The app “논문” tab currently shows the same daytrade recommendation list, and uses `/papers/summary` only for metadata (can be expanded later).

Backend endpoint: `backend/app/main.py:/papers/summary`

Additional (offline) report helper:
- `backend/engine/report.py` can render a text report from a KPI grid (research/backtest output).

## Known Bottlenecks / Practical Notes
- If `/reports/premarket` returns `FALLBACK` with “생성 대기/중”, server is generating; first-time generation for a new cache key can take ~30–60 seconds depending on cached KRX data and clustering.
- Chart mini-candles should not trigger dozens of `/chart/daily` requests at once; lazy loading is preferred for UX.
