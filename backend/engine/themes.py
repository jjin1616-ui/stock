from __future__ import annotations

import numpy as np
import pandas as pd

try:
    from sklearn.cluster import AgglomerativeClustering
except Exception:  # pragma: no cover
    AgglomerativeClustering = None


def build_themes(returns_df: pd.DataFrame, n_themes: int) -> pd.Series:
    if returns_df.empty:
        return pd.Series(dtype="int64")
    work = returns_df.fillna(0.0)
    codes = work.columns.tolist()
    x = work.T.values
    if AgglomerativeClustering is None or len(codes) < 2:
        labels = np.arange(len(codes)) % max(1, n_themes)
        return pd.Series(labels, index=codes, name="theme_id")

    k = min(n_themes, len(codes))
    corr = work.corr().fillna(0.0)
    dist = (1.0 - corr).values
    try:
        model = AgglomerativeClustering(n_clusters=k, metric="precomputed", linkage="average")
    except TypeError:  # older sklearn
        model = AgglomerativeClustering(n_clusters=k, affinity="precomputed", linkage="average")
    labels = model.fit_predict(dist)
    return pd.Series(labels.astype(int), index=codes, name="theme_id")


def apply_theme_cap(candidates: pd.DataFrame, theme_map: pd.Series, theme_cap: int) -> pd.DataFrame:
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
        if len(out) >= 10:
            break
    if not out:
        return candidates.head(0).copy()
    return pd.DataFrame(out)


def apply_theme_cap_with_rejections(candidates: pd.DataFrame, theme_map: pd.Series, theme_cap: int) -> tuple[pd.DataFrame, pd.DataFrame]:
    if candidates.empty:
        return candidates, pd.DataFrame()
    x = candidates.sort_values("score", ascending=False).copy().reset_index(drop=True)
    x["candidate_rank"] = x.index + 1
    x["theme_id"] = x["code"].map(lambda c: int(theme_map.get(c, -1)))
    selected = []
    rejected = []
    counts: dict[int, int] = {}

    for _, row in x.iterrows():
        theme = int(row["theme_id"])
        used = counts.get(theme, 0)
        if used >= theme_cap:
            r = row.copy()
            r["reason"] = "THEME_CAP"
            rejected.append(r)
            continue
        counts[theme] = used + 1
        selected.append(row)
        if len(selected) >= 10:
            continue

    selected_df = pd.DataFrame(selected).head(10)
    selected_codes = set(selected_df["code"].astype(str)) if not selected_df.empty else set()
    for _, row in x.iterrows():
        if str(row["code"]) in selected_codes:
            continue
        if row.get("reason") == "THEME_CAP":
            continue
        r = row.copy()
        r["reason"] = "CP" if float(row.get("value_ma20", 0.0)) > 0 else "OTHER"
        rejected.append(r)

    rejected_df = pd.DataFrame(rejected)
    return selected_df, rejected_df
