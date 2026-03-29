# 가설 검증 + 산출 알고리즘 + 데이터 매핑 풀 명세 (v2.0)
기준일: 2026-02-11 (KST)  
적용 범위: `단타`, `장투`, `논문`, `급등`, `미장`

## 1. 문서 목적
이 문서는 "검증 프레임 요약"이 아니라, 실제 서비스 코드 기준으로 아래를 재현하기 위한 풀 명세다.
1. 전략별 산출 알고리즘(필터, 수식, 정렬, 예외)
2. 데이터 소스와 fallback 경로
3. 백엔드 응답이 앱 종목 카드에 매핑되는 전체 경로

검증 AI는 본 문서와 함께 제공되는 원본 소스 파일을 우선 근거로 사용한다.

## 2. 소스 오브 트루스(코드 파일)
백엔드 핵심:
- `backend/engine/strategy.py`
- `backend/engine/features.py`
- `backend/engine/simulate.py`
- `backend/engine/universe.py`
- `backend/app/movers.py`
- `backend/app/us_insiders.py`
- `backend/app/main.py`
- `backend/app/schemas.py`
- `backend/app/config.py`

앱 핵심:
- `app/src/main/java/com/example/stock/data/api/ApiModels.kt`
- `app/src/main/java/com/example/stock/data/repository/StockRepository.kt`
- `app/src/main/java/com/example/stock/viewmodel/ViewModels.kt`
- `app/src/main/java/com/example/stock/ui/common/ReportComponents.kt`
- `app/src/main/java/com/example/stock/ui/screens/Screens.kt`
- `app/src/main/java/com/example/stock/ui/screens/MoversScreen.kt`
- `app/src/main/java/com/example/stock/ui/screens/UsInsiderScreen.kt`

## 3. 전략별 산출 알고리즘

### 3.1 단타 (Premarket Daytrade)
엔트리 함수:
- `generate_premarket(...)` in `backend/engine/strategy.py`

#### 3.1.1 데이터 로딩/후보군
1. 보고일 기준 데이터 윈도우 설정 후 panel 구성.
2. KRX API 키가 있으면 KRX 일별 API 우선 사용, 없으면 `load_universe` + `load_ohlcv` fallback.
3. 종목별 피처 계산:
- `value = Close * Volume` (`features.py`)
- `value_ma20 = rolling mean(20)`
- `ta = feat_ta(value, 3, 20) = log(MA3(value)/MA20(value))`
- `re = feat_re(df) = |Close-Open| / |High-Low|`

#### 3.1.2 단타 후보 필터(`_candidate_rows`)
종목별로 signal_date 행 기준:
1. 거래일 포함 여부 확인
2. 최소 히스토리(`loc >= 30`)
3. 유동성: `value_ma20 >= settings.min_value_krw`
4. 갭 제한:
- `gap_pct = (Open_t / Close_t-1 - 1) * 100`
- `settings.max_gap_pct > 0`이면 `gap_pct <= max_gap_pct`
5. RS 계산:
- `asset_r5 = Close_t / Close_t-5 - 1`
- `bench_r5 = Bench_t / Bench_t-5 - 1` (시장별 벤치 사용)
- `rs = asset_r5 - bench_r5`
6. RS 결측은 market별 중앙값으로 대체.

#### 3.1.3 점수 산출
후보 DataFrame에 대해:
1. winsorize 2%:
- `ta_w = winsorize(ta, 0.02)`
- `re_w = winsorize(re, 0.02)`
- `rs_w = winsorize(rs, 0.02)`
2. robust z-score:
- `z_x = (x - median(x)) / (1.4826 * MAD(x))`
- MAD=0이면 0 반환
3. 최종 점수:
```text
score = w_ta*z_ta + w_re*z_re + w_rs*z_rs
```
4. 가중치:
- 프리셋: DEFENSIVE/ADAPTIVE/AGGRESSIVE
- 또는 custom weight 3종.

#### 3.1.4 테마 분산 + 수량 충족
1. `build_themes`로 theme_id 구성(캐시 재사용/주기적 리밸런스).
2. 1차: `theme_cap` 상한으로 분산 선별(`_apply_theme_cap`).
3. 2차: 고득점 fallback_pool을 append해 요청 수량까지 채움.
4. 이 구조로 "항상 16개 고정" 문제를 회피하도록 설계됨.

#### 3.1.5 매매 레벨 산출(`_to_items`)
각 후보행에서:
```text
entry  = round_to_tick(base_ref * 1.005, UP)
stop   = round_to_tick(entry * 0.97, DOWN)
target = round_to_tick(entry * 1.06, UP)
```
- `base_ref`: `high_t` 우선, 없으면 `close_t`.
- tick rounding 후 `stop < entry < target` 보정.

