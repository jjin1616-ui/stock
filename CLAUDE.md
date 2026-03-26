# stock v1 — AI 작업 지침 (CLAUDE.md)

---

## 0. 참고 문서 (필요 시 읽기)

| 문서 | 내용 |
|------|------|
| `server_rules/RULES.md` | 상세 규칙 아카이브 (396줄) |
| `server_rules/HISTORY_2026-03.md` | 최신 작업 이력 (월별) |
| `backend/rules/MASTER_RULES.md` | 서버 마스터 규칙 |
| `backend/rules/DESIGN_COMPONENT_RULES.md` | 디자인 컴포넌트 규칙 |

> 이 CLAUDE.md에 핵심 규칙이 모두 포함되어 있다. 위 문서는 상세 내용이 필요할 때만 참조.

---

## 1. 절대 규칙

### 배포
- **배포 = 서버 + APK 동시**. 한쪽만 배포하면 안 된다.
- 배포 전 **엔드투엔드 검증 필수**: 서버 API 실제 응답 → 앱 DTO 대조 → 화면 데이터 표시 확인.
- 코드만 보고 "됐다" 판단 금지. 실데이터로 확인 후 배포.
- 서버 먼저 배포 → API 검증 통과 → APK 배포 순서 고정.

### 소통
- **코드 변경/개발 전에 반드시 사용자에게 먼저 보고하고 승인 받은 후 실행한다.** 바로 코드 수정 금지.
- 모든 설명/용어/결과는 **한글**로 작성한다.
- 푸시 알림은 사용자 명시적 오더 없이 절대 전송 금지.
- 회고성 이슈 발생 시 `RULES.md`와 최신 `HISTORY`를 **같은 턴에** 동시 업데이트.
- 작업/배포/검증/규칙 변경 시 **당월 히스토리 파일** (`HISTORY_YYYY-MM.md`)에 타임스탬프/변경내역/검증결과 기록.

### 히스토리 (필수)
- **작업이 완료되면 반드시** 히스토리를 기록한다. 기록 누락은 금지.
- **저장 위치**: `~/AndroidStudioProjects/stock/server_rules/HISTORY_YYYY-MM.md`
  - 예: 2026년 3월 → `server_rules/HISTORY_2026-03.md`
  - 예: 2026년 4월 → `server_rules/HISTORY_2026-04.md`
  - 해당 월 파일이 없으면 새로 생성한다.
- **기록 형식**:
  ```markdown
  ## YYYY-MM-DD HH:MM KST
  ### 작업 제목 (한 줄 요약)
  #### 변경 내역
  - 변경 1
  - 변경 2
  #### 배포
  - APK: V3_NNN 배포 완료 / 미배포
  - 서버: rsync 전송 + restart 완료 / 미배포
  #### 검증
  - API 응답 확인 내역
  #### 회고 (있으면)
  - 실수 내용 → 재발 방지 규칙
  ---
  ```
- 동일 영역 수정 전, 이전 작업 확인이 필요하면 히스토리를 먼저 읽는다.
- 사용자가 과거 작업을 물으면 히스토리를 참조한다.

### 작업 원칙
- 요청이 오면 분석만이 아니라 **실제 작업(수정/배포/검증)** 수행이 기본.
- 실전 손익에 영향 주는 주제는 구현 전 `보수안/균형안/최선안` 3안 제시.
- 새 화면/기능 만들 때 **기존 공용 컴포넌트부터 확인** (커스텀 새로 만들지 않는다).

---

## 2. 프로젝트 구조

### 기술 스택
- **앱**: Android Kotlin + Jetpack Compose, 단일 모듈 `:app`
- **서버**: Python 3.10 FastAPI + SQLite, EC2
- **DI 없음**: ViewModel이 `ServiceLocator`로 Repository 직접 생성
- **ViewModel 생성**: `AppViewModelFactory` 패턴
- **네트워크**: Retrofit + OkHttp (앱), httpx/aiohttp (서버)
- **직렬화**: Kotlin Serialization (앱), Pydantic (서버)
- **인증**: Bearer Token, `/auth/login` (user_code, password)

