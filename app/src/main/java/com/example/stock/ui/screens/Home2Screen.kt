package com.example.stock.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ── Design Tokens ────────────────────────────────────────────────────────────

private val H2Bg = Color(0xFFF7F8FA)
private val H2CardBg = Color(0xFFFFFFFF)
private val H2CardBorder = Color(0xFFE8EBF0)
private val H2CardRadius = 14.dp
private val H2SmallRadius = 8.dp
private val H2CardGap = 10.dp

private val H2TextPrimary = Color(0xFF1A1D26)
private val H2TextSecondary = Color(0xFF5A6070)
private val H2TextTertiary = Color(0xFF8E95A4)

private val H2Up = Color(0xFFD32F2F)
private val H2UpBg = Color(0x0FD32F2F)
private val H2Down = Color(0xFF1565C0)
private val H2DownBg = Color(0x0F1565C0)
private val H2Flat = Color(0xFF8E95A4)
private val H2Accent = Color(0xFF2D5BFF)
private val H2AccentBg = Color(0x0F2D5BFF)
private val H2Green = Color(0xFF00897B)
private val H2GreenBg = Color(0x1400897B)
private val H2Orange = Color(0xFFE65100)
private val H2OrangeBg = Color(0x14E65100)
private val H2Divider = Color(0xFFF0F1F4)

private val H2BriefingBg = Color(0xFFEEF3FF)
private val H2BriefingBorder = Color(0xFFD4DFFF)
private val H2BriefingText = Color(0xFF2D4175)

private val H2CellBg = Color(0xFFF7F8FA)

