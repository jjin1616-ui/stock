# KoreaStockDash 세션 저장 (2026-02-05)

## 1) 현재 상태 요약
- Android 앱 + FastAPI 백엔드 동시 개발/운영 중.
- 백엔드는 AWS EC2(16.176.148.77) + systemd(stock-backend) + nginx reverse proxy로 운영.
- 앱은 `nofcmDebug` 기준 단일 앱 설치/실행.
- 주요 요청사항 반영:
  - 장전/논문 탭 UI 통일
  - 용어 한글화(진입가/목표가/손절가 등)
  - 정렬 옵션, 표시 개수 설정, 공유 기능
  - 클릭 시 종목 차트 팝업(하단 시트)
  - 속도 저하 원인 로그캣 분석 기반 개선

## 2) 사용자 요구 핵심 히스토리
- 실제폰(USB 및 비USB)에서 동작, AWS 서버로 독립 운영.
- 앱 2개 설치 금지(항상 1개).
- 단타/장투 분리 강화, 관찰 모드/게이트 정책 명확화.
- 논문 탭은 장전과 동일 UX + 다른 추천 가설(variant) 적용.
- 로딩 지연/타임아웃 지속적으로 개선 요청.
- 종목 클릭 시 팝업 차트 요구.
- 스킬 설치 요청: trailofbits modern-python.

## 3) 백엔드 변경 사항(핵심)
### 파일: `backend/app/main.py`
- `/reports/premarket`
  - 캐시 존재 시 즉시 반환하도록 단순화(불필요 재생성 제거).
- `/eval/monthly`
  - 데이터 없을 때 무거운 실시간 재계산 대신 최신 캐시/기본값 우선 반환.
- `/chart/daily` 추가
  - 쿼리: `code`, `days`
  - pykrx 우선, 실패 시 FDR fallback.

### 파일: `backend/app/schemas.py`
- `ChartPoint`, `ChartDailyResponse` 스키마 추가.

### 파일: `backend/app/realtime_quotes.py`
- 네이버 배치 조회 timeout 단축(3.0 -> 1.5)
- 미조회 종목에 대한 느린 개별 호출 루프 제거(지연 원인 제거)
- 캐시 TTL 기반 응답 유지.

## 4) Android 변경 사항(핵심)
### 파일: `app/src/main/java/com/example/stock/ui/screens/Screens.kt`
- 장전/논문 화면에서 종목 카드 클릭 시 `ModalBottomSheet` 차트 팝업 구현.
- 카드 클릭 핸들러 `onOpenChart(ticker, name)` 연결.
- `MiniLineChart`(Canvas) 렌더링 추가.
- 논문 탭의 과도한 자동 로드 제거(초기 동시 부하 완화).

### 파일: `app/src/main/java/com/example/stock/data/api/ApiModels.kt`
- `ChartDailyDto`, `ChartPointDto` 추가.

### 파일: `app/src/main/java/com/example/stock/data/api/StockApiService.kt`
- `GET /chart/daily` API 추가.

### 파일: `app/src/main/java/com/example/stock/data/repository/StockRepository.kt`
- 엔드포인트 후보를 `current + default`로 축소(LAN/USB 자동 폴백 제거).
- 실시간 시세 chunked 조회(8개씩) + 부분성공 허용.
- `getPremarketCached()` 추가(캐시 즉시 표시용).
- `getPremarket`, `getPaperRecommendations`에 retry 추가.

### 파일: `app/src/main/java/com/example/stock/data/api/NetworkModule.kt`
- OkHttp timeout 조정:
  - connect 6s
  - read 15s

### 파일: `app/src/main/java/com/example/stock/viewmodel/ViewModels.kt`
- 장전 로드 시 캐시 먼저 표시 후 네트워크 갱신.
- 실시간 시세 polling 대상 축소(상위 8개 우선/고정).

## 5) 배포/운영
- EC2: `16.176.148.77`
- 서비스: `stock-backend.service`
- health 확인: `http://16.176.148.77/health` -> `{"ok":true}`
- chart API 확인: `/chart/daily?code=005930&days=30` 정상 응답.
- deploy script: `backend/scripts/deploy_ec2.sh`

## 6) 로그캣 기반 성능 진단 요약
- 과거 병목:
  - premarket/eval 요청 timeout
  - LAN/USB 폴백으로 추가 지연
  - 실시간 시세 대량/순차 요청으로 지연 누적
- 개선 후:
  - timeout 실패 빈도 감소
  - premarket 캐시 반환 안정화
  - quotes 요청은 대부분 0.7~1.6초 대, 간헐적 스파이크 존재

## 7) 아직 남은 리스크/할 일
1. 간헐적 premarket 첫 요청 지연(서버 순간 부하/워크로드 경쟁) 추가 완화
2. quotes API 병렬화/서버측 비동기 튜닝으로 지연 스파이크 추가 감소
3. 차트 팝업 UI 고도화(줌/기간 전환) 가능
4. 논문 탭 추천 엔진 variant와 장전 엔진 차이 검증 리포트 정식화

## 8) 스킬 설치
- 요청 스킬 설치 완료:
  - `~/.codex/skills/modern-python`
- 출처:
  - `trailofbits/skills` -> `plugins/modern-python/skills/modern-python`
- 주의:
  - Codex 재시작 후 스킬 완전 반영.

## 9) 주요 명령 기록(요약)
- 앱 설치: `./gradlew :app:installNofcmDebug`
- 로그캣 진단:
  - `adb -d logcat -c`
  - `adb -d shell am force-stop com.example.stock`
  - `adb -d shell am start -n com.example.stock/.MainActivity`
  - `adb -d logcat -d | rg ...`
- 서버 배포:
  - `backend/scripts/deploy_ec2.sh 16.176.148.77 ~/Desktop/stock-ec2-key.pem ubuntu`

## 10) 참고
- 이 파일은 세션 대화 내용과 작업 이력을 실무용으로 정리/보존한 기록이다.
- 원문 채팅의 1:1 전체 텍스트 덤프는 플랫폼상 직접 추출 기능이 없어, 수행/결정/변경사항 중심으로 보존했다.
