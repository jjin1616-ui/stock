# 보유/매도 UX QA 결과 (2026-02-28)

- 총 가설: 71
- 통과: 71
- 실패: 0

## 추가 보정 QA (23:07 KST)
1. [PASS] (정적) 거래 이력 검색창 compact 높이(42dp) 적용
2. [PASS] (정적) 예약 카드 주문 종류 라벨 구분(매수/매도/취소)
3. [PASS] (실행) `:app:compileDebugKotlin` 재검증

## 체크리스트
1. [PASS] (정적) 히스토리탭 enum 추가
2. [PASS] (정적) 히스토리상태필터 enum 추가
3. [PASS] (정적) 주문톤 enum 추가
4. [PASS] (정적) 주문요약 data class 추가
5. [PASS] (정적) 탭 라벨 진행중
6. [PASS] (정적) 탭 라벨 완료
7. [PASS] (정적) 탭 라벨 전체
8. [PASS] (정적) 상태필터 예약대기
9. [PASS] (정적) 상태필터 접수대기
10. [PASS] (정적) 상태필터 체결
11. [PASS] (정적) state orderHistoryTab
12. [PASS] (정적) state orderHistoryStatusFilter
13. [PASS] (정적) state orderHistoryQuery
14. [PASS] (정적) state fullSellSubmitting
15. [PASS] (정적) state partialSellSubmitting
16. [PASS] (정적) state full baseline refreshedAt
17. [PASS] (정적) state partial baseline refreshedAt
18. [PASS] (정적) state full baseline error
19. [PASS] (정적) state partial baseline error
20. [PASS] (정적) actionSummary remember 추가
21. [PASS] (정적) 상단 상태카드 composable
22. [PASS] (정적) 상태카드 title 최근 주문 상태
23. [PASS] (정적) 상태카드 LazyColumn 삽입
24. [PASS] (정적) 거래이력 card 탭 파라미터
25. [PASS] (정적) 거래이력 card 상태필터 파라미터
26. [PASS] (정적) 거래이력 card 검색 파라미터
27. [PASS] (정적) 거래이력 탭 칩 렌더
28. [PASS] (정적) 거래이력 상태칩 렌더
29. [PASS] (정적) 거래이력 검색 입력
30. [PASS] (정적) 조건없음 문구 개선
31. [PASS] (정적) tab filter 변수
32. [PASS] (정적) status filter 변수
33. [PASS] (정적) query filter 변수
34. [PASS] (정적) matchesHistoryTab 함수
35. [PASS] (정적) matchesHistoryStatus 함수
36. [PASS] (정적) matchesHistoryQuery 함수
37. [PASS] (정적) historyStatusCategory 함수
38. [PASS] (정적) BROKER_SUBMITTED 분류
39. [PASS] (정적) 예약 QUEUED 분류
40. [PASS] (정적) 예약 DONE 분류
41. [PASS] (정적) 전량 매도 onDismiss 가드
42. [PASS] (정적) 부분 매도 onDismiss 가드
43. [PASS] (정적) 전량 confirm 비활성
44. [PASS] (정적) 부분 confirm 비활성
45. [PASS] (정적) 전량 버튼 처리중 텍스트
46. [PASS] (정적) 부분 버튼 처리중 텍스트
47. [PASS] (정적) 처리중 spinner 추가
48. [PASS] (정적) 장중/장외 안내 문구
49. [PASS] (정적) 주문행 환경라벨
50. [PASS] (정적) 예약행 planned amount 표시
51. [PASS] (정적) 주문상태 사용자취소
52. [PASS] (정적) 주문상태 상태정리완료
53. [PASS] (정적) 용어집 진행중 이력
54. [PASS] (정적) 용어집 완료 이력
55. [PASS] (정적) 용어집 최근 주문 상태
56. [PASS] (정적) 용어집 예약대기
57. [PASS] (정적) 용어집 접수대기
58. [PASS] (정적) 거래이력 총건수 표시
59. [PASS] (정적) 상태카드 진행중 문구
60. [PASS] (정적) 상태카드 예약완료 문구
61. [PASS] (정적) 상태카드 실패 문구
62. [PASS] (정적) 탭/필터 변경 시 접기
63. [PASS] (정적) 필터 변경 시 탭 초기화
64. [PASS] (정적) 예약 수량 합산 로직
65. [PASS] (정적) 예약 금액 합산 로직
66. [PASS] (정적) 환경 라벨 함수 추가
67. [PASS] (정적) 예약 상세에 환경 표기
68. [PASS] (실행) 앱 컴파일(:app:compileDebugKotlin)
69. [PASS] (실행) 앱 패키징(:app:assembleDebug)
70. [PASS] (실행) 앱 단위테스트(:app:testDebugUnitTest)
71. [PASS] (실행) 백엔드 계약검사(preflight_autotrade_contract)