// ── Main Screen ──────────────────────────────────────────────────────────────

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
        },
        containerColor = H2Bg,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(H2CardGap),
        ) {
            // 0. Briefing
            vm.briefingState.value?.let { briefing ->
                if (briefing.isNotBlank()) {
                    item(key = "briefing") { BriefingBanner(text = briefing) }
                }
            }

            // 1. Account
            vm.accountState.value?.let { account ->
                item(key = "account") { AccountPositionsCard(account = account) }
            }
            vm.sectionErrorState["account"]?.let { err ->
                item(key = "account_error") { HomeSectionCard(title = "내 계좌", error = err) {} }
            }

            // 2. AutoTrade
            item(key = "autotrade") {
                AutoTradeStatusCard2(
                    enabled = vm.autoTradeEnabledState.value,
                    environment = vm.autoTradeEnvState.value,
                    performance = vm.performanceState.value,
                    regimeMode = vm.regimeModeState.value,
                    reservationCount = vm.reservationCountState.value,
                )
            }

            // 2b. Trade Feed
            val feedItems = vm.tradeFeedState.value
            val feedSummary = vm.tradeFeedSummaryState.value
            if (feedItems.isNotEmpty() || feedSummary != null) {
                item(key = "trade_feed") {
                    TradeFeedSummaryCard(items = feedItems.take(10), summary = feedSummary)
                }
            }

            // 3. Market Indices
            vm.liveIndicesState.value?.let { indices ->
                item(key = "market_indices") {
                    MarketIndicesCard(
                        indices = indices,
                        regimeMode = vm.regimeModeState.value,
                        marketTemperature = vm.marketTemperatureState.value,
                    )
                }
            }

            // 4. Sector Heatmap
            if (vm.sectorHeatmapState.value.isNotEmpty()) {
                item(key = "sector_heatmap") { SectorHeatmapCard(vm.sectorHeatmapState.value) }
            }

            // 5. Volume Surge
            if (vm.volumeSurgeState.value.isNotEmpty()) {
                item(key = "volume_surge") { VolumeSurgeCard(vm.volumeSurgeState.value) }
            }

            // 5b. 52-week extremes
            vm.weekExtremeState.value?.let { extremes ->
                item(key = "week_extremes") { WeekExtremesCard(data = extremes) }
            }

            // 6. Recommendations
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

            // 7. Investor Flow
            vm.investorFlowState.value?.let { flow ->
                item(key = "investor_flow") {
                    HomeSectionCard(title = "투자자 수급 현황") {
                        InvestorFlowCard2(flow = flow)
                    }
                }
            }

            // 8. Favorites
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

            // 9. Dividends
            item(key = "dividends") { DividendCard(vm.dividendState.value) }

            // 10. PnL Calendar
            vm.pnlCalendarState.value?.let { calendar ->
                val monthPnl = calendar.monthTotalPnl ?: 0.0
                val monthCount = calendar.monthTradeCount ?: 0
                item(key = "pnl_calendar") {
                    HomeSectionCard(title = "이달의 수익") {
                        val pColor = h2PnlColor(monthPnl)
                        val pnlSign = if (monthPnl >= 0) "+" else ""
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("월 누적", style = MaterialTheme.typography.labelSmall, color = H2TextTertiary)
                                Text(
                                    "${pnlSign}%,.0f원".format(monthPnl),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = FontFamily.Monospace, color = pColor),
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("거래", style = MaterialTheme.typography.labelSmall, color = H2TextTertiary)
                                Text(
                                    "${monthCount}건",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace, color = H2TextPrimary),
                                )
                            }
                        }
                    }
                }
            }

            // 11. News
            val newsClusters = vm.newsClustersState.value
            if (newsClusters.isNotEmpty()) {
                item(key = "news") { NewsSection(clusters = newsClusters) }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

// ── Utility ──────────────────────────────────────────────────────────────────

@Composable
private fun h2PnlColor(value: Double?): Color {
    if (value == null) return H2Flat
    return when {
        value > 0 -> H2Up
        value < 0 -> H2Down
        else -> H2Flat
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

// 엔진 raw 텍스트에서 게이트/숫자 패턴 제거 후 정규화
private fun normalizeThesis(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var s = raw
    // 게이트 텍스트 제거: "게이트OFF", "게이트ON", "게이트 OFF", "게이트 ON"
    s = s.replace(Regex("게이트\\s*O(N|FF)"), "")
    // 괄호 안의 +/-숫자(소수점 포함) 패턴 제거: (+0.000), (-1.23)
    s = s.replace(Regex("\\(\\s*[+\\-]?\\d+\\.\\d+\\s*\\)"), "")
    // 조건부 진입 → 조건부 진입으로 그대로 유지 (간결하게)
    // "분할/소액 검토" → "분할매수"
    s = s.replace("분할/소액 검토", "분할매수")
    // 중복 공백, 앞뒤 공백, 앞뒤 · 제거
    s = s.replace(Regex("\\s{2,}"), " ")
    s = s.replace(Regex("^[\\s·]+|[\\s·]+$"), "")
    return s.trim()
}

// ── HomeSectionCard — Styled Card Wrapper ────────────────────────────────────

@Composable
private fun HomeSectionCard(
    title: String,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = H2CardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                title,
                color = H2TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            if (error != null) {
                Text(error, color = H2Up, style = MaterialTheme.typography.bodySmall)
            } else {
                content()
            }
        }
    }
}

// ── StatusChip ───────────────────────────────────────────────────────────────

@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = color,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

// ── Briefing ─────────────────────────────────────────────────────────────────

@Composable
private fun BriefingBanner(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, H2BriefingBorder, RoundedCornerShape(H2CardRadius)),
        shape = RoundedCornerShape(H2CardRadius),
        color = H2BriefingBg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // AI circle icon
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(H2Accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "AI",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    ),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(color = H2BriefingText),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Account + Positions ──────────────────────────────────────────────────────

@Composable
private fun AccountPositionsCard(account: AutoTradeAccountSnapshotResponseDto) {
    var expanded by remember { mutableStateOf(false) }
    val positions = account.positions ?: emptyList()
    val hasPositions = positions.isNotEmpty()

    HomeSectionCard(title = "내 계좌") {
        // ── 홈과 동일한 상단 레이아웃 ─────────────────────────────────────────
        val totalAsset = account.totalAssetKrw ?: 0.0
        val unrealizedPnl = account.unrealizedPnlKrw ?: 0.0
        val pnlPct = account.realEvalPnlPct ?: 0.0
        val cash = account.cashKrw ?: 0.0
        val pnlColor = h2PnlColor(unrealizedPnl)
        val pnlSign = if (unrealizedPnl >= 0) "+" else ""

        // 총 평가자산
        Text("총 평가자산", color = H2TextTertiary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "%,.0f원".format(totalAsset),
            color = H2TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))

        // 평가손익 + 현금 (좌우 배치)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("평가손익", color = H2TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${pnlSign}%,.0f원 (${pnlSign}%.1f%%)".format(unrealizedPnl, pnlPct),
                    color = pnlColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("현금", color = H2TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "%,.0f원".format(cash),
                    color = H2TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // 보유 종목 수
        val posCount = positions.size
        if (posCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text("보유 ${posCount}종목", color = H2TextTertiary, fontSize = 11.sp)
        }

        // ── 홈2 전용 추가 영역 ─────────────────────────────────────────────────
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = H2Divider, thickness = 1.dp)
        Spacer(Modifier.height(12.dp))

        // Cash boxes
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CashBox(label = "예수금", value = formatKrw(account.cashKrw), modifier = Modifier.weight(1f))
            CashBox(label = "매수가능", value = formatKrw(account.orderableCashKrw), modifier = Modifier.weight(1f))
        }
        // Position toggle
        if (hasPositions) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "보유 종목 ${positions.size}개",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = H2TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = H2TextTertiary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                positions.forEachIndexed { idx, pos ->
                    if (idx > 0) {
                        HorizontalDivider(color = H2Divider, thickness = 1.dp)
                    }
                    PositionRow(pos)
                }
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text("보유 종목 없음", style = MaterialTheme.typography.bodySmall, color = H2TextTertiary)
        }
    }
}

