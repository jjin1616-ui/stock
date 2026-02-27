# KoreaStockDash Rules (Living Record)

Last updated: 2026-02-27

## Hard Rules
- App must install as a single package only (no multiple app variants on device).
- 자동매매는 허용하되, 실행 환경은 `demo/prod`만 사용한다(`paper` 내부모드 사용자 노출/기본값 금지). KIS 주문은 리스크 가드레일(일손실/주문수/예산) + 명시적 사용자 활성화 조건에서만 실행한다.
- Backend cache-first + queue; never spawn per-request executors.
- 모든 요청은 특별히 "분석만"을 명시하지 않는 한 실제 작업(수정/배포/검증)을 수행한다.
- 사용자 지정 운영 원칙: "항상 작업하고 히스토리에 기록"을 기본값으로 적용한다(분석/브레인스토밍 요청은 예외).
- 작업/배포/검증/규칙 변경이 발생한 턴에는 `HISTORY_2026-02-26.md`에 타임스탬프/변경내역/검증결과를 반드시 기록한다.
- 히스토리 기록 누락이 확인되면 즉시 같은 턴에서 보완 기록 후 다음 작업을 진행한다.
- 실전 손익/주문 정확도에 영향을 주는 주제는 구현 전에 반드시 `보수안/균형안/최선안` 3안과 권장안 1개를 제시한다(장단점/리스크/운영비용 포함).
- 사용자 요구가 실전 리스크를 키우거나 모호할 때는 반박을 생략하지 않는다. 반박 시 반드시 대체안을 함께 제시한다.
- If data is missing, return fallback metadata; do not fabricate tickers/prices.
- UI must show report source (LIVE/CACHE/FALLBACK).
- Quotes may be polled on an interval for real-time UX even when the report payload is CACHE; quote UI must still show quote quality (실시간/지연) and never imply the report itself is LIVE.
- Server data dir: /var/lib/stock-backend (logs must include path/errno on write failures).
- 급등주(무버) 화면은 “전 종목 실시간 스캔”이 아니라, KRX 일간 기준 유동성 상위 후보풀 + 실시간 시세(Naver)로 구성한다. 후보풀 기반임을 UI/문구로 과장하지 않는다.
- 급등2(세션형)는 6개 세션(장전/정규장/스파이크/장마감/시간외종가/시간외단일가)으로 운영하고, 공용 카드/정렬 컴포넌트를 재사용한다. 세션 전용 가격이 없을 때는 반드시 `APPROX` 품질 라벨을 노출한다.
  - 급등2 soft fallback은 `요청 세션 == 현재 활성 세션`일 때만 허용한다(비활성 세션에서 대형주 기본노출 금지).
  - 세션 거래대금/거래량 필터는 `over_value/over_volume`(세션 누적)을 우선 사용한다. `value/volume`(정규장 누적)은 세션값 미수신 시에만 fallback으로 사용한다.
  - Naver `accumulatedTradingValue` 단위 문자열(`백만/억/만`)은 숫자로 정규화해 파싱한다. 단위 미파싱으로 0 처리되는 구현은 금지한다.
- 테마(theme_id)는 상관관계 기반 클러스터 라벨이다(섹터/업종명 아님).
  - UI 표시는 우선순위:
    - `tags[]` (서버 매핑 테이블에서 주입된 업종/테마명 등)
    - 없으면 `테마 N` (theme_id 기반 fallback)
- 리스트/카드 UI는 공통 컴포넌트를 사용한다(단타/급등/장투/논문 등 탭 간 카드 스타일 불일치 금지).
- 공용 종목카드 3번째 줄(보조 라인)은 테마명만 노출한다(`조건부 진입/진입 가능` 등 액션 상태 라벨 노출 금지).
- 공용 종목카드 보조 라인 값은 `extraLines`의 `테마:` 파싱 규칙으로 통일한다(`eventTags` 직접 표시 금지).
- 종목 카드의 관심(즐겨찾기) 동작은 공통 하트 UI로 통일한다(탭별 별도 구현 금지).
- 급등2에서 방향 토글(`↑급등/↓급락`)을 제공할 때, 동일 의미의 `등락↑/등락↓` 정렬칩은 중복 노출하지 않는다.
- `장후` 탭은 `관심` 탭으로 운영하며, 관심 등록 시점의 기준가(`baseline_price`) 대비 성과(%)를 동일 규칙으로 계산/표시한다.
- `수급` 탭은 별도 메뉴로 운영하며, 기본 노출 조건을 `외국인/기관 3일 순매수 + 실시간 유동성(거래대금)` 동시 충족으로 제한한다.
  - 수급 유형 라벨은 `외국인 주도 / 기관 주도 / 동반 매수 / 개인 역추세`로 고정한다.
  - 수급 리스트/카드는 공용 컴포넌트(`CommonReportList`, `CommonReportItemUi`)를 재사용하고, 정렬 카드에 `수급점수` 필터를 기본 제공한다.
  - 수급 실시간 계산 결과가 일시적으로 `items=0`이면 최근 정상 스냅샷(last-good)을 `source=CACHE`로 대체 표시해 빈 화면을 방지한다(기본 최대 보관 15분).
- `미장` 탭은 SEC EDGAR Form 4 원문 기준으로만 구성한다:
  - 1차 포함: CEO/CFO + Non-derivative + Transaction Code=P
  - 1차 결과가 부족하면 2차 보강: 내부자(`OFFICER`/`DIRECTOR`/`TEN_PCT_OWNER`/`OTHER`)의 Non-derivative Code=P를 추가 표기한다(역할 라벨 필수).
  - 거래구분 필터는 `전체(P/A/M/F)` 및 `P`, `A`, `M`, `F` 단일 선택을 지원한다.
  - 미장 카드에는 거래구분(`transaction_code`)과 A/D 방향(`acquired_disposed_code`)을 함께 표기한다.
  - 교차 캐시 fallback(다른 파라미터 결과 재사용)은 `전체(ALL)`에서만 허용한다. 단일 코드 필터(`P/A/M/F`)에는 적용하지 않는다.
  - 제외: 파생/보상취득/기타 코드(P 외)
  - 기본 10거래일, 부족 시 20거래일 확장
  - SEC 상류 차단(429/자동화 제한) 시 "조건 부족"으로 숨기지 말고 차단 사유를 `shortage_reason`에 명시한다.
  - 미장 캐시는 메모리 단독이 아니라 DB 스냅샷까지 함께 유지한다(서버 재시작 후 빈화면 방지).
  - 미장 수집은 배치 prewarm을 기본 활성화하고, 앱 요청은 캐시 우선으로 처리한다(요청 시 장시간 스캔 최소화).
  - daily-index 후보 수집은 과도한 균등 샘플링으로 head filing을 누락시키지 않도록, 최신 head 구간을 반드시 포함해 추출한다.
  - 후보 병합은 `native(daily-index/atom)` 우선 보존 후 보조 소스(github enrich)를 추가하는 방식으로 유지한다(라운드로빈 혼합으로 primary 후보를 밀어내면 안 됨).
  - 과거 기준일 비교/재현이 필요할 때는 `base_date`를 고정해 동일 조건으로 재산출한다(오늘 기준 결과와 혼용 금지).
  - 결과 카드는 `신호강도(signal_grade)`와 `근거(signal_reason)`를 함께 제공한다(단순 나열 금지).
  - 기본 조회 윈도우는 `10/20/30 거래일`을 사용한다(기존 7/14 고정 금지).
  - SEC 변동(429) 대응을 위해 미장 수집 요청은 지터 포함 백오프 재시도를 적용한다.
  - 서버 안정성을 위해 미장 기본 후보 수는 안전값(기본 120)을 사용하고, 상향은 OOM 모니터링 후 단계적으로 적용한다.
