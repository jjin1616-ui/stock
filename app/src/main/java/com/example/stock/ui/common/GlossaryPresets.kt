package com.example.stock.ui.common

object GlossaryPresets {
    val AUTOTRADE: List<GlossaryItem> = listOf(
        GlossaryItem("요청", "이번 실행에서 주문 검토 대상으로 집계된 건수입니다."),
        GlossaryItem("접수", "증권사가 주문번호를 발급해 주문을 접수한 상태입니다."),
        GlossaryItem("체결", "실제로 매수/매도가 체결된 상태입니다."),
        GlossaryItem("스킵", "조건 미충족 또는 리스크 제한으로 주문을 의도적으로 건너뛴 상태입니다."),
        GlossaryItem("증권사거부", "증권사 검증 단계에서 주문이 거절된 상태입니다."),
        GlossaryItem("익절/손절 기준(%)", "포지션별 자동 청산 기준입니다. 예: +7% 익절, -5% 손절."),
        GlossaryItem("소스 선택", "단타/급등/논문/장투/관심 중 체크한 소스만 후보로 사용합니다."),
    )

    val DAYTRADE: List<GlossaryItem> = listOf(
        GlossaryItem("진입", "돌파 기준 가격입니다. 현재가가 진입 위에 안착할 때 분할 진입을 검토합니다."),
        GlossaryItem("목표", "1차 이익실현 기준 가격입니다. 도달 시 일부 익절로 리스크를 줄입니다."),
        GlossaryItem("손절", "손실 제한 기준 가격입니다. 이탈 시 규칙대로 손절합니다."),
        GlossaryItem("게이트 켜짐/꺼짐", "시장 환경 필터입니다. 꺼짐이면 공격 진입 대신 보수 운용을 권장합니다."),
        GlossaryItem("조건부 진입", "게이트 꺼짐 상태의 보수 진입 신호입니다. 분할/소액으로만 접근합니다."),
        GlossaryItem("후순위 관망", "관찰 우선 종목입니다. 신호 확인 전까지는 진입을 미룹니다."),
        GlossaryItem("테마", "서버 매핑 기반 업종/테마 라벨입니다. 공통 재료 그룹을 빠르게 확인합니다."),
    )

    val MOVERS: List<GlossaryItem> = listOf(
        GlossaryItem("세션", "장전/정규장/스파이크/장마감/시간외 구간별 탐지 모드입니다."),
        GlossaryItem("기준%", "세션 기준 가격 대비 변동률입니다. 세션별 기준점이 다를 수 있습니다."),
        GlossaryItem("거래대금", "세션 누적 거래대금입니다. 급등 강도 판단의 핵심 지표입니다."),
        GlossaryItem("거래량", "세션 누적 거래량입니다. 가격 변동의 체결 참여도를 확인합니다."),
        GlossaryItem("품질 정밀/추정", "정밀은 세션 전용 가격 기반, 추정은 대체 가격 기반 계산입니다."),
        GlossaryItem("지표", "세션체결/정규장누적/대체값 중 어떤 데이터로 계산했는지 표시합니다."),
    )

    val SUPPLY: List<GlossaryItem> = listOf(
        GlossaryItem("수급점수", "외국인/기관 순매수 강도, 연속성, 유동성을 합산한 우선순위 점수입니다."),
        GlossaryItem("외인3일", "최근 3거래일 외국인 순매수(+)·순매도(-) 수량입니다."),
        GlossaryItem("기관3일", "최근 3거래일 기관 순매수(+)·순매도(-) 수량입니다."),
        GlossaryItem("합계3일", "외국인3일 + 기관3일 합산 수량입니다."),
        GlossaryItem("유형", "외국인 주도/기관 주도/동반 매수/개인 역추세 중 수급 패턴 분류입니다."),
        GlossaryItem("신뢰", "투자자 원천(LIVE/CACHE/FALLBACK)과 데이터 일수 기준 신뢰도입니다."),
    )

    val US: List<GlossaryItem> = listOf(
        GlossaryItem("내부자 공시", "미국 내부자 거래 공시 문서입니다. 임원 거래의 공식 근거입니다."),
        GlossaryItem("거래구분 코드", "시장매수/무상취득/옵션행사/세금납부용 처분으로 구분합니다."),
        GlossaryItem("비파생", "보통주 등 현물성 거래 구간입니다. 파생 거래는 별도로 분리됩니다."),
        GlossaryItem("반복매수(90거래일)", "동일 임원이 90거래일 내 동일 종목을 2회 이상 매수한 상태입니다."),
        GlossaryItem("사전 계획 매매", "사전에 계획된 매매 여부 표기입니다. 자발성 해석 시 별도 주의가 필요합니다."),
        GlossaryItem("거래일/제출일", "거래 발생일과 공시 제출일입니다. 시차가 존재할 수 있습니다."),
    )

    val LONGTERM: List<GlossaryItem> = listOf(
        GlossaryItem("진입", "장기 매수 시작 기준 가격입니다."),
        GlossaryItem("상단", "권장 매수 구간의 상단 가격입니다."),
        GlossaryItem("손절", "장기 시나리오 무효화 기준 가격입니다."),
        GlossaryItem("목표", "12개월 기준 목표 가격입니다."),
        GlossaryItem("테마", "종목이 속한 재료/업종 그룹 라벨입니다."),
    )