### 버전
- v3.175, versionCode 스케일: 300xxx
- `publish_apk_ec2.sh`가 `build_counter.txt` 자동 증가
- Build label: `V3_NNN` 형식

### 경로
```
~/AndroidStudioProjects/stock/                    # 프로젝트 루트
├── app/src/main/java/com/example/stock/         # 앱 소스
├── backend/                                      # 서버 소스
│   ├── app/                                      # FastAPI 앱
│   └── engine/                                   # 단타/장타 엔진
├── server_rules/                                 # 규칙 + 히스토리
└── scripts/                                      # 빌드/배포 스크립트
```

---

## 3. 핵심 파일

### 앱 (Android)
| 파일 | 역할 |
|------|------|
| `viewmodel/ViewModels.kt` | 모든 ViewModel (한 파일) |
| `ui/common/ReportComponents.kt` | `CommonReportItemCard` 공용 종목카드 |
| `ui/common/TopBars.kt` | `AppTopBar` 공용 상단바 |
| `navigation/AppNavigation.kt` | `AppTab` enum, 탭 순서 |
| `data/api/ApiModels.kt` | DTO 정의 (`@Serializable`) |
| `data/api/StockApiService.kt` | Retrofit API 인터페이스 |
| `data/repository/StockRepository.kt` | 데이터 계층 |
| `ui/screens/HomeScreen.kt` | 홈 (지수/수급/단타/즐찾) |
| `ui/screens/StockDetailActivity.kt` | 종목 상세 (`.open()`) |

### 서버 (Python)
| 파일 | 역할 |
|------|------|
| `backend/app/main.py` | FastAPI 엔드포인트 전체 |
| `backend/app/schemas.py` | Pydantic 응답 모델 |
| `backend/app/models.py` | SQLAlchemy DB 모델 |
| `backend/app/config.py` | 설정 (`Settings`) |
| `backend/app/autotrade_service.py` | 자동매매 진입/청산 엔진 |
| `backend/app/autotrade_reservation.py` | 예약 매매 |
| `backend/app/kis_broker.py` | 한국투자증권 API 연동 |
| `backend/engine/strategy.py` | 단타/장타 전략 + report 생성 |
| `backend/engine/scoring.py` | 스코어링 + 시장 체제 분류 |
| `backend/engine/gate.py` | Gate 신호 (진입 허용/차단) |
| `backend/engine/simulate.py` | 백테스트 시뮬레이션 |
| `backend/app/news_service.py` | 뉴스 수집/분류 |

---

## 4. 배포

### APK 배포
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd ~/AndroidStudioProjects/stock
bash scripts/publish_apk_ec2.sh
```
- **반드시 debug variant** (release keystore 없음, release 빌드 금지)
- debug.keystore: `~/.android/debug.keystore` (SHA-256 일치 필수, 불일치 시 패키지 충돌)
- EC2 키: `~/AndroidStudioProjects/stock/stock-ec2-key.pem`
- 결과: `http://16.176.148.77/apk/app-latest.apk`

### 서버 배포
```bash
# v1은 GitHub remote 없음 → rsync 직접 전송
rsync -avz \
  --exclude='__pycache__' --exclude='*.pyc' \
  --exclude='*.db' --exclude='*.db-*' \
  --exclude='.venv' --exclude='results/' --exclude='.localdata/' \
  -e "ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem" \
  backend/app/ ubuntu@16.176.148.77:/home/ubuntu/stock/backend/app/
rsync -avz \
  --exclude='__pycache__' --exclude='*.pyc' \
  -e "ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem" \
  backend/engine/ ubuntu@16.176.148.77:/home/ubuntu/stock/backend/engine/
# main.py, run.py, requirements.txt 별도 전송
scp -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem \
  backend/main.py backend/run.py backend/requirements.txt \
  ubuntu@16.176.148.77:/home/ubuntu/stock/backend/

# 재시작
ssh -i ~/AndroidStudioProjects/stock/stock-ec2-key.pem ubuntu@16.176.148.77 \
  'sudo systemctl restart stock-backend.service && sleep 2 && sudo systemctl is-active stock-backend.service'
```
⚠️ rsync 시 **디렉토리 구조 유지** (app/→app/, engine/→engine/ 분리 전송)