- 리포트 캐시는 `(date, type, cache_key)` 단위로 저장한다(서버 worker가 기존 row의 `cache_key`를 덮어써서 다른 설정/limit 캐시를 파괴하면 안됨).
- `force=true`(재생성) 경로에서도 `tags[]` 주입은 동일하게 적용되어야 한다(새로고침 시 테마/업종명이 갑자기 사라지면 UX 깨짐).
- 급등주(무버) 의미를 지키기 위해 `mode=chg`에서는 최소 등락률 임계치(`MOVERS_MIN_CHG_PCT`, 기본 3.0%)를 적용한다.
- 가설 검증/백테스트/유니버스 로딩은 가능하면 KRX Data API(`KRX_API_KEY`)를 우선 사용한다(FinanceDataReader/pykrx 스크래핑은 실패할 수 있음).

## Current Operational Constraints
- KRX API access requires explicit approval per endpoint.
- If an endpoint is 승인대기, expect 401 or empty data.
- KOSPI only until KOSDAQ/KONEX are approved.

## Build/Versioning
- Build label auto-increments as V3_XX per build (APP_BUILD_LABEL).
- Single-app build only; no multiple flavors installed on device.

## Notes
- Store additional rules here as they are decided.

## UX/Implementation Rules (Added 2026-02-09)
- Saving any setting must immediately reflect in UI lists without app restart.
- If daytrade gate is OFF, keep top candidates as `조건부 진입(분할/소액)` and label lower ranks as `후순위 관망` (all-items `관찰(진입금지)` 노출 금지).
- Common components (header, list card, bottom bar) must update all tabs consistently.
- Problems/IDE errors must be validated by actual build; do not trust stale IDE caches alone.
- Auth flow:
  - First install requires PIN (01050051452).
  - After first success, biometric-only if available; failure exits app.
- When a reference UI is provided (e.g., Toss), match layout/behavior closely; avoid ad-hoc deviations.
- 보유 화면의 `현재가` 토글과 `펼치기/접기`는 같은 헤더 라인에 배치한다(보조 위치/하단 숨김 금지).
- 보유 리스트는 `펼치기/접기` 클릭 시 실제 표시 아이템 수가 즉시 변해야 하며, 변경 여부를 화면 기준으로 검증한다.
- 보유 화면의 기본 필터는 자동매매 설정 환경(`paper/demo/prod`)과 동기화한다. 사용자가 수동으로 필터를 변경하면 그 상태를 유지한다.
- 보유 화면에서 `모의` 필터는 `demo` 실계좌 연동(`BROKER_LIVE`) 보유만 노출한다(`paper` 장부/추정치 합산 금지).
- 보유 화면 거래 이력 필터에서도 `모의=demo`만 허용한다(`paper` 체결은 별도 레거시로 분리하고 모의 이력에 섞지 않는다).
- 체결 이력에 있는 종목이 보유 목록에서 누락되면 1순위로 필터/소스 분기 회귀를 점검한다(거래 이력/보유 목록 기준 불일치 금지).
- 자동매매 화면이 `테스트(모의투자)`일 때 계좌 현황/보유 수량 기준은 `demo` 실계좌 연동 스냅샷만 사용한다(`paper` 장부/추정치 병합 금지).
- 상세 종목 화면의 `모의구매` 요청 모드는 `paper`가 아니라 `demo`를 사용한다(내부 장부 체결로 우회 금지).
- 서버는 구버전 호환 요청으로 `manual-buy/manual-sell`에 `mode=paper`가 들어와도 `demo`로 강제 전환한다(내부모의 체결 재발 방지).
- Settings UI policy:
  - Use the same card/list visual language as report screens.
  - Prefer chip-style quick choices for presets/counts; keep direct input as fallback.
  - Avoid verbose helper paragraphs or scattered help icons; keep guidance concise in subtitles.
  - 자동매매 `실행환경/소스 선택` 칩은 `weight`로 풀폭 확장하지 말고 compact + wrap 배치를 사용한다(과도한 배경 폭/높이 금지).
  - 버튼/칩 라벨은 기본 1줄(`maxLines=1`)을 유지하고, 좁은 폭에서는 줄바꿈 대신 축약/ellipsis를 우선한다.
- Refresh handling policy:
  - Refresh feedback must be centralized in the shared list component, not duplicated per screen.
  - Screen-specific refresh counts are forbidden; use the common count logic based on visible items.
  - Pull-to-Refresh(아래로 당겨 새로고침)는 `CommonReportList`의 `onRefresh`로만 구현한다(각 화면별 제스처 구현 금지).
  - 1일(D1) 차트는 기준선(전일 종가)이 바닥에 붙지 않도록 기준선 중심 스케일링을 적용한다(직관성 우선).
  - `load()` 직후 `startPolling()`를 함께 쓰는 화면은 폴링 루프의 첫 네트워크 호출을 지연시켜 초기 중복 요청(더블 콜)을 금지한다.
- Skills policy:
  - Always default to using `modern-python` and the OpenAI curated skills when a task matches.
  - If skill installation is required, use `skill-installer` with SSL_CERT_FILE to avoid cert failures.

## Session Notes (2026-02-09, End-of-Day)
- Bottom bar redesigned to be compact, evenly spaced, with circular icon background and no scroll/chevrons.
- App icon updated to clean finance style (navy background + simple line chart mark).
- Initial auth screen added; placeholder removed; user must input PIN manually.
- Auth versioning added so previously installed devices are forced through PIN once after changes.
- Daytrade screen falls back to watch list when gate OFF (label as 관찰).

