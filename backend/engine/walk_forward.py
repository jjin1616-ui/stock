"""
Walk-forward 검증 모듈.

롤링 윈도우 방식으로 train/test 구간을 분할하여
전략의 과최적화(overfitting) 여부를 감지한다.

기본 설정:
  - train: 12개월 (약 252 거래일)
  - test:  3개월  (약 63 거래일)
  - 롤링:  3개월 단위 슬라이딩

각 구간별 sharpe, max_drawdown, win_rate 를 계산하고
train vs test 성과 괴리가 클 경우 경고를 출력한다.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Optional

import numpy as np
import pandas as pd

from .config import Config, Preset
from .gate import apply_gate, compute_gate, daily_mean_r
from .kpi import compute_kpi
from .simulate import simulate_trades


# ---------------------------------------------------------------------------
# 설정값
# ---------------------------------------------------------------------------

TRAIN_MONTHS = 12       # 훈련 구간 (개월)
TEST_MONTHS = 3         # 테스트 구간 (개월)
STEP_MONTHS = 3         # 롤링 보폭 (개월)
ANNUALIZE_FACTOR = 252  # 연간 거래일 (샤프 비율 연율화)

# 과최적화 경고 임계값
OVERFIT_SHARPE_DIFF = 0.5   # train - test 샤프 차이가 이 값 초과 시 경고
OVERFIT_WINRATE_DIFF = 0.10 # train - test 승률 차이가 이 값 초과 시 경고
OVERFIT_MDD_RATIO = 2.0     # test MDD / train MDD 비율이 이 값 초과 시 경고


# ---------------------------------------------------------------------------
# 결과 데이터 클래스
# ---------------------------------------------------------------------------

@dataclass
class FoldMetrics:
    """단일 fold(구간)의 성과 지표."""
    fold_idx: int
    split: str                    # "train" 또는 "test"
    start_date: date
    end_date: date
    num_trades: int
    win_rate: float               # 승률 (0~1)
    sharpe: float                 # 샤프 비율 (연율화)
    max_drawdown: float           # 최대 낙폭 (R 기준, 음수)
    avg_r: float                  # 평균 R
    expectancy_r: float           # 기대값 R


@dataclass
class OverfitWarning:
    """과최적화 경고 항목."""
    fold_idx: int
    metric: str                   # "sharpe" / "win_rate" / "max_drawdown"
    train_value: float
    test_value: float
    threshold: float
    message: str


@dataclass
class WalkForwardResult:
    """Walk-forward 검증 전체 결과."""
    folds: list[FoldMetrics] = field(default_factory=list)
    warnings: list[OverfitWarning] = field(default_factory=list)
    summary: dict = field(default_factory=dict)


# ---------------------------------------------------------------------------
# 핵심 계산 함수
# ---------------------------------------------------------------------------

def _compute_sharpe(pnl_series: pd.Series) -> float:
    """일별 PnL 시리즈로부터 연율화 샤프 비율 계산."""
    if pnl_series.empty or pnl_series.std() == 0:
        return 0.0
    return float(pnl_series.mean() / pnl_series.std() * np.sqrt(ANNUALIZE_FACTOR))


def _compute_max_drawdown(r_series: pd.Series) -> float:
    """R 시리즈 누적 기준 최대 낙폭 (음수로 반환)."""
    if r_series.empty:
        return 0.0
    cum = r_series.cumsum()
    peak = cum.cummax()
    dd = cum - peak
    return float(dd.min())


def _compute_win_rate(r_series: pd.Series) -> float:
    """승률 계산 (R > 0 비율)."""
    if r_series.empty:
        return 0.0
    return float((r_series > 0).mean())


def _fold_metrics_from_trades(
    trade_log: pd.DataFrame,
    fold_idx: int,
    split: str,
    start_date: date,
    end_date: date,
) -> FoldMetrics:
    """거래 로그에서 FoldMetrics 생성."""
    entered = trade_log[trade_log["entered"]].copy() if not trade_log.empty else pd.DataFrame()
    r_series = entered["R_exec"] if (not entered.empty and "R_exec" in entered.columns) else pd.Series(dtype=float)

    return FoldMetrics(
        fold_idx=fold_idx,
        split=split,
        start_date=start_date,
        end_date=end_date,
        num_trades=len(r_series),
        win_rate=_compute_win_rate(r_series),
        sharpe=_compute_sharpe(r_series),
        max_drawdown=_compute_max_drawdown(r_series),
        avg_r=float(r_series.mean()) if len(r_series) else 0.0,
        expectancy_r=float(r_series.mean()) if len(r_series) else 0.0,
    )


# ---------------------------------------------------------------------------
# 날짜 구간 생성
# ---------------------------------------------------------------------------

def _generate_folds(
    data_start: date,
    data_end: date,
    train_months: int = TRAIN_MONTHS,
    test_months: int = TEST_MONTHS,
    step_months: int = STEP_MONTHS,
) -> list[tuple[date, date, date, date]]:
    """
    롤링 윈도우 fold 목록 생성.

    Returns:
        [(train_start, train_end, test_start, test_end), ...]
    """
    folds = []
    cursor = data_start

    while True:
        train_start = cursor
        train_end = _add_months(train_start, train_months)
        test_start = train_end + timedelta(days=1)
        test_end = _add_months(test_start, test_months)

        # 데이터 범위를 초과하면 중단
        if test_end > data_end:
            # 마지막 fold: test_end를 data_end로 잘라서 포함
            if test_start <= data_end:
                test_end = data_end
                folds.append((train_start, train_end, test_start, test_end))
            break

        folds.append((train_start, train_end, test_start, test_end))
        cursor = _add_months(cursor, step_months)

    return folds


def _add_months(d: date, months: int) -> date:
    """날짜에 months개월 추가 (말일 보정)."""
    month = d.month - 1 + months
    year = d.year + month // 12
    month = month % 12 + 1
    import calendar
    max_day = calendar.monthrange(year, month)[1]
    day = min(d.day, max_day)
    return date(year, month, day)


# ---------------------------------------------------------------------------
# 거래 로그 날짜 필터링
# ---------------------------------------------------------------------------

def _filter_trades_by_date(
    trade_log: pd.DataFrame,
    start_date: date,
    end_date: date,
) -> pd.DataFrame:
    """거래 로그를 날짜 범위로 필터링."""
    if trade_log.empty:
        return trade_log.copy()
    df = trade_log.copy()
    # date 컬럼을 날짜로 변환
    if not pd.api.types.is_datetime64_any_dtype(df["date"]):
        df["date"] = pd.to_datetime(df["date"])
    mask = (df["date"].dt.date >= start_date) & (df["date"].dt.date <= end_date)
    return df[mask].copy()


# ---------------------------------------------------------------------------
# 과최적화 감지
# ---------------------------------------------------------------------------

def _detect_overfit(
    train_metrics: FoldMetrics,
    test_metrics: FoldMetrics,
    fold_idx: int,
) -> list[OverfitWarning]:
    """train vs test 성과를 비교하여 과최적화 경고 생성."""
    warnings = []

    # 1) 샤프 비율 괴리
    sharpe_diff = train_metrics.sharpe - test_metrics.sharpe
    if sharpe_diff > OVERFIT_SHARPE_DIFF:
        warnings.append(OverfitWarning(
            fold_idx=fold_idx,
            metric="sharpe",
            train_value=train_metrics.sharpe,
            test_value=test_metrics.sharpe,
            threshold=OVERFIT_SHARPE_DIFF,
            message=(
                f"[Fold {fold_idx}] 샤프 비율 괴리 감지: "
                f"train={train_metrics.sharpe:.3f}, test={test_metrics.sharpe:.3f} "
                f"(차이={sharpe_diff:.3f} > 임계값={OVERFIT_SHARPE_DIFF})"
            ),
        ))

    # 2) 승률 괴리
    wr_diff = train_metrics.win_rate - test_metrics.win_rate
    if wr_diff > OVERFIT_WINRATE_DIFF:
        warnings.append(OverfitWarning(
            fold_idx=fold_idx,
            metric="win_rate",
            train_value=train_metrics.win_rate,
            test_value=test_metrics.win_rate,
            threshold=OVERFIT_WINRATE_DIFF,
            message=(
                f"[Fold {fold_idx}] 승률 괴리 감지: "
                f"train={train_metrics.win_rate:.1%}, test={test_metrics.win_rate:.1%} "
                f"(차이={wr_diff:.1%} > 임계값={OVERFIT_WINRATE_DIFF:.1%})"
            ),
        ))

    # 3) MDD 악화 비율
    if train_metrics.max_drawdown < 0 and test_metrics.max_drawdown < 0:
        mdd_ratio = abs(test_metrics.max_drawdown) / abs(train_metrics.max_drawdown)
        if mdd_ratio > OVERFIT_MDD_RATIO:
            warnings.append(OverfitWarning(
                fold_idx=fold_idx,
                metric="max_drawdown",
                train_value=train_metrics.max_drawdown,
                test_value=test_metrics.max_drawdown,
                threshold=OVERFIT_MDD_RATIO,
                message=(
                    f"[Fold {fold_idx}] MDD 악화 감지: "
                    f"train={train_metrics.max_drawdown:.3f}R, test={test_metrics.max_drawdown:.3f}R "
                    f"(비율={mdd_ratio:.1f}x > 임계값={OVERFIT_MDD_RATIO:.1f}x)"
                ),
            ))

    return warnings


# ---------------------------------------------------------------------------
# 메인 함수: walk-forward 검증
# ---------------------------------------------------------------------------

def run_walk_forward(
    trade_log: pd.DataFrame,
    *,
    train_months: int = TRAIN_MONTHS,
    test_months: int = TEST_MONTHS,
    step_months: int = STEP_MONTHS,
) -> WalkForwardResult:
    """
    기존 백테스트 거래 로그에 walk-forward 검증을 수행한다.

    이미 생성된 trade_log(simulate_trades 또는 grid 결과)를
    시간 순서대로 train/test 구간으로 분할하여 평가한다.

    Args:
        trade_log: 거래 로그 DataFrame (date, R_exec, entered 등 필수)
        train_months: 훈련 구간 개월 수 (기본 12)
        test_months: 테스트 구간 개월 수 (기본 3)
        step_months: 롤링 보폭 개월 수 (기본 3)

    Returns:
        WalkForwardResult: fold별 지표, 과최적화 경고, 요약 통계
    """
    result = WalkForwardResult()

    if trade_log.empty:
        result.summary = {"error": "거래 로그가 비어 있습니다."}
        return result

    # 데이터 날짜 범위 파악
    df = trade_log.copy()
    if not pd.api.types.is_datetime64_any_dtype(df["date"]):
        df["date"] = pd.to_datetime(df["date"])
    data_start = df["date"].min().date()
    data_end = df["date"].max().date()

    # fold 목록 생성
    folds = _generate_folds(
        data_start, data_end,
        train_months=train_months,
        test_months=test_months,
        step_months=step_months,
    )

    if not folds:
        result.summary = {
            "error": (
                f"데이터 기간({data_start}~{data_end})이 "
                f"최소 요구 기간({train_months}+{test_months}개월)보다 짧습니다."
            ),
        }
        return result

    # 각 fold 실행
    train_sharpes = []
    test_sharpes = []
    train_winrates = []
    test_winrates = []

    for i, (tr_s, tr_e, te_s, te_e) in enumerate(folds):
        # train 구간 지표
        train_trades = _filter_trades_by_date(df, tr_s, tr_e)
        train_m = _fold_metrics_from_trades(train_trades, i, "train", tr_s, tr_e)
        result.folds.append(train_m)

        # test 구간 지표
        test_trades = _filter_trades_by_date(df, te_s, te_e)
        test_m = _fold_metrics_from_trades(test_trades, i, "test", te_s, te_e)
        result.folds.append(test_m)

        # 과최적화 감지
        overfit_warnings = _detect_overfit(train_m, test_m, i)
        result.warnings.extend(overfit_warnings)

        # 요약용 집계
        train_sharpes.append(train_m.sharpe)
        test_sharpes.append(test_m.sharpe)
        train_winrates.append(train_m.win_rate)
        test_winrates.append(test_m.win_rate)

    # 요약 통계
    result.summary = {
        "총_fold_수": len(folds),
        "데이터_시작": str(data_start),
        "데이터_종료": str(data_end),
        "train_개월": train_months,
        "test_개월": test_months,
        "롤링_보폭_개월": step_months,
        "train_평균_sharpe": float(np.mean(train_sharpes)) if train_sharpes else 0.0,
        "test_평균_sharpe": float(np.mean(test_sharpes)) if test_sharpes else 0.0,
        "train_평균_승률": float(np.mean(train_winrates)) if train_winrates else 0.0,
        "test_평균_승률": float(np.mean(test_winrates)) if test_winrates else 0.0,
        "sharpe_안정도": (
            float(np.mean(test_sharpes) / np.mean(train_sharpes))
            if train_sharpes and np.mean(train_sharpes) != 0
            else 0.0
        ),
        "과최적화_경고_수": len(result.warnings),
    }

    return result


# ---------------------------------------------------------------------------
# 편의 함수: 결과를 DataFrame으로 변환
# ---------------------------------------------------------------------------

def walk_forward_to_dataframe(result: WalkForwardResult) -> pd.DataFrame:
    """WalkForwardResult의 fold 지표를 DataFrame으로 변환."""
    if not result.folds:
        return pd.DataFrame()
    rows = []
    for fm in result.folds:
        rows.append({
            "fold": fm.fold_idx,
            "구간": fm.split,
            "시작일": fm.start_date,
            "종료일": fm.end_date,
            "거래수": fm.num_trades,
            "승률": fm.win_rate,
            "sharpe": fm.sharpe,
            "MDD_R": fm.max_drawdown,
            "평균R": fm.avg_r,
            "기대값R": fm.expectancy_r,
        })
    return pd.DataFrame(rows)


def walk_forward_warnings_to_dataframe(result: WalkForwardResult) -> pd.DataFrame:
    """WalkForwardResult의 경고 목록을 DataFrame으로 변환."""
    if not result.warnings:
        return pd.DataFrame()
    rows = []
    for w in result.warnings:
        rows.append({
            "fold": w.fold_idx,
            "지표": w.metric,
            "train_값": w.train_value,
            "test_값": w.test_value,
            "임계값": w.threshold,
            "메시지": w.message,
        })
    return pd.DataFrame(rows)


def print_walk_forward_report(result: WalkForwardResult) -> str:
    """Walk-forward 검증 결과를 텍스트 리포트로 출력."""
    lines = []
    lines.append("=" * 70)
    lines.append("  Walk-Forward 검증 리포트")
    lines.append("=" * 70)

    # 요약
    s = result.summary
    lines.append(f"\n데이터 기간: {s.get('데이터_시작', '?')} ~ {s.get('데이터_종료', '?')}")
    lines.append(f"설정: train {s.get('train_개월', '?')}개월 / test {s.get('test_개월', '?')}개월 / 보폭 {s.get('롤링_보폭_개월', '?')}개월")
    lines.append(f"총 Fold 수: {s.get('총_fold_수', 0)}")
    lines.append(f"\n{'구분':<8} {'평균 Sharpe':>12} {'평균 승률':>10}")
    lines.append("-" * 32)
    lines.append(f"{'Train':<8} {s.get('train_평균_sharpe', 0):.3f}{'':>5} {s.get('train_평균_승률', 0):.1%}")
    lines.append(f"{'Test':<8} {s.get('test_평균_sharpe', 0):.3f}{'':>5} {s.get('test_평균_승률', 0):.1%}")
    lines.append(f"\nSharpe 안정도 (test/train): {s.get('sharpe_안정도', 0):.2f}")

    # fold별 상세
    lines.append(f"\n{'─' * 70}")
    lines.append("  Fold별 상세")
    lines.append(f"{'─' * 70}")
    lines.append(f"{'Fold':>4} {'구간':<6} {'기간':<25} {'거래수':>6} {'승률':>6} {'Sharpe':>8} {'MDD_R':>8} {'평균R':>8}")
    lines.append("-" * 75)
    for fm in result.folds:
        period = f"{fm.start_date}~{fm.end_date}"
        lines.append(
            f"{fm.fold_idx:>4} {fm.split:<6} {period:<25} "
            f"{fm.num_trades:>6} {fm.win_rate:>5.1%} {fm.sharpe:>8.3f} {fm.max_drawdown:>8.3f} {fm.avg_r:>8.3f}"
        )

    # 경고
    if result.warnings:
        lines.append(f"\n{'─' * 70}")
        lines.append(f"  과최적화 경고 ({len(result.warnings)}건)")
        lines.append(f"{'─' * 70}")
        for w in result.warnings:
            lines.append(f"  ⚠ {w.message}")
    else:
        lines.append(f"\n✓ 과최적화 경고 없음 — train/test 성과가 안정적입니다.")

    lines.append("\n" + "=" * 70)
    return "\n".join(lines)
