package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto
import com.example.stock.data.api.AutoTradePerformanceItemDto
import com.example.stock.data.api.DaytradeTopItemDto
import com.example.stock.data.api.LongtermItemDto
import com.example.stock.data.api.NewsClusterListItemDto
import com.example.stock.data.api.ThemeItemDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.CommonReportItemCard
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.theme.BluePrimary
import com.example.stock.ui.theme.CoralAccent
import com.example.stock.ui.theme.TextMain
import com.example.stock.ui.theme.TextMuted
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.HomeViewModel
import com.example.stock.viewmodel.DailyFlow
import com.example.stock.viewmodel.InvestorFlowSummary
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.text.style.TextAlign

private val PageBg = Color(0xFFF5F6F8)
private val CardBg = Color.White
private val UpColor = Color(0xFFD32F2F)
private val DownColor = Color(0xFF2F6BFF)
private val GreenColor = Color(0xFF00B894)
private val ChipBg = Color(0xFFEFF3FF)
private val ChipText = Color(0xFF2F5BEA)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: HomeViewModel = viewModel(factory = AppViewModelFactory(repo))
    val premarketState by vm.premarketState
    val favorites = vm.favoritesState.value
    val quotes = vm.quoteState
    val miniCharts = vm.miniChartState
    val marketSnapshot = vm.marketSnapshotState.value
    val regimeMode = vm.regimeModeState.value
    val investorFlow = vm.investorFlowState.value
    val account = vm.accountState.value
    val performance = vm.performanceState.value
    val autoTradeEnabled = vm.autoTradeEnabledState.value
    val autoTradeEnv = vm.autoTradeEnvState.value
    val reservationCount = vm.reservationCountState.value
    val newsClusters = vm.newsClustersState.value
    val briefing = vm.briefingState.value
    val marketTemperature = vm.marketTemperatureState.value
    val snapshotDate = vm.snapshotDateState.value
    val liveIndices = vm.liveIndicesState.value
    val tradeFeed = vm.tradeFeedState.value
    val pnlCalendar = vm.pnlCalendarState.value

    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(Unit) { onDispose { vm.stopPolling() } }

    val themes = premarketState.data?.themes.orEmpty()
    val daytradeTop = premarketState.data?.daytradeTop.orEmpty()
    val longtermTop = premarketState.data?.longterm.orEmpty()
    val loading = premarketState.loading && premarketState.data == null

    Scaffold(
        topBar = { AppTopBar(title = "홈", showRefresh = true, onRefresh = { vm.load() }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(PageBg)
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 0. 한줄 브리핑 ──
            if (!briefing.isNullOrBlank()) {
                item {
                    BriefingCard(text = briefing)
                }
            }

            // ── 1. 내 계좌 요약 ──
            item {
                AccountSummaryCard(account = account)
            }

            // ── 2. 자동매매 상태 ──
            item {
                AutoTradeStatusCard(
                    enabled = autoTradeEnabled,
                    environment = autoTradeEnv,
                    performance = performance,
                    reservationCount = reservationCount,
                    gateOn = premarketState.data?.daytradeGate?.on,
                )
            }

            // ── 2b. 실시간 매매 피드 ──
            if (tradeFeed.isNotEmpty()) {
                item {
                    TradeFeedCard(items = tradeFeed.take(10))
                }
            }

            // ── 3. 시장 지표 + 체제 뱃지 ──
            item {
                val today = remember { java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString() }
                val isFallback = snapshotDate != null && snapshotDate != today
                HomeSectionCard(title = if (isFallback) "시장 지표 (전일 기준)" else "시장 지표") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        MarketIndexSimpleCard(
                            label = "코스피",
                            value = marketSnapshot?.kospiClose,
                            changePct = liveIndices?.kospi?.changePct,
                            modifier = Modifier.weight(1f),
                        )
                        MarketIndexSimpleCard(
                            label = "코스닥",
                            value = marketSnapshot?.kosdaqClose,
                            changePct = liveIndices?.kosdaq?.changePct,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (marketSnapshot?.usdkrwClose != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            MarketIndexSimpleCard(
                                label = "원/달러",
                                value = marketSnapshot.usdkrwClose,
                                changePct = liveIndices?.usdkrw?.changePct,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    if (regimeMode != null) {
                        Spacer(Modifier.height(8.dp))
                        RegimeBadge(mode = regimeMode)
                    }
                    if (marketTemperature != null) {
                        Spacer(Modifier.height(8.dp))
                        MarketTemperatureBar(temperature = marketTemperature)
                    }
                }
            }

            // ── 4. 추천 요약 (단타 TOP3 + 장타 TOP3) ──
            if (!loading && (daytradeTop.isNotEmpty() || longtermTop.isNotEmpty())) {
                item {
                    RecommendationSummaryCard(
                        daytradeTop = daytradeTop.take(3),
                        longtermTop = longtermTop.take(3),
                        context = context,
                    )
                }
            } else if (loading) {
                item {
                    HomeSectionCard(title = "오늘의 추천") {
                        Box(
                            Modifier.fillMaxWidth().height(60.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = BluePrimary, strokeWidth = 2.dp)
                        }
                    }
                }
            }

            // ── 5. 투자자 수급 ──
            item {
                HomeSectionCard(title = "투자자 수급 현황") {
                    if (investorFlow != null) {
                        InvestorFlowCard(flow = investorFlow)
                    } else {
                        Text("장 마감 후에는 수급 데이터가 제공되지 않습니다.", color = TextMuted, fontSize = 13.sp)
                    }
                }
            }

            // ── 6. 관심 종목 (10개 제한 + 펼치기) ──
            if (favorites.isNotEmpty()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    val visibleFavs = if (expanded) favorites else favorites.take(10)
                    Column {
                        Text(
                            text = "관심 종목",
                            color = TextMain,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        visibleFavs.forEach { fav ->
                            val ticker = fav.ticker.orEmpty()
                            val q = quotes[ticker]
                            CommonReportItemCard(
                                item = CommonReportItemUi(
                                    ticker = ticker,
                                    name = fav.name,
                                    title = fav.name.orEmpty(),
                                    quote = q,
                                    fallbackPrice = fav.currentPrice,
                                    fallbackChangePct = fav.changeSinceFavoritePct,
                                    metrics = emptyList(),
                                    sortPrice = q?.price ?: fav.currentPrice,
                                    sortChangePct = q?.chgPct ?: fav.changeSinceFavoritePct,
                                    miniPoints = miniCharts[ticker],
                                ),
                                onClick = {
                                    StockDetailActivity.open(context, ticker, fav.name.orEmpty(), "home", emptyList())
                                },
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (!expanded && favorites.size > 10) {
                            Text(
                                text = "더보기 (+${favorites.size - 10}개)",
                                color = BluePrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = true }
                                    .padding(vertical = 10.dp, horizontal = 16.dp),
                            )
                        }
                    }
                }
            }

            // ── 7b. 수익 캘린더 ──
            if (pnlCalendar != null && (pnlCalendar.days.orEmpty().isNotEmpty())) {
                item {
                    PnlCalendarCard(calendar = pnlCalendar)
                }
            }

            // ── 8. 주요 뉴스 ──
            if (newsClusters.isNotEmpty()) {
                item {
                    HomeSectionCard(title = "주요 뉴스") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            newsClusters.forEach { cluster ->
                                NewsClusterRow(cluster = cluster)
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ─────────────────────────────────────────────────
// 1. 계좌 요약 카드
// ─────────────────────────────────────────────────
@Composable
private fun AccountSummaryCard(account: AutoTradeAccountSnapshotResponseDto?) {
    HomeSectionCard(title = "내 계좌") {
        if (account == null) {
            Text("계좌 데이터 로딩 중...", color = TextMuted, fontSize = 13.sp)
            return@HomeSectionCard
        }
        if (account.source == "UNAVAILABLE" && account.totalAssetKrw == null) {
            Text("계좌 동기화 중입니다. 잠시 후 새로고침 해주세요.", color = TextMuted, fontSize = 13.sp)
            return@HomeSectionCard
        }
        val totalAsset = account.totalAssetKrw ?: 0.0
        val unrealizedPnl = account.unrealizedPnlKrw ?: 0.0
        val pnlPct = account.realEvalPnlPct ?: 0.0
        val cash = account.cashKrw ?: 0.0
        val pnlColor = if (unrealizedPnl >= 0) UpColor else DownColor
        val pnlSign = if (unrealizedPnl >= 0) "+" else ""

        // 총 평가자산
        Text(
            text = "총 평가자산",
            color = TextMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "%,.0f원".format(totalAsset),
            color = TextMain,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        // 평가손익 + 현금
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("평가손익", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${pnlSign}%,.0f원 (${pnlSign}%.1f%%)".format(unrealizedPnl, pnlPct),
                    color = pnlColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("현금", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "%,.0f원".format(cash),
                    color = TextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // 보유종목 수
        val posCount = account.positions?.size ?: 0
        if (posCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "보유 ${posCount}종목",
                color = TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

// ─────────────────────────────────────────────────
// 2. 자동매매 상태 카드
// ─────────────────────────────────────────────────
@Composable
private fun AutoTradeStatusCard(
    enabled: Boolean?,
    environment: String?,
    performance: AutoTradePerformanceItemDto?,
    reservationCount: Int,
    gateOn: Boolean?,
) {
    HomeSectionCard(title = "자동매매") {
        if (enabled == null) {
            Text("자동매매 설정을 불러오는 중...", color = TextMuted, fontSize = 13.sp)
            return@HomeSectionCard
        }

        // 상태 행: 활성/비활성 + 환경 + Gate
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 활성 표시등
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (enabled) GreenColor else Color.Gray),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (enabled) "실행 중" else "중지됨",
                    color = if (enabled) GreenColor else TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (environment != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = environment.uppercase(),
                        color = if (environment == "prod") UpColor else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .background(
                                color = if (environment == "prod") UpColor.copy(alpha = 0.1f) else Color(0xFFF0F1F4),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            // Gate 상태
            if (gateOn != null) {
                Text(
                    text = if (gateOn) "Gate ON" else "Gate OFF",
                    color = if (gateOn) GreenColor else UpColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(
                            color = if (gateOn) GreenColor.copy(alpha = 0.1f) else UpColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 성과 지표
        if (performance != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                PerformanceMetric(label = "수익률", value = "%.1f%%".format(performance.roiPct ?: 0.0), isPositive = (performance.roiPct ?: 0.0) >= 0)
                PerformanceMetric(label = "승률", value = "%.0f%%".format((performance.winRate ?: 0.0) * 100.0))
                PerformanceMetric(label = "MDD", value = "%.1f%%".format(performance.mddPct ?: 0.0))
                PerformanceMetric(label = "총 주문", value = "${performance.filledTotal ?: 0}건")
            }
        }

        // 예약 대기
        if (reservationCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "예약 대기 ${reservationCount}건",
                color = BluePrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PerformanceMetric(
    label: String,
    value: String,
    isPositive: Boolean? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.height(2.dp))
        val color = when (isPositive) {
            true -> UpColor
            false -> DownColor
            null -> TextMain
        }
        Text(text = value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────
// 3. 체제 뱃지
// ─────────────────────────────────────────────────
@Composable
private fun RegimeBadge(mode: String) {
    val (bgColor, textColor, label) = when (mode.uppercase()) {
        "BULL", "LOW_VOL_UP", "HIGH_VOL_UP" -> Triple(GreenColor.copy(alpha = 0.12f), GreenColor, "강세장")
        "BEAR", "LOW_VOL_FLAT", "HIGH_VOL_DOWN" -> Triple(UpColor.copy(alpha = 0.12f), UpColor, "약세장")
        else -> Triple(Color(0xFFF0F1F4), TextMuted, "중립")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "시장 체제", color = TextMuted, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(color = bgColor, shape = RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

// ─────────────────────────────────────────────────
// 4. 추천 요약 (단타 TOP3 + 장타 TOP3)
// ─────────────────────────────────────────────────
@Composable
private fun RecommendationSummaryCard(
    daytradeTop: List<DaytradeTopItemDto>,
    longtermTop: List<LongtermItemDto>,
    context: android.content.Context,
) {
    HomeSectionCard(title = "오늘의 추천") {
        if (daytradeTop.isNotEmpty()) {
            Text("단타 TOP", color = BluePrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            daytradeTop.forEach { stock ->
                RecommendationRow(
                    name = stock.name.orEmpty(),
                    thesis = stock.thesis,
                    onClick = {
                        StockDetailActivity.open(context, stock.ticker.orEmpty(), stock.name.orEmpty(), "home", emptyList())
                    },
                )
            }
        }
        if (daytradeTop.isNotEmpty() && longtermTop.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
        }
        if (longtermTop.isNotEmpty()) {
            Text("장타 TOP", color = CoralAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            longtermTop.forEach { stock ->
                RecommendationRow(
                    name = stock.name.orEmpty(),
                    thesis = stock.thesis,
                    onClick = {
                        StockDetailActivity.open(context, stock.ticker.orEmpty(), stock.name.orEmpty(), "home", emptyList())
                    },
                )
            }
        }
    }
}

@Composable
private fun RecommendationRow(
    name: String,
    thesis: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            color = TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(80.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = thesis.orEmpty(),
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─────────────────────────────────────────────────
// 7. 뉴스 클러스터 행
// ─────────────────────────────────────────────────
@Composable
private fun NewsClusterRow(cluster: NewsClusterListItemDto) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = cluster.title.orEmpty(),
            color = TextMain,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row {
            Text(
                text = "${cluster.articleCount ?: 0}건",
                color = BluePrimary,
                fontSize = 11.sp,
            )
            if (!cluster.topTickers.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = cluster.topTickers.take(3).joinToString(", "),
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// 공통 컴포넌트
// ─────────────────────────────────────────────────
@Composable
private fun HomeSectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = title,
                color = TextMain,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MarketIndexSimpleCard(
    label: String,
    value: Double?,
    changePct: Double? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                color = TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (value != null) "%,.2f".format(value) else "--",
                color = if (value != null) TextMain else TextMuted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            if (changePct != null) {
                val sign = if (changePct >= 0) "+" else ""
                val color = if (changePct >= 0) UpColor else DownColor
                Text(
                    text = "${sign}${"%.2f".format(changePct)}%",
                    color = color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ─── 투자자 수급 카드: 실시간 + 3일누적(diverging bar) + 5일 스파크라인 ───

@Composable
private fun InvestorFlowCard(flow: InvestorFlowSummary) {
    val isValue = flow.unit == "value"
    val todayFlow = flow.dailyFlow.lastOrNull()
    val entries3d = listOf(
        "외국인" to flow.foreign,
        "기관" to flow.institution,
        "개인" to flow.individual,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1단: 오늘 (dailyFlow 최근일)
        if (todayFlow != null) {
            FlowSubHeader("오늘")
            DivergingBarRows(
                entries = listOf(
                    "외국인" to todayFlow.foreign,
                    "기관" to todayFlow.institution,
                    "개인" to todayFlow.individual,
                ),
                isValue = isValue,
            )
        }
        // 2단: 3일 누적
        FlowSubHeader("3일 누적")
        DivergingBarRows(entries = entries3d, isValue = isValue)
        // 3단: 5일 추세 스파크라인
        if (flow.dailyFlow.size >= 2) {
            FlowSubHeader("5일 추세")
            FlowSparklines3Col(dailyFlow = flow.dailyFlow, isValue = isValue)
        }
    }
}

@Composable
private fun FlowSubHeader(text: String) {
    Text(text = text, color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}

/** 중앙 기준 diverging bar: 양수→오른쪽(빨강), 음수→왼쪽(파랑) */
@Composable
private fun DivergingBarRows(entries: List<Pair<String, Long>>, isValue: Boolean) {
    val maxAbs = entries.maxOfOrNull { abs(it.second.toFloat()) }?.coerceAtLeast(1f) ?: 1f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { (label, rawValue) ->
            val isPositive = rawValue >= 0
            val barColor = if (isPositive) UpColor else DownColor
            val fraction = (abs(rawValue.toFloat()) / maxAbs).coerceIn(0f, 1f)
            val displayText = fmtSupplyValue(rawValue, isValue)
            val animFraction by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(durationMillis = 600),
                label = "bar_$label",
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(40.dp),
                )
                Canvas(modifier = Modifier.weight(1f).height(20.dp)) {
                    val w = size.width
                    val h = size.height
                    val cx = w / 2f
                    val bw = (w / 2f) * animFraction
                    // 배경
                    drawRoundRect(color = Color(0xFFF0F1F4), cornerRadius = CornerRadius(4.dp.toPx()))
                    // 막대
                    if (isPositive) {
                        drawRect(
                            color = barColor.copy(alpha = 0.82f),
                            topLeft = Offset(cx, 0f),
                            size = GeoSize(bw.coerceAtLeast(0f), h),
                        )
                    } else {
                        drawRect(
                            color = barColor.copy(alpha = 0.82f),
                            topLeft = Offset((cx - bw).coerceAtLeast(0f), 0f),
                            size = GeoSize(bw.coerceAtLeast(0f), h),
                        )
                    }
                    // 중앙 기준선
                    drawLine(
                        color = Color(0xFF999999),
                        start = Offset(cx, 2f),
                        end = Offset(cx, h - 2f),
                        strokeWidth = 2f,
                    )
                }
                Text(
                    text = displayText,
                    color = barColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp).padding(start = 6.dp),
                )
            }
        }
    }
}

/** 3열 스파크라인: 외국인/기관/개인 나란히 */
@Composable
private fun FlowSparklines3Col(dailyFlow: List<DailyFlow>, isValue: Boolean) {
    val sparkEntries = listOf(
        Triple("외국인", UpColor, { d: DailyFlow -> d.foreign }),
        Triple("기관", DownColor, { d: DailyFlow -> d.institution }),
        Triple("개인", Color(0xFF8E8E93), { d: DailyFlow -> d.individual }),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sparkEntries.forEach { (label, lineColor, extract) ->
            val values = dailyFlow.map { extract(it).toFloat() }
            val lastVal = values.lastOrNull() ?: 0f
            val isPositive = lastVal >= 0
            val displayText = fmtSupplyValue(lastVal.toLong(), isValue)
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = label, color = TextMuted, fontSize = 10.sp)
                    Text(
                        text = displayText,
                        color = if (isPositive) UpColor else DownColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(2.dp))
                MiniSparkline(
                    values = values,
                    lineColor = lineColor,
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
            }
        }
    }
}

/** 원 단위 거래액 → 축약 표시 (±X.X억 / ±X.X조) */
private fun fmtSupplyValue(v: Long, isValue: Boolean): String {
    val sign = if (v >= 0) "+" else "-"
    val absV = abs(v.toDouble())
    return if (isValue) {
        val uk = absV / 100_000_000.0  // 원 → 억
        when {
            uk >= 10_000 -> "${sign}${"%.1f".format(uk / 10_000)}조"
            uk >= 10 -> "${sign}${"%.0f".format(uk)}억"
            else -> "${sign}${"%.1f".format(uk)}억"
        }
    } else {
        val mk = absV / 10_000.0
        "${sign}${"%.0f".format(mk)}만주"
    }
}

@Composable
private fun MiniSparkline(
    values: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    if (values.size < 2) return
    val maxVal = values.maxOfOrNull { abs(it) }?.coerceAtLeast(1f) ?: 1f
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val step = w / (values.size - 1).coerceAtLeast(1)
        // 0 기준선
        drawLine(
            color = Color(0xFFE0E0E0),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1f,
        )
        // 스파크라인 path
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = midY - (v / maxVal) * (h * 0.4f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2f, cap = StrokeCap.Round))
        // 마지막 점
        val lastX = (values.size - 1) * step
        val lastY = midY - (values.last() / maxVal) * (h * 0.4f)
        drawCircle(color = lineColor, radius = 3f, center = Offset(lastX, lastY))
    }
}

// ─────────────────────────────────────────────────
// 0. 한줄 브리핑
// ─────────────────────────────────────────────────
@Composable
private fun BriefingCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2332)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────
// 시장 온도계
// ─────────────────────────────────────────────────
@Composable
private fun MarketTemperatureBar(temperature: com.example.stock.data.api.MarketTemperatureDto) {
    val score = temperature.score ?: 5
    val label = temperature.label ?: "중립"
    val fraction = score / 10f
    val barColor = when {
        score <= 3 -> BluePrimary
        score <= 6 -> Color(0xFFFFA726)
        else -> UpColor
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "시장 온도", color = TextMuted, fontSize = 12.sp)
            Text(
                text = "$label ($score/10)",
                color = barColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(color = Color(0xFFE8EAF0), shape = RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(color = barColor, shape = RoundedCornerShape(4.dp)),
            )
        }
    }
}

// ─────────────────────────────────────────────────
// 실시간 매매 피드
// ─────────────────────────────────────────────────
@Composable
private fun TradeFeedCard(items: List<com.example.stock.data.api.TradeFeedItemDto>) {
    HomeSectionCard(title = "오늘의 매매") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                val isBuy = item.side == "BUY"
                val sideLabel = if (isBuy) "매수" else "매도"
                val sideColor = if (isBuy) UpColor else BluePrimary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.time ?: "--:--",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.width(40.dp),
                    )
                    Text(
                        text = item.name ?: item.ticker ?: "",
                        color = TextMain,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$sideLabel ${item.qty ?: 0}주",
                        color = sideColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (item.pnl != null) {
                        Spacer(Modifier.width(8.dp))
                        val pnlSign = if (item.pnl >= 0) "+" else ""
                        Text(
                            text = "${pnlSign}%,.0f원".format(item.pnl),
                            color = if (item.pnl >= 0) UpColor else BluePrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
// 수익 캘린더
// ─────────────────────────────────────────────────
@Composable
private fun PnlCalendarCard(calendar: com.example.stock.data.api.PnlCalendarResponseDto) {
    val days = calendar.days.orEmpty()
    val monthPnl = calendar.monthTotalPnl ?: 0.0
    val monthCount = calendar.monthTradeCount ?: 0
    val pnlColor = if (monthPnl >= 0) UpColor else BluePrimary
    val pnlSign = if (monthPnl >= 0) "+" else ""

    HomeSectionCard(title = "이달의 수익") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("월 누적", color = TextMuted, fontSize = 11.sp)
                Text(
                    text = "${pnlSign}%,.0f원".format(monthPnl),
                    color = pnlColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("거래", color = TextMuted, fontSize = 11.sp)
                Text(
                    text = "${monthCount}건",
                    color = TextMain,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            days.forEach { day ->
                val dayPnl = day.pnl ?: 0.0
                val chipColor = when {
                    dayPnl > 0 -> GreenColor
                    dayPnl < 0 -> UpColor
                    else -> Color(0xFFE8EAF0)
                }
                val alpha = when {
                    abs(dayPnl) > 100000 -> 1f
                    abs(dayPnl) > 10000 -> 0.7f
                    dayPnl != 0.0 -> 0.4f
                    else -> 0.15f
                }
                val dayNum = day.date?.takeLast(2) ?: ""
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(
                                color = chipColor.copy(alpha = alpha),
                                shape = RoundedCornerShape(4.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = dayNum,
                            color = if (dayPnl != 0.0) Color.White else TextMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