### 푸시 알림
```bash
# SSH 접속 후
TOKEN=$(curl -s -X POST http://localhost:8000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"user_code":"...","password":"..."}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

curl -s -X POST http://localhost:8000/admin/push/send \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"...","body":"...","data":{"action":"update","url":"http://16.176.148.77/apk/app-latest.apk"}}'
```
- 인증: 로그인 토큰 (MASTER 권한 필요), `hash = sha256(f"{token}:{pepper}")`
- pepper: `/etc/stock-backend.env`의 `AUTH_PEPPER` (sudo 필요)

---

## 5. 검증 체크리스트

배포 전 **반드시** 순서대로:

1. [ ] 서버 코드 변경 → rsync로 EC2 전송
2. [ ] `systemctl restart` → `is-active` 확인
3. [ ] **서버 API 호출 → 실제 JSON 응답 키/값 확인**
4. [ ] **앱 DTO 필드와 서버 응답 필드 1:1 대조**
5. [ ] 서버 Python 3.10 호환 확인 (`from __future__ import annotations` 필수)
6. [ ] APK 빌드 (`publish_apk_ec2.sh`) → EC2 업로드
7. [ ] **앱에서 해당 화면 열어 실데이터 표시 확인**
8. [ ] 히스토리 기록 (`HISTORY_YYYY-MM.md`)

---

## 6. 서버 참고

### DB
- **운영 DB**: `/var/lib/stock-backend/korea_stock_dash.db` (production)
- `/home/ubuntu/stock/backend/korea_stock_dash.db`는 dev용 — **혼동 금지**

### 환경변수
- `/etc/stock-backend.env` (sudo 필요)
- `AUTH_PEPPER`: 토큰 해시 형식 = `f"{token}:{pepper}"`
- `KIS_TRADING_ENABLED`: 실주문 허용 플래그
- `FIREBASE_ADMIN_JSON`: FCM 키 파일 경로

### 서비스
- `stock-backend.service` (systemd, uvicorn)
- Python 3.10: `float | None`은 `from __future__ import annotations` 있어야 동작

---

## 7. 공통 컴포넌트 규칙

- 종목 카드 → **`CommonReportItemCard`** (커스텀 Row 만들지 않는다)
- 상단바 → **`AppTopBar`**
- 종목 상세 이동 → **`StockDetailActivity.open(context, ticker, name, origin, eventTags)`** (`.start()` 아님)
- 리스트 → **`CommonReportList`** (탭별 개별 구현 금지)
- 하트(관심) → **공통 하트 UI** (탭별 별도 구현 금지)
- Pull-to-Refresh → **`CommonReportList.onRefresh`** (각 화면별 제스처 금지)

---

## 8. 핵심 도메인 규칙

### 자동매매
- 실행 경로는 **백엔드 단일 경로** (앱에 API 키 저장 금지)
- 기본 모드: `demo` (paper는 내부 레거시, 사용자 노출 금지)
- **청산 우선**: 익절/손절 먼저 → 이후 신규 진입
- 일일 손실 한도 초과 시 진입만 차단 (청산은 허용)
- 재진입 정책: `cooldown(30분)` / `day_block` / `manual_block`
- KIS 실주문: `KIS_TRADING_ENABLED=true` + `environment=prod|demo` + `enabled=true` 모두 필요
- `demo/prod`에서 보유포지션은 브로커 잔고(`inquire_balance`) 기준 (로컬 장부 금지)
- `demo/prod`에서 브로커 잔고 실패 시 실행 중단 (`BROKER_BALANCE_UNAVAILABLE`)
- 상시 엔진: 청산 주기(짧게) / 진입 주기(길게) 분리 운영
- 중복 주문 방지: 동일 종목 `BROKER_SUBMITTED` 대기주문 보호구간(300초) 차단
- `BROKER_SUBMITTED` = 접수 상태 (체결 아님), 화면/푸시/용어 통일