@Composable
private fun CashBox(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(H2CellBg, RoundedCornerShape(H2SmallRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = H2TextTertiary)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = H2TextPrimary,
                ),
            )
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
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                pos.name ?: pos.ticker ?: "",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = H2TextPrimary,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${pos.qty ?: 0}주 · 평단 ${formatPrice(pos.avgPrice)}",
                style = MaterialTheme.typography.labelSmall.copy(color = H2TextTertiary),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val pnl = pos.pnlAmountKrw ?: 0.0
            Text(
                formatKrw(pnl),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = h2PnlColor(pnl),
                ),
            )
            Text(
                formatPct(pos.pnlPct),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = h2PnlColor(pos.pnlPct),
                ),
            )
        }
    }
}

// ── AutoTrade Status Card ────────────────────────────────────────────────────

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
            Text(
                "자동매매 설정을 불러오는 중...",
                color = H2TextTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
            return@HomeSectionCard
        }
        // Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(
                label = if (enabled) "실행중" else "중지",
                color = if (enabled) H2Green else H2Flat,
            )
            if (environment != null) {
                StatusChip(
                    label = if (environment == "prod") "실전" else "DEMO",
                    color = if (environment == "prod") H2Up else H2Accent,
                )
            }
            StatusChip(
                label = if (gateOn) "진입 허용" else "진입 차단",
                color = if (gateOn) H2Green else H2Up,
            )
        }
        // Metrics 4-col grid
        if (performance != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(H2CellBg, RoundedCornerShape(H2SmallRadius))
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MetricCell(label = "수익률", value = "%.1f%%".format(performance.roiPct ?: 0.0))
                MetricCell(label = "승률", value = "%.0f%%".format((performance.winRate ?: 0.0) * 100.0))
                MetricCell(label = "MDD", value = "%.1f%%".format(performance.mddPct ?: 0.0))
                MetricCell(label = "주문", value = "${performance.filledTotal ?: 0}건")
            }
        }
        // Reservation
        if (reservationCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                "예약 대기 ${reservationCount}건",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = H2Accent,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(color = H2TextTertiary),
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = H2TextPrimary,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

// ── Trade Feed Summary ───────────────────────────────────────────────────────

@Composable
private fun TradeFeedSummaryCard(
    items: List<TradeFeedItemDto>,
    summary: TradeFeedSummaryDto?,
) {
    var expanded by remember { mutableStateOf(false) }
    HomeSectionCard(title = "오늘의 매매") {
        // Summary row
        if (summary != null) {
            val pnl = summary.realizedPnl ?: 0.0
            val pnlColor = h2PnlColor(pnl)
            val pnlSign = if (pnl >= 0) "+" else ""
            // Big PnL display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        "실현손익",
                        style = MaterialTheme.typography.labelSmall,
                        color = H2TextTertiary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${pnlSign}%,.0f원".format(pnl),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = pnlColor,
                        ),
                    )
                }
                // Count badge
                Box(
                    modifier = Modifier
                        .background(H2AccentBg, RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "체결 ${summary.totalCount ?: 0}건",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = H2Accent,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "매수 ${summary.buyCount ?: 0}건 / 매도 ${summary.sellCount ?: 0}건",
                style = MaterialTheme.typography.labelSmall.copy(color = H2TextTertiary),
            )
            Spacer(Modifier.height(10.dp))
        }
        // Expand toggle
        if (items.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "체결 내역",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = H2TextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = H2TextTertiary,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    val sideColor = if (isBuy) H2Up else H2Down
    val sideBg = if (isBuy) H2UpBg else H2DownBg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Time (monospace)
        Text(
            item.time ?: "--:--",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = H2TextTertiary,
            ),
            modifier = Modifier.width(42.dp),
        )
        // Side badge
        Box(
            modifier = Modifier
                .background(sideBg, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                sideLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = sideColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                ),
            )
        }
        Spacer(Modifier.width(6.dp))
        // Name
        Text(
            item.name ?: item.ticker ?: "",
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = H2TextPrimary,
            ),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Qty
        Text(
            "${item.qty ?: 0}주",
            style = MaterialTheme.typography.labelSmall.copy(
                color = H2TextSecondary,
                fontFamily = FontFamily.Monospace,
            ),
        )
        // PnL
        if (item.pnl != null) {
            Spacer(Modifier.width(8.dp))
            val pnlSign = if (item.pnl >= 0) "+" else ""
            Text(
                "${pnlSign}%,.0f원".format(item.pnl),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = h2PnlColor(item.pnl),
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

// ── Market Indices ───────────────────────────────────────────────────────────

@Composable
private fun MarketIndicesCard(
    indices: com.example.stock.data.api.MarketIndicesResponseDto,
    regimeMode: String?,
    marketTemperature: com.example.stock.data.api.MarketTemperatureDto?,
) {
    HomeSectionCard(title = "시장 지표") {
        // KOSPI / KOSDAQ 2-col
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IndexBox(label = "코스피", value = indices.kospi?.value, changePct = indices.kospi?.changePct, modifier = Modifier.weight(1f))
            IndexBox(label = "코스닥", value = indices.kosdaq?.value, changePct = indices.kosdaq?.changePct, modifier = Modifier.weight(1f))
        }
        // USD/KRW full-width
        indices.usdkrw?.let { usd ->
            Spacer(Modifier.height(8.dp))
            IndexBox(label = "원/달러", value = usd.value, changePct = usd.changePct, modifier = Modifier.fillMaxWidth())
        }
        // Regime badge
        if (regimeMode != null) {
            Spacer(Modifier.height(10.dp))
            RegimeBadge2(mode = regimeMode)
        }
        // Temperature bar
        if (marketTemperature != null) {
            Spacer(Modifier.height(10.dp))
            val score = marketTemperature.score ?: 5
            val label = marketTemperature.label ?: "중립"
            val barColor = when {
                score <= 3 -> H2Down
                score <= 6 -> H2Orange
                else -> H2Up
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("시장 온도", style = MaterialTheme.typography.labelSmall, color = H2TextTertiary)
                Text(
                    "$label ($score/10)",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = barColor,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(H2Divider, RoundedCornerShape(3.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(score / 10f)
                        .height(6.dp)
                        .background(barColor, RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

@Composable
private fun IndexBox(label: String, value: Double?, changePct: Double?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(H2CellBg, RoundedCornerShape(H2SmallRadius))
            .padding(12.dp),
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = H2TextTertiary)
            Spacer(Modifier.height(4.dp))
            Text(
                if (value != null) "%,.2f".format(value) else "--",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = H2TextPrimary,
                ),
            )
            if (changePct != null) {
                val arrow = if (changePct >= 0) "\u25B2" else "\u25BC"
                val sign = if (changePct >= 0) "+" else ""
                Text(
                    "$arrow ${sign}${"%.2f".format(changePct)}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (changePct >= 0) H2Up else H2Down,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RegimeBadge2(mode: String) {
    val (bgColor, textColor, label) = when (mode.uppercase()) {
        "BULL", "LOW_VOL_UP", "HIGH_VOL_UP" -> Triple(H2GreenBg, H2Green, "강세장")
        "BEAR", "LOW_VOL_FLAT", "HIGH_VOL_DOWN" -> Triple(H2UpBg, H2Up, "약세장")
        else -> Triple(Color(0x14808080), H2Flat, "중립")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("시장 체제", style = MaterialTheme.typography.labelSmall, color = H2TextTertiary)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = textColor,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier
                .background(bgColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

// ── Sector Heatmap (Treemap Style) ───────────────────────────────────────────

@Composable
private fun SectorHeatmapCard(sectors: List<com.example.stock.data.api.SectorItemDto>) {
    HomeSectionCard(title = "업종별 등락") {
        val items = sectors.take(15)
        if (items.isEmpty()) return@HomeSectionCard

        // Big cells (top 4) span wider, rest in grid
        val big = items.take(4)
        val rest = items.drop(4)

        // Top row: 2 big cells
        if (big.size >= 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                HeatmapCell(sector = big[0], modifier = Modifier.weight(1f).height(72.dp))
                HeatmapCell(sector = big[1], modifier = Modifier.weight(1f).height(72.dp))
            }
            Spacer(Modifier.height(2.dp))
        }
        // Second row: next 2 big cells
        if (big.size >= 4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                HeatmapCell(sector = big[2], modifier = Modifier.weight(1f).height(72.dp))
                HeatmapCell(sector = big[3], modifier = Modifier.weight(1f).height(72.dp))
            }
            Spacer(Modifier.height(2.dp))
        } else if (big.size == 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                HeatmapCell(sector = big[2], modifier = Modifier.weight(1f).height(72.dp))
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(2.dp))
        }
        // Remaining: chunked in rows of 3, smaller cells
        val chunked = rest.chunked(3)
        chunked.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { sector ->
                    HeatmapCell(sector = sector, modifier = Modifier.weight(1f).height(52.dp))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun HeatmapCell(sector: com.example.stock.data.api.SectorItemDto, modifier: Modifier = Modifier) {
    val pct = sector.changePct ?: 0.0
    val bgColor = when {
        pct > 2.0 -> Color(0xFFB71C1C)
        pct > 1.5 -> Color(0xFFC62828)
        pct > 1.0 -> Color(0xFFD32F2F)
        pct > 0.5 -> Color(0xFFE53935)
        pct > 0.0 -> Color(0xFFEF5350)
        pct == 0.0 -> Color(0xFF9E9E9E)
        pct > -0.5 -> Color(0xFF42A5F5)
        pct > -1.0 -> Color(0xFF1E88E5)
        pct > -1.5 -> Color(0xFF1565C0)
        else -> Color(0xFF0D47A1)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                sector.name ?: "",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatPct(pct),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

// ── Volume Surge ─────────────────────────────────────────────────────────────

@Composable
private fun VolumeSurgeCard(items: List<VolumeSurgeItemDto>) {
    HomeSectionCard(title = "거래량 급등") {
        items.take(5).forEachIndexed { idx, item ->
            if (idx > 0) HorizontalDivider(color = H2Divider, thickness = 1.dp)
            val context = LocalContext.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        StockDetailActivity.open(
                            context, item.ticker ?: "", item.name ?: "", "home2_volume", emptyList()
                        )
                    }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name ?: "",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = H2TextPrimary,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    // Volume ratio badge
                    val ratio = item.volumeRatio
                    if (ratio != null && ratio > 0) {
                        Box(
                            modifier = Modifier
                                .background(H2UpBg, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "\u2191 ${"%.1f".format(ratio)}배",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = H2Up,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatPrice(item.price),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = H2TextPrimary,
                        ),
                    )
                    Text(
                        formatPct(item.changePct),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = h2PnlColor(item.changePct),
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }
}

// ── 52-week Extremes ─────────────────────────────────────────────────────────

@Composable
private fun WeekExtremesCard(data: WeekExtremeResponseDto) {
    val highs = data.highs ?: emptyList()
    val lows = data.lows ?: emptyList()
    if (highs.isEmpty() && lows.isEmpty()) return

    var selectedTab by remember { mutableStateOf(0) } // 0 = highs, 1 = lows

    HomeSectionCard(title = "52주 신고가/신저가") {
        // Tab selector pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(H2CellBg, RoundedCornerShape(H2SmallRadius))
                .padding(2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ExtremeTab(label = "신고가", selected = selectedTab == 0, onClick = { selectedTab = 0 }, modifier = Modifier.weight(1f))
            ExtremeTab(label = "신저가", selected = selectedTab == 1, onClick = { selectedTab = 1 }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))

        val displayItems = if (selectedTab == 0) highs.take(5) else lows.take(5)
        val isHigh = selectedTab == 0
        displayItems.forEachIndexed { idx, item ->
            if (idx > 0) HorizontalDivider(color = H2Divider, thickness = 1.dp)
            ExtremeRow(item, isHigh = isHigh)
        }
    }
}

@Composable
private fun ExtremeTab(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .then(
                if (selected) Modifier.shadow(1.dp, RoundedCornerShape(6.dp))
                else Modifier
            )
            .background(
                if (selected) Color.White else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (selected) H2TextPrimary else H2TextTertiary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
        )
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
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.name ?: "",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = H2TextPrimary,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val prevLabel = if (isHigh) "이전 고가" else "이전 저가"
            val prevVal = item.prevExtreme
            if (prevVal != null && prevVal > 0) {
                Text(
                    "$prevLabel ${formatPrice(prevVal)} \u2192 ${if (isHigh) "신고가" else "신저가"}",
                    style = MaterialTheme.typography.labelSmall.copy(color = H2TextTertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatPrice(item.price),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = if (isHigh) H2Up else H2Down,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

// ── Recommendations ──────────────────────────────────────────────────────────

@Composable
private fun RecommendationCard(
    daytradeTop: List<DaytradeTopItemDto>,
    longtermTop: List<LongtermItemDto>,
) {
    val context = LocalContext.current
    HomeSectionCard(title = "오늘의 추천") {
        if (daytradeTop.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(H2AccentBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        "단타 TOP",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = H2Accent,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            daytradeTop.forEachIndexed { idx, stock ->
                if (idx > 0) HorizontalDivider(color = H2Divider, thickness = 1.dp)
                RecommendRow(
                    name = stock.name.orEmpty(),
                    thesis = stock.thesis,
                    onClick = {
                        StockDetailActivity.open(context, stock.ticker.orEmpty(), stock.name.orEmpty(), "home2", emptyList())
                    },
                )
            }
        }
        if (daytradeTop.isNotEmpty() && longtermTop.isNotEmpty()) Spacer(Modifier.height(12.dp))
        if (longtermTop.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(H2OrangeBg, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        "장타 TOP",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = H2Orange,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            longtermTop.forEachIndexed { idx, stock ->
                if (idx > 0) HorizontalDivider(color = H2Divider, thickness = 1.dp)
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
    val normalizedThesis = normalizeThesis(thesis)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = H2TextPrimary,
            ),
            modifier = Modifier.width(90.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        if (normalizedThesis.isNotBlank()) {
            Text(
                normalizedThesis,
                style = MaterialTheme.typography.labelSmall.copy(color = H2TextSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Investor Flow ────────────────────────────────────────────────────────────

@Composable
private fun InvestorFlowCard2(flow: InvestorFlowSummary) {
    val isValue = flow.unit == "value"
    val todayFlow = flow.dailyFlow.lastOrNull()
    val entries3d = listOf(
        "외국인" to flow.foreign,
        "기관" to flow.institution,
        "개인" to flow.individual,
    )
    // Compute max abs value for today's bar scaling
    val todayMaxAbs = if (todayFlow != null) {
        maxOf(
            kotlin.math.abs(todayFlow.foreign),
            kotlin.math.abs(todayFlow.institution),
            kotlin.math.abs(todayFlow.individual),
            1L,
        )
    } else 1L
    // Compute max abs for 3-day bar scaling
    val threeDayMaxAbs = maxOf(
        kotlin.math.abs(flow.foreign),
        kotlin.math.abs(flow.institution),
        kotlin.math.abs(flow.individual),
        1L,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (todayFlow != null) {
            val todayKst = LocalDate.now(ZoneId.of("Asia/Seoul"))
            val flowDate = runCatching { LocalDate.parse(todayFlow.date) }.getOrNull()
            val todayLabel = if (flowDate == todayKst) "오늘 (실시간)" else {
                flowDate?.format(DateTimeFormatter.ofPattern("MM/dd")) ?: todayFlow.date.takeLast(5).replace("-", "/")
            }
            FlowItem(label = "외국인", value = todayFlow.foreign, isValue = isValue, maxAbsValue = todayMaxAbs, header = todayLabel)
            FlowItem(label = "기관", value = todayFlow.institution, isValue = isValue, maxAbsValue = todayMaxAbs)
            FlowItem(label = "개인", value = todayFlow.individual, isValue = isValue, maxAbsValue = todayMaxAbs)
            HorizontalDivider(color = H2Divider, thickness = 1.dp)
        }
        Text("3일 누적", style = MaterialTheme.typography.labelSmall.copy(color = H2TextTertiary, fontWeight = FontWeight.SemiBold))
        entries3d.forEach { (label, value) ->
            FlowItem(label = label, value = value, isValue = isValue, maxAbsValue = threeDayMaxAbs)
        }
    }
}

@Composable
private fun FlowItem(label: String, value: Long, isValue: Boolean, maxAbsValue: Long = 1L, header: String? = null) {
    val color = if (value >= 0) H2Up else H2Down
    val barBg = if (value >= 0) H2UpBg else H2DownBg
    val sign = if (value >= 0) "+" else ""
    val displayText = if (isValue) {
        val billions = value / 100_000_000.0
        "${sign}${"%.1f".format(billions)}억"
    } else {
        "${sign}${"%,d".format(value)}주"
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val displayLabel = header ?: label
            Text(
                displayLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (header != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = H2TextSecondary,
                ),
                modifier = Modifier.width(80.dp),
            )
            Text(
                displayText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = color,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(3.dp))
        // Horizontal bar chart
        val fraction = if (maxAbsValue > 0) (kotlin.math.abs(value).toFloat() / maxAbsValue.toFloat()).coerceIn(0f, 1f) else 0f
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(H2Divider, RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(5.dp)
                    .background(color.copy(alpha = 0.5f), RoundedCornerShape(3.dp)),
            )
        }
    }
}

// ── Favorites ────────────────────────────────────────────────────────────────

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
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = H2Accent,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = true }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

// ── News ─────────────────────────────────────────────────────────────────────

private fun newsTimeAgo(publishedStart: String?): String {
    if (publishedStart.isNullOrBlank()) return ""
    return try {
        val now = java.time.OffsetDateTime.now(ZoneId.of("Asia/Seoul"))
        val dt = java.time.OffsetDateTime.parse(publishedStart)
        val minutes = java.time.Duration.between(dt, now).toMinutes()
        when {
            minutes < 60 -> "${minutes}분 전"
            minutes < 1440 -> "${minutes / 60}시간 전"
            else -> "${minutes / 1440}일 전"
        }
    } catch (e: Exception) {
        try {
            // Try without offset
            val now = java.time.LocalDateTime.now(ZoneId.of("Asia/Seoul"))
            val dt = java.time.LocalDateTime.parse(publishedStart.take(19))
            val minutes = java.time.Duration.between(dt, now).toMinutes()
            when {
                minutes < 60 -> "${minutes}분 전"
                minutes < 1440 -> "${minutes / 60}시간 전"
                else -> "${minutes / 1440}일 전"
            }
        } catch (e2: Exception) {
            ""
        }
    }
}

@Composable
private fun NewsSection(clusters: List<NewsClusterListItemDto>) {
    HomeSectionCard(title = "주요 뉴스") {
        // Count badge in header area
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(1.dp))
            if (clusters.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .background(H2AccentBg, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        "${clusters.size}개",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = H2Accent,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            clusters.forEach { cluster ->
                // Card style per news item
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(H2SmallRadius),
                    color = H2CellBg,
                    border = BorderStroke(1.dp, H2CardBorder),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        // Title
                        Text(
                            cluster.title.orEmpty(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = H2TextPrimary,
                            ),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        // Meta row: time badge + article count + tickers
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // Time badge
                            val timeAgo = newsTimeAgo(cluster.publishedStart)
                            if (timeAgo.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .background(H2Divider, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        timeAgo,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = H2TextTertiary,
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                    )
                                }
                            }
                            // Article count badge
                            Box(
                                modifier = Modifier
                                    .background(H2AccentBg, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    "${cluster.articleCount ?: 0}건",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = H2Accent,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                )
                            }
                            // Top tickers
                            if (!cluster.topTickers.isNullOrEmpty()) {
                                Text(
                                    cluster.topTickers.take(3).joinToString(", "),
                                    style = MaterialTheme.typography.labelSmall.copy(color = H2TextTertiary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Dividend ─────────────────────────────────────────────────────────────────

@Composable
private fun DividendCard(items: List<DividendItemDto>) {
    HomeSectionCard(title = "배당/권리락 일정") {
        if (items.isEmpty()) {
            Text(
                "예정된 배당 일정이 없습니다",
                style = MaterialTheme.typography.bodySmall.copy(color = H2TextTertiary),
            )
        } else {
            items.take(5).forEachIndexed { idx, item ->
                if (idx > 0) HorizontalDivider(color = H2Divider, thickness = 1.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name ?: "",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = H2TextPrimary,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "권리락 ${item.exDate ?: "-"}",
                            style = MaterialTheme.typography.labelSmall.copy(color = H2TextTertiary),
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${formatPrice(item.dividendPerShare)}/주",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = H2TextPrimary,
                            ),
                        )
                        Text(
                            "배당률 ${formatPct(item.dividendYield)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = H2Green,
                            ),
                        )
                    }
                }
            }
        }
    }
}
