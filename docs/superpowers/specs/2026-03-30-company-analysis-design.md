# 기업분석 기능 — 설계 스펙

> **목표**: 일반인이 DART 재무제표를 회계사 수준으로 쉽게 이해할 수 있도록,
> 친근한 언어와 시각적 지표로 상장 기업을 분석·비교·매매까지 연결하는 기능

---

## 1. 핵심 가치 제안

증권사 앱: 재무 데이터만 보여줌.
**이 앱: 펀더멘탈(건강점수) × 기술적(Gate ON) × 자동매매를 하나로 연결 → 진짜 의사결정 도구.**

- 일반인이 DART를 직접 볼 필요 없이 "이 회사 매출은 얼마고, 부채는 적고, 앞으로 이게 기대돼요" 형식으로 이해
- 건강점수로 좋은 회사/나쁜 회사 한눈에 구분
- 건강한 회사 + 기술적 매수 타이밍(Gate ON) = 최적 매수 신호 → 자동매매 직접 연결

---

## 2. 화면 구성

### 2-1. 기업분석 탭 (리스트 화면)

**구조 (위→아래):**

1. **큐레이션 상단** — "오늘의 우량주 TOP5" 가로 스크롤 카드
   - 서버에서 건강점수 상위 5개 자동 선정
   - 카드: 기업명 + 건강점수 + 간단 한줄 태그

2. **검색바** — 공통 SearchBar 컴포넌트 활용
   - 기업명 또는 종목코드 검색
   - 기존 `/stocks/search` API 활용

3. **필터 칩 (가로 스크롤)**
   - 건전성 등급: 전체 | 🟢좋은기업(80~100) | 🟡보통(50~79) | 🔴위험(0~49)
   - 시장: 코스피 | 코스닥
   - 업종: 반도체, 바이오, 금융, 건설 등 (KRX 업종)
   - 시가총액: 대형주(10조↑) | 중형주(1~10조) | 소형주(1조↓)
   - 배당: 배당주만 ON/OFF
   - **Gate ON**: 기술적 매수 신호 활성 종목만 필터 (차별화 핵심)
   - 관심종목: 내 관심종목만 ON/OFF

4. **정렬** — 건강점수↓ | 매출↓ | 성장률↓ | 시가총액↓

5. **종목 카드 리스트** — LazyColumn, 페이지네이션(20개씩)

**종목 카드 1장 구성:**

| 영역 | 내용 | 데이터 소스 |
|------|------|------------|
| 좌상단 | 신호등(🟢🟡🔴) + 기업명 + 종목코드 | healthScore 계산 |
| 우상단 | 현재가 + 등락률 | KIS 시세 API |
| 중단 | 건강점수, 매출액, 부채비율, 성장률 | DART 재무 |
| 하단 | AI 한줄 요약 + Gate ON 뱃지(해당 시) | 서버 생성 |

→ **기존 CommonReportItemCard 확장** (건강점수·AI요약 필드 추가)

### 2-2. 기업 상세 화면 (종목 카드 클릭 시)

**구조 (위→아래 스크롤):**

1. **헤더** — 기업명 + 종목코드 + 현재가 + 등락률

2. **AI 해설 카드** — "AI가 알려주는 기업 현황"
   - 3~5문장, 친근한 말투 ("~에요", "~거든요")
   - 매출 실적 → 성장 원인 → 재무 건전성 → 주의 사항

3. **핵심 재무 지표** (9개)
   - 매출액, 영업이익, 순이익, 부채비율, ROE, 유동비율, PER, PBR, 배당수익률
   - 각 지표 옆 🟢🟡🔴 상태 + "업종 평균(45%)보다 낮아요" 맥락 설명

4. **건전성 진단서**
   - 종합 점수: 92점/100점 🟢
   - 5개 카테고리별 프로그레스 바
   - 한줄 코멘트: "수익성과 안정성 모두 우수, 다만 현재 주가가 다소 높은 편이에요"

5. **투자 포인트**
   - ✅ 기대 요인 2~3개
   - ⚠️ 리스크 요인 2~3개