    val PAPERS: List<GlossaryItem> = listOf(
        GlossaryItem("진입/목표/손절", "논문 가설 기반 추천 종목의 실행 기준 가격입니다."),
        GlossaryItem("테마 분산", "동일 테마 쏠림을 줄여 리스트 리스크를 낮추는 규칙입니다."),
        GlossaryItem("추천 근거", "기술/수급/추세 등 점수 구성 요소를 요약한 설명입니다."),
    )

    val NEWS: List<GlossaryItem> = listOf(
        GlossaryItem("핫지수", "영향도, 기사수, 클러스터수를 반영한 뉴스 강도 점수입니다."),
        GlossaryItem("클러스터", "유사 제목/이벤트 기사 묶음입니다. 같은 이슈의 중복 노이즈를 줄입니다."),
        GlossaryItem("기사", "개별 원문 기사 단위입니다. 상세에서 전문/출처를 확인합니다."),
        GlossaryItem("공시/언론", "공시는 공시 시스템, 언론은 뉴스 수집 기반 데이터입니다."),
        GlossaryItem("악재숨김", "증자/규제/소송 계열 이벤트를 리스트에서 제외합니다."),
        GlossaryItem("이벤트", "실적/계약/자사주/증자 등 기사 성격 분류 필터입니다."),
    )

    val AI_SIGNAL: List<GlossaryItem> = listOf(
        GlossaryItem("종합점수", "기술 55% + 뉴스 25% + (100-리스크) 20%를 합산한 점수입니다."),
        GlossaryItem("신호 단계", "강한 매수 > 매수 > 관망 > 주의 > 회피 순으로 적극성이 낮아집니다."),
        GlossaryItem("기술 점수", "이동평균선(20/60), 모멘텀, 거래량을 반영합니다."),
        GlossaryItem("뉴스 점수", "최근 기간 기사 수, 기사 극성, 뉴스 핫지수 점수를 반영합니다."),
        GlossaryItem("리스크 점수", "최근 변동성(14일)과 급등락 강도를 반영하며 높을수록 위험합니다."),
        GlossaryItem("손익비", "예상 수익폭 ÷ 예상 손실폭입니다. 1.00보다 높을수록 보상이 큽니다."),
        GlossaryItem("진입/손절/목표", "진입가, 손실 제한선, 1차 이익실현 가격입니다."),
    )

    val HOLDINGS: List<GlossaryItem> = listOf(
        GlossaryItem("보유 목록", "현재 보유 중인 종목 목록입니다. 종목을 눌러 상세를 확인합니다."),
        GlossaryItem("현재가 보기", "평가금액 대신 현재가격을 표시합니다."),
        GlossaryItem("평가금액 보기", "현재가 × 보유수량으로 계산된 평가금액을 표시합니다."),
        GlossaryItem("총 손익", "전체 보유 종목의 평가손익 합계입니다."),
        GlossaryItem("수익률", "총 손익 ÷ 총 매입금액 × 100으로 계산합니다."),
        GlossaryItem("평균 수익률", "보유 종목들의 수익률 평균입니다."),
        GlossaryItem("승률", "수익률이 플러스인 종목 비중입니다."),
        GlossaryItem("최대수익", "보유 종목 중 가장 높은 수익률입니다."),
        GlossaryItem("최대손실", "보유 종목 중 가장 낮은 수익률입니다."),
        GlossaryItem("총 매입금액", "보유 종목들의 매입금액 합계입니다."),
        GlossaryItem("총 평가금액", "보유 종목들의 현재 평가금액 합계입니다."),
        GlossaryItem("주문가능현금", "현재 주문에 사용할 수 있는 현금입니다."),
        GlossaryItem("총자산", "현금 + 주식 평가금액을 합산한 값입니다."),
        GlossaryItem("데이터 출처", "실계좌 연동/모의 장부/서버 추정치/미연동 상태를 표시합니다."),
        GlossaryItem("실계좌 연동", "증권사에서 실계좌 데이터를 정상 수신한 상태입니다."),
        GlossaryItem("모의 장부", "모의 실행 환경의 내부 계좌 기록입니다."),
        GlossaryItem("서버 추정치", "실계좌 연동 전 서버가 계산한 추정치입니다."),
        GlossaryItem("거래 이력", "보유 화면에서 최근 매수/매도 요청과 체결 내역을 보여줍니다."),
        GlossaryItem("증권사접수", "증권사가 주문번호를 발급해 접수한 상태입니다."),
        GlossaryItem("증권사체결", "증권사에서 실제 체결된 상태입니다."),
        GlossaryItem("전량 매도", "보유 수량 전체를 매도합니다."),
        GlossaryItem("부분 매도", "보유 수량 일부를 지정해 매도합니다."),
        GlossaryItem("어제보다", "전일 대비 등락률입니다(현재가 보기 기준)."),
    )
}