## Retrospective Rules (Added 2026-02-10)
- When user says "알아서", treat it as choice #2 by default.
- Never pre-truncate report items before passing to the common list component; allow the shared list to own pagination/load-more.
- Refresh count/snackbar must reflect server-received counts, not display counts.
- If settings affect data size (e.g., display count), the server must receive those values and cache keys must include them.
- If a setting change doesn’t reflect in UI, verify the full pipeline: app setting → API params → server generation → cache key → response size.
- Always validate that all tabs use the same shared header/footer components; any change must be applied across tabs unless explicitly exempted.
- For performance complaints, identify actual bottlenecks (network, server generation, client rendering) before adding caching workarounds.
- For list count mismatches, confirm server payload size directly (log/API check) before UI changes.
- "롤백 완료" 선언 전에는 반드시 `의도 잠금(Intent Lock)` 3단계 검증을 수행한다:
  - 1) 금지 구조가 코드에 남아있지 않은지 검색 확인(예: `TradeActionBar`).
  - 2) 의도 구조가 존재하는지 검색 확인(예: `모의구매/실전구매` 클릭 → 팝업).
  - 3) `installDebug` 후 앱 재시작까지 수행한 실제 실행 상태를 기준으로 완료 판정.
- 동일 파일(특히 상세카드처럼 변경 빈도가 높은 파일)에서 후속 작업을 할 때, 직전 사용자 확정 UX를 깨는 diff가 있으면 기능 추가보다 먼저 회귀 차단을 우선한다.
- UI 구조 변경 작업은 "어디에 렌더되는가"를 `Scaffold`/`bottomBar`/`dialog` 단위로 명시적으로 점검한다(증상만 보고 부분 수정 금지).
- 사용자가 명시한 화면 의도(예: "하단 고정 금지, 클릭 시 노출")는 다음 작업에서 기본 불변조건으로 취급하고, 예외 변경은 사용자 재승인 없이는 적용하지 않는다.
- Compose UI에서 `NoSuchMethodError`가 발생하면 기능 결함이 아니라 **compile/runtime dependency skew**를 1순위로 의심하고, API 변경보다 의존성 정렬을 먼저 수행한다.
- Compose API(`FlowRow`, `Lazy*`, Material3 신규 API 등) 추가/변경 후에는 릴리즈 전에 아래 2개를 모두 확인한다:
  - `./gradlew :app:dependencyInsight --configuration debugCompileClasspath --dependency androidx.compose.foundation:foundation-layout --no-daemon`
  - `./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency androidx.compose.foundation:foundation-layout --no-daemon`
  - compile/runtime 버전 불일치가 있으면 배포 금지.
- Compose BOM 사용 프로젝트에서 특정 Compose artifact를 명시 추가할 때는 BOM/직접 버전이 충돌하지 않도록 목표 버전으로 강제 정렬하고, 정렬 근거를 히스토리에 기록한다.
- 크래시 이슈 해결 완료 판정은 `설정->자동매매`, `autotrade route`, `holdings route` 진입 로그켓에서 `FATAL EXCEPTION` 부재까지 확인해야 한다.

## Access Control Rules (Added 2026-02-10)
- 모든 API는 인증 토큰이 없으면 접근 불가(health/auth 제외).
- 인증 토큰은 `access + refresh` 이중 토큰 구조를 사용하고, `TOKEN_EXPIRED` 시 클라이언트가 `/auth/refresh`로 자동 갱신할 수 있어야 한다.
- refresh 토큰은 1회 회전(rotate) 원칙을 적용한다(동일 refresh 재사용 시 `TOKEN_REVOKED` 반환).
- 앱은 전역 401 감지 시 화면별 개별 처리 대신 공통 인증 복구 경로를 사용한다(자동 refresh 1회 → 실패 시 토큰 정리 + 로그인 전환).
- `TOKEN_INVALID/TOKEN_REVOKED/TOKEN_EXPIRED` 사유코드는 사용자 안내 문구와 1:1 매핑해 모호한 `UNAUTHORIZED` 단일 노출을 금지한다.
- force_password_change=true 상태에서는 리포트/추천/차트 등 메인 API 접근을 403으로 차단한다.
- 임시 비밀번호는 1회 노출 원칙이며 서버에는 해시만 저장한다.
- `AUTO` 임시 비밀번호는 모바일 입력 편의 기준의 숫자 PIN(기본 6자리)으로 발급한다.
- 비밀번호 변경/리셋/차단 시 모든 세션 즉시 무효화.
- 생체 인증은 “서버 인증 대체”가 아니라 “로컬 토큰 해제” 용도로만 사용한다.
- 초대/리셋/권한 변경 등 관리자 액션은 반드시 감사로그에 남긴다.
- 단말 키스토어/암호화 저장소 불일치(예: `EncryptedSharedPreferences` MAC 검증 실패) 발생 시 앱은 크래시하지 않고 보안 저장소를 초기화해 복구해야 한다.

## Push Delivery Rules (Added 2026-02-26)
- 운영 수동 푸시 발송 전, MASTER는 반드시 `/admin/push/status`에서 `push_ready`, `all_token_count`, `active_7d_token_count`를 먼저 확인한다.
- `target_count > 0`이어도 `token_count == 0`이면 발송이 아니라 토큰 등록 이슈로 분류하고, 앱 토큰 경로(`FcmInitializer`/`onNewToken`/`/device/register`)를 먼저 복구한다.
- 앱 배포물에서 푸시 기능을 요구할 때는 `app/google-services.json` 포함 여부와 `BuildConfig.FCM_ENABLED=true`를 릴리즈 체크리스트에 포함한다.
- 운영 서버 Firebase Admin 키 파일(`FIREBASE_ADMIN_JSON`)은 **서비스 실행 계정이 읽을 수 있는 권한**이어야 한다(권장: `root:ubuntu`, `640`). 권한 오류 상태로 배포하면 `push_ready=false`가 되고 모든 발송이 `FIREBASE_NOT_READY`로 실패한다.
- APK 빌드/배포는 `scripts/preflight_fcm_config.sh`를 선행 실행해 `app/google-services.json` 존재/형식/패키지명(`com.example.stock`)을 검증해야 한다. 실패 시 배포를 중단한다.
  - 배포 경로(`scripts/publish_apk_ec2.sh`)는 모든 VARIANT에서 차단한다.
  - Gradle 기본 게이트는 release 산출(`assembleRelease/bundleRelease/exportRelease`)에서 차단한다(IDE debug 개발 흐름은 예외).
- `ALLOW_FCM_DISABLED=true` 예외 플래그는 의도적 무푸시 빌드에서만 허용하며, 사용 시 사유를 반드시 `HISTORY_2026-02-26.md`에 기록한다.
- 푸시 payload의 `route`는 클라이언트에서 `type` 기반 fallback보다 우선 적용한다(운영 공지의 딥링크 오동작 방지).
- 푸시 복구 완료 판정은 `status(push_ready=true)`만으로 끝내지 말고, 같은 턴에 `POST /admin/push/send (target=test, dry_run=false)`를 실행해 `SEND_OK`를 확인한다.

## Auto Trading Rules (Added 2026-02-16)
- 자동매매 후보 소스는 `단타(daytrade)` + `급등(movers2)` + `논문(var7/base)` + `장투(longterm)` + `관심(favorites)` 5축을 통합한다.
- 자동매매 실행 경로는 **반드시 백엔드 단일 경로**로 제한한다(앱에 API 키/시크릿 저장 금지).
- KIS 실주문은 `KIS_TRADING_ENABLED=true` + 사용자 설정 `environment=prod|demo` + `enabled=true`를 모두 만족할 때만 허용한다.
- 기본 실행 모드는 `demo`이며, `paper`는 내부 레거시 디버깅 용도로만 유지한다(사용자 기본값 금지).
- 자동매매 실행 UI는 `모의 점검(주문 없음)`과 실주문 실행을 명확히 구분해야 하며, `DRY_RUN` 결과는 실제 주문 미제출 경고를 반드시 노출한다.
- 장시간 외 실행 요청은 즉시 실패시키지 말고 `예약 가능 안내 -> 사용자 확인 -> 예약 등록` 흐름을 기본 제공한다(설정에서 예약 비활성 시에만 차단).
- 자동매매 주문 실행 원칙은 `주문 가능 시간대 = 즉시 주문`, `주문 불가 시간대 = 예약`으로 고정한다(정규장 단일 하드코딩으로 전체 판단 금지).
- 예약 주문은 실행 트리거 시점에 주문 가능 여부를 재검증해야 하며, 여전히 주문 불가면 즉시 취소하고 원인코드/근거값/조치문구를 함께 기록한다.
- 장상태 판정은 브로커 주문 가능 신호(원문 코드/메시지)를 우선 사용하고, 로컬 시간 판정은 fallback 용도로만 사용한다.
- 자동매매/보유/상세의 사용자 노출 문구에서 `브로커` 용어는 사용하지 않고 `증권사`로 통일한다(상태 라벨/오류문구/용어집 포함).
- 자동매매 히스토리는 `run_id` 단위로 저장하고, 주문 상태(`PAPER_FILLED/BROKER_SUBMITTED/BROKER_REJECTED/SKIPPED`)를 숨기지 않는다.
- 수익 지표는 최소 `orders_total`, `filled_total`, `buy_amount_krw`, `eval_amount_krw`, `unrealized_pnl_krw`, `roi_pct`, `win_rate`, `mdd_pct`를 일단위로 제공한다.
- 자동매매 초기 진입은 `/autotrade/bootstrap` 단일 API를 우선 사용하고, 무거운 섹션(후보군/성과)은 지연 로딩으로 분리한다(초기 화면 전체 블로킹 금지).
- `/autotrade/candidates`는 `profile=initial` 경량 조회 + 단기 캐시(TTL) 우선으로 처리하고, 화면은 요약/상세 갱신을 분리한다.
- 일 손실 한도(`max_daily_loss_pct`) 초과 시 자동 주문을 즉시 중지하고 `DAILY_LOSS_LIMIT_REACHED` 상태를 반환한다.
- UI는 공용 컴포넌트(`AppTopBar`, `CommonReportList`)를 사용해 자동매매 화면을 구성한다.
- 자동매매 후보/보유 종목 목록은 텍스트 줄 나열이 아니라 공용 종목 카드(`CommonReportItemCard`)로 통일하고, 화면에서 임의 개수 제한(pre-truncate)을 두지 않는다(요약 영역 제외).
- 자동매매 화면은 계좌 스냅샷 실패/지연 시에도 빈 화면이 되면 안 된다. 주문 히스토리 fallback은 `BROKER_SUBMITTED`를 포함해 보유/접수 상태를 표시한다.
- 자동매매 화면은 실행환경(`paper/demo/prod`)과 최근 주문 환경 신호(예: 모의투자 주문 문구)가 불일치할 때 상단 경고를 노출해 사용자 혼선을 방지한다.
- 자동매매 설정은 최소 `seed_krw(총시드)`, `take_profit_pct(익절)`, `stop_loss_pct(손절)`를 제공해야 한다.
- 자동매매 실행은 **청산 우선** 원칙을 따른다: 익절/손절 청산을 먼저 시도하고, 이후 신규 진입을 평가한다.
- 일 손실 한도 도달 시에도 신규 진입만 차단하며, 손절/익절 청산은 허용한다(리스크 축소 우선).
- 신규 진입은 현재 노출금액이 `seed_krw`를 초과하지 않도록 제한한다.
- 자동매매 신규 매수의 요청가격은 `current_price` 우선, 없으면 `signal_price`를 사용한다. 수량은 `floor(order_budget_krw / 요청가격)`로 계산한다.
- `allow_market_order=false`에서는 브로커에 지정가 요청을 보낸다(주문 요청가 기준). `BROKER_SUBMITTED`는 접수 상태이며 최종 체결가는 별도 체결 동기화 전까지 확정값이 아니다.
- 종목별 수동 제어에서 `enabled=false` 규칙은 해당 종목 신규 매수를 차단한다(`ENTRY_BLOCKED_MANUAL`). 사용자가 즉시 매수 시도 취소/재개할 수 있어야 한다.
- `demo/prod` 실행 시 보유포지션/노출 계산은 로컬 장부가 아니라 브로커 잔고(`inquire_balance`) 기준으로 판단한다(환경 불일치 로컬 포지션으로 강제 매도/시드초과 판정 금지).
- `demo/prod`에서 브로커 잔고 조회에 실패하면 실행을 중단하고 `BROKER_BALANCE_UNAVAILABLE`을 반환한다(불확실 상태에서 로컬 추정치로 주문 진행 금지).
- `demo/prod`에서 `KIS_TRADING_ENABLED=false`인 경우 체결을 생성하지 않고 `SKIPPED(KIS_TRADING_DISABLED)`로 처리한다(`PAPER_FILLED` 가짜 체결 생성 금지).
- 주문 이력 응답(`AutoTradeOrderItem`)에는 `environment`를 포함해 화면 필터가 모의/실전을 명시적으로 구분할 수 있어야 한다.
- 주문/스킵 원인 응답은 표준 구조(`결론 한 줄`, `reason_code`, `근거값(evidence)`, `바로 조치(action)`)를 기본으로 제공한다(모호한 `증권사 거부` 단독 문구 금지).
- 자동매매는 수동 버튼 실행만이 아니라 서버 상시 엔진(scheduler)으로도 장중에 주기 실행되어야 하며, 익절/손절 평가는 실행 버튼과 무관하게 계속 동작해야 한다.
- 상시 엔진은 `청산(익절/손절)`과 `신규 진입` 주기를 분리해 운영한다(청산 주기 더 짧게, 진입 주기 더 길게). 청산 지연으로 인한 손절 미스가 발생하지 않도록 청산 사이클을 우선 보장한다.
- 상시 엔진은 `SKIPPED` 주문을 무분별하게 적재하지 않는다(로그/DB 폭증 방지). 엔진 경량 후보 프로필(`initial`)을 사용해 주기 실행 지연을 줄인다.
- 재진입 정책은 `손절 후`와 `익절 후`를 분리해 사용자 설정으로 제어한다: `즉시 허용(immediate)` / `재진입 대기시간(cooldown)` / `당일 차단(day_block)` / `수동 해제 전 차단(manual_block)`. 기본값은 `재진입 대기시간 30분`으로 유지한다.
- 상시 엔진은 주문 이벤트를 푸시(`TRIGGER`)로도 전달해야 한다.
  - 익절/손절 트리거 발생 시: `익절/손절 실행` 푸시
  - 자동 진입/청산 주문 접수 시: `주문 접수` 푸시
  - 반복 실패 시: 같은 사유코드 기준 쿨다운을 적용해 푸시 폭주를 방지한다.