### 단타 엔진
- 3-Factor: TA(거래량 가속) + RE(캔들 효율) + RS(상대강도)
- Gate ON/OFF로 진입 허용/조건부 진입 판단
- V1(고정 레벨) / V2(ATR 기반 동적 레벨) 이중 버전

### 장타 엔진
- 60일/120일 수익률 + MA 정렬 + 상대강도 + 최대낙폭 필터

### 뉴스
- 9개 RSS + DART + Naver 수집
- 규칙 기반 분류 (9 이벤트, 14 테마, 4 극성)
- 커뮤니티는 `event_type=community`로 분리 조회 (`q` 강제 금지)

### 알고리즘 버전
- `V1`: 기준 보존 (회귀 검증용, 동작 의미 변경 금지)
- `V2`: 운영 기본 (강화)
- 캐시 키에 `algo_version` 포함 필수

### 데이터 표시
- 데이터 없으면 fallback 메타 반환 (티커/가격 조작 금지)
- UI에 report source 표시 필수 (`LIVE/CACHE/FALLBACK`)
- raw 문자열(`NAVER_RT`, `REMOTE`, `SEED`) 사용자에게 직접 노출 금지 → 정규화
- 테마는 `tags[]` 우선, 없으면 `테마 N` fallback

### UX 원칙
- 수정은 "코드 기준"이 아니라 **"사용자 행동 의도"** 1순위로 설계
- `Scaffold` 사용 시 `innerPadding` 무시/덮어쓰기 금지
- 탭 전환 시 이전 탭 스크롤 위치 초기화 (잔상 금지)
- 텍스트는 `maxLines=1` + `overflow=Ellipsis` 기본
- `load()` 직후 `startPolling()` 쓸 때 첫 호출 지연 (더블콜 금지)

### 장애 대응
- 외부 소스 이슈 시 UI 수정 전에 **소스 가용성 먼저 확인**
- "결과 적다/비었다" → 단계별 카운트(수집→파싱→필터→표시) 확인 후 작업
- 상류 차단 확인 시 배치 수집 + 캐시 우선 전략으로 전환

### 우선순위
- 1) 사용자 영향도 → 2) 운영 리스크 → 3) 해결 속도 → 4) 변경 비용
- 동급이면 "검증 빠르고 롤백 쉬운 것" 우선
- 원인 미확정 상태에서 UI 변경 먼저 하는 것 금지

---

## 9. 인프라

- **AWS EC2**: `ubuntu@16.176.148.77`
- **EC2 키**: `~/AndroidStudioProjects/stock/stock-ec2-key.pem`
- **APK URL**: `http://16.176.148.77/apk/`
- **서버 포트**: 8000 (uvicorn), 80 (nginx)
- **GitHub remote 없음** — rsync 직접 전송

---

## 10. 회고 교훈 (반복 금지)

1. **APK만 배포하고 서버 안 올림** → 항상 서버+APK 동시
2. **서버 스키마에 필드 누락** (regime 없이 앱 DTO만 만듦) → 양쪽 필드 대조 필수
3. **rsync 디렉토리 구조 파괴** (app/+engine/ 한 디렉토리로 섞임) → 분리 전송
4. **DB 경로 혼동** (dev DB vs production DB) → `/var/lib/stock-backend/` 고정
5. **Python 3.10 문법 호환** → `from __future__ import annotations` 확인
6. **코스피 200(KPI200) ≠ 코스피 종합** → v2 기준 확인 후 적용
7. **debug.keystore 불일치** → SHA-256 반드시 확인, 원본 Mac 것 사용
8. **기존 가드 확인 없이 중복 락 추가** → 새 방어 로직 전에 기존 가드(user+env 단위 등) 먼저 확인
9. **기본값 변경으로 기존 호출자 동작 깨짐** → 새 기능은 opt-in(기본 False), 기존 호출자에 영향 금지
10. **서버 필드 의미 미확인 (qty→금액 오표시)** → qty=수량, value=금액 — 앱 단위 변환 전 원본 필드 의미 대조
11. **리스트 추가 후 소비 코드 미확인 (면책 문구 잘림)** → append 후 [:N] 슬라이싱/take() 등 소비 코드 반드시 확인
