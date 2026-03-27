# 홈 화면 리디자인 설계 문서 (안 C — 대규모 확장)

> 작성일: 2026-03-27
> 상태: 설계 확정

---

## 0. 구현 전략 — 홈2 신규 탭

### 방침
- **기존 HomeScreen은 건드리지 않는다.**
- `AppTab` enum에 `HOME2` 탭을 신규 추가하고, `Home2Screen.kt`를 새로 만든다.
- 서버 API는 기존 엔드포인트 재활용 + 신규 엔드포인트 추가 (기존 API 변경 없음).
- 홈2가 안정화되면 기존 홈을 제거하고 홈2를 홈으로 승격 (별도 작업).

### 이점
- **즉시 롤백**: 홈2 탭만 제거하면 원상복구
- **A/B 비교**: 사용자가 두 탭을 오가며 직접 비교 가능
- **기존 사용자 영향 0**: 기존 홈은 그대로 작동

### 앱 변경 범위
- `AppNavigation.kt`: `AppTab.HOME2` 추가
- `ui/screens/Home2Screen.kt`: 신규 파일
- `viewmodel/Home2ViewModel.kt` 또는 `ViewModels.kt`에 Home2ViewModel 추가
- `ApiModels.kt`: 신규 DTO 추가 (기존 DTO 수정 없음)
- `StockApiService.kt`: 신규 API 엔드포인트 추가

---

## 1. 배경 및 목표

### 배경
- Stock v1 홈 화면은 현재 11개 섹션(브리핑~뉴스)으로 구성
- 자동매매 트레이더가 홈에서 모든 상태를 한눈에 파악하기엔 정보 부족
- 보유 종목 손익, 업종 흐름, 이상 거래 등 핵심 정보가 홈에 없음

### 목표
- **정보 완결성 우선**: 자동매매 트레이더가 홈 한 화면에서 시장·내 계좌·전략 상태를 모두 파악
- **홈2 신규 탭으로 구현** — 기존 홈은 변경하지 않음
- 서버 기존 API를 최대한 재활용하고, 신규 API는 단계적으로 추가

---

## 2. 현재 홈 화면 구성 (AS-IS)

```
0. 한줄 브리핑 (AI 요약)
1. 내 계좌 (총자산, 평가손익, 현금, 보유 종목 수)
2. 자동매매 상태 (실행/DEMO/Gate ON-OFF, 수익률, 승률, 체결)
2b. 실시간 매매 피드 (최근 10건)
3. 시장 지표 (코스피/코스닥/원달러 + Regime + 시장 온도계)
4. 오늘의 추천 (단타 TOP3 + 장타 TOP3)
5. 투자자 수급 현황
6. 관심 종목 (10개 + 더보기)
7b. 수익 캘린더
8. 주요 뉴스
```

---

## 3. 변경 후 홈 화면 구성 (TO-BE)

```
0.  한줄 브리핑                              [유지]
1.  내 계좌 + 보유 종목 (통합)               [통합+개선]
    - 총자산, 평가손익, 예수금, 매수가능금액
    - 보유 종목 리스트 (종목별 손익, 수익률)
2.  자동매매 상태                             [개선]
    - Gate ON → "진입 허용" / Gate OFF → "진입 차단"
    - 검정 배너 제거, 상태 뱃지로 통합
2b. 오늘 체결 요약                            [개선]
    - 헤더: 총 체결 건수 + 실현손익 합계
    - 아래: 개별 체결 리스트 (기존 매매 피드)
3.  시장 지표 + 업종 히트맵                   [확장]
    - 코스피/코스닥/원달러 + Regime + 온도계 (기존)
    - 업종별 등락 트리맵 (신규)
4.  거래량 급등 종목                           [신규]
5.  52주 신고가/신저가                         [신규]
6.  오늘의 추천 (단타 TOP3 + 장타 TOP3)       [유지]
7.  투자자 수급 현황                           [유지]
8.  관심 종목                                 [유지]
9.  배당/권리락 일정                           [신규]
10. 수익 캘린더                               [유지]
11. 주요 뉴스                                 [유지]
```

### 변경 요약
- 신규 섹션: 4개 (업종 히트맵, 거래량 급등, 52주 고저, 배당 일정)
- 통합/개선: 3개 (계좌+보유종목, 자동매매 Gate 레이블, 체결 요약)
- 유지: 6개