- 자동매매는 동일 사용자/동일 환경/동일 종목의 `BROKER_SUBMITTED` 대기주문이 최근 보호구간(`AUTOTRADE_PENDING_ORDER_GUARD_SEC`, 기본 300초) 내 존재하면 중복 주문을 차단해야 한다(매수/매도 모두 적용).
- 자동매매 성공 알림(`AUTOTRADE_ORDER_SUBMITTED`, `AUTOTRADE_EXIT_EXECUTED`)도 시그니처 기반 쿨다운(`AUTOTRADE_PUSH_SUCCESS_COOLDOWN_SEC`, 기본 120초)을 적용해 동일 내용 연속 푸시를 차단한다.
- `BROKER_SUBMITTED` 상태는 체결이 아니라 접수 상태로 표기한다(푸시/화면/용어집 공통).
- `BROKER_SUBMITTED` 접수대기 주문은 자동매매 화면 `진행중/미체결` 섹션에서 개별 취소/전체 취소를 제공해야 하며, 성공 시 상태를 `BROKER_CANCELED`로 명시한다.
- 자동매매 화면은 `진행중/미체결`, `체결`, `실패/스킵`을 분리 노출한다. 체결 섹션에는 `PAPER_FILLED/BROKER_FILLED`만 표시하고 `BROKER_SUBMITTED`는 진행중 섹션으로 고정한다.
- 예약 주문 카드는 예약 시점 대상 종목(`preview_count`, `preview_items`)과 취소/실행 액션을 함께 제공해야 한다(예약 대상 0건 오인 방지).
- 자동매매 주문 취소 실패(예: 장종료/장전/환경불일치)는 HTTP 400 숫자 오류만 노출하지 않는다. API는 `ok=false + message`를 우선 반환하고, 앱은 해당 `message`를 사용자 문장으로 그대로 안내한다.
- 배포 스크립트는 `backend/scripts/preflight_autotrade_contract.py`를 필수 게이트로 실행한다. 미통과 시 배포를 중단한다(취소 API 계약 회귀 차단).

## Incident Prevention Rules (Added 2026-02-11)
- 상류 데이터 소스(SEC/KRX/Naver 등) 이슈가 의심되면, UI 수정/튜닝보다 먼저 소스 가용성(HTTP 상태/응답시간/차단 여부)을 확정한다.
- 외부 소스 장애형 이슈는 반드시 아래 순서로 분석한다:
  - 1) 소스 접근 성공률
  - 2) 수집 후보 수
  - 3) 파싱 성공 수
  - 4) 필터 통과 수
  - 5) 앱 렌더링/표시
- "결과가 적다/비었다" 이슈에서 단계별 카운트(checked/parsed/filtered/returned) 없이 UI부터 변경하는 작업을 금지한다.
- 상류 차단/지연이 확인되면, 즉시 배치 수집 + 캐시 스냅샷 우선 전략으로 전환하고, 앱은 캐시 조회를 우선한다(요청 시 실시간 파싱 금지).
- 회고가 발생한 동일 유형 이슈는 규칙(`RULES.md`)과 히스토리(`HISTORY_2026-02-26.md`)를 같은 턴에 함께 업데이트한다.
- systemd `EnvironmentFile`에는 공백/괄호 포함 값을 반드시 따옴표로 감싼다. 파싱 실패 시 일부 환경변수가 누락되어 인증/복호화 오진을 유발할 수 있다.
- 서버 런타임 진단 시 로컬 셸 기본 env로 판정하지 말고, 반드시 실행 중 프로세스(`/proc/<pid>/environ`) 기준으로 확인한다.
- 브로커 토큰 발급 제한(EGW00133) 재현 시: 디스크 캐시 토큰을 우선 사용하고, 연속 실패는 지수 지연 재시도로 완화한다. 연동 실패 시 앱은 `추정치 표시`가 아니라 `미연동/점검 필요` 상태를 노출하고 보유 목록은 숨긴다.
- KIS 잔고 조회(`inquire-balance`)는 `CTX_AREA_FK100/NK100` 연속조회를 끝까지 수행한다(페이지 1개만 조회해 20건 상한으로 보유를 누락시키는 구현 금지).
- KIS 잔고 요약(`output2`)과 포지션 합계(`output1`)가 어긋나면 차이 로그를 남기고, 화면 노출 값은 단일 계산 기준(포지션 합계 기반)으로 정합성을 유지한다.
- KIS 토큰 만료시각(`access_token_token_expired`)은 KST 절대시각으로 해석해 UTC 기준으로 저장/비교한다(서버 로컬 타임존 비교 금지).
- KIS 주문/잔고 응답에서 `기간 만료된 token` 계열 메시지 또는 토큰 만료 코드가 감지되면, 토큰 캐시(메모리/디스크)를 즉시 무효화하고 1회 재발급 재시도한다.
- FastAPI 경로 설계 시 정적 경로와 동적 경로 충돌을 금지한다. `/{id}` 계열보다 고정 세그먼트(예: `/pending-cancel`)를 우선 배치하고, 예약어(`pending`, `latest`, `summary`)가 동적 파라미터로 해석되지 않도록 명시 경로를 분리한다.

## Detail Card News Regression Rules (Added 2026-02-23)
- 상세카드 뉴스 작업의 1순위는 "의도한 데이터가 충분히 보이는가"이며, UI 미세조정보다 데이터 경로/필터 축 검증을 먼저 수행한다.
- `커뮤니티` 데이터 축(`ticker + event_type=community`)과 텍스트 검색 축(`q`)은 기본적으로 분리한다.
  - `커뮤니티` 기본 조회에서 `q`를 강제하면 안 된다.
  - 텍스트 검색이 필요하면 별도 "검색 모드"로만 사용한다.
- `event_type=community` 조회는 MISS(0건)만 보지 말고 저건수(기본 20건 미만)도 품질 경보로 간주한다.
  - 저건수면 백엔드 보강(backfill) 재시도 + 쿨다운 로그를 반드시 남긴다.
