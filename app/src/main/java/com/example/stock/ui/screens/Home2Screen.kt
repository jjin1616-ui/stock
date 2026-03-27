package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.AutoTradeAccountPositionDto
import com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto
import com.example.stock.data.api.AutoTradePerformanceItemDto
import com.example.stock.data.api.DaytradeTopItemDto
import com.example.stock.data.api.LongtermItemDto
import com.example.stock.data.api.NewsClusterListItemDto
import com.example.stock.data.api.DividendItemDto
import com.example.stock.data.api.TradeFeedItemDto
import com.example.stock.data.api.TradeFeedSummaryDto
import com.example.stock.data.api.VolumeSurgeItemDto
import com.example.stock.data.api.WeekExtremeItemDto
import com.example.stock.data.api.WeekExtremeResponseDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.CommonReportItemCard
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.theme.BluePrimary
import com.example.stock.ui.theme.CoralAccent
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.Home2ViewModel
import com.example.stock.viewmodel.InvestorFlowSummary
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home2Screen() {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: Home2ViewModel = viewModel(factory = AppViewModelFactory(repo))

    LaunchedEffect(Unit) { vm.load() }
    DisposableEffect(Unit) { onDispose { vm.stopPolling() } }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "홈2",
                showRefresh = true,
                onRefresh = { vm.load() },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 0. 한줄 브리핑
            vm.briefingState.value?.let { briefing ->
                if (briefing.isNotBlank()) {
                    item(key = "briefing") { BriefingBanner(text = briefing) }
                }
            }

            // 1. 내 계좌
            vm.accountState.value?.let { account ->
                item(key = "account") { AccountPositionsCard(account = account) }
            }
            vm.sectionErrorState["account"]?.let { err ->
                item(key = "account_error") { HomeSectionCard(title = "내 계좌", error = err) {} }
            }

            // 2. 자동매매 상태
            item(key = "autotrade") {
                AutoTradeStatusCard2(
                    enabled = vm.autoTradeEnabledState.value,
                    environment = vm.autoTradeEnvState.value,
                    performance = vm.performanceState.value,
                    regimeMode = vm.regimeModeState.value,
                    reservationCount = vm.reservationCountState.value,
                )
            }

            // 2b. 매매 피드 요약
            val feedItems = vm.tradeFeedState.value
            val feedSummary = vm.tradeFeedSummaryState.value
            if (feedItems.isNotEmpty() || feedSummary != null) {
                item(key = "trade_feed") {
                    TradeFeedSummaryCard(items = feedItems.take(10), summary = feedSummary)
                }
            }

            // 3. 시장 지수
            vm.liveIndicesState.value?.let { indices ->
                item(key = "market_indices") {
                    MarketIndicesCard(
                        indices = indices,
                        regimeMode = vm.regimeModeState.value,
                        marketTemperature = vm.marketTemperatureState.value,
                    )
                }
            }

            // 4. 업종별 등락 히트맵
            if (vm.sectorHeatmapState.value.isNotEmpty()) {
                item(key = "sector_heatmap") { SectorHeatmapCard(vm.sectorHeatmapState.value) }
            }

            // 5. 거래량 급등
            if (vm.volumeSurgeState.value.isNotEmpty()) {
                item(key = "volume_surge") { VolumeSurgeCard(vm.volumeSurgeState.value) }
            }

            // 5b. 52주 신고가/신저가
            vm.weekExtremeState.value?.let { extremes ->
                item(key = "week_extremes") { WeekExtremesCard(data = extremes) }
            }

            // 6. 오늘의 추천
            vm.premarketState.value.data?.let { data ->
                val daytradeTop = data.daytradeTop.orEmpty().take(3)
                val longtermTop = data.longterm.orEmpty().take(3)
                if (daytradeTop.isNotEmpty() || longtermTop.isNotEmpty()) {
                    item(key = "recommendations") {
                        RecommendationCard(
                            daytradeTop = daytradeTop,
                            longtermTop = longtermTop,
                        )
                    }
                }
            }

            // 7. 투자자 수급
            vm.investorFlowState.value?.let { flow ->
                item(key = "investor_flow") {
                    HomeSectionCard(title = "투자자 수급 현황") {
                        InvestorFlowCard2(flow = flow)
                    }
                }
            }

            // 8. 관심 종목
            val favorites = vm.favoritesState.value
            if (favorites.isNotEmpty()) {
                item(key = "favorites") {
                    FavoritesSection(
                        favorites = favorites,
                        quotes = vm.quoteState,
                        miniCharts = vm.miniChartState,
                    )
                }
            }

            // 9. 배당/권리락 일정
            item(key = "dividends") { DividendCard(vm.dividendState.value) }

            // 10. 수익 캘린더
            vm.pnlCalendarState.value?.let { calendar ->
                val monthPnl = calendar.monthTotalPnl ?: 0.0
                val monthCount = calendar.monthTradeCount ?: 0
                item(key = "pnl_calendar") {
                    HomeSectionCard(title = "이달의 수익") {
                        val pnlColor = if (monthPnl >= 0) UpColor else DownColor
                        val pnlSign = if (monthPnl >= 0) "+" else ""
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("월 누적", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${pnlSign}%,.0f원".format(monthPnl),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace, color = pnlColor)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("거래", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${monthCount}건",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                }
            }

            // 11. 주요 뉴스
            val newsClusters = vm.newsClustersState.value
            if (newsClusters.isNotEmpty()) {
                item(key = "news") {
                    NewsSection(clusters = newsClusters)
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ── Utility ──────────────────────────────────────────────────────────────────

private val UpColor = Color(0xFFD32F2F)
private val DownColor = Color(0xFF1565C0)
private val FlatColor = Color.Gray
private val GreenColor = Color(0xFF00B894)

@Composable
private fun pnlColor(value: Double?): Color {
    if (value == null) return FlatColor
    return when {
        value > 0 -> UpColor
        value < 0 -> DownColor
        else -> FlatColor
    }
}

private fun formatKrw(value: Double?): String {
    if (value == null) return "-"
    val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.KOREA)
    return "${fmt.format(value.toLong())}원"
}

private fun formatPrice(value: Double?): String {
    if (value == null) return "-"
    val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale.KOREA)
    return "${fmt.format(value.toLong())}원"
}

private fun formatPct(value: Double?): String {
    if (value == null) return "-"
    val sign = if (value > 0) "+" else ""
    return "${sign}${String.format("%.2f", value)}%"
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun HomeSectionCard(
    title: String,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else {
                content()
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            label,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun MetricItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

// ── Briefing ──────────────────────────────────────────────────────────────────

@Composable
private fun BriefingBanner(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Account + Positions ───────────────────────────────────────────────────────

@Composable
private fun AccountPositionsCard(account: AutoTradeAccountSnapshotResponseDto) {
    var expanded by remember { mutableStateOf(true) }
    val positions = account.positions ?: emptyList()
    val hasPositions = positions.isNotEmpty()

    HomeSectionCard(title = "내 계좌") {
        // Top row: total asset + PnL
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("총 평가자산", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatKrw(account.totalAssetKrw), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("평가손익", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val pnl = account.realEvalPnlKrw ?: 0.0
                val pnlPct = account.realEvalPnlPct ?: 0.0
                Text(
                    "${formatKrw(pnl)} (${formatPct(pnlPct)})",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = pnlColor(pnl),
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Mid row: cash + orderable
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabelValue("예수금", formatKrw(account.cashKrw))
            LabelValue("매수가능", formatKrw(account.orderableCashKrw))
        }
        // Positions list
        if (hasPositions) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("보유 종목 ${positions.size}개", style = MaterialTheme.typography.labelMedium)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                positions.forEach { pos -> PositionRow(pos) }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text("보유 종목 없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PositionRow(pos: AutoTradeAccountPositionDto) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                StockDetailActivity.open(
                    context,
                    ticker = pos.ticker ?: "",
                    name = pos.name ?: "",
                    origin = "home2_positions",
                    eventTags = emptyList(),
                )
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                pos.name ?: pos.ticker ?: "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${pos.qty ?: 0}주 · 평단 ${formatPrice(pos.avgPrice)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val pnl = pos.pnlAmountKrw ?: 0.0
            Text(
                formatKrw(pnl),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = pnlColor(pnl),
                ),
            )
            Text(
                formatPct(pos.pnlPct),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = pnlColor(pos.pnlPct),
                ),
            )
        }
    }
}

// ── AutoTrade Status Card ──────────────────────────────────────────────────────

@Composable
private fun AutoTradeStatusCard2(
    enabled: Boolean?,
    environment: String?,
    performance: AutoTradePerformanceItemDto?,
    regimeMode: String?,
    reservationCount: Int,
) {
    val gateOn = regimeMode != null && regimeMode != "RISK_OFF"
    HomeSectionCard(title = "자동매매") {
        if (enabled == null) {
            Text("자동매매 설정을 불러오는 중...",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            return@HomeSectionCard
        }
        // Status chips row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                label = if (enabled) "실행중" else "중지",
                color = if (enabled) GreenColor else Color.Gray,
            )
            if (environment != null) {
                StatusChip(
                    label = if (environment == "prod") "실전" else "DEMO",
                    color = if (environment == "prod") UpColor else Color.Gray,
                )
            }
            StatusChip(
                label = if (gateOn) "진입 허용" else "진입 차단",
                color = if (gateOn) GreenColor else UpColor,
            )
        }
        // Performance metrics
        if (performance != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MetricItem(label = "수익률", value = "%.1f%%".format(performance.roiPct ?: 0.0))
                MetricItem(label = "승률", value = "%.0f%%".format((performance.winRate ?: 0.0) * 100.0))
                MetricItem(label = "MDD", value = "%.1f%%".format(performance.mddPct ?: 0.0))
                MetricItem(label = "주문 건수", value = "${performance.filledTotal ?: 0}건")
            }
        }
        // Reservation count
        if (reservationCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "예약 대기 ${reservationCount}건",
                style = MaterialTheme.typography.labelSmall,
                color = BluePrimary,
            )
        }
    }
}

// ── Trade Feed Summary ─────────────────────────────────────────────────────────

@Composable
private fun TradeFeedSummaryCard(
    items: List<TradeFeedItemDto>,
    summary: TradeFeedSummaryDto?,
) {
    var expanded by remember { mutableStateOf(false) }
    HomeSectionCard(title = "오늘의 매매") {
        // Summary row
        if (summary != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "총 체결 ${summary.totalCount ?: 0}건",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val pnl = summary.realizedPnl ?: 0.0
                val pnlSign = if (pnl >= 0) "+" else ""
                Text(
                    "실현손익 ${pnlSign}%,.0f원".format(pnl),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = if (pnl >= 0) UpColor else DownColor,
                    ),
                )
                Text(
                    "매수/매도 ${summary.buyCount ?: 0}/${summary.sellCount ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        // Expand/collapse trade list
        if (items.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("체결 내역", style = MaterialTheme.typography.labelMedium)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items.forEach { item -> TradeFeedRow(item) }
                }
            }
        }
    }
}

@Composable
private fun TradeFeedRow(item: TradeFeedItemDto) {
    val isBuy = item.side == "BUY"
    val sideLabel = if (isBuy) "매수" else "매도"
    val sideColor = if (isBuy) UpColor else DownColor
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.time ?: "--:--",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            item.name ?: item.ticker ?: "",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$sideLabel ${item.qty ?: 0}주",
            style = MaterialTheme.typography.labelSmall.copy(color = sideColor, fontWeight = FontWeight.Bold),
        )
        if (item.pnl != null) {
            Spacer(Modifier.width(8.dp))
            val pnlSign = if (item.pnl >= 0) "+" else ""
            Text(
                "${pnlSign}%,.0f원".format(item.pnl),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = if (item.pnl >= 0) UpColor else DownColor,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

// ── Sector Heatmap ─────────────────────────────────────────────────────────────

@Composable
private fun SectorHeatmapCard(sectors: List<com.example.stock.data.api.SectorItemDto>) {
    HomeSectionCard(title = "업종별 등락") {
        val chunked = sectors.take(15).chunked(3)
        chunked.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { sector ->
                    val pct = sector.changePct ?: 0.0
                    val bgColor = when {
                        pct > 1.0 -> Color(0xFFD32F2F).copy(alpha = 0.8f)
                        pct > 0.5 -> Color(0xFFD32F2F).copy(alpha = 0.5f)
                        pct > 0.0 -> Color(0xFFD32F2F).copy(alpha = 0.25f)
                        pct < -1.0 -> Color(0xFF1565C0).copy(alpha = 0.8f)
                        pct < -0.5 -> Color(0xFF1565C0).copy(alpha = 0.5f)
                        pct < 0.0 -> Color(0xFF1565C0).copy(alpha = 0.25f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(6.dp),
                        color = bgColor,
                    ) {
                        Column(
                            Modifier.padding(6.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                sector.name ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White,
                            )
                            Text(
                                formatPct(pct),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = Color.White,
                            )
                        }
                    }
                }
                // Fill remaining cells if row has < 3 items
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ── Market Indices ─────────────────────────────────────────────────────────────

@Composable
private fun MarketIndicesCard(
    indices: com.example.stock.data.api.MarketIndicesResponseDto,
    regimeMode: String?,
    marketTemperature: com.example.stock.data.api.MarketTemperatureDto?,
) {
    HomeSectionCard(title = "시장 지표") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IndexValueItem(label = "코스피", value = indices.kospi?.value, changePct = indices.kospi?.changePct, modifier = Modifier.weight(1f))
            IndexValueItem(label = "코스닥", value = indices.kosdaq?.value, changePct = indices.kosdaq?.changePct, modifier = Modifier.weight(1f))
        }
        indices.usdkrw?.let { usd ->
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IndexValueItem(label = "원/달러", value = usd.value, changePct = usd.changePct, modifier = Modifier.weight(1f))
                Spacer(Modifier.weight(1f))
            }
        }
        if (regimeMode != null) {
            Spacer(Modifier.height(8.dp))
            RegimeBadge2(mode = regimeMode)
        }
        if (marketTemperature != null) {
            Spacer(Modifier.height(8.dp))
            val score = marketTemperature.score ?: 5
            val label = marketTemperature.label ?: "중립"
            val barColor = when {
                score <= 3 -> DownColor
                score <= 6 -> Color(0xFFFFA726)
                else -> UpColor
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("시장 온도", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$label ($score/10)", style = MaterialTheme.typography.labelSmall.copy(color = barColor, fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0xFFE8EAF0), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(score / 10f).height(6.dp).background(barColor, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
private fun IndexValueItem(label: String, value: Double?, changePct: Double?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                if (value != null) "%,.2f".format(value) else "--",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            )
            if (changePct != null) {
                val sign = if (changePct >= 0) "+" else ""
                Text(
                    "${sign}${"%.2f".format(changePct)}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (changePct >= 0) UpColor else DownColor,
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RegimeBadge2(mode: String) {
    val (bgColor, textColor, label) = when (mode.uppercase()) {
        "BULL", "LOW_VOL_UP", "HIGH_VOL_UP" -> Triple(GreenColor.copy(alpha = 0.12f), GreenColor, "강세장")
        "BEAR", "LOW_VOL_FLAT", "HIGH_VOL_DOWN" -> Triple(UpColor.copy(alpha = 0.12f), UpColor, "약세장")
        else -> Triple(Color(0xFFF0F1F4), Color.Gray, "중립")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("시장 체제", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(color = textColor, fontWeight = FontWeight.Bold),
            modifier = Modifier.background(bgColor, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

// ── Recommendations ───────────────────────────────────────────────────────────

@Composable
private fun RecommendationCard(
    daytradeTop: List<DaytradeTopItemDto>,
    longtermTop: List<LongtermItemDto>,
) {
    val context = LocalContext.current
    HomeSectionCard(title = "오늘의 추천") {
        if (daytradeTop.isNotEmpty()) {
            Text("단타 TOP", style = MaterialTheme.typography.labelSmall.copy(color = BluePrimary, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(6.dp))
            daytradeTop.forEach { stock ->
                RecommendRow(
                    name = stock.name.orEmpty(),
                    thesis = stock.thesis,
                    onClick = {
                        StockDetailActivity.open(context, stock.ticker.orEmpty(), stock.name.orEmpty(), "home2", emptyList())
                    },
                )
            }
        }
        if (daytradeTop.isNotEmpty() && longtermTop.isNotEmpty()) Spacer(Modifier.height(10.dp))
        if (longtermTop.isNotEmpty()) {
            Text("장타 TOP", style = MaterialTheme.typography.labelSmall.copy(color = CoralAccent, fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(6.dp))
            longtermTop.forEach { stock ->
                RecommendRow(
                    name = stock.name.orEmpty(),
                    thesis = stock.thesis,
                    onClick = {
                        StockDetailActivity.open(context, stock.ticker.orEmpty(), stock.name.orEmpty(), "home2", emptyList())
                    },
                )
            }
        }
    }
}

@Composable
private fun RecommendRow(name: String, thesis: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.width(80.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            thesis.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Investor Flow ─────────────────────────────────────────────────────────────

@Composable
private fun InvestorFlowCard2(flow: InvestorFlowSummary) {
    val isValue = flow.unit == "value"
    val todayFlow = flow.dailyFlow.lastOrNull()
    val entries3d = listOf(
        "외국인" to flow.foreign,
        "기관" to flow.institution,
        "개인" to flow.individual,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (todayFlow != null) {
            val todayKst = LocalDate.now(ZoneId.of("Asia/Seoul"))
            val flowDate = runCatching { LocalDate.parse(todayFlow.date) }.getOrNull()
            val todayLabel = if (flowDate == todayKst) "오늘 (실시간)" else {
                flowDate?.format(DateTimeFormatter.ofPattern("MM/dd")) ?: todayFlow.date.takeLast(5).replace("-", "/")
            }
            FlowItem(label = "외국인", value = todayFlow.foreign, isValue = isValue, header = todayLabel)
            FlowItem(label = "기관", value = todayFlow.institution, isValue = isValue)
            FlowItem(label = "개인", value = todayFlow.individual, isValue = isValue)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        }
        Text("3일 누적", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        entries3d.forEach { (label, value) ->
            FlowItem(label = label, value = value, isValue = isValue)
        }
    }
}

@Composable
private fun FlowItem(label: String, value: Long, isValue: Boolean, header: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (header != null) {
            Text(header, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(70.dp))
        } else {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
        }
        val color = if (value >= 0) UpColor else DownColor
        val sign = if (value >= 0) "+" else ""
        val displayText = if (isValue) {
            val billions = value / 100_000_000.0
            "${sign}${"%.1f".format(billions)}억"
        } else {
            "${sign}${"%,d".format(value)}주"
        }
        Text(
            displayText,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = color, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Favorites ─────────────────────────────────────────────────────────────────

@Composable
private fun FavoritesSection(
    favorites: List<com.example.stock.data.api.FavoriteItemDto>,
    quotes: Map<String, com.example.stock.data.api.RealtimeQuoteItemDto>,
    miniCharts: Map<String, List<com.example.stock.data.api.ChartPointDto>>,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) favorites else favorites.take(10)
    HomeSectionCard(title = "관심 종목") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            visible.forEach { fav ->
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
                        StockDetailActivity.open(context, ticker, fav.name.orEmpty(), "home2", emptyList())
                    },
                )
            }
            if (!expanded && favorites.size > 10) {
                Text(
                    "더보기 (+${favorites.size - 10}개)",
                    style = MaterialTheme.typography.labelSmall.copy(color = BluePrimary, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 8.dp),
                )
            }
        }
    }
}

// ── News ──────────────────────────────────────────────────────────────────────

@Composable
private fun NewsSection(clusters: List<NewsClusterListItemDto>) {
    HomeSectionCard(title = "주요 뉴스") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            clusters.forEach { cluster ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        cluster.title.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row {
                        Text(
                            "${cluster.articleCount ?: 0}건",
                            style = MaterialTheme.typography.labelSmall.copy(color = BluePrimary),
                        )
                        if (!cluster.topTickers.isNullOrEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                cluster.topTickers.take(3).joinToString(", "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Volume Surge ──────────────────────────────────────────────────────────────

@Composable
private fun VolumeSurgeCard(items: List<VolumeSurgeItemDto>) {
    HomeSectionCard(title = "거래량 급등") {
        items.take(5).forEach { item ->
            val context = LocalContext.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        StockDetailActivity.open(
                            context, item.ticker ?: "", item.name ?: "", "home2_volume", emptyList()
                        )
                    }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.ticker ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatPrice(item.price),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(
                        formatPct(item.changePct),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = pnlColor(item.changePct),
                        ),
                    )
                }
            }
        }
    }
}

// ── 52-week Extremes ──────────────────────────────────────────────────────────

@Composable
private fun WeekExtremesCard(data: WeekExtremeResponseDto) {
    val highs = data.highs ?: emptyList()
    val lows = data.lows ?: emptyList()
    if (highs.isEmpty() && lows.isEmpty()) return

    HomeSectionCard(title = "52주 신고가/신저가") {
        if (highs.isNotEmpty()) {
            Text("신고가", style = MaterialTheme.typography.labelMedium, color = UpColor)
            highs.take(5).forEach { item ->
                ExtremeRow(item, isHigh = true)
            }
        }
        if (lows.isNotEmpty()) {
            if (highs.isNotEmpty()) Spacer(Modifier.height(8.dp))
            Text("신저가", style = MaterialTheme.typography.labelMedium, color = DownColor)
            lows.take(5).forEach { item ->
                ExtremeRow(item, isHigh = false)
            }
        }
    }
}

@Composable
private fun ExtremeRow(item: WeekExtremeItemDto, isHigh: Boolean) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                StockDetailActivity.open(
                    context, item.ticker ?: "", item.name ?: "", "home2_52week", emptyList()
                )
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            item.name ?: "",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatPrice(item.price),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = if (isHigh) UpColor else DownColor,
            ),
        )
    }
}

// ── Dividend ──────────────────────────────────────────────────────────────────

@Composable
private fun DividendCard(items: List<DividendItemDto>) {
    HomeSectionCard(title = "배당/권리락 일정") {
        if (items.isEmpty()) {
            Text(
                "예정된 배당 일정이 없습니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            items.take(5).forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "권리락 ${item.exDate ?: "-"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${formatPrice(item.dividendPerShare)}/주",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        Text(
                            "배당률 ${formatPct(item.dividendYield)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}