---

## 4. 기능별 상세 설계

### 4.1 내 계좌 + 보유 종목 통합

**현재 문제**: 내 계좌 카드에 "보유 종목 N개"만 표시. 종목별 손익은 홈에서 볼 수 없음.

**변경 내용**:
- 계좌 카드 상단: 총자산, 평가손익(%, 원), 예수금, 매수가능금액
- 계좌 카드 하단: 보유 종목 리스트 (종목명, 수량, 평가손익, 수익률)
- 접기/펼치기 가능 (기본: 펼침)
- 보유 종목 0개일 때: "보유 종목 없음" 텍스트 표시, 접기/펼치기 비활성

**데이터 소스**: `KIS inquire_balance()` (`kis_broker.py:458`)
- `positions[]` — 종목별 보유 정보 (이미 존재)
- `cashKrw` — 예수금 (이미 존재)
- `nrcvs_buy_amt` — 매수가능금액 (서버 DTO에 1필드 추가 필요)

**서버 변경**:
- `AutoTradeAccountSnapshotResponseDto`에 `buyableAmountKrw: float` 필드 추가
- `kis_broker.py`의 `inquire_balance()` 응답에서 `nrcvs_buy_amt` 파싱 추가

**앱 변경**:
- `ApiModels.kt`: `AutoTradeAccountSnapshotResponseDto`에 `buyableAmountKrw` 필드 추가
- `HomeScreen.kt`: `AccountSummaryCard` 컴포저블 재구성

---

### 4.2 자동매매 Gate 레이블 개선

**현재 문제**: "Gate ON/OFF"는 내부 용어. 주식 초보자에게 의미 불명.

**변경 내용**:
- `Gate ON` → `진입 허용` (초록 뱃지)
- `Gate OFF` → `진입 차단` (빨간 뱃지)
- 검정 배너 제거 → 자동매매 카드 내 칩으로 통합

**서버 변경**: 없음 (앱 UI 텍스트만 변경)

**앱 변경**:
- `HomeScreen.kt`: `AutoTradeStatusCard` 내 Gate 칩 레이블 변경

---

### 4.3 오늘 체결 요약 헤더

**현재 문제**: 매매 피드에 건수/총손익 집계 없이 개별 체결만 나열됨.

**변경 내용**:
- 카드 상단에 요약 박스: `총 체결 N건 | 실현손익 +XX,XXX원`
- 아래에 기존 개별 체결 리스트

**데이터 소스**: 기존 `/autotrade/trade-feed` API

**서버 변경**:
- 응답에 `summary` 객체 추가: `{totalCount: int, realizedPnl: float}`

**앱 변경**:
- `ApiModels.kt`: `TradeFeedResponseDto`에 `summary` 필드 추가
- `HomeScreen.kt`: `TradeFeedCard` 상단에 요약 UI 추가

---

### 4.4 업종별 등락 히트맵

**데이터 소스**: Naver Finance 업종 시세 HTML 파싱

**왜 Naver인가**:
- `news_service.py`에 이미 Naver HTML 파싱 패턴 존재
- KRX는 종목 단위 데이터라 업종 그룹핑 비용이 큼
- Naver는 업종별로 이미 분류된 데이터 제공

**서버 신규**:
- 파일: `backend/app/sector_service.py`
- API: `GET /market/sectors`
- 응답: `[{name: str, changePct: float, volume: int, topStocks: [{ticker, name, changePct}]}]`
- 캐싱: 장중 5분 TTL, 장후 종가 1회 캐시

**앱 신규**:
- 트리맵 Composable (면적 = 거래량 비례, 색상 = 등락률)
- 상승 빨강, 하락 파랑 (한국 증권 컨벤션)
- 업종명 + 등락률 텍스트 표시

---

### 4.5 거래량 급등 종목

**데이터 소스**: Naver 인기검색(`movers.py:20`) + KRX `ACC_TRDVOL`

**탐지 로직**: `오늘 거래량 / 20일 평균 거래량 > 5배` → 급등 판정

**서버 변경**:
- `movers.py` 확장 또는 별도 함수 추가
- API: `GET /market/volume-surge`
- 응답: `[{ticker, name, volumeRatio: float, price: float, changePct: float}]`
- 캐싱: 장중 3분 TTL

