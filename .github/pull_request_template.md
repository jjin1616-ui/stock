## Summary
- 변경 목적:
- 사용자 영향:

## Scope
- Android:
- Backend:
- Rules/History:

## Verification
- [ ] `./gradlew :app:compileDebugKotlin`
- [ ] `python3 -m py_compile` (변경된 backend 파일)
- [ ] 서버 health 확인 (`/health`)

## Detail Card News Checklist (Mandatory when touched)
- [ ] `event_type=community` API 건수와 앱 노출 건수를 비교했다.
- [ ] 대표 종목 2개 이상(대형/중소형)으로 커뮤니티 노출량을 수동 검증했다.
- [ ] `네이버 가격보기` 실제 클릭 이동을 검증했다(5자리 코드 포함).
- [ ] 뉴스/커뮤니티 본문의 문장부호 뒤 줄바꿈을 검증했다.
- [ ] RULES/HISTORY를 같은 PR에서 함께 업데이트했다.

## Rollback
- 롤백 방법:
- 리스크/주의사항:
