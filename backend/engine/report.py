from __future__ import annotations

import pandas as pd

from .config import Config


def render_summary(cfg: Config, kpi_grid: pd.DataFrame) -> str:
    if kpi_grid.empty:
        return "No results."
    ranked = kpi_grid.sort_values(["expectancy_r_exec", "mdd_r_exec"], ascending=[False, False])
    top10 = ranked.head(10)
    base_v1 = kpi_grid[
        (kpi_grid["preset"] == "BASELINE_V1")
    ].head(1)
    base_v2 = kpi_grid[
        (kpi_grid["preset"] == "BASELINE_V2")
    ].head(1)
    base_adaptive = kpi_grid[
        (kpi_grid["preset"] == "ADAPTIVE")
        & (kpi_grid["theme_cap"] == 2)
        & (kpi_grid["gate_lookback_N"] == 20)
        & (kpi_grid["gate_universe"] == "TOP_M_BASED")
    ].head(1)

    lines = []
    lines.append("[섹션1] 가설 요약")
    lines.append("- v2 점수(TA/RE/RS) + 대형주 집중 패널티 + theme_cap으로 Top10 분산 선별")
    lines.append("- gate_metric(rolling mean of daily_mean_R, shift=1)으로 ON일에만 실행")
    lines.append("- 목표는 기대값(+R) 유지와 MDD/테마집중(HHI) 동시 개선")

    lines.append("\n[섹션2] 점수식/룰 정의")
    lines.append("- Score = wTA*z(TA) + wRE*z(RE) + wRS*z(RS) - lambda*max(0,p-p0)")
    lines.append("- entry=High_t*1.005, stop=entry*0.97, target=entry*1.06, 동시도달시 손절 우선")
    lines.append("- gate_on = rolling_mean_N(daily_mean_R).shift(1) >= 0")

    lines.append("\n[섹션3] 검증 결과 상위 10 + 베이스라인")
    lines.append(top10.to_string(index=False))
    if not base_adaptive.empty:
        lines.append("\n[BASELINE: ADAPTIVE, theme_cap=2, N=20, TOP_M_BASED]")
        lines.append(base_adaptive.to_string(index=False))
    if not base_v1.empty:
        lines.append("\n[BASELINE_V1: 룰만 + costs]")
        lines.append(base_v1.to_string(index=False))
    if not base_v2.empty:
        lines.append("\n[BASELINE_V2: gate + costs]")
        lines.append(base_v2.to_string(index=False))

    lines.append("\n[섹션4] 편중/분산 분석")
    lines.append(
        f"- 평균 top10_avg_value_krw={kpi_grid['top10_avg_value_krw'].mean():,.0f}, "
        f"평균 theme_hhi={kpi_grid['theme_hhi'].mean():.3f}"
    )
    lines.append(f"- 평균 비용영향(delta_cost_impact)={kpi_grid['delta_cost_impact'].mean():.4f}")

    lines.append("\n[섹션5] 리스크 및 보완")
    lines.append("- 누수 방지: feature=t, trade=t+1, gate shift=1 적용")
    lines.append("- 생존편향: 현재 상장 유니버스 기반 결과이며 상폐 종목 미반영")
    lines.append("- 과최적화 완화: lookback_days를 60/120 등 다중 구간 비교 권장")

    lines.append("\n[섹션6] 권고 파라미터")
    lines.append("- DEF: theme_cap=1, N=20, TOP_M_BASED")
    lines.append("- ADP: theme_cap=2, N=20, TOP_M_BASED")
    lines.append("- AGR: theme_cap=3, N=10, TOP10_BASED")

    return "\n".join(lines)