**앱 신규**:
- 리스트 카드 (거래량 배율 강조 표시)

---

### 4.6 52주 신고가/신저가

**데이터 소스**: pykrx (`engine/data_sources.py`에 이미 import 존재)

**범위**: 전 종목 순회 X → **관심 종목 + 보유 종목 + 추천 종목 범위만** 체크

**탐지 로직**: 252거래일 데이터로 `오늘 종가 ≥ 252일 최고가` → 신고가 / `≤ 최저가` → 신저가

**서버 신규**:
- API: `GET /market/52week-extremes?tickers=005930,035420,...`
- 응답: `{highs: [{ticker, name, price, prev52wHigh}], lows: [{ticker, name, price, prev52wLow}]}`
- 캐싱: 장중 30분 TTL
- 성능: **일 1회 배치 계산 → 캐시** (pykrx 252일 조회는 느리므로)

**앱 신규**:
- 리스트 카드 (신고가 빨강/신저가 파랑 강조)

---

### 4.7 배당/권리락 일정

**데이터 소스**: pykrx 우선 + DART 공시 보조

**왜 pykrx 우선인가**:
- `pykrx.stock.get_market_cap_by_date()`에 배당수익률 컬럼 포함
- DART는 공시 문서 단위라 배당금/권리락일을 PDF에서 추출해야 하므로 복잡

**범위**: 보유 종목 기준, 향후 30일 내 배당/권리락 일정

**서버 신규**:
- API: `GET /market/dividends?tickers=005930,035420`
- 응답: `[{ticker, name, exDate, dividendPerShare, dividendYield}]`
- 캐싱: 1일 TTL (배당 일정은 자주 변하지 않음)

**앱 신규**:
- 캘린더 카드 (날짜 + 종목 + 배당금 표시)

---

## 5. 작업별 스킬 매핑

### 기능 구현 스킬

| 작업 | 구현 스킬 |
|---|---|
| Gate 레이블 변경 | 직접 수정 (Edit) |
| 계좌+보유종목+예수금 | `feature-dev:feature-dev` |
| 체결 요약 헤더 | `feature-dev:feature-dev` |
| 업종 히트맵 (서버) | `superpowers:writing-plans` → `superpowers:executing-plans` |
| 거래량 급등 | `feature-dev:feature-dev` |
| 52주 고저 | `feature-dev:feature-dev` |
| 배당 일정 | `feature-dev:feature-dev` |
| 전체 코드 검증 | `code-review:code-review` |

### 디자인 스킬

| 작업 | 디자인 스킬 | 용도 |
|---|---|---|
| 디자인 시스템 정의 (최초 1회) | `frontend-design:frontend-design` | 색상, 폰트, 간격, 카드 스타일 토큰 |
| Gate 배너 → 뱃지 | `design:design-critique` | UX 검토 |
| 계좌+보유종목+예수금 | `design:design-critique` → `frontend-design:frontend-design` | 정보 계층 검토 → UI 구현 |
| 체결 요약 헤더 | `frontend-design:frontend-design` | UI 구현 |
| 업종 히트맵 | `frontend-design:frontend-design` | 트리맵 UI |
| 거래량 급등 | `frontend-design:frontend-design` | 카드 UI |
| 52주 고저 | `frontend-design:frontend-design` | 카드 UI |
| 배당 일정 | `frontend-design:frontend-design` | 캘린더 카드 UI |
| 전체 디자인 일관성 | `design:design-system` + `design:design-critique` | 토큰 일관성 + 사용성 |
| UX 라이팅 + 접근성 | `design:ux-copy` + `design:accessibility-review` | 라벨/문구 + WCAG |

---

## 6. 실행 순서

### 1차 — 기존 API 재활용 (즉시 구현 가능)

| 순서 | 작업 | 서버 | 앱 | 신규 라이브러리 |
|---|---|---|---|---|
| 0 | 디자인 시스템 정의 | — | — | — |
| 1 | Gate 레이블 변경 | 없음 | UI 텍스트 변경 | 없음 |
| 2 | 계좌+보유종목+예수금 | DTO 1필드 추가 | HomeScreen 재구성 | 없음 |
| 3 | 체결 요약 헤더 | summary 객체 추가 | 요약 UI 추가 | 없음 |

