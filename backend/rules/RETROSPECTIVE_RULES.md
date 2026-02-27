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

## 회고 추가 (2026-02-26, 상세카드 주문 UX 회귀)
- 같은 파일에서 연속 기능 변경 중, 사용자 확정 UX(클릭형 주문 팝업)가 다시 하단 고정 주문바로 회귀했다.
- 원인:
  - "완료" 판정 기준이 코드 검색/실행 검증으로 고정되어 있지 않아 회귀가 즉시 차단되지 못했다.
  - 후속 수정 시 직전 사용자 확정 의도(불변조건)를 diff 기준으로 재검증하지 않았다.
- 재발 방지 필수 규칙:
  - 1) 롤백/UX복구 작업은 `금지 심볼 없음 + 의도 심볼 존재 + installDebug 실행` 3단계를 모두 통과해야 완료로 기록한다.
  - 2) 상세카드처럼 변경 빈도가 높은 파일은 후속 작업마다 `Scaffold.bottomBar`, `Dialog`, 상단 액션 배치 변경 여부를 먼저 점검한다.
  - 3) 사용자 확정 의도(예: "하단 고정 금지")는 다음 작업의 불변조건으로 고정하고, 예외는 사용자 재승인 전 적용 금지.
