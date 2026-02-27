from __future__ import annotations

import logging
import numpy as np
import pandas as pd

logger = logging.getLogger("stock.features")


def compute_value(df: pd.DataFrame) -> pd.Series:
    return df["Close"] * df["Volume"]


def winsorize(s: pd.Series, pct: float) -> pd.Series:
    if s.empty:
        return s
    low = s.quantile(pct)
    high = s.quantile(1 - pct)
    return s.clip(lower=low, upper=high)


def robust_z(s: pd.Series) -> pd.Series:
    if s.empty:
        return s
    med = s.median()
    mad = (s - med).abs().median()
    if mad <= 1e-12:
        logger.warning("robust_z: MAD=0; returning zeros (feature variance=0)")
        return (s - med) * 0.0
    denom = 1.4826 * mad
    return (s - med) / denom


def feat_ta(value: pd.Series, s: int, l: int) -> pd.Series:
    short = value.rolling(s, min_periods=s).mean()
    long = value.rolling(l, min_periods=l).mean()
    return np.log((short + 1e-9) / (long + 1e-9))


def feat_re(df: pd.DataFrame) -> pd.Series:
    tr = (df["High"] - df["Low"]).abs()
    return (df["Close"] - df["Open"]).abs() / tr.clip(lower=1e-9)


def feat_rs(close: pd.Series, bench_close: pd.Series, k: int) -> pd.Series:
    asset_r = close / close.shift(k) - 1.0
    bench_r = bench_close / bench_close.shift(k) - 1.0
    bench_aligned = bench_r.reindex(asset_r.index).ffill()
    return asset_r - bench_aligned