- "링크 동작"은 URL 문자열 검토로 끝내지 말고 실제 클릭 동작 기준으로 검증한다.
  - 종목코드 정규화는 전 화면에서 동일 규칙(`digits -> 6자리`)을 적용한다.
  - 링크 함수가 여러 파일에 중복되어 있으면 동일 패치를 모두 반영했는지 체크리스트로 확인한다.
  - Android `ACTION_VIEW` 외부 브라우저 실행은 `resolveActivity()` 선검사에 의존하지 말고 `startActivity()` + `ActivityNotFoundException` fallback으로 처리한다(패키지 가시성 이슈로 false negative 방지).
- 문장 포맷 규칙(마침표 뒤 줄바꿈)은 뉴스/커뮤니티 모두 동일 함수/동일 정규식으로 유지한다.
  - 공백 없는 문장 경계 케이스까지 포함해 검증한다.
- 상세카드 `종목투자자` 탭의 일별 수급은 투자자별 원천(`개인/외국인/기관계/사모/기타법인`)을 분리해 제공하고, 응답에 `source(LIVE/CACHE/FALLBACK)`를 포함한다.
- `종목투자자` 수급의 primary(pykrx) 조회가 공백/실패일 때는 네이버 `trend/all` 요약(개인/외국인/기관)으로 즉시 fallback해 "빈 화면"을 금지한다.
- `종목투자자` pykrx primary가 예외 없이 `empty DataFrame`을 반환해도 실패로 간주하고, 30분 cooldown을 걸어 반복 재시도/로그 폭주를 막는다.
- 상세카드 `종목거래동향` 탭에서 시간별 `순매수`는 추정치일 수 있으므로 라벨/메시지에 `추정`을 명시한다(실측 데이터로 오인되게 표기 금지).
- 상세카드 뉴스 관련 변경을 배포할 때 아래 검증을 릴리즈 게이트로 강제한다:
  - 1) API 검증: `/api/news/articles?event_type=community&ticker=...` 결과 건수 확인
  - 2) 앱 검증: 동일 종목 상세카드 `커뮤니티` 노출 건수 확인
  - 3) 링크 검증: `네이버 가격보기` 버튼 클릭 시 브라우저 이동 확인
  - 4) 본문 검증: 커뮤니티 본문에서 문장부호 뒤 줄바꿈 확인
  - 5) 회고 기록: RULES/HISTORY 동시 업데이트 확인
- 배포 자동화 규칙:
  - `backend/scripts/deploy_ec2.sh`는 시작 단계에서 `backend/scripts/preflight_detail_card_news.py`를 실행해야 하며, 실패 시 배포를 중단한다.
  - preflight는 최소 아래 항목을 정적 검증해야 한다:
    - `RULES.md` 회귀 섹션 존재
    - `HISTORY_2026-02-10.md` 심층 회고/체크리스트 존재(과거 회고 원본)
    - 커뮤니티 조회 축 분리(`event_type=community`에서 `q` 강제 금지)
    - 네이버 링크 6자리 종목코드 정규화
    - 커뮤니티 저건수 backfill 보강 로직 존재
  - PR 생성 시 `.github/pull_request_template.md`의 `Detail Card News Checklist`를 필수로 사용한다.

## Priority & Efficiency Rules (Added 2026-02-11)
- 우선순위가 헷갈리면 구현 전에 반드시 `우선순위/효율성 분석`을 먼저 수행한다.
- 분석은 아래 순서로 고정한다:
  - 1) 사용자 영향도(장애/데이터정합성/핵심 UX)
  - 2) 운영 리스크(외부 의존 차단/재현성/확장성)
  - 3) 해결 속도(가장 빨리 검증 가능한 경로)
  - 4) 변경 비용(코드 영향 범위/롤백 난이도)
- 동급 후보가 여러 개면 “검증이 빠르고, 롤백이 쉬운 변경”을 우선 선택한다.
- 원인 미확정 상태에서 UI/디자인/부가 기능 변경을 먼저 수행하는 것을 금지한다.
- 각 작업 시작 전 1줄로 `선택한 우선순위와 이유`를 명시하고 진행한다.

## Device Insets & QA Rules (Added 2026-02-13)
- 하단 탭/고정 CTA/바텀시트가 있는 모든 화면은 `WindowInsets.safeDrawing` + `navigationBarsPadding()` 기준으로 구현한다(기기 기본 내비게이션 UI와 겹침 금지).
- `Scaffold` 사용 시 `innerPadding`을 무시하거나 덮어쓰는 구현을 금지한다. 본문 스크롤 영역 하단 여백은 시스템 내비게이션 바 높이를 반드시 포함한다.
- 릴리즈 전 UI 검증 매트릭스를 고정한다:
  - 1) Pixel(제스처 내비)
  - 2) Galaxy/OneUI(3버튼 내비)
  - 3) 글자 크기 확대(접근성) 상태
- 탭 라벨/정렬칩/상단 액션 텍스트는 `maxLines=1` + `overflow=Ellipsis`를 기본으로 하며, 작은 폭에서 잘림/겹침이 있으면 약칭 라벨을 우선 적용한다.
- 회고성 UI 이슈가 발생하면 같은 턴에 `RULES.md`와 `HISTORY_2026-02-26.md`를 동시에 업데이트한다(원인/재발방지/검증기준 포함).

## Execution Autonomy Rules (Added 2026-02-16)
- 사용자가 "풀권한/알아서"를 명시한 세션에서는 배포/검증/에뮬 조작을 중간 승인 질문 없이 연속 실행한다.
- 동일 유형 작업(예: ADB 조작, EC2 배포, 외부 헬스체크)은 승인된 명령 프리픽스를 재사용해 반복 질문을 금지한다.
- 샌드박스 정책상 새 에스컬레이션이 불가피한 경우에도, 요청을 최소 횟수로 묶어 1회로 처리한다(작업 단위 분할 승인 금지).

## Deploy Domain Safety Rules (Added 2026-02-19)
- `backend/scripts/deploy_ec2.sh`의 도메인 자동 탐색은 외부 공급자 도메인(예: `openapi.koreainvestment.com`)을 후보에서 제외한다.
- `DOMAIN`을 명시하지 않은 배포는 IP 기반 서비스 검증(`127.0.0.1:8000/health`, `127.0.0.1/health`)을 우선 통과 기준으로 삼는다.
- HTTPS(certbot) 실패가 발생해도 백엔드/Nginx 헬스가 정상이면 배포는 완료로 기록하고, DNS/방화벽 이슈를 별도 운영 항목으로 분리한다.