#### 3.1.6 게이트(gate) 산출
`compute_gate_status`:
1. 과거 거래일별 가상 트레이드(`simulate_trades`)로 `daily_mean_R` 산출.
2. `gate_metric = rolling_mean(daily_mean_R, gate_lookback).shift(1)`
3. 판정:
- `gate_quantile` 존재 시 분위 임계
- 없으면 `gate_threshold` 고정 임계
4. gate OFF면:
- `daytrade_primary` 비움
- watch 후보를 관찰용으로만 제공.

#### 3.1.7 단타 출력 필드
- `daytrade_top`, `daytrade_primary`, `daytrade_watch`, `daytrade_top10`
- `daytrade_gate(on, lookback_days, gate_metric, gate_on_days, gate_total_days, reason)`
- `themes`(UI 필터용 요약)

### 3.2 장투 (Premarket Longterm)
엔트리 함수:
- `_build_longterm(...)` in `backend/engine/strategy.py`

필터:
1. 단타 상위와 중복 티커 제외
2. `value_ma20 >= long_min_value_krw`
3. `ret20 = Close_t / Close_t-20 - 1 > 0`

레벨:
```text
buy_low   = round_to_tick(close * 0.97, DOWN)
buy_high  = round_to_tick(close * 1.01, UP)
target    = round_to_tick(close * 1.25, UP)
stop_loss = round_to_tick(close * 0.85, DOWN)
```
검증: `stop < buy_low < buy_high < target`.

정렬:
- 시가총액(cap) 내림차순.

출력:
- `longterm`, `longterm_top10`

### 3.3 논문 (Papers)
현재 구현은 독립 산출 엔진이 아님.
1. 앱 저장소에서 `getPaperRecommendations(date) = getPremarket(date)`로 연결.
2. PapersScreen은 premarket payload의 daytrade 계열을 재사용:
- `daytradePrimary(ifEmpty daytradeTop) + daytradeWatch`
3. `/papers/summary`는 정적 설명 섹션 응답이며 종목 산출 로직이 아님.

결론:
- 현재 논문 탭 종목 가설은 단타 산출과 동일 데이터 경로를 공유한다.

### 3.4 급등 (Movers)
엔트리 함수:
- `compute_movers(...)` in `backend/app/movers.py`

모드:
- `chg`, `chg_down`, `value`, `volume`, `value_ratio`, `popular`

주요 단계:
1. 입력 정규화:
- period: `1d/1w/1m/3m/6m/1y`
- count: 5~100, pool_size: 30~400
2. 단기 캐시:
- 키별 TTL 8초
3. 후보 풀:
- 최근 KRX 거래일 데이터에서 `baseline_value`(전일 거래대금) 상위 `pool_size`
4. 실시간 결합:
- `fetch_quotes(tickers)`로 현재가/등락/거래량/거래대금 결합
5. 등락률:
- 기본은 quote chg_pct
- period 모드에 따라 `ref_close` 기준 재산출:
```text
chg_pct = (price / ref_close - 1) * 100
```
6. 급등/급락 최소 필터:
- `movers_min_chg_pct` (기본 3.0)
7. value_ratio:
```text
value_ratio = live_value / baseline_value
```
8. 정렬 키:
- value -> 거래대금
- volume -> 거래량
- value_ratio -> 비율
- chg/chg_down -> 등락률
9. popular 모드:
- 네이버 인기 목록 파싱 + 실시간 시세 결합.

출력:
- `MoversResponse { as_of, bas_dd, ref_bas_dd, period, mode, items[] }`

### 3.5 미장 (US Insiders, SEC Form4)
엔트리 함수:
- `compute_us_insider_screen(...)` in `backend/app/us_insiders.py`

#### 3.5.1 수집 경로
1. 1순위: SEC daily-index(master.idx)에서 Form4 후보 수집
2. 후보 없음 시: atom(getcurrent) 폴백
3. rate limit(429), 차단(403) 대응 포함

#### 3.5.2 원문 파싱
1. txt 제출문에서 `<ownershipDocument>` XML 추출
2. index 기반 접근 시 xsl 변환 경로보다 raw xml 경로를 우선
3. issuer/reporting owner/non-derivative transaction 파싱

#### 3.5.3 필터 조건
1. Reporting Owner 직책이 CEO/CFO로 분류될 것
2. `nonDerivativeTransaction`만
3. `transactionCode == P`만
4. 거래일/수량/단가 모두 유효
5. A/D 코드가 D(처분)면 제외

#### 3.5.4 집계 및 반복매수
집계 단위:
- `(ticker, executive_name)` 기준 그룹

산출:
```text
total_shares    = sum(shares)
total_value_usd = sum(shares * price)
avg_price_usd   = total_value_usd / total_shares
```

반복 매수:
- 최근 90거래일 내 거래일 2회 이상이면 `repeat_buy_90d=true`, count 제공.

