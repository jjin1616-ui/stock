# KoreaStockDash 자동매매 기획안 (v1)

작성일: 2026-02-16  
기준 규칙: `server_rules/RULES.md` (Auto Trading Rules, 2026-02-16)

사용자 관점 상세 기획: `docs/AUTOTRADE_USER_PLAN.md`

## 1) 목표
- 앱 내 추천 소스(단타/급등/논문/장투/관심)에서 추려진 종목을 자동 주문으로 연결한다.
- 기본은 모의(`paper`) 실행이며, 사용자 명시 활성화 + 서버 가드레일을 만족할 때만 KIS 실주문/모의주문 API를 사용한다.
- 주문 히스토리와 수익률 지표를 앱에서 즉시 확인 가능하게 제공한다.

## 2) 참고 소스 선정
- 1순위(적용): [koreainvestment/open-trading-api](https://github.com/koreainvestment/open-trading-api)
  - 최신성: 2026-02-15 기준 최근 업데이트 확인
  - 적합성: Python/REST 샘플 + TR-ID/주문 파라미터가 현재 FastAPI 구조와 직접 매핑 가능
- 보조 비교:
  - [pjueon/pykis](https://github.com/pjueon/pykis)
  - [youhogeon/finance.kis_api](https://github.com/youhogeon/finance.kis_api)

## 3) 사용자 시나리오
1. 사용자는 자동매매 탭에서 `활성화 ON`, 실행 환경(`paper/demo/prod`)을 선택한다.
2. 포함 소스(단타/급등/논문/장투/관심), 종목당 예산, 1회 주문 수, 일 손실한도를 저장한다.
3. `모의 점검`으로 후보/예상 주문 결과를 확인한다.
4. `실행` 시 백엔드가 후보를 통합하고 주문을 실행한다.
5. 사용자는 히스토리 카드에서 주문 상태와 수익 지표(ROI, win rate, MDD)를 확인한다.

## 4) 범위
### In
- 자동매매 설정 API/화면
- 후보 통합(단타/급등/논문/장투/관심)
- 실행 API(모의/실주문 분기)
- 주문 히스토리 API/화면
- 일단위 수익 지표 API/화면

### Out (v1 제외)
- 분할매수/분할청산 고급 전략
- 포지션/체결 실시간 WebSocket 동기화
- 다계좌/다브로커 멀티 라우팅
- 세금/수수료 실정산 반영

## 5) UX 원칙
- 상단/리스트는 공용 컴포넌트(`AppTopBar`, `CommonReportList`)를 사용한다.
- 상태 숨김 금지: `PAPER_FILLED`, `BROKER_SUBMITTED`, `BROKER_REJECTED`, `SKIPPED`를 그대로 노출한다.
- 실주문 off 상태에서도 동일 UI에서 모의 이력과 성과를 확인 가능해야 한다.

## 6) 리스크 가드레일
- 기본 환경: `paper`
- 일 손실 한도 초과 시 자동 주문 차단(`DAILY_LOSS_LIMIT_REACHED`)
- 주문당 예산/1회 주문 수 상한 강제
- force_password_change 상태에서는 기존 정책과 동일하게 메인 API 차단

## 7) 단계별 추진
### Phase 1 (이번 반영)
- 규칙 전환 + 백엔드 저장 모델/자동매매 API 골격 + 앱 탭/기본 화면

### Phase 2
- KIS 체결조회 연동 강화(주문접수/체결완료 상태 동기화)
- 스케줄 자동 실행(장전/장중 세션별)

### Phase 3
- 수익 지표 고도화(수수료/슬리피지/전략별 PnL Attribution)
- 경보/푸시(손실한도 임박, 연속 실패, 체결 이상)