## Detail Card & Distribution Rules (Added 2026-02-20)
- 설정 화면에는 항상 재설치 진입 링크(`/apk/install`)와 직접 다운로드 링크(`/apk/download`)를 함께 제공한다.
- 업데이트 필요 판단은 `build_label`(예: `V3_372` → `V372`) 기준의 최신 빌드 순서를 우선 사용한다(코드/이름 불일치 방어).
- 업데이트 팝업 표기는 `현재 버전: Vxxx`, `최신 버전: Vyyy` 형식으로 단순 표기한다(버전명/코드 중복 노출 금지).
- 업데이트 다운로드는 항상 최신 버전을 직접 가리키는 URL(`apk_versioned_url` 우선, 없으면 `apk_url`)을 사용한다.
- 설정 요약에는 로컬 현재 버전과 서버 최신 버전을 함께 표기한다.
- 상세카드(종목 상세) 기사 목록/뉴스 점수 조회 윈도우는 차트 기간 선택값과 반드시 동기화한다(고정 `24h` 금지).
  - 기본 매핑: `1D→24h`, `7D→7d`, `1M/3M→30d`, `1Y→365d`, `5Y→1825d`, `ALL→2000d`.
- 상세카드 `관련 기사`는 기본 5건만 노출하고, 하단 `더보기` 버튼으로 10건씩 점진 확장한다.
  - 버튼 문구는 현재/전체 개수를 함께 보여준다(예: `더보기 (5/23)`).
- 상세카드 뉴스는 외부 브라우저 이탈 없이 인라인 펼침(accordion)으로 보여준다.
  - 기사 탭 시 제목 아래 요약 본문을 펼치고, 문장 구분(`.`, `!`, `?`) 단위로 줄바꿈한다.
  - 펼친 본문은 축약 없이 전체 요약을 노출하고, 해당 종목명/종목코드 언급은 볼드로 강조한다.
- 종목별 뉴스 MISS 보강(backfill)에서 네이버 보강은 기본 `OFF`로 두고, 일반 기사(`event_type` 미지정/비-community)에는 `네이버 검색/커뮤니티`를 주입하지 않는다.
  - `커뮤니티` 필터(`event_type=community`) 요청에서만 `네이버 증권 종목토론실` 보강을 허용한다.
  - 커뮤니티 글은 본문까지 파싱해 `summary`로 저장하고, `event_type=community`, `source=naver_finance_community`로 구분한다.
- 상세카드 상단 시세 영역은 과대 타이포를 피하고, 가격 폰트를 종목명과 같은 레벨로 유지한다.
- 종목명 우측에는 `AI 신호 배지 + 종합점수 + 하트(관심)`를 한 줄로 배치해 핵심 의사결정 정보를 즉시 확인 가능하게 한다.
- 상세카드 주문 UX는 `상단 시세 우측 모의구매/실전구매 버튼 클릭 → 팝업 입력` 흐름을 기본으로 유지한다(하단 고정 주문바 회귀 금지).
- 상세카드 관심 토글은 상단 종목명 영역의 `하트` 아이콘으로 제공한다(탭 헤더 액션 분산 금지).
- 상세카드 진입 시 하단 메뉴 활성 상태는 진입 직전 탭을 유지해야 한다(`currentRoute` null로 비활성 표시 금지).
- 상세카드 이슈 태그 칩은 단순 라벨이 아니라 실제 뉴스 목록 필터로 동작해야 한다(클릭 불가/무동작 금지).
- 상세카드 뉴스 필터는 `event_type` 중심 고정축으로 운영한다: `전체`, `공시`, `실적`, `계약/M&A`, `리포트`, `커뮤니티`.
  - `커뮤니티`를 제외한 필터는 일반 기사 목록(RSS/DART 기반)에서만 동작하고, `커뮤니티`는 `event_type=community` 목록만 사용한다.
  - `커뮤니티` 조회는 `ticker + event_type=community`를 기준으로 하고, 종목명 `q` 검색어를 기본 필터로 강제하지 않는다(게시판 글 과소노출 방지).
  - `커뮤니티` 결과가 소량(기본 20건 미만)일 때는 백엔드 ticker backfill을 재시도해 게시판 수집 누락을 보강한다(쿨다운 준수).
- 상세카드 뉴스는 필터와 정렬을 분리한다.
  - 정렬 옵션은 `최신순`, `영향순` 2개를 기본으로 제공한다.
- 기사 리스트 보조 메타는 최소 `event 라벨`, `source 라벨`, `impact`를 함께 표시해 노출 근거를 숨기지 않는다.
- 상세카드/차트의 `네이버 가격보기` 링크는 5자리 종목코드가 넘어와도 좌측 `0` 패딩으로 6자리 정규화 후 열어야 한다(링크 미동작 방지).
- 상세카드 뉴스/커뮤니티 본문은 문장부호(`.`, `!`, `?`, `。`) 뒤 줄바꿈 포맷을 동일 적용한다.
- 백엔드 뉴스 윈도우 파서는 위 기간 문자열을 허용해야 하며(`30d`, `365d` 등), 앱/서버 매핑 불일치 상태로 배포하지 않는다.
- 배포 APK 파일명은 운영 공유본 기준 `james._V(빌드라벨).apk` 단순 규칙을 유지한다.
- `관심` 탭은 일반 추천 카드와 동일 템플릿을 재사용하지 않고, 추적 목적의 전용 카드(`favorite_tracking`)를 사용한다.
- `관심` 탭의 기간/상태 텍스트는 `보유` 용어를 사용하지 않고 `관심` 용어로만 표기한다(예: `관심일`, `관심 7일`).

## Build Stability Rules (Added 2026-02-20)
- Android Studio/Logcat 에러가 급증하면 기능 개발보다 먼저 `:app:compileDebugKotlin`으로 컴파일 파손 여부를 확정한다.
- 소스 파일에 중복 함수/깨진 머지 텍스트가 발견되면 즉시 제거하고, 기능 검증은 `compileDebugKotlin`과 `assembleDebug` 성공 후 진행한다.
- `RESET_DB_ON_START` 기본값은 항상 `false`로 유지한다. DB 초기화는 로컬 임시 검증에서 환경변수로 명시(`RESET_DB_ON_START=true`)한 경우에만 허용한다.
- 로컬 uvicorn 스모크 실행 시에는 `DATABASE_URL`과 `RESET_DB_ON_START=false`를 명시해 운영 DB drop 및 경로 불일치 오진을 방지한다.

## APK Download Robustness Rules (Added 2026-02-20)
- APK 재설치 안내는 첨부 다운로드만 강제하지 말고 브라우저 상호작용 가능한 설치 페이지(`/apk/install`)를 기본 진입점으로 제공한다.
- 배포 후 APK 검증은 헤더만 보지 말고 실제 GET 바디가 ZIP 시그니처(`PK`)인지 확인한다(HTML 차단 페이지 저장 방지).
- 다운로드 파일이 수KB HTML이면 설치를 진행하지 않고 재다운로드 안내(차단 페이지 Continue 클릭)를 노출한다.
- `/apk/download`가 보안 차단/인터스티셜(예: 503)로 실패해도 배포를 중단하지 말고, `apk_versioned_url`(예: `/apk/james._Vxxx.apk`)의 ZIP 시그니처 검증으로 대체한다.

