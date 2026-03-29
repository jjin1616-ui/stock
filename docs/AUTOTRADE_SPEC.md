# KoreaStockDash 자동매매 개발 명세서 (v1)

작성일: 2026-02-16  
대상: Android + FastAPI + SQLite

## 1) 아키텍처
## 1.1 컴포넌트
- Android
  - 탭: `autotrade`
  - 화면: `AutoTradeScreen`
  - ViewModel: `AutoTradeViewModel`
  - API: `StockApiService`의 `/autotrade/*` 엔드포인트
- Backend
  - 라우트: `backend/app/main.py`
  - 서비스: `backend/app/autotrade_service.py`
  - 브로커 어댑터: `backend/app/kis_broker.py`
  - 저장소: SQLite (`autotrade_settings`, `user_broker_credentials`, `autotrade_orders`, `autotrade_daily_metrics`)

## 1.2 외부 연동 기준
- KIS 공식 샘플(`open-trading-api`) 기준 매핑
  - 토큰: `POST /oauth2/tokenP`
  - 주문: `POST /uapi/domestic-stock/v1/trading/order-cash`
  - TR-ID:
    - 실전 매수 `TTTC0012U`, 실전 매도 `TTTC0011U`
    - 모의 매수 `VTTC0012U`, 모의 매도 `VTTC0011U`

## 2) 데이터 모델
## 2.1 autotrade_settings
- user_id (unique)
- enabled
- environment (`paper|demo|prod`)
- include_daytrade / include_movers / include_papers / include_longterm / include_favorites
- order_budget_krw
- max_orders_per_run
- max_daily_loss_pct
- allow_market_order
- created_at / updated_at

## 2.2 autotrade_orders
- user_id, run_id
- source_tab (`DAYTRADE|MOVERS|PAPERS|LONGTERM|FAVORITES`)
- ticker, name, side, qty
- requested_price, filled_price, current_price, pnl_pct
- status (`PAPER_FILLED|BROKER_SUBMITTED|BROKER_REJECTED|SKIPPED`)
- broker_order_no, reason, metadata_json
- requested_at, filled_at

## 2.3 user_broker_credentials
- user_id (unique)
- use_user_credentials
- app_key_demo_enc / app_secret_demo_enc
- app_key_prod_enc / app_secret_prod_enc
- account_no_enc / account_product_code
- created_at / updated_at

## 2.4 autotrade_daily_metrics
- user_id + ymd (unique)
- orders_total, filled_total
- buy_amount_krw, eval_amount_krw
- realized_pnl_krw, unrealized_pnl_krw
- roi_pct, win_rate, mdd_pct
- updated_at

## 3) 후보 통합 규칙
우선순위(중복 티커 제거 순서):
1. DAYTRADE: `PREMARKET.daytrade_top + daytrade_watch`
2. PAPERS: `PREMARKET.var7_top10` (없으면 `base_top10`)
3. LONGTERM: `PREMARKET.longterm`
4. MOVERS: `compute_movers2(session=regular, direction=up)`
5. FAVORITES: `user_favorites`

중복 처리:
- 동일 ticker는 먼저 들어온 소스를 우선 채택
- KR ticker는 실시간 시세(`fetch_quotes`)로 `current_price/chg_pct`를 채움

## 4) 실행 규칙
## 4.1 공통
- `dry_run=true`이면 DB 주문 생성 없이 후보만 계산
- `enabled=false`이면 실주행 차단
- 일손실 한도 검사:
  - 당일 `roi_pct <= -max_daily_loss_pct` 이면 `DAILY_LOSS_LIMIT_REACHED`

## 4.2 주문 수량
- `qty = floor(order_budget_krw / order_price)`
- `qty <= 0`이면 `SKIPPED(QTY_ZERO)`

## 4.3 실행 모드
- `paper` 또는 `KIS_TRADING_ENABLED=false`:
  - `PAPER_FILLED`로 기록
- `demo/prod` + `KIS_TRADING_ENABLED=true`:
  - 자격정보 미준비 시 `BROKER_CREDENTIAL_MISSING` 반환
  - KIS 주문 전송
  - 성공: `BROKER_SUBMITTED`
  - 실패: `BROKER_REJECTED`

## 5) 지표 계산
- buy_amount_krw = sum(filled_price * qty)
- eval_amount_krw = sum(current_price * qty)
- unrealized_pnl_krw = sum((current_price - filled_price) * qty)
- realized_pnl_krw = v1에서는 0 고정 (청산 로직 미포함)
- roi_pct = (realized + unrealized) / buy_amount * 100
- win_rate = (pnl_pct > 0 인 주문 수) / (지표 계산 대상 주문 수)
- mdd_pct = 당일 주문의 최소 pnl_pct 절댓값

## 6) API 명세
## 6.1 GET `/autotrade/settings`
- Response: `AutoTradeSettingsResponse`

## 6.2 POST `/autotrade/settings`
- Request: `AutoTradeSettingsPayload`
- Validation:
  - order_budget_krw >= 10000
  - max_orders_per_run: 1..100
  - max_daily_loss_pct: 0.1..50

## 6.3 GET `/autotrade/candidates?limit=50`
- Response: `AutoTradeCandidatesResponse`

## 6.4 GET `/autotrade/broker`
- Response: `AutoTradeBrokerCredentialResponse`
- 설명: 사용자별 한국투자 자격정보 저장 상태(마스킹) + demo/prod 준비상태 + `kis_trading_enabled` 반환

## 6.5 POST `/autotrade/broker`
- Request: `AutoTradeBrokerCredentialPayload`
- 설명: 입력한 필드만 암호화 저장(원문 재조회 불가)

## 6.6 POST `/autotrade/run`
- Request: `{ dry_run: bool, limit?: int }`
- Response: `AutoTradeRunResponse`

## 6.7 GET `/autotrade/orders?page=1&size=50`
- Response: `AutoTradeOrdersResponse`

## 6.8 GET `/autotrade/performance?days=30`
- Response: `AutoTradePerformanceResponse`

## 7) Android 화면 명세
## 7.1 탭/네비
- `AppTab.AUTOTRADE(route="autotrade", label="자동")`

## 7.2 UI 구성
- Top: `AppTopBar("자동매매")`
- Body: `CommonReportList`
  - topContent: 설정카드 + 브로커정보카드 + 수익요약카드 + 후보요약카드
  - list items: 주문 히스토리 카드

## 7.3 ViewModel 동작
- `loadAll()`:
  - settings / candidates / orders / performance 동시 로드
- `run(dryRun)`:
  - 실행 후 orders/performance/candidates 재로드

## 8) 환경변수
- `KIS_BASE_URL_PROD`
- `KIS_BASE_URL_DEMO`
- `KIS_APP_KEY_PROD`
- `KIS_APP_SECRET_PROD`
- `KIS_APP_KEY_DEMO`
- `KIS_APP_SECRET_DEMO`
- `KIS_ACCOUNT_NO`
- `KIS_ACCOUNT_PRODUCT_CODE`
- `KIS_TRADING_ENABLED`
- `KIS_ORDER_TIMEOUT_SEC`
- `CREDENTIALS_CRYPTO_SECRET`

## 9) 검증 시나리오
1. 설정 저장 후 재조회 값 일치 확인
2. dry_run 실행 시 주문 row 미생성 확인
3. paper 실행 시 `PAPER_FILLED` 상태 생성 확인
4. 일손실 한도 도달 시 `DAILY_LOSS_LIMIT_REACHED` 반환 확인
5. 자동매매 탭에서 히스토리/ROI/승률/MDD 노출 확인