### 2차 — 서버 신규 API

| 순서 | 작업 | 서버 | 앱 | 신규 라이브러리 |
|---|---|---|---|---|
| 4 | 업종 히트맵 | `sector_service.py` 신규 | 트리맵 Composable | 없음 |
| 5 | 거래량 급등 | `movers.py` 확장 | 리스트 카드 | 없음 |
| 6 | 52주 고저 | pykrx 활용 API | 리스트 카드 | 없음 (이미 설치됨) |
| 7 | 배당 일정 | pykrx + DART | 캘린더 카드 | 없음 |

### 전체 완료 후

| 순서 | 작업 | 스킬 |
|---|---|---|
| 8 | 디자인 일관성 검증 | `design:design-system` + `design:design-critique` |
| 9 | UX 라이팅 + 접근성 | `design:ux-copy` + `design:accessibility-review` |
| 10 | 코드 + 보안 검증 | `code-review:code-review` |

---

## 7. 데이터 아키텍처

```
┌─────────────────────────────────────────────────┐
│                    앱 (Android)                  │
│  HomeViewModel → StockRepository → Retrofit     │
└──────────────────────┬──────────────────────────┘
                       │ HTTP
┌──────────────────────▼──────────────────────────┐
│                FastAPI 서버                       │
├─────────────────────────────────────────────────┤
│  기존 (1차)               │  신규 (2차)          │
│  ├ kis_broker.py           │  ├ sector_service.py │
│  │  └ inquire_balance()    │  │  └ Naver 업종 파싱 │
│  ├ main.py                 │  ├ movers.py (확장)  │
│  │  └ trade-feed API       │  │  └ 거래량 급등    │
│  └ realtime_quotes.py      │  ├ pykrx            │
│     └ Naver polling        │  │  └ 52주 고저      │
│                            │  └ news_service.py   │
│                            │     └ DART 배당      │
└─────────────────────────────────────────────────┘
         │              │             │
    ┌────▼────┐   ┌─────▼────┐  ┌────▼─────┐
    │   KIS   │   │  Naver   │  │ KRX/DART │
    │ 증권API │   │ Finance  │  │  pykrx   │
    └─────────┘   └──────────┘  └──────────┘
```

---

## 8. 디자인 조건

- 한국 증권 컨벤션: 상승 빨강(#D32F2F), 하락 파랑(#1565C0)
- 숫자 전용 모노스페이스 폰트
- 원핸드 조작: 핵심 정보는 화면 상단 60%에 배치
- Android Jetpack Compose 기준
- 다크/라이트 테마 호환
- `maxLines=1` + `overflow=Ellipsis` 기본
- 빈 상태 처리 필수 (보유 종목 0개, 장 시작 전 데이터 없음 등)

---

## 9. 검증 체크리스트

배포 전 반드시 확인:

- [ ] 서버 DTO와 앱 DTO 필드 1:1 대조
- [ ] Python 3.10 호환 (`from __future__ import annotations`)
- [ ] Naver HTML 파싱: 구조 변경 시 graceful fallback
- [ ] 캐싱 TTL 동작 확인 (장중/장후 분리)
- [ ] 52주 고저 배치 계산 성능 (타임아웃 방지)
- [ ] 빈 상태 UI 처리 (데이터 없을 때 크래시 방지)
- [ ] 상승 빨강/하락 파랑 일관성
- [ ] 모노스페이스 폰트 적용 확인
- [ ] 터치 영역 최소 48dp
- [ ] 서버+APK 동시 배포

---

## 10. 위험 요소 및 대응

| 위험 | 영향 | 대응 |
|---|---|---|
| Naver 업종 시세 HTML 구조 변경 | 히트맵 데이터 깨짐 | graceful fallback + 파싱 실패 시 빈 카드 표시 |
| pykrx 252일 조회 느림 | 52주 고저 API 타임아웃 | 일 1회 배치 계산 + 캐시 |
| DART 배당 데이터 부정확 | 배당 일정 오표시 | pykrx 우선 사용, DART는 보조 |
| 홈 화면 스크롤 과도 | UX 저하 | 접기/펼치기 + 섹션별 collapse 지원 |
| 서버 API 5~6개 동시 호출 | 앱 로딩 느림 | 기존 패턴처럼 `coroutineScope + async` 병렬 호출 |
