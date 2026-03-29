# Access Control v1.1 — Spec Summary (Single Source)

## 상태모델 전이표
- invite_status:
  - CREATED → SENT → ACTIVATED → PASSWORD_CHANGED → ACTIVE
- force_password_change:
  - true (초대/리셋 직후)
  - false (비밀번호 변경 완료 후)

## reason_code 목록
- OK
- INVALID_CRED
- BLOCKED
- LOCKED
- EXPIRED_TEMP_PASSWORD
- FORCE_CHANGE_REQUIRED
- DELETED
- DEVICE_NOT_ALLOWED
- STATUS_INACTIVE

## 관리자 액션 목록
- INVITE_CREATE (AUTO/MANUAL)
- INVITE_MARK_SENT
- USER_BLOCK / USER_UNBLOCK
- PASSWORD_RESET (AUTO/MANUAL)
- ROLE_CHANGE
- EXPIRES_EXTEND
- DEVICE_BIND_TOGGLE / DEVICE_RESET

## 메뉴 권한 필드(요약)
- `menu_daytrade`, `menu_supply`, `menu_autotrade`, `menu_holdings`, `menu_movers`, `menu_us`, `menu_news`, `menu_longterm`, `menu_papers`, `menu_eod`, `menu_alerts`

## 테스트 체크리스트
- [ ] AUTO 초대 → 최초 로그인 → 프로필 → 비번 변경 → 메인 접근
- [ ] MANUAL 초대(전화번호) → 최초 로그인 → 비번 변경
- [ ] 임시비번 만료 후 로그인 실패
- [ ] 로그인 N회 실패 → locked 동작
- [ ] MASTER 차단 후 즉시 로그인 실패
- [ ] MASTER 리셋 후 기존 세션 무효화 확인
- [ ] 생체 ON/OFF 전환 및 재실행 시 동작 확인
- [ ] 로그/감사로그 누락 없는지 확인
