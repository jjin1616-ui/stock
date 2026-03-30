"""단타2 사유 코드 — 기존 autotrade_reason_codes 확장 + suggestion 필드."""
from __future__ import annotations

import re


# ── 사유별 라벨 + 사용자 행동 제안(suggestion) ──

_REASON_MAP: dict[str, tuple[str, str]] = {
    # (한글 라벨, suggestion)
    "SEED_LIMIT_EXCEEDED": ("총 시드 한도 초과", "seed_krw를 높이거나 기존 포지션 정리 후 재시도"),
    "ORDERABLE_CASH_LIMIT": ("주문가능현금 부족", "입금 또는 기존 포지션 일부 정리"),
    "ALREADY_OPEN_POSITION": ("이미 보유중", "기존 포지션 청산 후 재진입 또는 추가매수 비활성"),
    "ENTRY_BLOCKED_MANUAL": ("사용자 차단 설정", "심볼 규칙에서 해당 종목 차단 해제"),
    "PENDING_BUY_ORDER": ("접수 대기 중복매수 방지", "미체결 주문 확인 후 취소 또는 대기"),
    "PENDING_SELL_ORDER": ("접수 대기 중복매도 방지", "미체결 매도 주문 확인"),
    "QTY_ZERO": ("주문 수량 0주", "order_budget_krw 증액 또는 저가주 확인"),
    "STOPLOSS_REENTRY_COOLDOWN": ("손절 후 재진입 대기", "쿨다운 완료 시 자동 해제"),
    "STOPLOSS_REENTRY_BLOCKED_TODAY": ("손절 당일 재진입 차단", "다음 거래일에 자동 해제"),
    "STOPLOSS_REENTRY_BLOCKED_MANUAL": ("손절 후 재진입 수동 차단", "리엔트리 블락 수동 해제 필요"),
    "TAKEPROFIT_REENTRY_COOLDOWN": ("익절 후 재진입 대기", "쿨다운 완료 시 자동 해제"),
    "TAKEPROFIT_REENTRY_BLOCKED_TODAY": ("익절 당일 재진입 차단", "다음 거래일에 자동 해제"),
    "TAKEPROFIT_REENTRY_BLOCKED_MANUAL": ("익절 후 재진입 수동 차단", "리엔트리 블락 수동 해제 필요"),
    "SELLABLE_QTY_ZERO": ("매도가능수량 0주", "T+2 결제 대기 또는 브로커 잔고 확인"),
    "TAKE_PROFIT": ("익절 조건 도달", ""),
    "STOP_LOSS": ("손절 조건 도달", ""),
    "PARTIAL_TAKE_PROFIT": ("부분 익절 실행", "잔여 수량은 최종 익절까지 보유"),
    "MARKET_CLOSED": ("장시간 외", "장 시작 후(09:00) 자동 재시도"),
    "BROKER_NO_HOLDING": ("증권사 보유잔고 없음", "보유 종목 확인"),
    "BROKER_CREDENTIAL_MISSING": ("증권사 계정정보 누락", "설정에서 증권사 API 키 등록"),
    "BROKER_BALANCE_UNAVAILABLE": ("증권사 잔고 조회 실패", "잠시 후 재시도"),
    "BROKER_REJECTED": ("증권사 주문 거부", "주문 조건 확인 (가격 단위, 수량 등)"),
    "BROKER_SUBMITTED": ("증권사 주문 접수", ""),
    "BROKER_FILLED": ("증권사 주문 체결", ""),
    "BROKER_CANCELED": ("증권사 접수취소 완료", ""),
    "BROKER_CLOSED": ("증권사 상태정리", ""),
    "PAPER_FILLED": ("내부 모의 체결", ""),
    "ERROR": ("주문 처리 오류", "로그 확인 후 재시도"),
    "SKIPPED": ("주문 조건 미충족", ""),
    "KIS_TRADING_DISABLED": ("실주문 비활성", "관리자에게 KIS 활성화 요청"),
    "DAILY_LOSS_LIMIT_REACHED": ("일일 손실 한계 도달", "다음 거래일까지 자동 차단"),
    "DAILY_LOSS_THROTTLED": ("일일 손실 축소 모드", "주문량 50% 자동 축소 적용 중"),
    "CONCURRENT_RUN_BLOCKED": ("동시 실행 차단", "이전 실행 완료 후 자동 해제"),
    "PRICE_UNAVAILABLE": ("실시간 가격 미확보", "시세 조회 후 재시도"),
    "REQUEST_PRICE_REQUIRED": ("요청 가격 필요", "가격 입력 후 재시도"),
    "TICKER_EMPTY": ("종목코드 누락", "유효한 종목코드 확인"),
    "AVG_FALLBACK_FORCE_EXIT": ("실시간 가격 미확보 강제 청산", "시세 서비스 상태 확인"),
    "SKIPPED_PRICE_UNCERTAIN": ("가격 불확실 — 청산 보류", "시세 정상화 후 자동 재시도"),
    "TICK_VALIDATION_FAILED": ("tick 역전/비정상 레벨", "해당 종목 자동 제외됨"),
}


def normalize_reason_code(status: str, reason: str) -> str:
    """사유 코드 정규화."""
    status_u = str(status or "").strip().upper()
    reason_u = str(reason or "").strip().upper()
    if reason_u in _REASON_MAP:
        return reason_u
    if reason_u.startswith("MARKET_"):
        return reason_u
    if re.fullmatch(r"[A-Z0-9_]+", reason_u or ""):
        if status_u in {"BROKER_CLOSED", "BROKER_CANCELED", "BROKER_SUBMITTED", "BROKER_FILLED", "PAPER_FILLED"}:
            return status_u
        if status_u == "ERROR":
            return "ERROR"
        return reason_u
    if reason_u.startswith("BROKER_BALANCE_UNAVAILABLE"):
        return "BROKER_BALANCE_UNAVAILABLE"
    reason_raw = str(reason or "")
    if "주문가능금액을 초과" in reason_raw:
        return "ORDERABLE_CASH_LIMIT"
    if "잔고내역이 없습니다" in reason_raw:
        return "BROKER_NO_HOLDING"
    if ("장시작전" in reason_raw) or ("장운영시간이 아닙니다" in reason_raw):
        return "MARKET_CLOSED"
    if status_u == "BROKER_REJECTED":
        return "BROKER_REJECTED"
    if status_u == "ERROR":
        return "ERROR"
    if status_u == "SKIPPED":
        return "SKIPPED_BY_RULE"
    return reason_u or status_u or "UNKNOWN"


def reason_code_label(code: str) -> str:
    """사유 코드 → 한글 라벨."""
    code_u = str(code or "").strip().upper()
    entry = _REASON_MAP.get(code_u)
    if entry:
        return entry[0]
    if code_u.startswith("MARKET_"):
        return "장시간 외"
    return "주문 조건 미충족"


def reason_code_suggestion(code: str) -> str:
    """사유 코드 → 사용자 행동 제안. 빈 문자열이면 제안 없음."""
    code_u = str(code or "").strip().upper()
    entry = _REASON_MAP.get(code_u)
    if entry:
        return entry[1]
    return ""
