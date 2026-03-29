# 보유 주문 메시지/거래이력 UX QA 결과 (2026-03-03)

## 범위
- 대상 화면: 보유 > 거래 이력, 최근 주문 상태 카드
- 대상 파일: `HoldingsScreen.kt`
- 목표: 상태/사유/조치 문구를 사용자 언어로 통일

## QA 체크리스트 (총 34건)
1. [PASS] 주문 행 헤더가 `매수/매도 · 즉시 · 상태` 형식으로 노출된다.
2. [PASS] 예약 행 헤더가 `매수/매도/취소 · 예약 · 상태` 형식으로 노출된다.
3. [PASS] 주문 행에 종목명이 별도 줄로 노출된다.
4. [PASS] 주문 행에 `코드·수량·가격·계좌·시간`이 한 줄로 노출된다.
5. [PASS] 예약 행에 `코드·수량·가격·금액·종류·계좌·시간`이 노출된다.
6. [PASS] 주문 행에서 사유가 있으면 `사유:` 접두로 노출된다.
7. [PASS] 주문 행에서 조치가 있으면 `조치:` 접두로 노출된다.
8. [PASS] 예약 행에서 사유가 있으면 `사유:` 접두로 노출된다.
9. [PASS] 예약 행에서 조치가 있으면 `조치:` 접두로 노출된다.
10. [PASS] 상단 카드 로딩 문구가 `주문 요청을 확인하고 있습니다.`로 통일된다.
11. [PASS] 상단 카드 로딩 상세 문구가 `주문 가능 수량과 계좌 상태를 확인 중입니다.`로 통일된다.
12. [PASS] `BROKER_SUBMITTED` 상태 라벨이 `접수완료`로 노출된다.
13. [PASS] `BROKER_FILLED` 상태 라벨이 `체결완료`로 노출된다.
14. [PASS] `PAPER_FILLED` 상태 라벨이 `체결완료`로 노출된다.
15. [PASS] `BROKER_REJECTED` 상태 라벨이 `증권사거부`로 노출된다.
16. [PASS] `BROKER_CANCELED` 상태 라벨이 `취소완료`로 노출된다.
17. [PASS] `SKIPPED` 상태 라벨이 `실행안됨`으로 노출된다.
18. [PASS] `ERROR` 상태 라벨이 `처리실패`로 노출된다.
19. [PASS] `SELLABLE_QTY_ZERO` 사유가 한글 문장으로 번역된다.
20. [PASS] `PENDING_SELL_ORDER` 사유가 한글 문장으로 번역된다.
21. [PASS] `PENDING_BUY_ORDER` 사유가 한글 문장으로 번역된다.
22. [PASS] `SEED_LIMIT_EXCEEDED` 사유가 한글 문장으로 번역된다.
23. [PASS] `ALREADY_OPEN_POSITION` 사유가 한글 문장으로 번역된다.
24. [PASS] `STOPLOSS_REENTRY_COOLDOWN` 사유가 한글 문장으로 번역된다.
25. [PASS] `BROKER_REJECTED` 사유가 잔고/주문가능수량 불일치 문장으로 번역된다.
26. [PASS] `MANUAL_ORDER_REJECTED` 사유가 한글 문장으로 번역된다.
27. [PASS] `RESERVATION_EXECUTED` 사유가 한글 문장으로 번역된다.
28. [PASS] `RESERVATION_PARTIAL` 사유가 한글 문장으로 번역된다.
29. [PASS] `USER_CANCELED` 사유가 한글 문장으로 번역된다.
30. [PASS] `MARKET_*_RESERVED` 사유가 장상태 기반 예약 문장으로 번역된다.
31. [PASS] `MARKET_*_BLOCKED` 사유가 장상태 기반 차단 문장으로 번역된다.
32. [PASS] `resolveReasonActionLabel`이 주요 차단 사유별 다음 행동 문구를 제공한다.
33. [PASS] 검색창 compact 높이(42dp)가 유지된다.
34. [PASS] `:app:compileDebugKotlin` 빌드가 성공한다.

## 검증 방법
- 정적 검증: 코드 라인 점검 (`HoldingsScreen.kt`)
- 빌드 검증: `./gradlew :app:compileDebugKotlin --no-daemon`

## 결과 요약
- 총 34건 중 34건 PASS
- 실패 0건

## 2차 재검증 (2026-03-03 10:05 KST)
1. [PASS] `:app:compileDebugKotlin` 재실행 통과
2. [PASS] `:app:testDebugUnitTest` 재실행 통과
3. [PASS] `:app:assembleDebug` 재실행 통과
4. [PASS] `resolveOrderReasonLabel`에 핵심 사유값 매핑 유지 확인
5. [PASS] `resolveReasonActionLabel`에 핵심 조치값 매핑 유지 확인
6. [PASS] 주문 카드 헤더 `매수/매도 · 즉시 · 상태` 유지 확인
7. [PASS] 예약 카드 헤더 `매수/매도/취소 · 예약 · 상태` 유지 확인
8. [PASS] 상단 로딩 문구 2줄(`주문 요청 확인`, `수량/계좌 확인`) 유지 확인

## 2차 결론
- 재검증 8건 전부 PASS
- 컴파일/유닛테스트/디버그 조립 모두 정상

## 3차 보정 검증 (2026-03-03 10:12 KST)
1. [PASS] 최근 주문 상태 카드가 `reason_detail.conclusion` 우선 사용
2. [PASS] 최근 주문 상태 카드가 `reason_detail.action`을 상세 문구에 포함
3. [PASS] `잔고내역이 없습니다` 원문을 `증권사 보유 잔고가 없어 주문이 거부되었습니다`로 변환
4. [PASS] 기본 fallback 문구가 `증권사 응답 사유 확인 필요`로 개선
5. [PASS] `:app:compileDebugKotlin` 재빌드 통과

## 3차 결론
- 지적된 `증권사 거부 + 처리 사유 확인 필요` 노출 경로를 보정 완료

## 4차 재시작 QA (2026-03-03 11:03 KST)
1. [PASS] `:app:compileDebugKotlin` 재실행 통과 (초기 1회 파일잠금 오류 후 재실행 정상)
2. [PASS] `:app:testDebugUnitTest` 재실행 통과
3. [PASS] `:app:assembleDebug` 재실행 통과
4. [PASS] 최근 주문 상태 스낵바가 `reason_detail.conclusion` 우선 사용
5. [PASS] 최근 주문 상태 카드가 `reason_detail.conclusion` 우선 사용
6. [PASS] 최근 주문 상태 카드가 `reason_detail.action` 포함
7. [PASS] 증권사 원문 `잔고내역이 없습니다` 변환 규칙 유지
8. [PASS] fallback 문구가 `증권사 응답 사유 확인 필요`로 유지
9. [PASS] 핵심 reason 매핑 9종(`SELLABLE_QTY_ZERO` 등) 모두 존재
10. [PASS] 핵심 action 매핑 5종(`BROKER_REJECTED` 등) 모두 존재
11. [PASS] 백엔드 `AutoTradeOrderItem`의 `reason_detail` 생성 경로 유지 확인
12. [PASS] `python3 -m py_compile backend/app/main.py backend/app/autotrade_reason_codes.py` 통과

## 4차 결론
- 재시작 QA 12건 전부 PASS
- 현재 확인 범위(코드/빌드/테스트)에서는 동일 이슈 재현되지 않음