6. **주문 연결 버튼**
   - [📈 매수하기] → 기존 OrderScreen (ticker, currentPrice 전달)
   - [♥ 관심종목 추가] → 기존 WatchlistRepository
   - [🤖 자동매매 추가] → 기존 AutoTradeSettingScreen (ticker 전달)
   - 건강점수 80↑ + Gate ON → "최적 매수 타이밍 🎯" 뱃지 표시

### 2-3. 포트폴리오 건강검진 (기업분석 탭 하단 또는 별도 섹션)

- KIS 보유종목 API → 각 종목 건강점수 자동 조회
- 평균 건강점수 표시
- 건강점수 낮은 종목 경고: "카카오 51점 — 건강하지 않아요. 매도를 고려해보세요."

---

## 3. 건전성 점수 산출

### 3-1. 5대 평가 카테고리 (각 20점, 총 100점)

| 카테고리 | 배점 | 세부 지표 | 가중치 |
|----------|------|----------|--------|
| 수익성 | 20점 | 영업이익률(40%), ROE(30%), 순이익률(30%) | |
| 안정성 | 20점 | 부채비율(40%), 유동비율(30%), 이자보상배율(30%) | |
| 성장성 | 20점 | 매출 성장률 YoY(50%), 영업이익 성장률 YoY(50%) | |
| 효율성 | 20점 | 자산회전율(60%), 재고자산회전율(40%) | |
| 밸류에이션 | 20점 | PER 업종대비(50%), PBR 업종대비(50%) | |

### 3-2. 등급 분류

| 등급 | 점수 | 표시 | 의미 |
|------|------|------|------|
| 좋은 기업 | 80~100 | 🟢 #4CAF50 | 재무적으로 우수하고 성장 중 |
| 보통 기업 | 50~79 | 🟡 #FFC107 | 일부 지표에서 주의 필요 |
| 위험 기업 | 0~49 | 🔴 #FF4747 | 재무 건전성에 심각한 문제 |

### 3-3. 점수 계산 로직

각 세부 지표를 업종 내 백분위(percentile)로 환산 → 0~20점 스케일링.
- 상위 20% → 18~20점
- 상위 40% → 14~17점
- 상위 60% → 10~13점
- 상위 80% → 6~9점
- 하위 20% → 0~5점

부채비율/PER 등 낮을수록 좋은 지표는 역순 적용.

---

## 4. 데이터 아키텍처

### 4-1. 데이터 소스

| 데이터 | 출처 | 갱신 주기 |
|--------|------|----------|
| 재무제표 | DART OpenAPI (fnlttSinglAcnt) | 분기별 |
| 실시간 시세 | KIS API (기존 연동) | 실시간/종가 |
| 업종 분류 | KRX 업종 코드 | 월 1회 |
| AI 요약/투자포인트 | 서버 생성 (템플릿 기반) | 분기별 |
| 뉴스 기반 리스크 | 기존 news_service.py | 장중 30분 |
| Gate ON 신호 | 기존 단타엔진 gate.py | 실시간 |

### 4-2. 초기 대상 범위

- **Phase 1**: 코스피200 + 코스닥150 = ~350개 종목
- **Phase 3**: 전종목 2,500개 확대 (DART API 호출제한 일 10,000회 고려)

### 4-3. DART OpenAPI 활용

- 기존 연동: `news_service.py`의 `fetch_dart()` → 공시 목록 수집 (opendart_api_key)
- **신규**: `fnlttSinglAcnt` (단일회사 전체 재무제표) API 추가
- 엔드포인트: `https://opendart.fss.or.kr/api/fnlttSinglAcnt.json`
- 파라미터: corp_code, bsns_year, reprt_code(11013=1분기, 11012=반기, 11014=3분기, 11011=사업보고서)
- 필요 항목: 매출액, 영업이익, 당기순이익, 자산총계, 부채총계, 자본총계, 유동자산, 유동부채

### 4-4. Fallback 전략

- DART 데이터 미제공 종목 (신규상장, 소형주) → "분석 준비 중" 표시 + 공시일 안내
- 데이터 부분 누락 → 계산 가능한 카테고리만 점수화, 누락 카테고리는 N/A

---

## 5. AI 해설 톤 & 매너

### 5-1. 작성 원칙

