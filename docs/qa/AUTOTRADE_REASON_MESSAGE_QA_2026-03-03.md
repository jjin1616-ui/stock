# 자동매매 상태 메시지 표준화 QA (2026-03-03)

## 목적
- 자동매매 상태 메시지를 `결론/코드/근거/조치` 구조로 일관화했는지 검증한다.
- 문자열 파싱 기반 오판을 줄이고 `reason_code` 기준 집계가 동작하는지 확인한다.

## 실행 명령
1. `python3 -m py_compile backend/app/main.py backend/app/autotrade_reason_codes.py`
2. `python3 backend/scripts/preflight_autotrade_contract.py`
3. `./gradlew :app:compileDebugKotlin --no-daemon`
4. 커스텀 시나리오 스크립트(35건) 실행

## 시나리오 가설 및 결과
- [PASS] 01 `SEED_LIMIT_EXCEEDED`는 시드 근거값(현재노출/필요금액/시드한도)을 포함한다.
- [PASS] 02 `ORDERABLE_CASH_LIMIT`는 주문가능현금/필요주문금액/요청수량을 포함한다.
- [PASS] 03 `ALREADY_OPEN_POSITION`는 보유수량/평균단가를 포함한다.
- [PASS] 04 `ENTRY_BLOCKED_MANUAL`은 차단 해제 조치를 포함한다.
- [PASS] 05 `PENDING_BUY_ORDER`는 보호시간/기존주문 식별값을 포함한다.
- [PASS] 06 `PENDING_SELL_ORDER`는 보호시간/매도가능수량을 포함한다.
- [PASS] 07 `QTY_ZERO`는 예산/요청가격/계산수량을 포함한다.
- [PASS] 08 `STOPLOSS_REENTRY_COOLDOWN`은 대기시간을 포함한다.
- [PASS] 09 `STOPLOSS_REENTRY_BLOCKED_TODAY`는 당일 차단 안내를 준다.
- [PASS] 10 `STOPLOSS_REENTRY_BLOCKED_MANUAL`은 수동해제 조치를 준다.
- [PASS] 11 `TAKEPROFIT_REENTRY_COOLDOWN`은 대기시간을 포함한다.
- [PASS] 12 `TAKEPROFIT_REENTRY_BLOCKED_TODAY`는 당일 차단 안내를 준다.
- [PASS] 13 `TAKEPROFIT_REENTRY_BLOCKED_MANUAL`은 수동해제 조치를 준다.
- [PASS] 14 `SELLABLE_QTY_ZERO`는 보유/매도가능수량 근거를 준다.
- [PASS] 15 장외 한국어 원문은 `MARKET_CLOSED`로 정규화된다.
- [PASS] 16 잔고없음 원문은 `BROKER_NO_HOLDING`으로 정규화된다.
- [PASS] 17 `BROKER_CREDENTIAL_MISSING`은 실행환경 근거를 준다.
- [PASS] 18 `BROKER_BALANCE_UNAVAILABLE:*`는 공통 코드로 정규화된다.
- [PASS] 19 `TAKE_PROFIT`은 현재손익률/익절기준 근거를 준다.
- [PASS] 20 `STOP_LOSS`는 현재손익률/손절기준 근거를 준다.
- [PASS] 21 일반 `BROKER_REJECTED`는 증권사원문 근거를 포함한다.
- [PASS] 22 `BROKER_SUBMITTED` 기본 케이스도 reason_detail이 생성된다.
- [PASS] 23 `BROKER_SUBMITTED` + 자유문구(모의주문 완료 원문)도 코드가 `BROKER_SUBMITTED`로 고정된다.
- [PASS] 24 `BROKER_FILLED`는 체결 완료 결론을 생성한다.
- [PASS] 25 `PAPER_FILLED`도 체결 완료 결론을 생성한다.
- [PASS] 26 `BROKER_CANCELED`는 취소 반영 결론을 생성한다.
- [PASS] 27 `BROKER_CLOSED` + `NOT_PENDING_ORDER`에서도 코드가 상태 코드로 정규화된다.
- [PASS] 28 `ERROR` + 임의 reason(DB_LOCK)에서도 코드가 `ERROR`로 정규화된다.
- [PASS] 29 `SKIPPED` + 토큰형 임의 코드(`CUSTOM_RULE_ABC`)는 코드 보존된다.
- [PASS] 30 `SKIPPED` + 비정형 한글 사유는 `SKIPPED_BY_RULE`로 수렴된다.
- [PASS] 31 미정의 상태(`WAITING`)도 fallback reason_detail이 생성된다.
- [PASS] 32 `MARKET_PREOPEN_BLOCKED`는 시장코드 보존된다.
- [PASS] 33 `MARKET_CLOSED` 코드는 그대로 보존된다.
- [PASS] 34 거부 원문 `주문가능금액 초과`는 `ORDERABLE_CASH_LIMIT`로 정규화된다.
- [PASS] 35 `SEED_LIMIT_EXCEEDED` 메타 누락 상황에서도 결론/코드/조치 필수필드가 유지된다.

## 결과 요약
- 총 35건 / 성공 35건 / 실패 0건
- 앱 컴파일 통과
- 자동매매 계약 preflight 통과

## 확인한 개선 포인트
- 실행 결과 요약은 `reason 문자열`이 아니라 `reason_code` 기준으로 집계하도록 변경.
- `reason_detail`이 누락되던 상태(`BROKER_SUBMITTED`, `BROKER_FILLED`, `BROKER_CANCELED`, `BROKER_CLOSED`, `ERROR`, 기타)에도 fallback 구조를 강제 생성.
