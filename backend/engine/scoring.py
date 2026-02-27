from __future__ import annotations

import pandas as pd

from .config import Preset


def concentration_penalty(value_ma20: pd.Series, p0: float, lam: float) -> pd.Series:
    p = value_ma20.rank(pct=True)
    return lam * (p - p0).clip(lower=0.0)


def compute_score(feats: pd.DataFrame, preset: Preset, value_ma20: pd.Series) -> pd.Series:
    w_ta, w_re, w_rs = preset.weights
    cp = concentration_penalty(value_ma20=value_ma20, p0=preset.penalty_p0, lam=preset.penalty_lambda)
    return w_ta * feats["z_ta"] + w_re * feats["z_re"] + w_rs * feats["z_rs"] - cp
