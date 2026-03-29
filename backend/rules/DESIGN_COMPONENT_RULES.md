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

