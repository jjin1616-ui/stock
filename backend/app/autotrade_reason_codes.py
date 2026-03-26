from __future__ import annotations

import re


def normalize_reason_code(status: str, reason: str) -> str:
    status_u = str(status or "").strip().upper()
    reason_u = str(reason or "").strip().upper()
    if reason_u in {
        "SEED_LIMIT_EXCEEDED",
        "ORDERABLE_CASH_LIMIT",
        "ALREADY_OPEN_POSITION",
        "ENTRY_BLOCKED_MANUAL",
        "PENDING_BUY_ORDER",
        "PENDING_SELL_ORDER",
        "QTY_ZERO",
        "STOPLOSS_REENTRY_COOLDOWN",
        "STOPLOSS_REENTRY_BLOCKED_TODAY",
        "STOPLOSS_REENTRY_BLOCKED_MANUAL",
        "TAKEPROFIT_REENTRY_COOLDOWN",
        "TAKEPROFIT_REENTRY_BLOCKED_TODAY",
        "TAKEPROFIT_REENTRY_BLOCKED_MANUAL",
        "SELLABLE_QTY_ZERO",
        "TAKE_PROFIT",
        "STOP_LOSS",
        "BROKER_CREDENTIAL_MISSING",
        "PRICE_UNAVAILABLE",
        "REQUEST_PRICE_REQUIRED",
        "KIS_TRADING_DISABLED",
    }:
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
    code_u = str(code or "").strip().upper()
    mapping = {
        "SEED_LIMIT_EXCEEDED": "총 시드 한도 초과",
        "ORDERABLE_CASH_LIMIT": "주문가능현금 부족",
        "ALREADY_OPEN_POSITION": "이미 보유중",
        "ENTRY_BLOCKED_MANUAL": "사용자 차단 설정",
        "PENDING_BUY_ORDER": "접수 대기 중복매수 방지",
        "PENDING_SELL_ORDER": "접수 대기 중복매도 방지",
        "QTY_ZERO": "주문 수량 0주",
        "STOPLOSS_REENTRY_COOLDOWN": "손절 후 재진입 대기",
        "STOPLOSS_REENTRY_BLOCKED_TODAY": "손절 당일 재진입 차단",
        "STOPLOSS_REENTRY_BLOCKED_MANUAL": "손절 후 재진입 수동 차단",
        "TAKEPROFIT_REENTRY_COOLDOWN": "익절 후 재진입 대기",
        "TAKEPROFIT_REENTRY_BLOCKED_TODAY": "익절 당일 재진입 차단",
        "TAKEPROFIT_REENTRY_BLOCKED_MANUAL": "익절 후 재진입 수동 차단",
        "SELLABLE_QTY_ZERO": "매도가능수량 0주",
        "TAKE_PROFIT": "익절 조건 도달",
        "STOP_LOSS": "손절 조건 도달",
        "MARKET_CLOSED": "장시간 외",
        "BROKER_NO_HOLDING": "증권사 보유잔고 없음",
        "BROKER_CREDENTIAL_MISSING": "증권사 계정정보 누락",
        "BROKER_BALANCE_UNAVAILABLE": "증권사 잔고 조회 실패",
        "BROKER_REJECTED": "증권사 주문 거부",
        "BROKER_SUBMITTED": "증권사 주문 접수",
        "BROKER_FILLED": "증권사 주문 체결",
        "BROKER_CANCELED": "증권사 접수취소 완료",
        "BROKER_CLOSED": "증권사 상태정리",
        "PAPER_FILLED": "내부 모의 체결",
        "ERROR": "주문 처리 오류",
        "SKIPPED": "주문 조건 미충족",
        "KIS_TRADING_DISABLED": "실주문 비활성",
    }
    if code_u.startswith("MARKET_"):
        return "장시간 외"
    return mapping.get(code_u, "주문 조건 미충족")