## EC2 Auto-Recovery Rules (Added 2026-02-20)
- EC2 상태 검사 자동복구는 `StatusCheckFailed_Instance` + `StatusCheckFailed_System` 2중 알람을 기본 구성으로 운영한다.
- `StatusCheckFailed_Instance` 알람은 `EC2 action: Reboot this instance`를 사용한다.
- `StatusCheckFailed_System` 알람은 `EC2 action: Recover this instance`를 사용한다.
- 임계값 표준은 `Statistic=Maximum`, `Period=1 minute`, `Threshold >= 1`, `Datapoints to alarm = 2 out of 2`로 고정한다.
- CloudWatch EC2 상태검사 알람은 인프라 레벨 복구를 담당한다. 앱 프로세스 장애 대응(`systemd` 재시작, `/health` 모니터링)은 별도 운영 항목으로 분리한다.
- SNS 알림은 미구성으로도 생성 가능하나, 운영 단계에서는 알림 채널(SNS/이메일) 구성까지 완료하는 것을 권장한다.

## Algorithm Versioning Rules (Added 2026-02-20)
- 단타/장투(`PREMARKET`) 및 급등2(`/market/movers2`) 알고리즘은 `V1`(기준 보존)과 `V2`(강화 기본) 이중 버전을 유지한다.
- `V1` 로직은 회귀 검증/긴급 롤백 기준선으로 취급하며, 동작 의미를 바꾸는 수정은 금지한다.
- 운영 기본값은 `V2`이며, 긴급 롤백은 `algo_version=V1`로 즉시 전환 가능해야 한다.
- `algo_version`은 서버 전략 설정(`GET/POST /api/settings`)에서 관리하고, 필요 시 조회 API 쿼리(`/reports/premarket`, `/market/movers2`)로 1회 오버라이드할 수 있어야 한다.
- 캐시 키는 반드시 `algo_version`을 포함해야 하며, 서로 다른 버전 결과가 동일 캐시를 공유하면 안 된다.
- 응답 메타(`status` 또는 payload)에 `algo_version`을 포함해 운영자가 현재 버전을 즉시 식별할 수 있어야 한다.

## Premarket Queue Reliability Rules (Added 2026-02-20)
- `PREMARKET` worker 예외 발생 시 최근 실패 원인을 보존하고 `/reports/premarket`의 `status.message`로 즉시 노출한다(`생성중` 무한 표시 금지).
- 동일 `cache_key` 실패 직후에는 짧은 쿨다운을 두어 재큐잉 폭주를 막고, 재시도 가능 시점을 메시지로 안내한다.
- 앱 폴링은 `FALLBACK`이라도 상태 메시지가 `생성/대기`가 아니면 즉시 화면을 갱신해 실패/지연 원인을 숨기지 않는다.

## Daytrade Realtime Validity Rules (Added 2026-02-23)
- 단타 리스트는 cache-first를 유지하되, 카드 상태는 반드시 실시간 시세와 `진입/목표/손절` 기준으로 재판정해 노출한다.
- `daytrade_gate.on=false`일 때 헤더를 `추천`으로 표기하지 말고 `관찰(게이트 OFF)`로 명시한다.
- 단타 기본 정렬은 `진입 가능/조건부 진입` 우선, `대기` 다음, `무효(손절 이탈)` 후순위로 유지한다.
- 단타 헤더 상태문구에는 최소 `진입/대기/무효` 카운트를 포함해 현재 실행 가능성을 즉시 확인 가능하게 한다.
- 카드의 실행 상태 라벨은 전용 필드로 관리하고, 테마/이슈 태그와 혼용하지 않는다(상세카드 필터 오염 방지).

## Stock Card Field Contract Rules (Added 2026-02-24)
- 종목카드에는 사용자가 지정한 필드만 노출한다(임의 상태 문구 삽입 금지).
- 단타 실행상태(`조건부 진입/조건부 대기/무효/게이트 OFF`)는 카드 본문 텍스트로 노출하지 않고, 헤더 상태문구/정렬 계산에만 사용한다.
- 종목카드 AI 단계(`강함/매수/관망/주의/회피`)는 `thesis 텍스트`와 `실시간 시세+미니차트`를 병합 계산한다.
- 병합 우선순위는 `max(thesis score, market score)`를 사용해 카드가 상세 AI보다 과소평가(예: 강한매수→관망)되지 않도록 한다.
- AI 단계 계산에서 실행상태 문구(`조건부 진입` 등)를 신호값으로 해석하는 로직을 금지한다.
- AI 신호 점수가 없는 경우를 `관망`으로 강제 매핑하지 않는다. 점수 미존재는 `분석중`으로 표기한다.
- AI 텍스트 부재 시 `TOP/WATCH` 레이블만으로 AI 단계를 추정해 노출하는 fallback을 금지한다.

## Admin Role Visibility Rules (Added 2026-02-20)
- 관리자 UI 노출 판단은 `GET /auth/me`의 최신 `role`을 우선 사용하고, 로컬 캐시 role은 네트워크 실패 시에만 폴백으로 사용한다.
- `getMe()` 성공 시 앱은 `role`/`user_code` 캐시를 즉시 동기화해 stale 권한 캐시로 관리자 기능이 숨겨지지 않도록 한다.
- MASTER 계정의 설정 진입 기본 섹션은 관리자 패널을 우선 열어, 초대/계정관리 기능 접근성을 보장한다.
- MASTER 계정에서는 관리자 섹션(초대 링크/카카오톡 공유/계정관리)을 별도 토글로 숨기지 않고 항상 노출한다.
- 설정 화면 네트워크 실패 메시지는 예외 원문(`timeout` 등)을 그대로 노출하지 말고 사용자 안내 문구로 변환한다.
- `/auth/me` 일시 실패가 있더라도 MASTER로 판별된 경우 초대 생성 폼 자체를 차단하지 않는다.

## Work & History Discipline Rules (Added 2026-02-24)
- 사용자 요청은 계획/설명만으로 종료하지 않고, 가능한 범위에서 실제 작업(수정/빌드/배포/검증)을 수행한다.
- 작업 완료 보고 전에는 반드시 `server_rules/HISTORY_2026-02-26.md`에 타임스탬프/요약/수정파일/검증 결과를 기록한다.
- `RULES.md` 변경이 발생한 턴은 동일 턴에 `HISTORY_2026-02-26.md`를 함께 업데이트한다.
- 사용자 명시 규칙: "항상 작업하고 히스토리에 기록"을 기본 실행 원칙으로 유지한다.
- 모든 턴의 최종 응답 직전에 `HISTORY_2026-02-26.md` 신규 항목 존재를 확인하고, 누락 시 같은 턴에서 즉시 보완 기록 후 응답한다.