정렬:
- `total_value_usd desc -> total_shares desc -> transaction_date desc`

기간 확장:
- 기본 `trading_days=7`
- 부족 시 `expand_days`로 재집계.

#### 3.5.5 API 운영 보강
`/market/us-insiders`:
1. 결과가 0건이고 SEC 차단 징후(shortage_reason)가 있으면
2. 최근 성공 캐시(6시간) 스냅샷으로 임시 응답.

## 4. API 스키마와 응답 구조
스키마 기준 파일:
- `backend/app/schemas.py`

핵심 모델:
1. `PremarketResponse`
- daytrade_gate/daytrade_top/daytrade_watch/longterm/themes/hard_rules
2. `MoversResponse`
- period/mode/items
3. `UsInsiderResponse`
- requested/effective trading days, forms_checked/forms_parsed/parse_errors/shortage_reason/items
4. `FavoriteItem`
- baseline_price, current_price, change_since_favorite_pct

## 5. 백엔드 -> 앱 DTO -> 카드 매핑

### 5.1 공통 카드
공통 UI 모델:
- `CommonReportItemUi` in `ReportComponents.kt`

정렬 키:
- `sortPrice`, `sortChangePct`, `sortName`
- sort option: 가격/등락/이름 오름차순/내림차순

공통 리스트 기능:
1. 검색(name/ticker/title)
2. pull-to-refresh
3. 점증 로드(displayCount +10)
4. 공통 카드(미니캔들, 메트릭, 하트 즐겨찾기)

### 5.2 단타 매핑
화면:
- `PremarketBody` in `Screens.kt`

매핑:
1. `daytradeTop + daytradeWatch` -> 화면 items
2. `trigger_buy/target_1/stop_loss` -> metrics(진입/목표/손절)
3. 실시간 quote map 조인 -> quote line
4. tags/theme -> extraLines "테마: ..."

### 5.3 장투 매핑
화면:
- `LongtermScreen` in `Screens.kt`

매핑:
1. `longterm[]` -> items
2. `buy_zone.low/high`, `stop_loss` -> metrics
3. `target_12m` -> extra line
4. 실시간 없을 때 `d1_close` fallback

### 5.4 논문 매핑
화면:
- `PapersScreen` in `Screens.kt`

매핑:
1. `daytradePrimary(ifEmpty daytradeTop) + daytradeWatch`
2. 카드 구성은 단타와 동일

### 5.5 급등 매핑
화면:
- `MoversScreen.kt`

매핑:
1. `MoverItemDto` -> `CommonReportItemUi`
2. mode별 metrics 분기:
- popular: 인기순위/등락/거래량
- others: 등락/거래대금/거래량
3. `tags`, `value_ratio`, `search_ratio` -> extraLines
4. 미니캔들: `prev_close -> price` 1바 합성

### 5.6 미장 매핑
화면:
- `UsInsiderScreen.kt`

매핑:
1. `UsInsiderItemDto` -> 공통카드
2. metrics:
- 매수금액(total_value_usd)
- 수량(total_shares)
- 평균단가(avg_price_usd)
3. extra:
- 직책/임원명
- 거래일/제출일
- 반복매수 횟수
- 10b5-1
- accession(Form4 식별자)
4. notes -> thesis 영역

## 6. 운영/캐시/성능 로직
1. Premarket:
- 서버 캐시 + 생성 큐 + fallback 응답 + 태그 주입
2. Movers:
- 8초 메모리 TTL 캐시
3. US insiders:
- SEC 이슈 시 6시간 성공 스냅샷 재사용
4. App chart:
- chartSemaphore(동시 4), chart cache TTL 5분

## 7. 검증 시 필수 수집 지표
전략별 공통:
1. 결과 건수(요청 대비 반환)
2. 생성 시간 p50/p95
3. 데이터 fallback 비율
4. 소스 신선도(`as_of`, `generated_at`)

미장 추가:
1. `forms_checked`
2. `forms_parsed`
3. `parse_errors`
4. `shortage_reason`

단타 추가:
1. `gate_on`, `gate_metric`
2. `daytrade_top + watch` 반환 개수 vs 설정 개수

## 8. 현재 구조상 명확한 제한사항
1. 논문 탭 종목 산출은 단타 엔진 재사용(독립 엔진 아님)
2. Movers 후보군이 "전일 거래대금 상위 pool"에 의존하므로 초소형 신규 급등 탐지 한계 존재
3. SEC 호출 제한(429/403)은 완전 제거 불가, 캐시 재사용으로 운영 연속성만 보강

## 9. 다른 AI 전달 시 권장 입력
아래를 함께 전달:
1. 본 문서
2. 동봉된 원본 소스 파일
3. 검증 기간/시장 레짐/평가 지표 정의
4. 결과 제출 포맷(전략별 산출식 검증표 + 매핑 검증표 + 판정)
