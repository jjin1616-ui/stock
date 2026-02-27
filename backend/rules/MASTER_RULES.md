# KoreaStockDash 운영/배포 회고 규칙 (2026-02-09)

## 반성 (요약)
- 배포 스크립트가 systemd 환경변수를 덮어써서 KRX 키/엔드포인트/데이터 경로가 유실되었고, 이로 인해 데이터 수집 실패가 반복되었다.
- 보고서 저장 시 SQLite UNIQUE 충돌로 워커가 실패했는데, 실패 상태가 사용자에게 노출되지 않아 원인 파악이 지연되었다.
- 초기 데이터 히스토리 조건이 과하게 보수적이라 후보가 0으로 떨어졌고, 이를 사후에 완화하면서 시간이 소요되었다.

## 앞으로의 규칙 (필수)
1. 배포 후 systemd 환경변수 강제 복구
   - `/etc/systemd/system/stock-backend.service`에 아래 키가 반드시 존재해야 한다.
     - `STOCK_DATA_DIR=/var/lib/stock-backend`
     - `DATABASE_URL=sqlite:////var/lib/stock-backend/korea_stock_dash.db`
     - `KRX_API_KEY=...`
     - `KRX_ENDPOINT_KOSPI=stk_bydd_trd`
     - `KRX_ENDPOINT_KOSDAQ=ksq_bydd_trd`
     - `KRX_ENDPOINT_KONEX=knx_bydd_trd`
   - 적용 순서 고정:
     - `systemctl daemon-reload` → `systemctl restart stock-backend` → `systemctl show stock-backend --property=Environment`

2. 배포 직후 3단계 헬스 체크 고정
   - 1) `curl http://127.0.0.1:8000/health`
   - 2) `curl http://127.0.0.1:8000/reports/premarket?date=YYYY-MM-DD`
   - 3) `sqlite3`로 최신 `reports` row 확인

3. 보고서 생성 실패 시 즉시 노출
   - 워커 실패는 `/reports/premarket` 응답의 `status.message`에 명확히 출력한다.
   - 최소한 실패 원인을 `diagnostics.json`에 기록한다.

4. KRX 데이터 사전 검증
   - 최초 실행 시 KRX API가 정상 응답하는지 `bas_dd=최근 영업일` 1회 샘플 호출로 확인한다.
   - 후보 0 발생 시 `min_history`를 자동 완화하거나, 데이터 부족 경고를 즉시 출력한다.

5. 변경 후 캐시 무효화와 단발성 확인
   - 규칙 변경 시 반드시 `reports` 캐시를 삭제 후 1회 생성/검증을 수행한다.
   - “변경 → 캐시 삭제 → 1회 생성 → 결과 확인” 루프를 고정한다.

# KoreaStockDash 디자인 컴포넌트 규칙 (2026-02-09)

## 목적
- 단타/장투/논문 UI는 동일한 공통 컴포넌트 구조로 유지한다.
- 디자인 변경은 공통 컴포넌트에서만 수행한다.

## 공통 컴포넌트 위치
- `app/src/main/java/com/example/stock/ui/common/ReportComponents.kt`

## 필수 규칙
1. 단타/장투/논문 화면은 공통 컴포넌트를 사용한다.
   - `CommonReportList`
   - `CommonReportItemCard`
   - `MetricUi`, `CommonReportItemUi`
2. 카드/배경/타이포 스타일 변경은 공통 컴포넌트만 수정한다.
3. 탭별 차이는 데이터와 옵션만 전달한다.
   - 단타: `진입/목표/손절`
   - 장투: `진입/상단/손절 + 목표 라인`
   - 논문: `진입/목표/손절`
4. 동일한 상태 카드(서버/시스템 상태)를 공통 UI로 유지한다.

## 금지 사항
- 탭별 개별 UI 레이아웃 추가 금지.
- 공통 컴포넌트 없이 직접 Card/Row/Column 스타일 수정 금지.

# KoreaStockDash 공통 컴포넌트 회고 (2026-02-09)

## 잘한 점
1. 단타/장투/논문을 `CommonReportList` 기반으로 통일해 변경 지점 1곳으로 축소.
2. 카드/메트릭/상태/정렬 UI를 공통화해 디자인 일관성 확보.
3. 실시간 없을 때 fallback 표시(전일 종가)도 공통 로직으로 흡수.

## 아쉬운 점
1. 공통화 이후에도 각 화면에 잔여 UI가 남아 있었고, 제거 정리 과정이 더뎠음.
2. 공통 컴포넌트 변경 시 빌드 에러가 발생(누락 import/참조). 사전 체크가 필요.

## 개선 규칙
1. 탭 화면은 데이터 바인딩 + 옵션만 전달, 레이아웃 직접 수정 금지.
2. 공통 컴포넌트 변경 후 즉시 빌드 + 폰 설치로 검증 고정.
3. 새 UI 요구사항은 먼저 공통 컴포넌트에 반영 후 탭 적용.
4. 헤더/푸터/배너/정렬 등 공통 UI는 반드시 규칙을 먼저 확정한 뒤, 공통 컴포넌트만 수정한다.
5. 화면 단위 임시 수정 금지. 예외 요청이 있더라도 공통 컴포넌트로 흡수 후 적용.

# UI 헤더/푸터 공통 규칙 (2026-02-09)

## 원칙
1. 상단(헤더)와 하단(푸터)은 반드시 공통 컴포넌트로만 관리한다.
2. 화면별 TopAppBar 직접 구현 금지. 예외 없음.
3. 헤더/푸터 디자인 변경은 공통 컴포넌트에서만 한다.

## 공통 파일
- 상단: `app/src/main/java/com/example/stock/ui/common/TopBars.kt`
- 하단: `app/src/main/java/com/example/stock/ui/common/BottomBar.kt`