- 친근한 존댓말: "~에요", "~거든요", "~이에요"
- 숫자는 맥락과 함께: "부채비율 24%" → "업종 평균(45%)보다 훨씬 낮아요"
- 비유 적극 활용: "유동비율 215%는 1년 안에 갚을 돈의 2배 이상을 현금으로 가지고 있다는 뜻이에요"
- 좋은 점과 우려점 균형
- 전문 용어 사용 시 괄호 설명: "ROE(자기자본이익률)는 12.8%로..."

### 5-2. 생성 방식

- 1차: 템플릿 + 규칙 기반 (외부 LLM 호출 없음)
- 향후: LLM 기반 고도화 가능하도록 인터페이스 분리 (ABC)

---

## 6. 기존 컴포넌트 재활용

| 기능 | 기존 컴포넌트 | 재활용 방식 |
|------|-------------|------------|
| 종목 카드 | CommonReportItemCard | 건강점수·AI요약 필드 추가 확장 |
| 상단바 | AppTopBar | 그대로 사용 |
| 종목 상세 이동 | StockDetailActivity.open() | 그대로 사용 |
| 검색 | /stocks/search API | 기존 API 활용 |
| 관심종목 | WatchlistRepository | 기존 API |
| 시장 지표 | MarketIndexSimpleCard | 업종비교 카드에 재활용 |
| 뉴스 연결 | NewsDetailBottomSheet | 공시 상세에 재활용 |

---

## 7. API 설계

### 7-1. GET /analysis/companies

```
Query: grade, market, sector, marketCap, dividend, gateOn, watchlistOnly, sortBy, sortOrder, page, size
Response: {
  companies: [CompanyCard],
  curation: { topCompanies: [CompanyCard] },  // 오늘의 우량주
  totalCount, page, totalPages
}
```

### 7-2. GET /analysis/companies/{ticker}

```
Response: {
  basicInfo: { ticker, name, market, sector, marketCap, currentPrice, changeRate },
  financials: { revenue, operatingProfit, netIncome, debtRatio, roe, currentRatio, per, pbr, dividendYield, revenueGrowth, profitGrowth },
  healthScore: { total, profitability, stability, growth, efficiency, valuation },
  aiAnalysis: { summary, positivePoints[], riskPoints[], healthComment },
  gateSignal: { gateOn, technicalScore },
  charts: { revenueHistory[], profitHistory[] }
}
```

### 7-3. GET /analysis/portfolio-health

```
Header: Authorization Bearer Token
Response: {
  averageScore, holdings: [{ ticker, name, healthScore, grade }],
  warnings: [{ ticker, name, score, message }]
}
```

---

## 8. 서버/앱 파일 구조

### 서버 (신규)

| 파일 | 역할 |
|------|------|
| backend/app/dart_financial_client.py | DART 재무제표 조회 클라이언트 |
| backend/app/analysis_service.py | 건전성 점수 계산 + 기업분석 비즈니스 로직 |
| backend/app/analysis_text_generator.py | AI 해설·투자포인트 텍스트 생성 |
| backend/app/models.py (수정) | CompanyAnalysis 테이블 추가 |
| backend/app/main.py (수정) | /analysis/* 엔드포인트 등록 |

### 앱 (신규)

| 파일 | 역할 |
|------|------|
| ui/screens/CompanyAnalysisScreen.kt | 기업분석 탭 (리스트+큐레이션+필터) |
| ui/screens/CompanyDetailScreen.kt | 기업 상세 화면 |
| data/api/ApiModels.kt (수정) | CompanyCard, CompanyDetail DTO 추가 |
| data/api/StockApiService.kt (수정) | /analysis/* API 추가 |
| viewmodel/ViewModels.kt (수정) | CompanyAnalysisViewModel 추가 |
| navigation/AppNavigation.kt (수정) | ANALYSIS 탭 추가 |

---

## 9. Phase 로드맵

| Phase | 기간 | 범위 |
|-------|------|------|
| Phase 1 MVP | 2주 | DART 수집 → 점수엔진 → API → 리스트/상세 → 주문연결 |
| Phase 2 AI고도화 | 1주 | 자연어 해설 + Gate ON 필터 + 포트폴리오 건강검진 |
| Phase 3 고도화 | 1주 | 동종업계 비교 + 분기차트 + DART 공시연결 + 전종목 확대 |
