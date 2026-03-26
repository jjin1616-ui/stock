package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto
import com.example.stock.data.api.AutoTradeOrderItemDto
import com.example.stock.data.api.AutoTradePerformanceItemDto
import com.example.stock.data.api.AutoTradePerformanceResponseDto
import com.example.stock.data.api.AutoTradeReservationItemDto
import com.example.stock.data.api.AutoTradeRunResponseDto
import com.example.stock.data.api.AutoTradeSymbolRuleItemDto
import com.example.stock.data.api.AutoTradeSymbolRuleUpsertDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.repository.UiSource
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.CommonFilterCard
import com.example.stock.ui.common.CommonPillChip
import com.example.stock.ui.common.GlossaryPresets
import com.example.stock.ui.common.ReportHeaderCard
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.HoldingsViewModel
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

private enum class HoldingsFilter(val label: String) {
    ALL("전체"),
    SIM("모의"),
    PROD("실전"),
}

private enum class HoldingsPeriodPreset(val label: String) {
    TODAY("오늘"),
    WEEK_1("1주"),
    MONTH_1("1개월"),
    MONTH_3("3개월"),
    YEAR_1("1년"),
    CUSTOM("직접"),
}

private data class HoldingsDateRange(
    val start: LocalDate,
    val end: LocalDate,
)

private data class HoldingRow(
    val ticker: String,
    val name: String,
    val env: String,
    val qty: Int,
    val avgPrice: Double,
    val currentPrice: Double,
    val pnlPct: Double,
) {
    val evalAmount: Double get() = currentPrice * qty.toDouble()
}

private data class HoldingDetail(
    val ticker: String,
    val name: String,
    val env: String,
    val qty: Int,
    val avgPrice: Double,
    val currentPrice: Double,
    val evalAmount: Double,
    val investedAmount: Double,
    val pnlAmount: Double,
    val pnlPct: Double,
    val dayChangePct: Double?,
)

private data class HoldingSummary(
    val count: Int,
    val totalCost: Double,
    val totalEval: Double,
    val totalPnl: Double,
    val pnlPct: Double?,
)

private data class HoldingRoiStats(
    val avgPnlPct: Double,
    val winRatePct: Double,
    val maxProfitPct: Double,
    val maxLossPct: Double,
    val totalCost: Double,
    val totalEval: Double,
)

private data class HoldingCashSummary(
    val orderableCash: Double?,
    val totalAsset: Double?,
)

private data class HoldingAccountSnapshotSummary(
    val orderableCash: Double?,
    val cash: Double?,
    val stockEval: Double?,
    val totalAsset: Double?,
    val holdingPnl: Double?,
    val holdingPnlPct: Double?,
    val prevTradingDayPnl: Double?,
    val prevTradingDayPnlPct: Double?,
)

private data class HoldingPeriodSummary(
    val periodPnl: Double,
    val periodPnlPct: Double,
    val todayPnl: Double,
    val todayPnlPct: Double,
    val periodReliable: Boolean,
)

private data class HoldingContributionRow(
    val ticker: String,
    val name: String,
    val pnlAmount: Double,
    val pnlPct: Double,
)

private enum class HoldingsHistoryTab(val label: String) {
    PENDING("진행중"),
    DONE("완료"),
    ALL("전체"),
}

private enum class HoldingsHistoryStatusFilter(val label: String) {
    ALL("전체"),
    RESERVING("예약대기"),
    SUBMITTED("접수대기"),
    FILLED("체결"),
    FAILED("실패"),
    CANCELED("취소"),
}

private enum class HoldingsActionTone {
    INFO,
    SUCCESS,
    ERROR,
}

private data class HoldingsActionSummary(
    val headline: String,
    val detail: String?,
    val tone: HoldingsActionTone,
)

private val isoDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val shortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HoldingsScreen() {
    val context = LocalContext.current
    val vm: HoldingsViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    val snackbarHostState = remember { SnackbarHostState() }
    val holdingDetailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    val todayText = today.format(isoDateFormatter)
    var pendingFilter by rememberSaveable { mutableStateOf(HoldingsFilter.ALL) }
    var appliedFilter by rememberSaveable { mutableStateOf(HoldingsFilter.ALL) }
    var pendingPeriod by rememberSaveable { mutableStateOf(HoldingsPeriodPreset.TODAY) }
    var appliedPeriod by rememberSaveable { mutableStateOf(HoldingsPeriodPreset.TODAY) }
    var pendingStartText by rememberSaveable { mutableStateOf(todayText) }
    var pendingEndText by rememberSaveable { mutableStateOf(todayText) }
    var appliedStartText by rememberSaveable { mutableStateOf(todayText) }
    var appliedEndText by rememberSaveable { mutableStateOf(todayText) }
    var filterLocked by rememberSaveable { mutableStateOf(false) }
    var contributionExpanded by rememberSaveable { mutableStateOf(false) }
    var criteriaErrorMessage by remember { mutableStateOf<String?>(null) }
    var selectedHolding by remember { mutableStateOf<HoldingDetail?>(null) }
    var showPartialSellDialog by remember { mutableStateOf(false) }
    var showFullSellDialog by remember { mutableStateOf(false) }
    var sellTargetHolding by remember { mutableStateOf<HoldingDetail?>(null) }
    var partialSellQtyText by remember { mutableStateOf("") }
    var sellPriceInput by remember { mutableStateOf("") }
    var fullSellSubmitting by remember { mutableStateOf(false) }
    var partialSellSubmitting by remember { mutableStateOf(false) }
    var fullSellBaselineRefreshedAt by remember { mutableStateOf<String?>(null) }
    var partialSellBaselineRefreshedAt by remember { mutableStateOf<String?>(null) }
    var fullSellBaselineError by remember { mutableStateOf<String?>(null) }
    var partialSellBaselineError by remember { mutableStateOf<String?>(null) }
    var orderHistoryExpanded by rememberSaveable { mutableStateOf(false) }
    var orderHistoryTab by rememberSaveable { mutableStateOf(HoldingsHistoryTab.PENDING) }
    var orderHistoryStatusFilter by rememberSaveable { mutableStateOf(HoldingsHistoryStatusFilter.ALL) }
    var orderHistoryQuery by rememberSaveable { mutableStateOf("") }
    var holdingsExpanded by rememberSaveable { mutableStateOf(false) }
    var showCurrentPrice by remember { mutableStateOf(false) }

    val paperState = vm.accountPaperState.value
    val demoState = vm.accountDemoState.value
    val prodState = vm.accountProdState.value
    val symbolRulesState = vm.symbolRulesState.value
    val ordersState = vm.ordersState.value
    val reservationsState = vm.reservationsState.value
    val performanceState = vm.performanceState.value
    val actionState = vm.actionState.value
    val quoteMap = vm.holdingQuoteState.value

    LaunchedEffect(Unit) {
        vm.loadAccounts()
        vm.loadSymbolRules()
        vm.loadOrders()
        vm.loadReservations(limit = 200)
    }

    val autoTradeEnvState = produceState<String?>(initialValue = null) {
        val repo = ServiceLocator.repository(context)
        val env = repo.getAutoTradeSettings().getOrNull()?.settings?.environment
        value = env?.trim()?.lowercase()
    }

    val rowsDemo = buildHoldingRows(demoState.data, env = "demo", requireLive = true)
    val rowsProd = buildHoldingRows(prodState.data, env = "prod", requireLive = true)
    val allRows = rowsDemo + rowsProd

    LaunchedEffect(autoTradeEnvState.value, filterLocked) {
        if (filterLocked) return@LaunchedEffect
        val defaultFilter = when (autoTradeEnvState.value) {
            "prod" -> HoldingsFilter.PROD
            "demo", "paper" -> HoldingsFilter.SIM
            else -> HoldingsFilter.ALL
        }
        pendingFilter = defaultFilter
        appliedFilter = defaultFilter
        vm.loadPerformance(days = 365, environment = performanceEnvironmentForFilter(defaultFilter))
    }

    val appliedRange = remember(appliedStartText, appliedEndText, todayText) {
        parseDateRange(
            startText = appliedStartText,
            endText = appliedEndText,
            fallback = HoldingsDateRange(start = today, end = today),
        )
    }

    val filteredRows = when (appliedFilter) {
        HoldingsFilter.ALL -> allRows
        HoldingsFilter.SIM -> rowsDemo
        HoldingsFilter.PROD -> rowsProd
    }
    val sortedFilteredRows = remember(filteredRows, quoteMap) {
        filteredRows.sortedByDescending { row ->
            val current = quoteMap[row.ticker]?.price?.takeIf { it > 0.0 } ?: row.currentPrice
            current * row.qty.toDouble()
        }
    }

    LaunchedEffect(allRows.map { it.ticker }.sorted().joinToString("|")) {
        vm.loadHoldingQuotes(allRows.map { it.ticker })
    }

    val actionMessage = remember(actionState.data, actionState.error) {
        buildActionMessage(actionState.data, actionState.error)
    }
    val actionSummary = remember(actionState.loading, actionState.data, actionState.error) {
        buildActionSummary(
            loading = actionState.loading,
            dto = actionState.data,
            error = actionState.error,
        )
    }
    LaunchedEffect(actionState.refreshedAt, actionMessage) {
        if (!actionMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(actionMessage)
        }
    }
    LaunchedEffect(criteriaErrorMessage) {
        val msg = criteriaErrorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        criteriaErrorMessage = null
    }

    val summary = remember(sortedFilteredRows, quoteMap) { computeSummary(sortedFilteredRows, quoteMap) }
    val accountSummary = remember(appliedFilter, demoState.data, prodState.data) {
        computeAccountSnapshotSummary(appliedFilter, demoState.data, prodState.data)
    }
    val accountNotice = remember(appliedFilter, paperState.data, demoState.data, prodState.data) {
        buildAccountNotice(appliedFilter, paperState.data, demoState.data, prodState.data)
    }
    val sourceLine = remember(appliedFilter, demoState.data, prodState.data) {
        buildSourceLine(appliedFilter, demoState.data, prodState.data)
    }
    val statusMessage = actionState.error ?: paperState.error ?: demoState.error ?: prodState.error ?: performanceState.error ?: symbolRulesState.error ?: reservationsState.error
    val headerUpdatedAt = latestUpdatedAt(demoState.data, prodState.data)
    val headerStatus = listOfNotNull(statusMessage, accountNotice)
        .joinToString(" · ")
        .ifBlank { "보유 현황을 확인하세요." }
    val headerSource = if (listOf(demoState.data, prodState.data).any { snapshot ->
        snapshot?.source?.trim()?.uppercase() == "BROKER_LIVE"
    }) UiSource.LIVE else UiSource.FALLBACK
    val scopedOrders = remember(ordersState.data, appliedRange, appliedFilter) {
        ordersState.data?.items.orEmpty().filter { order ->
            matchesOrderFilter(order, appliedFilter) && matchesOrderDateRange(order, appliedRange)
        }
    }
    val scopedReservations = remember(reservationsState.data, appliedRange, appliedFilter) {
        reservationsState.data?.items.orEmpty().filter { reservation ->
            matchesReservationFilter(reservation, appliedFilter) && matchesReservationDateRange(reservation, appliedRange)
        }
    }
    val periodSummary = remember(performanceState.data, appliedRange, today) {
        computePeriodSummary(
            performance = performanceState.data,
            range = appliedRange,
            today = today,
        )
    }
    val contribution = remember(sortedFilteredRows, quoteMap) {
        computeContributionRows(sortedFilteredRows, quoteMap)
    }
    val visibleContribution = if (contributionExpanded) contribution else contribution.take(3)
    val hiddenContributionCount = (contribution.size - visibleContribution.size).coerceAtLeast(0)
    val holdingsPreviewCount = 8
    val canExpandHoldings = sortedFilteredRows.size > holdingsPreviewCount
    val visibleRows = if (holdingsExpanded || !canExpandHoldings) sortedFilteredRows else sortedFilteredRows.take(holdingsPreviewCount)

    LaunchedEffect(appliedFilter, appliedRange.start, appliedRange.end) {
        holdingsExpanded = false
        contributionExpanded = false
        orderHistoryExpanded = false
        orderHistoryTab = HoldingsHistoryTab.PENDING
        orderHistoryStatusFilter = HoldingsHistoryStatusFilter.ALL
        orderHistoryQuery = ""
    }

    LaunchedEffect(orderHistoryTab, orderHistoryStatusFilter, orderHistoryQuery) {
        orderHistoryExpanded = false
    }

    LaunchedEffect(
        actionState.loading,
        actionState.refreshedAt,
        actionState.error,
        fullSellSubmitting,
        partialSellSubmitting,
    ) {
        if (!actionState.loading) {
            if (fullSellSubmitting) {
                val refreshedChanged = actionState.refreshedAt != fullSellBaselineRefreshedAt
                val errorChanged = actionState.error != fullSellBaselineError
                if (refreshedChanged || errorChanged) {
                    fullSellSubmitting = false
                    showFullSellDialog = false
                    sellTargetHolding = null
                }
            }
            if (partialSellSubmitting) {
                val refreshedChanged = actionState.refreshedAt != partialSellBaselineRefreshedAt
                val errorChanged = actionState.error != partialSellBaselineError
                if (refreshedChanged || errorChanged) {
                    partialSellSubmitting = false
                    showPartialSellDialog = false
                    sellTargetHolding = null
                }
            }
        }
    }

    val ruleMap = remember(symbolRulesState.data) {
        symbolRulesState.data?.items.orEmpty().associateBy { it.ticker?.trim().orEmpty() }
    }

    val refreshAction = {
        vm.loadAccounts()
        vm.loadSymbolRules()
        vm.loadOrders()
        vm.loadReservations(limit = 200)
        vm.loadPerformance(
            days = performanceFetchDays(appliedRange, today),
            environment = performanceEnvironmentForFilter(appliedFilter),
        )
    }
    val rangeLabel = remember(appliedRange) {
        "${appliedRange.start.format(shortDateFormatter)} ~ ${appliedRange.end.format(shortDateFormatter)}"
    }
    val applyCriteria: () -> Unit = apply@{
        val range = when (pendingPeriod) {
            HoldingsPeriodPreset.CUSTOM -> parseDateRangeOrNull(
                startText = pendingStartText,
                endText = pendingEndText,
            )
            else -> resolvePresetRange(pendingPeriod, today)
        }
        if (range == null) {
            criteriaErrorMessage = "날짜 형식을 확인하세요. 예: 2026-02-27"
            return@apply
        }
        if (range.start.isAfter(range.end)) {
            criteriaErrorMessage = "시작일은 종료일보다 늦을 수 없습니다."
            return@apply
        }
        val nextStart = range.start.format(isoDateFormatter)
        val nextEnd = range.end.format(isoDateFormatter)
        pendingStartText = nextStart
        pendingEndText = nextEnd
        appliedFilter = pendingFilter
        appliedPeriod = pendingPeriod
        appliedStartText = nextStart
        appliedEndText = nextEnd
        filterLocked = true
        val daysToFetch = performanceFetchDays(range, today)
        vm.loadPerformance(days = daysToFetch, environment = performanceEnvironmentForFilter(pendingFilter))
    }

    Scaffold(
        topBar = { AppTopBar(title = "보유", showRefresh = true, onRefresh = refreshAction) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = Color(0xFFF5F6F8),
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ReportHeaderCard(
                    title = "보유",
                    statusMessage = headerStatus,
                    updatedAt = headerUpdatedAt,
                    source = headerSource,
                    glossaryDialogTitle = "보유 용어 설명집",
                    glossaryItems = GlossaryPresets.HOLDINGS,
                )
            }
            if (actionSummary != null) {
                item {
                    HoldingsActionStatusCard(
                        summary = actionSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            item {
                HoldingsCriteriaCard(
                    pendingFilter = pendingFilter,
                    onPendingFilterChange = { pendingFilter = it },
                    pendingPeriod = pendingPeriod,
                    onPendingPeriodChange = { preset ->
                        pendingPeriod = preset
                        if (preset != HoldingsPeriodPreset.CUSTOM) {
                            val nextRange = resolvePresetRange(preset, today)
                            pendingStartText = nextRange.start.format(isoDateFormatter)
                            pendingEndText = nextRange.end.format(isoDateFormatter)
                        }
                    },
                    pendingStartText = pendingStartText,
                    pendingEndText = pendingEndText,
                    onPendingStartChange = { pendingStartText = it },
                    onPendingEndChange = { pendingEndText = it },
                    appliedRangeLabel = rangeLabel,
                    appliedPeriodLabel = appliedPeriod.label,
                    onApply = applyCriteria,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                HoldingsAccountSnapshotCard(
                    summary = accountSummary,
                    sourceLine = sourceLine,
                    notice = accountNotice,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                HoldingsPeriodCard(
                    summary = periodSummary,
                    accountSummary = accountSummary,
                    holdingSummary = summary,
                    rangeLabel = rangeLabel,
                    periodLabel = appliedPeriod.label,
                    loading = performanceState.loading,
                    error = performanceState.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                HoldingsContributionCard(
                    rows = visibleContribution,
                    totalCount = contribution.size,
                    hiddenCount = hiddenContributionCount,
                    expanded = contributionExpanded,
                    onToggleExpanded = { contributionExpanded = !contributionExpanded },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "보유 목록${if (sortedFilteredRows.isNotEmpty()) " (${sortedFilteredRows.size})" else ""}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .background(Color(0xFFF3F4F6), RoundedCornerShape(999.dp))
                                .clickable { showCurrentPrice = !showCurrentPrice }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                color = if (showCurrentPrice) Color(0xFF2F7BF6) else Color(0xFFE5E7EB),
                                shape = CircleShape,
                                modifier = Modifier.size(16.dp),
                            ) {
                                if (showCurrentPrice) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.padding(2.dp),
                                    )
                                }
                            }
                            Text("현재가", style = MaterialTheme.typography.labelLarge, color = Color(0xFF6B7280))
                        }
                        if (canExpandHoldings) {
                            TextButton(onClick = { holdingsExpanded = !holdingsExpanded }) {
                                val moreCount = (sortedFilteredRows.size - holdingsPreviewCount).coerceAtLeast(0)
                                Text(if (holdingsExpanded) "접기" else "펼치기 (+$moreCount)")
                            }
                        }
                    }
                }
            }
            if (sortedFilteredRows.isEmpty()) {
                item {
                    Text(
                        "보유 종목이 없습니다. 모의/실전 계좌 상태를 확인하고 다시 시도해주세요.",
                        color = Color(0xFF6B7280),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(visibleRows, key = { it.ticker + it.env }) { row ->
                    val detail = toHoldingDetail(row, quoteMap[row.ticker])
                    HoldingListRow(
                        detail = detail,
                        showCurrentPrice = showCurrentPrice,
                        onClick = { selectedHolding = detail },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            item {
                HoldingsOrderHistoryCard(
                    orders = scopedOrders,
                    reservations = scopedReservations,
                    rangeLabel = rangeLabel,
                    loading = ordersState.loading || reservationsState.loading,
                    error = ordersState.error ?: reservationsState.error,
                    tab = orderHistoryTab,
                    onTabChange = { orderHistoryTab = it },
                    statusFilter = orderHistoryStatusFilter,
                    onStatusFilterChange = { orderHistoryStatusFilter = it },
                    query = orderHistoryQuery,
                    onQueryChange = { orderHistoryQuery = it },
                    expanded = orderHistoryExpanded,
                    onToggleExpanded = { orderHistoryExpanded = !orderHistoryExpanded },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (selectedHolding != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedHolding = null },
            sheetState = holdingDetailSheetState,
        ) {
            HoldingDetailSheet(
                detail = selectedHolding!!,
                symbolRule = ruleMap[selectedHolding!!.ticker],
                onClose = { selectedHolding = null },
                onToggleEntry = { enabled ->
                    val detail = selectedHolding ?: return@HoldingDetailSheet
                    val existing = ruleMap[detail.ticker]
                    vm.saveSymbolRule(
                        AutoTradeSymbolRuleUpsertDto(
                            ticker = detail.ticker,
                            name = detail.name,
                            takeProfitPct = existing?.takeProfitPct ?: 7.0,
                            stopLossPct = existing?.stopLossPct ?: 5.0,
                            enabled = enabled,
                        )
                    )
                },
                onSellAll = {
                    val detail = selectedHolding ?: return@HoldingDetailSheet
                    sellTargetHolding = detail
                    sellPriceInput = detail.currentPrice.roundToLong().toString()
                    showFullSellDialog = true
                    selectedHolding = null
                },
                onSellPartial = {
                    val detail = selectedHolding ?: return@HoldingDetailSheet
                    sellTargetHolding = detail
                    partialSellQtyText = detail.qty.toString()
                    sellPriceInput = detail.currentPrice.roundToLong().toString()
                    showPartialSellDialog = true
                    selectedHolding = null
                },
            )
        }
    }

    if (showFullSellDialog && sellTargetHolding != null) {
        val detail = sellTargetHolding!!
        val sellPrice = parsePriceInput(sellPriceInput)
        AlertDialog(
            onDismissRequest = {
                if (!fullSellSubmitting) {
                    showFullSellDialog = false
                    sellTargetHolding = null
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (sellPrice != null && !fullSellSubmitting) {
                        fullSellBaselineRefreshedAt = actionState.refreshedAt
                        fullSellBaselineError = actionState.error
                        fullSellSubmitting = true
                        vm.runManualSell(
                            ticker = detail.ticker,
                            name = detail.name,
                            mode = detail.env,
                            qty = detail.qty,
                            requestPrice = sellPrice,
                            marketOrder = false,
                        )
                    }
                }, enabled = sellPrice != null && !fullSellSubmitting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (fullSellSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (fullSellSubmitting) "주문 처리중..." else "전량 매도")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (!fullSellSubmitting) {
                        showFullSellDialog = false
                        sellTargetHolding = null
                    }
                }, enabled = !fullSellSubmitting) { Text("취소") }
            },
            title = { Text("전량 매도 확인") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${detail.name} ${detail.qty}주를 전량 매도합니다.")
                    OutlinedTextField(
                        value = sellPriceInput,
                        onValueChange = { sellPriceInput = it.filter { ch -> ch.isDigit() }.take(12) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        placeholder = { Text("매도가 입력") },
                        suffix = { Text("원") },
                    )
                    if (sellPrice == null) {
                        Text("매도가를 입력해야 합니다.", color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "장중에는 즉시 주문, 장외/휴장에는 예약 주문으로 자동 전환됩니다.",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }

    if (showPartialSellDialog && sellTargetHolding != null) {
        val detail = sellTargetHolding!!
        val sellPrice = parsePriceInput(sellPriceInput)
        AlertDialog(
            onDismissRequest = {
                if (!partialSellSubmitting) {
                    showPartialSellDialog = false
                    sellTargetHolding = null
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val qty = partialSellQtyText.trim().toIntOrNull()?.coerceIn(1, detail.qty) ?: 0
                    if (qty > 0 && sellPrice != null && !partialSellSubmitting) {
                        partialSellBaselineRefreshedAt = actionState.refreshedAt
                        partialSellBaselineError = actionState.error
                        partialSellSubmitting = true
                        vm.runManualSell(
                            ticker = detail.ticker,
                            name = detail.name,
                            mode = detail.env,
                            qty = qty,
                            requestPrice = sellPrice,
                            marketOrder = false,
                        )
                    }
                }, enabled = (partialSellQtyText.trim().toIntOrNull() ?: 0) > 0 && sellPrice != null && !partialSellSubmitting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (partialSellSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (partialSellSubmitting) "주문 처리중..." else "부분 매도")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (!partialSellSubmitting) {
                        showPartialSellDialog = false
                        sellTargetHolding = null
                    }
                }, enabled = !partialSellSubmitting) { Text("취소") }
            },
            title = { Text("부분 매도") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("매도 수량을 입력하세요. (보유 ${detail.qty}주)")
                    OutlinedTextField(
                        value = partialSellQtyText,
                        onValueChange = { partialSellQtyText = it.filter { ch -> ch.isDigit() } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = sellPriceInput,
                        onValueChange = { sellPriceInput = it.filter { ch -> ch.isDigit() }.take(12) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        placeholder = { Text("매도가 입력") },
                        suffix = { Text("원") },
                    )
                    if (sellPrice == null) {
                        Text("매도가를 입력해야 합니다.", color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "장중에는 즉시 주문, 장외/휴장에는 예약 주문으로 자동 전환됩니다.",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }
}

@Composable
private fun HoldingsCriteriaCard(
    pendingFilter: HoldingsFilter,
    onPendingFilterChange: (HoldingsFilter) -> Unit,
    pendingPeriod: HoldingsPeriodPreset,
    onPendingPeriodChange: (HoldingsPeriodPreset) -> Unit,
    pendingStartText: String,
    pendingEndText: String,
    onPendingStartChange: (String) -> Unit,
    onPendingEndChange: (String) -> Unit,
    appliedRangeLabel: String,
    appliedPeriodLabel: String,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CommonFilterCard(title = "조회 기준", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("계좌", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldingsFilter.entries.forEach { filter ->
                    CommonPillChip(
                        text = filter.label,
                        selected = pendingFilter == filter,
                        onClick = { onPendingFilterChange(filter) },
                    )
                }
            }
            Text("기간", style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HoldingsPeriodPreset.entries.forEach { preset ->
                    CommonPillChip(
                        text = preset.label,
                        selected = pendingPeriod == preset,
                        onClick = { onPendingPeriodChange(preset) },
                    )
                }
            }
            if (pendingPeriod == HoldingsPeriodPreset.CUSTOM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pendingStartText,
                        onValueChange = { onPendingStartChange(it.filter { ch -> ch.isDigit() || ch == '-' }.take(10)) },
                        singleLine = true,
                        label = { Text("시작일") },
                        placeholder = { Text("2026-02-01") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = pendingEndText,
                        onValueChange = { onPendingEndChange(it.filter { ch -> ch.isDigit() || ch == '-' }.take(10)) },
                        singleLine = true,
                        label = { Text("종료일") },
                        placeholder = { Text("2026-02-27") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "적용 기준: $appliedPeriodLabel · $appliedRangeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Button(
                onClick = onApply,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
            ) {
                Text("기준 적용", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HoldingsAccountSnapshotCard(
    summary: HoldingAccountSnapshotSummary,
    sourceLine: String,
    notice: String?,
    modifier: Modifier = Modifier,
) {
    CommonFilterCard(title = "계좌 현황", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "주문가능현금", value = formatWonExact(summary.orderableCash))
                SummaryMetricCell(label = "예수금", value = formatWonExact(summary.cash))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "주식평가", value = formatWonExact(summary.stockEval))
                SummaryMetricCell(label = "총자산", value = formatWonExact(summary.totalAsset))
            }
            if (sourceLine.isNotBlank()) {
                Text(sourceLine, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
            if (!notice.isNullOrBlank()) {
                Text(notice, style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
            }
        }
    }
}

@Composable
private fun HoldingsPeriodCard(
    summary: HoldingPeriodSummary,
    accountSummary: HoldingAccountSnapshotSummary,
    holdingSummary: HoldingSummary,
    rangeLabel: String,
    periodLabel: String,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val holdingPnl = accountSummary.holdingPnl ?: holdingSummary.totalPnl
    val holdingPnlPct = accountSummary.holdingPnlPct ?: holdingSummary.pnlPct
    val todayPnl = summary.todayPnl
    val todayPnlPct = summary.todayPnlPct
    val periodPnlText = if (summary.periodReliable) formatSignedWon(summary.periodPnl) else "데이터 부족"
    val periodPnlPctText = if (summary.periodReliable) formatSignedPct(summary.periodPnlPct) else "데이터 부족"
    val prevTradingDayPnlText = accountSummary.prevTradingDayPnl?.let { formatSignedWon(it) } ?: "-"
    CommonFilterCard(title = "수익률 현황", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "기준: $periodLabel · $rangeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            if (!summary.periodReliable) {
                Text(
                    "기간 데이터 부족: 직전 기준점이 없어 기간 손익 계산을 보류합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B7280),
                )
            }
            if (!error.isNullOrBlank()) {
                Text(
                    "기간 손익 로딩 실패: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFDC2626),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "기간 손익", value = periodPnlText)
                SummaryMetricCell(label = "기간 수익률", value = periodPnlPctText)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "보유 손익", value = formatSignedWon(holdingPnl))
                SummaryMetricCell(label = "보유 수익률", value = formatSignedPct(holdingPnlPct))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "오늘 손익", value = formatSignedWon(todayPnl))
                SummaryMetricCell(label = "오늘 수익률", value = formatSignedPct(todayPnlPct))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "직전영업일 손익", value = prevTradingDayPnlText)
                SummaryMetricCell(label = "직전영업일 수익률", value = formatSignedPct(accountSummary.prevTradingDayPnlPct))
            }
            if (loading) {
                Text(
                    "기간 손익을 갱신 중입니다...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            }
        }
    }
}

@Composable
private fun HoldingsContributionCard(
    rows: List<HoldingContributionRow>,
    totalCount: Int,
    hiddenCount: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CommonFilterCard(title = "종목 기여도", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) {
                Text("기여도 데이터가 없습니다.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
            } else {
                rows.forEach { row ->
                    HoldingsContributionRowItem(row = row)
                }
                if (totalCount > rows.size || expanded) {
                    TextButton(onClick = onToggleExpanded) {
                        Text(if (expanded) "접기" else "펼치기 (+$hiddenCount)")
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldingsContributionRowItem(row: HoldingContributionRow) {
    val color = movementColor(row.pnlAmount)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Text(row.name, fontWeight = FontWeight.SemiBold, color = Color(0xFF1F2937))
                Text(row.ticker, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            }
            Text(
                "${formatSignedWon(row.pnlAmount)} (${formatSignedPctTight(row.pnlPct)})",
                color = color,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HoldingsSummaryCard(
    filter: HoldingsFilter,
    summary: HoldingSummary,
    demo: AutoTradeAccountSnapshotResponseDto?,
    prod: AutoTradeAccountSnapshotResponseDto?,
    modifier: Modifier = Modifier,
) {
    CommonFilterCard(title = "보유 요약") {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "보유 종목", value = "${summary.count}개")
                SummaryMetricCell(label = "총 평가", value = formatWonExact(summary.totalEval))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "총 손익", value = formatSignedWon(summary.totalPnl))
                SummaryMetricCell(label = "수익률", value = summary.pnlPct?.let { formatSignedPct(it) } ?: "-")
            }
            val sourceLine = buildSourceLine(filter, demo, prod)
            if (sourceLine.isNotBlank()) {
                Text(sourceLine, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            }
        }
    }
}

@Composable
private fun RowScope.SummaryMetricCell(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.weight(1f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF64748B))
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
        }
    }
}

private fun buildSourceLine(
    filter: HoldingsFilter,
    demo: AutoTradeAccountSnapshotResponseDto?,
    prod: AutoTradeAccountSnapshotResponseDto?,
): String {
    val parts = mutableListOf<String>()
    fun add(label: String, snapshot: AutoTradeAccountSnapshotResponseDto?) {
        val source = snapshot?.source?.trim()?.uppercase().orEmpty()
        if (source.isBlank()) return
        val resolved = when (source) {
            "BROKER_LIVE" -> "실계좌 연동"
            "LOCAL_ESTIMATE" -> "연동 실패(추정치 미표시)"
            "UNAVAILABLE" -> "미연동"
            else -> "미확인"
        }
        parts += "$label=$resolved"
    }
    when (filter) {
        HoldingsFilter.ALL -> {
            add("모의", demo)
            add("실전", prod)
        }
        HoldingsFilter.SIM -> add("모의", demo)
        HoldingsFilter.PROD -> add("실전", prod)
    }
    return if (parts.isEmpty()) "" else "데이터 출처: ${parts.joinToString(" · ")}".trim()
}

private fun latestUpdatedAt(
    demo: AutoTradeAccountSnapshotResponseDto?,
    prod: AutoTradeAccountSnapshotResponseDto?,
): String? {
    return listOfNotNull(demo?.updatedAt, prod?.updatedAt)
        .maxOrNull()
}

private fun buildHoldingRows(
    snapshot: AutoTradeAccountSnapshotResponseDto?,
    env: String,
    requireLive: Boolean,
): List<HoldingRow> {
    val source = snapshot?.source?.trim()?.uppercase().orEmpty()
    if (source == "UNAVAILABLE") return emptyList()
    if (requireLive && source != "BROKER_LIVE") return emptyList()
    return snapshot?.positions.orEmpty().mapNotNull { row ->
        val ticker = row.ticker?.trim().orEmpty()
        if (ticker.isBlank()) return@mapNotNull null
        val qty = (row.qty ?: 0).coerceAtLeast(0)
        if (qty <= 0) return@mapNotNull null
        val avg = row.avgPrice ?: 0.0
        val current = row.currentPrice ?: avg
        val pnlPct = row.pnlPct ?: if (avg > 0.0) ((current / avg) - 1.0) * 100.0 else 0.0
        HoldingRow(
            ticker = ticker,
            name = row.name?.ifBlank { ticker } ?: ticker,
            env = env,
            qty = qty,
            avgPrice = avg,
            currentPrice = current,
            pnlPct = pnlPct,
        )
    }.sortedByDescending { it.evalAmount }
}

private fun buildAccountNotice(
    filter: HoldingsFilter,
    paper: AutoTradeAccountSnapshotResponseDto?,
    demo: AutoTradeAccountSnapshotResponseDto?,
    prod: AutoTradeAccountSnapshotResponseDto?,
): String? {
    fun isLive(snapshot: AutoTradeAccountSnapshotResponseDto?): Boolean {
        return snapshot?.source?.trim()?.uppercase() == "BROKER_LIVE"
    }
    val hiddenPaperCount = buildHoldingRows(paper, env = "paper", requireLive = false).size
    val hiddenPaperNotice = if (hiddenPaperCount > 0) {
        "내부모의 체결 ${hiddenPaperCount}종목은 보유 목록에서 제외됩니다."
    } else {
        null
    }
    fun mergeNotice(base: String?): String? {
        return when {
            base.isNullOrBlank() -> hiddenPaperNotice
            hiddenPaperNotice.isNullOrBlank() -> base
            else -> "$base $hiddenPaperNotice"
        }
    }
    return when (filter) {
        HoldingsFilter.PROD -> when {
            isLive(prod) -> mergeNotice(null)
            else -> mergeNotice("실전 계좌 연동 실패로 보유 목록을 표시하지 않습니다.")
        }
        HoldingsFilter.SIM -> {
            when {
                isLive(demo) -> mergeNotice(null)
                else -> mergeNotice("모의투자 계좌 연동 실패로 보유 목록을 표시하지 않습니다.")
            }
        }
        HoldingsFilter.ALL -> {
            when {
                isLive(prod) && isLive(demo) -> mergeNotice(null)
                isLive(prod) && !isLive(demo) -> mergeNotice("모의투자 계좌 연동 실패로 실전 보유만 표시됩니다.")
                !isLive(prod) && isLive(demo) -> mergeNotice("실전 계좌 연동 실패로 모의 보유만 표시됩니다.")
                else -> mergeNotice("실계좌/모의투자 계좌 연동 실패로 보유 목록을 표시하지 않습니다.")
            }
        }
    }
}

private fun toHoldingDetail(row: HoldingRow, quote: RealtimeQuoteItemDto?): HoldingDetail {
    val current = quote?.price?.takeIf { it > 0.0 } ?: row.currentPrice
    val invested = row.avgPrice * row.qty.toDouble()
    val eval = current * row.qty.toDouble()
    val pnl = eval - invested
    val pnlPct = if (invested > 0.0) (pnl / invested) * 100.0 else 0.0
    return HoldingDetail(
        ticker = row.ticker,
        name = row.name,
        env = row.env,
        qty = row.qty,
        avgPrice = row.avgPrice,
        currentPrice = current,
        evalAmount = eval,
        investedAmount = invested,
        pnlAmount = pnl,
        pnlPct = pnlPct,
        dayChangePct = quote?.chgPct,
    )
}

private fun computeSummary(rows: List<HoldingRow>, quoteMap: Map<String, RealtimeQuoteItemDto>): HoldingSummary {
    if (rows.isEmpty()) {
        return HoldingSummary(count = 0, totalCost = 0.0, totalEval = 0.0, totalPnl = 0.0, pnlPct = null)
    }
    val totalCost = rows.sumOf { it.avgPrice * it.qty.toDouble() }
    val totalEval = rows.sumOf { row ->
        val current = quoteMap[row.ticker]?.price?.takeIf { it > 0.0 } ?: row.currentPrice
        current * row.qty.toDouble()
    }
    val totalPnl = totalEval - totalCost
    val pnlPct = if (totalCost > 0.0) (totalPnl / totalCost) * 100.0 else null
    return HoldingSummary(
        count = rows.size,
        totalCost = totalCost,
        totalEval = totalEval,
        totalPnl = totalPnl,
        pnlPct = pnlPct,
    )
}

private fun parseDateRange(
    startText: String,
    endText: String,
    fallback: HoldingsDateRange,
): HoldingsDateRange = parseDateRangeOrNull(startText, endText) ?: fallback

private fun parseDateRangeOrNull(
    startText: String,
    endText: String,
): HoldingsDateRange? {
    val start = parseIsoDateOrNull(startText) ?: return null
    val end = parseIsoDateOrNull(endText) ?: return null
    return HoldingsDateRange(start = start, end = end)
}

private fun parseIsoDateOrNull(raw: String?): LocalDate? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    return runCatching { LocalDate.parse(text, isoDateFormatter) }.getOrNull()
}

private fun resolvePresetRange(preset: HoldingsPeriodPreset, today: LocalDate): HoldingsDateRange {
    val days = when (preset) {
        HoldingsPeriodPreset.TODAY -> 1L
        HoldingsPeriodPreset.WEEK_1 -> 7L
        HoldingsPeriodPreset.MONTH_1 -> 30L
        HoldingsPeriodPreset.MONTH_3 -> 90L
        HoldingsPeriodPreset.YEAR_1 -> 365L
        HoldingsPeriodPreset.CUSTOM -> 1L
    }
    val start = today.minusDays((days - 1L).coerceAtLeast(0L))
    return HoldingsDateRange(start = start, end = today)
}

private fun computeAccountSnapshotSummary(
    filter: HoldingsFilter,
    demo: AutoTradeAccountSnapshotResponseDto?,
    prod: AutoTradeAccountSnapshotResponseDto?,
): HoldingAccountSnapshotSummary {
    fun liveOnly(snapshot: AutoTradeAccountSnapshotResponseDto?): AutoTradeAccountSnapshotResponseDto? {
        return snapshot?.takeIf { it.source?.trim()?.uppercase() == "BROKER_LIVE" }
    }
    val targets = when (filter) {
        HoldingsFilter.ALL -> listOfNotNull(liveOnly(demo), liveOnly(prod))
        HoldingsFilter.SIM -> listOfNotNull(liveOnly(demo))
        HoldingsFilter.PROD -> listOfNotNull(liveOnly(prod))
    }

    fun sumOf(selector: (AutoTradeAccountSnapshotResponseDto) -> Double?): Double? {
        val values = targets.mapNotNull(selector)
        return if (values.isEmpty()) null else values.sum()
    }

    val invested = targets.sumOf { snapshot ->
        snapshot.positions.orEmpty().sumOf { pos ->
            val qty = (pos.qty ?: 0).coerceAtLeast(0)
            val avg = pos.avgPrice ?: 0.0
            avg * qty.toDouble()
        }
    }
    val holdingPnl = sumOf { it.realEvalPnlKrw ?: it.unrealizedPnlKrw }
    val holdingPnlPct = when {
        invested > 0.0 && holdingPnl != null -> (holdingPnl / invested) * 100.0
        else -> null
    }

    val prevTradingDayPnl = sumOf { it.assetChangeKrw }
    val prevAsset = targets.mapNotNull { snap ->
        val total = snap.totalAssetKrw ?: return@mapNotNull null
        val delta = snap.assetChangeKrw ?: return@mapNotNull null
        total - delta
    }.sum()
    val prevTradingDayPnlPct = when {
        prevTradingDayPnl != null && prevAsset > 0.0 -> (prevTradingDayPnl / prevAsset) * 100.0
        else -> null
    }

    return HoldingAccountSnapshotSummary(
        orderableCash = sumOf { it.orderableCashKrw },
        cash = sumOf { it.cashKrw },
        stockEval = sumOf { it.stockEvalKrw },
        totalAsset = sumOf { it.totalAssetKrw },
        holdingPnl = holdingPnl,
        holdingPnlPct = holdingPnlPct,
        prevTradingDayPnl = prevTradingDayPnl,
        prevTradingDayPnlPct = prevTradingDayPnlPct,
    )
}

private fun computeContributionRows(
    rows: List<HoldingRow>,
    quoteMap: Map<String, RealtimeQuoteItemDto>,
): List<HoldingContributionRow> {
    return rows.map { row ->
        val detail = toHoldingDetail(row, quoteMap[row.ticker])
        HoldingContributionRow(
            ticker = detail.ticker,
            name = detail.name,
            pnlAmount = detail.pnlAmount,
            pnlPct = detail.pnlPct,
        )
    }.sortedWith(
        compareByDescending<HoldingContributionRow> { it.pnlAmount }
            .thenByDescending { it.pnlPct }
    )
}

private fun computePeriodSummary(
    performance: AutoTradePerformanceResponseDto?,
    range: HoldingsDateRange,
    today: LocalDate,
): HoldingPeriodSummary {
    val dated = performance?.items.orEmpty().mapNotNull { item ->
        val day = parseIsoDateOrNull(item.ymd) ?: return@mapNotNull null
        day to item
    }.sortedBy { it.first }
    if (dated.isEmpty()) {
        return HoldingPeriodSummary(
            periodPnl = 0.0,
            periodPnlPct = 0.0,
            todayPnl = 0.0,
            todayPnlPct = 0.0,
            periodReliable = false,
        )
    }
    val inRange = dated.filter { (date, _) ->
        !date.isBefore(range.start) && !date.isAfter(range.end)
    }
    if (inRange.isEmpty()) {
        return HoldingPeriodSummary(
            periodPnl = 0.0,
            periodPnlPct = 0.0,
            todayPnl = 0.0,
            todayPnlPct = 0.0,
            periodReliable = false,
        )
    }
    val lastPair = inRange.last()
    val beforeRange = dated.lastOrNull { (date, _) -> date.isBefore(range.start) }?.second
    val beforeLast = dated.lastOrNull { (date, _) -> date.isBefore(lastPair.first) }?.second
    val lastItem = lastPair.second
    val todayBase = beforeLast ?: beforeRange
    val todayPnlRaw = resolveTodayPnl(lastItem, todayBase)
    val todayPnlPctRaw = resolveTodayPnlPct(lastItem, todayPnlRaw, todayBase)
    val clampedToday = if (lastPair.first == today) todayPnlRaw else 0.0
    val clampedTodayPct = if (lastPair.first == today) todayPnlPctRaw else 0.0
    val hasHistoricalBaseline = dated.any { (date, item) ->
        date.isBefore(lastPair.first) && isMeaningfulPerformanceItem(item)
    }
    if (!hasHistoricalBaseline) {
        return HoldingPeriodSummary(
            periodPnl = 0.0,
            periodPnlPct = 0.0,
            todayPnl = clampedToday,
            todayPnlPct = clampedTodayPct,
            periodReliable = false,
        )
    }
    val periodPnl = resolvePeriodPnl(lastItem, beforeRange)
    val periodPnlPct = resolvePeriodPnlPct(inRange.map { it.second }, lastItem, beforeRange)
    return HoldingPeriodSummary(
        periodPnl = periodPnl,
        periodPnlPct = periodPnlPct,
        todayPnl = clampedToday,
        todayPnlPct = clampedTodayPct,
        periodReliable = true,
    )
}

private fun isMeaningfulPerformanceItem(item: AutoTradePerformanceItemDto?): Boolean {
    if (item == null) return false
    if ((item.filledTotal ?: 0) > 0) return true
    val values = listOf(
        item.buyAmountKrw,
        item.evalAmountKrw,
        item.realizedPnlKrw,
        item.unrealizedPnlKrw,
        item.totalAssetKrw,
        item.holdingPnlKrw,
        item.todayPnlKrw,
    )
    return values.any { abs(it ?: 0.0) > 0.000_001 }
}

private fun totalPnl(item: AutoTradePerformanceItemDto?): Double {
    val realized = item?.realizedPnlKrw ?: 0.0
    val unrealized = item?.unrealizedPnlKrw ?: 0.0
    return realized + unrealized
}

private fun totalAsset(item: AutoTradePerformanceItemDto?): Double? {
    val explicit = item?.totalAssetKrw
    if (explicit != null) return explicit
    val eval = item?.evalAmountKrw ?: return null
    return eval + (item?.realizedPnlKrw ?: 0.0)
}

private fun resolvePeriodPnl(
    last: AutoTradePerformanceItemDto?,
    before: AutoTradePerformanceItemDto?,
): Double {
    val lastAsset = totalAsset(last)
    val beforeAsset = totalAsset(before)
    return if (lastAsset != null && beforeAsset != null) {
        lastAsset - beforeAsset
    } else {
        totalPnl(last) - totalPnl(before)
    }
}

private fun resolvePeriodPnlPct(
    inRange: List<AutoTradePerformanceItemDto>,
    last: AutoTradePerformanceItemDto?,
    before: AutoTradePerformanceItemDto?,
): Double {
    if (inRange.isNotEmpty() && inRange.all { it.dailyReturnPct != null }) {
        val factor = inRange.fold(1.0) { acc, item ->
            acc * (1.0 + ((item.dailyReturnPct ?: 0.0) / 100.0))
        }
        return (factor - 1.0) * 100.0
    }
    val lastCum = last?.twrCumPct
    val beforeCum = before?.twrCumPct
    if (lastCum != null && beforeCum != null) {
        val lastFactor = 1.0 + (lastCum / 100.0)
        val beforeFactor = 1.0 + (beforeCum / 100.0)
        if (beforeFactor > 0.0) {
            return ((lastFactor / beforeFactor) - 1.0) * 100.0
        }
    }
    return (last?.roiPct ?: 0.0) - (before?.roiPct ?: 0.0)
}

private fun resolveTodayPnl(
    last: AutoTradePerformanceItemDto?,
    before: AutoTradePerformanceItemDto?,
): Double {
    val explicit = last?.todayPnlKrw
    if (explicit != null) return explicit
    val lastAsset = totalAsset(last)
    val beforeAsset = totalAsset(before)
    return if (lastAsset != null && beforeAsset != null) {
        lastAsset - beforeAsset
    } else {
        totalPnl(last) - totalPnl(before)
    }
}

private fun resolveTodayPnlPct(
    last: AutoTradePerformanceItemDto?,
    todayPnl: Double,
    before: AutoTradePerformanceItemDto?,
): Double {
    val explicit = last?.todayPnlPct ?: last?.dailyReturnPct
    if (explicit != null) return explicit
    val beforeAsset = totalAsset(before)
    return if (beforeAsset != null && beforeAsset > 0.0) (todayPnl / beforeAsset) * 100.0 else 0.0
}

private fun performanceEnvironmentForFilter(filter: HoldingsFilter): String? {
    return when (filter) {
        HoldingsFilter.ALL -> null
        HoldingsFilter.SIM -> "demo"
        HoldingsFilter.PROD -> "prod"
    }
}

private fun performanceFetchDays(range: HoldingsDateRange, today: LocalDate): Int {
    val diffDays = (today.toEpochDay() - range.start.toEpochDay()).toInt().coerceAtLeast(0)
    return (diffDays + 2).coerceIn(2, 365)
}

private fun matchesOrderFilter(order: AutoTradeOrderItemDto, filter: HoldingsFilter): Boolean {
    val env = resolveOrderEnvironment(order)
    return when (filter) {
        HoldingsFilter.ALL -> true
        HoldingsFilter.SIM -> env == "demo"
        HoldingsFilter.PROD -> env == "prod"
    }
}

private fun matchesOrderDateRange(order: AutoTradeOrderItemDto, range: HoldingsDateRange): Boolean {
    val orderDate = extractOrderDate(order) ?: return false
    return !orderDate.isBefore(range.start) && !orderDate.isAfter(range.end)
}

private fun matchesReservationFilter(reservation: AutoTradeReservationItemDto, filter: HoldingsFilter): Boolean {
    val env = reservation.environment?.trim()?.lowercase().orEmpty()
    return when (filter) {
        HoldingsFilter.ALL -> true
        HoldingsFilter.SIM -> env == "demo"
        HoldingsFilter.PROD -> env == "prod"
    }
}

private fun matchesReservationDateRange(reservation: AutoTradeReservationItemDto, range: HoldingsDateRange): Boolean {
    val reservationDate = extractReservationDate(reservation) ?: return false
    return !reservationDate.isBefore(range.start) && !reservationDate.isAfter(range.end)
}

private fun resolveOrderEnvironment(order: AutoTradeOrderItemDto): String {
    val env = order.environment?.trim()?.lowercase().orEmpty()
    if (env.isNotBlank()) return env
    val reason = order.reason?.trim()?.uppercase().orEmpty()
    return when {
        "_PROD_" in reason -> "prod"
        "_DEMO_" in reason -> "demo"
        "_PAPER_" in reason -> "paper"
        else -> ""
    }
}

private fun extractOrderDate(order: AutoTradeOrderItemDto): LocalDate? {
    val raw = order.requestedAt ?: order.filledAt
    return parseDateTokenOrNull(raw)
}

private fun extractReservationDate(reservation: AutoTradeReservationItemDto): LocalDate? {
    val raw = reservation.updatedAt ?: reservation.requestedAt ?: reservation.executeAt
    return parseDateTokenOrNull(raw)
}

private fun parseDateTokenOrNull(raw: String?): LocalDate? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    val token = Regex("""\d{4}-\d{2}-\d{2}""").find(text)?.value ?: return null
    return parseIsoDateOrNull(token)
}

private fun computeRoiStats(rows: List<HoldingRow>, quoteMap: Map<String, RealtimeQuoteItemDto>): HoldingRoiStats? {
    if (rows.isEmpty()) return null
    val pnlPcts = rows.map { row ->
        val current = quoteMap[row.ticker]?.price?.takeIf { it > 0.0 } ?: row.currentPrice
        val invested = row.avgPrice * row.qty.toDouble()
        val eval = current * row.qty.toDouble()
        if (invested > 0.0) ((eval - invested) / invested) * 100.0 else 0.0
    }
    val avgPnl = pnlPcts.average()
    val winRate = pnlPcts.count { it > 0.0 }.toDouble() / pnlPcts.size.toDouble() * 100.0
    val maxProfit = pnlPcts.maxOrNull() ?: 0.0
    val maxLoss = pnlPcts.minOrNull() ?: 0.0
    val totalCost = rows.sumOf { it.avgPrice * it.qty.toDouble() }
    val totalEval = rows.sumOf { row ->
        val current = quoteMap[row.ticker]?.price?.takeIf { it > 0.0 } ?: row.currentPrice
        current * row.qty.toDouble()
    }
    return HoldingRoiStats(
        avgPnlPct = avgPnl,
        winRatePct = winRate,
        maxProfitPct = maxProfit,
        maxLossPct = maxLoss,
        totalCost = totalCost,
        totalEval = totalEval,
    )
}

private fun computeCashSummary(
    filter: HoldingsFilter,
    demo: AutoTradeAccountSnapshotResponseDto?,
    prod: AutoTradeAccountSnapshotResponseDto?,
): HoldingCashSummary {
    fun allowSnapshot(snapshot: AutoTradeAccountSnapshotResponseDto?): Boolean {
        if (snapshot == null) return false
        val source = snapshot.source?.trim()?.uppercase()
        return source == "BROKER_LIVE"
    }
    val targets = when (filter) {
        HoldingsFilter.ALL -> listOf(
            "demo" to demo,
            "prod" to prod,
        )
        HoldingsFilter.SIM -> listOf(
            "demo" to demo,
        )
        HoldingsFilter.PROD -> listOf(
            "prod" to prod,
        )
    }.filter { (_, snapshot) -> allowSnapshot(snapshot) }

    val cashValues = targets.mapNotNull { (_, snapshot) ->
        val orderable = snapshot?.orderableCashKrw
        val cash = snapshot?.cashKrw
        (orderable?.takeIf { it > 0.0 } ?: cash)?.takeIf { it > 0.0 }
    }
    val assetValues = targets.mapNotNull { (_, snapshot) ->
        snapshot?.totalAssetKrw?.takeIf { it > 0.0 }
    }
    val cashSum = cashValues.takeIf { it.isNotEmpty() }?.sum()
    val assetSum = assetValues.takeIf { it.isNotEmpty() }?.sum()
    return HoldingCashSummary(orderableCash = cashSum, totalAsset = assetSum)
}

@Composable
private fun HoldingsRoiCard(
    stats: HoldingRoiStats,
    cashSummary: HoldingCashSummary,
    modifier: Modifier = Modifier,
) {
    CommonFilterCard(title = "수익률 현황") {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "평균 수익률", value = formatSignedPct(stats.avgPnlPct))
                SummaryMetricCell(label = "승률", value = formatPercent(stats.winRatePct, 1))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "최대수익", value = formatSignedPct(stats.maxProfitPct))
                SummaryMetricCell(label = "최대손실", value = formatSignedPct(stats.maxLossPct))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "총 매입금액", value = formatWonExact(stats.totalCost))
                SummaryMetricCell(label = "총 평가금액", value = formatWonExact(stats.totalEval))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "주문가능현금", value = formatWonExact(cashSummary.orderableCash))
                SummaryMetricCell(label = "총자산", value = formatWonExact(cashSummary.totalAsset))
            }
        }
    }
}

@Composable
private fun HoldingsActionStatusCard(
    summary: HoldingsActionSummary,
    modifier: Modifier = Modifier,
) {
    val toneColor = when (summary.tone) {
        HoldingsActionTone.INFO -> Color(0xFF1D4ED8)
        HoldingsActionTone.SUCCESS -> Color(0xFF047857)
        HoldingsActionTone.ERROR -> Color(0xFFB91C1C)
    }
    CommonFilterCard(title = "최근 주문 상태", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = summary.headline,
                style = MaterialTheme.typography.titleSmall,
                color = toneColor,
                fontWeight = FontWeight.Bold,
            )
            summary.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            }
        }
    }
}

@Composable
private fun HoldingsOrderHistoryCard(
    orders: List<AutoTradeOrderItemDto>,
    reservations: List<AutoTradeReservationItemDto>,
    rangeLabel: String,
    loading: Boolean,
    error: String?,
    tab: HoldingsHistoryTab,
    onTabChange: (HoldingsHistoryTab) -> Unit,
    statusFilter: HoldingsHistoryStatusFilter,
    onStatusFilterChange: (HoldingsHistoryStatusFilter) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val historyItems = remember(orders, reservations) {
        mergeHistoryItems(orders = orders, reservations = reservations)
    }
    val tabFilteredItems = remember(historyItems, tab) {
        historyItems.filter { matchesHistoryTab(it, tab) }
    }
    val statusFilteredItems = remember(tabFilteredItems, statusFilter) {
        tabFilteredItems.filter { matchesHistoryStatus(it, statusFilter) }
    }
    val queryFilteredItems = remember(statusFilteredItems, query) {
        val normalized = query.trim()
        if (normalized.isBlank()) statusFilteredItems
        else statusFilteredItems.filter { matchesHistoryQuery(it, normalized) }
    }
    val previewLimit = 20
    val visibleItems = if (expanded) queryFilteredItems else queryFilteredItems.take(previewLimit)
    val hiddenCount = (queryFilteredItems.size - visibleItems.size).coerceAtLeast(0)
    CommonFilterCard(title = "거래 이력", modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "기준 기간: $rangeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldingsHistoryTab.entries.forEach { candidate ->
                    CommonPillChip(
                        text = candidate.label,
                        selected = tab == candidate,
                        onClick = { onTabChange(candidate) },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HoldingsHistoryStatusFilter.entries.forEach { candidate ->
                    CommonPillChip(
                        text = candidate.label,
                        selected = statusFilter == candidate,
                        onClick = { onStatusFilterChange(candidate) },
                    )
                }
            }
            HoldingsHistorySearchField(
                value = query,
                onValueChange = { onQueryChange(it.take(24)) },
                placeholder = "종목명 또는 코드 검색",
            )
            when {
                loading && queryFilteredItems.isEmpty() -> {
                    Text("거래 이력 불러오는 중...", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
                !error.isNullOrBlank() && queryFilteredItems.isEmpty() -> {
                    Text("거래 이력 로딩 실패: $error", color = Color(0xFFDC2626), style = MaterialTheme.typography.bodySmall)
                }
                queryFilteredItems.isEmpty() -> {
                    Text("조건에 맞는 거래 이력이 없습니다.", color = Color(0xFF6B7280), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text(
                        "총 ${queryFilteredItems.size}건",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    visibleItems.forEach { item ->
                        when (item) {
                            is HoldingsHistoryItem.Order -> HoldingsOrderRow(order = item.order)
                            is HoldingsHistoryItem.Reservation -> HoldingsReservationRow(reservation = item.reservation)
                        }
                    }
                    if (hiddenCount > 0 || expanded) {
                        TextButton(onClick = onToggleExpanded, modifier = Modifier.align(Alignment.End)) {
                            Text(
                                if (expanded) "접기" else "펼치기 (+$hiddenCount)",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class HoldingsHistoryItem(open val sortKey: String) {
    data class Order(val order: AutoTradeOrderItemDto, override val sortKey: String) : HoldingsHistoryItem(sortKey)
    data class Reservation(val reservation: AutoTradeReservationItemDto, override val sortKey: String) : HoldingsHistoryItem(sortKey)
}

private fun mergeHistoryItems(
    orders: List<AutoTradeOrderItemDto>,
    reservations: List<AutoTradeReservationItemDto>,
): List<HoldingsHistoryItem> {
    val orderItems = orders.map { order ->
        HoldingsHistoryItem.Order(
            order = order,
            sortKey = normalizeHistorySortKey(order.requestedAt ?: order.filledAt),
        )
    }
    val reservationItems = reservations.map { reservation ->
        HoldingsHistoryItem.Reservation(
            reservation = reservation,
            sortKey = normalizeHistorySortKey(reservation.updatedAt ?: reservation.requestedAt ?: reservation.executeAt),
        )
    }
    return (orderItems + reservationItems).sortedByDescending { it.sortKey }
}

private fun normalizeHistorySortKey(raw: String?): String {
    return raw?.replace(" ", "T")?.trim().orEmpty()
}

private fun matchesHistoryTab(item: HoldingsHistoryItem, tab: HoldingsHistoryTab): Boolean {
    if (tab == HoldingsHistoryTab.ALL) return true
    return when (tab) {
        HoldingsHistoryTab.PENDING -> historyStatusCategory(item).isPending
        HoldingsHistoryTab.DONE -> !historyStatusCategory(item).isPending
        HoldingsHistoryTab.ALL -> true
    }
}

private fun matchesHistoryStatus(item: HoldingsHistoryItem, filter: HoldingsHistoryStatusFilter): Boolean {
    if (filter == HoldingsHistoryStatusFilter.ALL) return true
    val category = historyStatusCategory(item)
    return category.filter == filter
}

private fun matchesHistoryQuery(item: HoldingsHistoryItem, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isBlank()) return true
    return when (item) {
        is HoldingsHistoryItem.Order -> {
            val name = item.order.name?.lowercase().orEmpty()
            val ticker = item.order.ticker?.lowercase().orEmpty()
            name.contains(q) || ticker.contains(q)
        }
        is HoldingsHistoryItem.Reservation -> {
            val previewItems = item.reservation.previewItems.orEmpty()
            val itemName = previewItems.firstOrNull()?.name?.lowercase().orEmpty()
            val itemTicker = previewItems.firstOrNull()?.ticker?.lowercase().orEmpty()
            val fallbackTicker = previewItems.joinToString(" ") { it.ticker.orEmpty().lowercase() }
            itemName.contains(q) || itemTicker.contains(q) || fallbackTicker.contains(q)
        }
    }
}

private data class HistoryStatusCategory(
    val filter: HoldingsHistoryStatusFilter,
    val isPending: Boolean,
)

private fun historyStatusCategory(item: HoldingsHistoryItem): HistoryStatusCategory {
    return when (item) {
        is HoldingsHistoryItem.Order -> {
            when (item.order.status?.trim()?.uppercase()) {
                "BROKER_SUBMITTED" -> HistoryStatusCategory(HoldingsHistoryStatusFilter.SUBMITTED, isPending = true)
                "PAPER_FILLED", "BROKER_FILLED" -> HistoryStatusCategory(HoldingsHistoryStatusFilter.FILLED, isPending = false)
                "BROKER_CANCELED", "BROKER_CLOSED" -> HistoryStatusCategory(HoldingsHistoryStatusFilter.CANCELED, isPending = false)
                "BROKER_REJECTED", "SKIPPED", "ERROR" -> HistoryStatusCategory(HoldingsHistoryStatusFilter.FAILED, isPending = false)
                else -> HistoryStatusCategory(HoldingsHistoryStatusFilter.FAILED, isPending = false)
            }
        }
        is HoldingsHistoryItem.Reservation -> {
            when (item.reservation.status?.trim()?.uppercase()) {
                "QUEUED", "WAIT_CONFIRM", "RUNNING" -> HistoryStatusCategory(HoldingsHistoryStatusFilter.RESERVING, isPending = true)
                "DONE" -> HistoryStatusCategory(HoldingsHistoryStatusFilter.FILLED, isPending = false)
                "PARTIAL", "FAILED" -> HistoryStatusCategory(HoldingsHistoryStatusFilter.FAILED, isPending = false)
                "CANCELED", "EXPIRED" -> HistoryStatusCategory(HoldingsHistoryStatusFilter.CANCELED, isPending = false)
                else -> HistoryStatusCategory(HoldingsHistoryStatusFilter.FAILED, isPending = false)
            }
        }
    }
}

@Composable
private fun HoldingsOrderRow(order: AutoTradeOrderItemDto) {
    val sideLabel = orderSideLabel(order.side)
    val statusLabel = orderStatusLabel(order.status)
    val flowLabel = "즉시"
    val titleLabel = "$sideLabel · $flowLabel · $statusLabel"
    val envLabel = historyEnvironmentLabel(resolveOrderEnvironment(order))
    val price = order.requestedPrice?.takeIf { it > 0.0 }
        ?: order.filledPrice?.takeIf { it > 0.0 }
        ?: order.currentPrice?.takeIf { it > 0.0 }
    val priceLabel = price?.let { formatWonExact(it) } ?: "-"
    val qtyLabel = order.qty?.takeIf { it > 0 }?.let { "${it}주" } ?: "-"
    val timeLabel = formatOrderTime(order.requestedAt ?: order.filledAt)
    val sideColor = if (sideLabel == "매도") Color(0xFFE11D48) else Color(0xFF2563EB)
    val orderName = order.name?.ifBlank { order.ticker.orEmpty() } ?: order.ticker.orEmpty()
    val reasonLabel = order.reasonDetail?.conclusion?.trim().takeUnless { it.isNullOrBlank() }
        ?: resolveOrderReasonLabel(order.reason, null)
    val actionLabel = order.reasonDetail?.action?.trim().takeUnless { it.isNullOrBlank() }
        ?: resolveReasonActionLabel(order.reasonDetail?.reasonCode ?: order.reason)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(titleLabel, color = sideColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    orderName,
                    color = Color(0xFF0F172A),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${order.ticker.orEmpty()} · $qtyLabel · $priceLabel · $envLabel · $timeLabel",
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.labelSmall,
                )
                reasonLabel?.takeIf { it.isNotBlank() }?.let { reason ->
                    Text(
                        "사유: $reason",
                        color = Color(0xFF475569),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                actionLabel?.takeIf { it.isNotBlank() }?.let { action ->
                    Text(
                        "조치: $action",
                        color = Color(0xFF0F766E),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldingsHistorySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Medium,
            ),
            cursorBrush = SolidColor(Color(0xFF1E3A8A)),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                if (value.isBlank()) {
                    Text(
                        text = placeholder,
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                innerTextField()
            },
        )
        if (value.isNotBlank()) {
            TextButton(
                onClick = { onValueChange("") },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                Text("지우기", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HoldingsReservationRow(reservation: AutoTradeReservationItemDto) {
    val statusLabel = reservationStatusLabel(reservation.status)
    val previewItems = reservation.previewItems.orEmpty()
    val firstItem = previewItems.firstOrNull()
    val kindLabel = reservationKindLabel(reservation.kind, firstItem?.sourceTab)
    val actionLabel = reservationActionLabel(reservation.kind, firstItem?.sourceTab)
    val titleLabel = "$actionLabel · 예약 · $statusLabel"
    val previewCount = (reservation.previewCount ?: previewItems.size).coerceAtLeast(previewItems.size)
    val totalPlannedQty = previewItems.sumOf { (it.plannedQty ?: 0).coerceAtLeast(0) }
    val itemName = firstItem?.name?.ifBlank { firstItem.ticker.orEmpty() } ?: "예약 주문"
    val itemTicker = firstItem?.ticker?.orEmpty().orEmpty()
    val itemPrice = firstItem?.plannedPrice ?: firstItem?.currentPrice ?: firstItem?.signalPrice
    val plannedAmount = previewItems.mapNotNull { it.plannedAmountKrw?.takeIf { value -> value > 0.0 } }.sum().takeIf { it > 0.0 }
    val priceLabel = itemPrice?.takeIf { it > 0.0 }?.let { formatWonExact(it) } ?: "-"
    val qtyLabel = when {
        previewCount > 1 && totalPlannedQty > 0 -> "총 ${previewCount}건 · ${totalPlannedQty}주"
        previewCount > 1 -> "총 ${previewCount}건"
        totalPlannedQty > 0 -> "${totalPlannedQty}주"
        else -> "1건"
    }
    val envLabel = historyEnvironmentLabel(reservation.environment)
    val timeLabel = formatOrderTime(reservation.updatedAt ?: reservation.requestedAt ?: reservation.executeAt)
    val reasonLabel = resolveOrderReasonLabel(reservation.reasonCode, reservation.reasonMessage)
    val actionGuide = resolveReasonActionLabel(reservation.reasonCode)

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(titleLabel, color = Color(0xFF0EA5E9), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
                Text(itemName, fontWeight = FontWeight.SemiBold)
                val detail = buildString {
                    append(if (itemTicker.isBlank()) "-" else itemTicker)
                    append(" · ")
                    append(qtyLabel)
                    append(" · ")
                    append(priceLabel)
                    if (plannedAmount != null) {
                        append(" · ")
                        append(formatWonExact(plannedAmount))
                    }
                    append(" · ")
                    append(kindLabel)
                    append(" · ")
                    append(envLabel)
                    append(" · ")
                    append(timeLabel)
                }
                Text(detail, color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
                reasonLabel?.takeIf { it.isNotBlank() }?.let { reason ->
                    Text(
                        "사유: $reason",
                        color = Color(0xFF475569),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                actionGuide?.takeIf { it.isNotBlank() }?.let { action ->
                    Text(
                        "조치: $action",
                        color = Color(0xFF0F766E),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun HoldingListRow(
    detail: HoldingDetail,
    showCurrentPrice: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metricColor = if (showCurrentPrice) movementColor(detail.dayChangePct ?: 0.0) else movementColor(detail.pnlAmount)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoTradeTickerLogo(ticker = detail.ticker)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        detail.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF1F2937),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("${detail.qty}주", style = MaterialTheme.typography.bodySmall, color = Color(0xFF6B7280))
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (showCurrentPrice) formatWonExact(detail.currentPrice) else formatWonExact(detail.evalAmount),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF374151),
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
                Text(
                    text = if (showCurrentPrice) {
                        detail.dayChangePct?.let { "어제보다 ${formatSignedPctTight(it)}" } ?: "어제보다 --"
                    } else {
                        "${formatSignedWon(detail.pnlAmount)} (${formatSignedPctTight(detail.pnlPct)})"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = metricColor,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HoldingDetailSheet(
    detail: HoldingDetail,
    symbolRule: AutoTradeSymbolRuleItemDto?,
    onClose: () -> Unit,
    onToggleEntry: (Boolean) -> Unit,
    onSellAll: () -> Unit,
    onSellPartial: () -> Unit,
) {
    val dayColor = movementColor(detail.dayChangePct ?: 0.0)
    val pnlColor = movementColor(detail.pnlAmount)
    val isEntryEnabled = symbolRule?.enabled != false
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(52.dp)
                .height(4.dp)
                .background(Color(0xFFE5E7EB), RoundedCornerShape(999.dp)),
        )
        Text(detail.name, style = MaterialTheme.typography.titleLarge, color = Color(0xFF111827), fontWeight = FontWeight.ExtraBold)
        Text("${detail.qty}주 · ${envLabel(detail.env)}", style = MaterialTheme.typography.titleMedium, color = Color(0xFF6B7280), fontWeight = FontWeight.Medium)

        Spacer(Modifier.height(6.dp))
        HoldingDetailRow(label = "총 투자한 금액", value = formatWonExact(detail.investedAmount))
        HoldingDetailRow(label = "총 현재 손익", value = formatSignedWon(detail.pnlAmount), valueColor = pnlColor)
        HoldingDetailRow(label = "총 평가 금액", value = formatWonExact(detail.evalAmount))
        HorizontalDivider(thickness = 1.dp, color = Color(0xFFE5E7EB))
        HoldingDetailRow(label = "내 매수 평균가격", value = formatWonExact(detail.avgPrice))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("현재가격", style = MaterialTheme.typography.titleMedium, color = Color(0xFF374151), fontWeight = FontWeight.Medium)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(formatWonExact(detail.currentPrice), style = MaterialTheme.typography.titleMedium, color = dayColor, fontWeight = FontWeight.Bold)
                Text(
                    detail.dayChangePct?.let { "어제보다 ${formatSignedPctTight(it)}" } ?: "어제보다 --",
                    style = MaterialTheme.typography.titleMedium,
                    color = dayColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onSellAll,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48), contentColor = Color.White),
            ) { Text("전량 매도", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            Button(
                onClick = onSellPartial,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF3F4F6), contentColor = Color(0xFF374151)),
            ) { Text("부분 매도", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { onToggleEntry(!isEntryEnabled) },
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A), contentColor = Color.White),
            ) { Text(if (isEntryEnabled) "매수 중지" else "매수 재개", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5E7EB), contentColor = Color(0xFF6B7280)),
            ) { Text("닫기", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HoldingDetailRow(
    label: String,
    value: String,
    valueColor: Color = Color(0xFF4B5563),
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color(0xFF374151), fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AutoTradeTickerLogo(ticker: String) {
    val logoUrl = remember(ticker) {
        if (ticker.length == 6 && ticker.all(Char::isDigit)) {
            "https://ssl.pstatic.net/imgstock/fn/real/logo/png/stock/Stock$ticker.png"
        } else {
            null
        }
    }
    val logoBitmap = rememberRemoteLogoBitmapForHoldings(logoUrl)
    if (logoBitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = logoBitmap,
            contentDescription = "종목 로고",
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFE8EEF9))
                .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(ticker.takeLast(2), style = MaterialTheme.typography.labelSmall, color = Color(0xFF4B5563), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun rememberRemoteLogoBitmapForHoldings(url: String?): ImageBitmap? {
    val safeUrl = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val state by produceState<ImageBitmap?>(initialValue = null, key1 = safeUrl) {
        value = null
        if (safeUrl.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(safeUrl).openStream().use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return state
}

private fun envLabel(env: String): String = when (env) {
    "demo" -> "모의투자"
    "prod" -> "실전"
    else -> "모의"
}

private fun historyEnvironmentLabel(raw: String?): String {
    return when (raw?.trim()?.lowercase()) {
        "demo", "paper" -> "모의"
        "prod" -> "실전"
        else -> "환경확인"
    }
}

private fun formatWonExact(v: Double?): String = if (v == null) "-" else "${"%,.0f".format(v)}원"

private fun formatSignedPct(v: Double?): String {
    if (v == null) return "-"
    return "${if (v >= 0.0) "+" else ""}${"%.2f".format(v)}%"
}

private fun formatSignedPctTight(v: Double): String = "${if (v >= 0.0) "+" else ""}${"%.2f".format(v)}%"

private fun formatSignedWon(v: Double): String = "${if (v >= 0.0) "+" else "-"}${"%,.0f".format(kotlin.math.abs(v))}원"

private fun movementColor(v: Double): Color = if (v >= 0) Color(0xFFD15267) else Color(0xFF2D6CB3)

private fun formatPercent(v: Double, digits: Int): String =
    "%.${digits}f".format(v.coerceAtLeast(0.0)) + "%"

private fun parsePriceInput(raw: String): Double? {
    val digits = raw.filter(Char::isDigit)
    if (digits.isBlank()) return null
    return digits.toDoubleOrNull()?.takeIf { it > 0.0 }
}

private fun buildActionMessage(dto: AutoTradeRunResponseDto?, error: String?): String? {
    if (!error.isNullOrBlank()) return error
    if (dto == null) return null
    val order = dto.orders.orEmpty().firstOrNull()
    val rawMessage = dto.message?.trim().orEmpty()
    val side = orderSideLabel(order?.side ?: extractSideFromMessage(rawMessage))
    val statusLabel = order?.status?.takeIf { it.isNotBlank() }?.let { orderStatusLabel(it) }
    val reasonLabel = order?.reasonDetail?.conclusion?.trim().takeUnless { it.isNullOrBlank() }
        ?: resolveOrderReasonLabel(order?.reason, rawMessage)
    val parts = mutableListOf<String>()
    parts += if (side.isNotBlank()) "$side 요청 완료" else "요청 완료"
    if (!statusLabel.isNullOrBlank()) parts += statusLabel
    if (!reasonLabel.isNullOrBlank()) parts += reasonLabel
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun buildActionSummary(
    loading: Boolean,
    dto: AutoTradeRunResponseDto?,
    error: String?,
): HoldingsActionSummary? {
    if (loading) {
        return HoldingsActionSummary(
            headline = "주문 요청을 확인하고 있습니다.",
            detail = "주문 가능 수량과 계좌 상태를 확인 중입니다.",
            tone = HoldingsActionTone.INFO,
        )
    }
    if (!error.isNullOrBlank()) {
        return HoldingsActionSummary(
            headline = "주문 처리에 실패했습니다.",
            detail = error,
            tone = HoldingsActionTone.ERROR,
        )
    }
    if (dto == null) return null
    val rawMessage = dto.message?.trim().orEmpty()
    if (dto.queued == true || dto.reservationId != null) {
        val preview = dto.reservationPreviewItems.orEmpty().firstOrNull()
        val name = preview?.name?.ifBlank { preview.ticker.orEmpty() } ?: "예약 주문"
        val qtyLabel = preview?.plannedQty?.takeIf { it > 0 }?.let { "${it}주" }
            ?: dto.reservationPreviewCount?.takeIf { it > 0 }?.let { "${it}건" }
            ?: "수량 확인 필요"
        val reasonLabel = resolveOrderReasonLabel(null, rawMessage)
        val detailParts = mutableListOf<String>()
        if (!reasonLabel.isNullOrBlank()) detailParts += reasonLabel
        dto.reservationStatus?.takeIf { it.isNotBlank() }?.let { detailParts += reservationStatusLabel(it) }
        dto.reservationId?.let { detailParts += "예약번호 $it" }
        return HoldingsActionSummary(
            headline = "예약 주문 등록 완료 · $name · $qtyLabel",
            detail = detailParts.joinToString(" · ").ifBlank { null },
            tone = HoldingsActionTone.INFO,
        )
    }
    val order = dto.orders.orEmpty().firstOrNull()
    val side = orderSideLabel(order?.side ?: extractSideFromMessage(rawMessage))
    val name = order?.name?.ifBlank { order.ticker.orEmpty() } ?: order?.ticker ?: "주문 종목"
    val qtyLabel = order?.qty?.takeIf { it > 0 }?.let { "${it}주" } ?: "수량 확인 필요"
    val statusLabel = orderStatusLabel(order?.status)
    val reasonLabel = order?.reasonDetail?.conclusion?.trim().takeUnless { it.isNullOrBlank() }
        ?: resolveOrderReasonLabel(order?.reason, rawMessage)
    val actionLabel = order?.reasonDetail?.action?.trim().takeUnless { it.isNullOrBlank() }
        ?: resolveReasonActionLabel(order?.reasonDetail?.reasonCode ?: order?.reason)
    val tone = when (order?.status?.trim()?.uppercase()) {
        "PAPER_FILLED", "BROKER_FILLED" -> HoldingsActionTone.SUCCESS
        "BROKER_SUBMITTED" -> HoldingsActionTone.INFO
        "BROKER_REJECTED", "SKIPPED", "ERROR" -> HoldingsActionTone.ERROR
        else -> HoldingsActionTone.INFO
    }
    val detail = listOfNotNull(
        statusLabel.takeIf { it.isNotBlank() },
        reasonLabel,
        actionLabel,
    ).joinToString(" · ").ifBlank { null }
    return HoldingsActionSummary(
        headline = "$side 요청 완료 · $name · $qtyLabel",
        detail = detail,
        tone = tone,
    )
}

private fun orderSideLabel(raw: String?): String {
    val s = raw?.trim()?.uppercase().orEmpty()
    return if (s == "SELL") "매도" else "매수"
}

private fun orderStatusLabel(raw: String?): String {
    return when (raw?.trim()?.uppercase()) {
        "PAPER_FILLED", "BROKER_FILLED" -> "체결완료"
        "BROKER_SUBMITTED" -> "접수완료"
        "BROKER_REJECTED" -> "증권사거부"
        "BROKER_CANCELED" -> "취소완료"
        "BROKER_CLOSED" -> "상태정리완료"
        "SKIPPED" -> "실행안됨"
        "ERROR" -> "처리실패"
        else -> "상태확인필요"
    }
}

private fun reservationStatusLabel(raw: String?): String {
    return when (raw?.trim()?.uppercase()) {
        "QUEUED" -> "예약대기"
        "WAIT_CONFIRM" -> "실행확인대기"
        "RUNNING" -> "예약실행중"
        "DONE" -> "예약완료"
        "PARTIAL" -> "예약일부실패"
        "FAILED" -> "예약실패"
        "CANCELED" -> "예약취소"
        "EXPIRED" -> "예약만료"
        else -> "예약상태확인필요"
    }
}

private fun reservationKindLabel(raw: String?, sourceTab: String? = null): String {
    val source = sourceTab?.trim()?.uppercase().orEmpty()
    return when (raw?.trim()?.uppercase()) {
        "MANUAL_ORDER" -> when (source) {
            "HOLDINGS" -> "수동 매도 예약"
            "DETAIL_CARD" -> "수동 매수 예약"
            else -> "수동 주문 예약"
        }
        "ORDER_CANCEL" -> "접수취소 예약"
        "AUTOTRADE_ENTRY" -> "자동매매 매수 예약"
        else -> "예약"
    }
}

private fun reservationActionLabel(raw: String?, sourceTab: String? = null): String {
    val source = sourceTab?.trim()?.uppercase().orEmpty()
    return when (raw?.trim()?.uppercase()) {
        "MANUAL_ORDER" -> when (source) {
            "HOLDINGS" -> "매도"
            "DETAIL_CARD" -> "매수"
            else -> "주문"
        }
        "ORDER_CANCEL" -> "취소"
        "AUTOTRADE_ENTRY" -> "매수"
        else -> "주문"
    }
}

private fun formatOrderTime(raw: String?): String {
    val v = raw?.replace("T", " ")?.trim().orEmpty()
    return when {
        v.length >= 16 -> v.take(16)
        v.isNotBlank() -> v
        else -> "-"
    }
}

private fun resolveOrderReasonLabel(reason: String?, message: String?): String? {
    val raw = reason?.trim().orEmpty().ifBlank { extractReasonFromMessage(message).orEmpty() }
    if (raw.isBlank()) return null
    if (raw.contains("잔고내역이 없습니다")) return "증권사 보유 잔고가 없어 주문이 거부되었습니다"
    if (raw.contains("주문가능수량") && raw.contains("없")) return "주문 가능 수량이 없어 주문이 거부되었습니다"
    val upper = raw.uppercase()
    return when {
        upper.startsWith("MANUAL_SELL_PAPER_") -> {
            val orderType = upper.removePrefix("MANUAL_SELL_PAPER_")
            val orderLabel = when (orderType) {
                "LIMIT" -> "지정가"
                "MARKET" -> "시장가"
                else -> "기타"
            }
            "모의 매도($orderLabel)"
        }
        upper.startsWith("MANUAL_SELL_DEMO_") -> {
            val orderType = upper.removePrefix("MANUAL_SELL_DEMO_")
            val orderLabel = when (orderType) {
                "LIMIT" -> "지정가"
                "MARKET" -> "시장가"
                else -> "기타"
            }
            "모의 매도($orderLabel)"
        }
        upper.startsWith("MANUAL_SELL_PROD_") -> {
            val orderType = upper.removePrefix("MANUAL_SELL_PROD_")
            val orderLabel = when (orderType) {
                "LIMIT" -> "지정가"
                "MARKET" -> "시장가"
                else -> "기타"
            }
            "실전 매도($orderLabel)"
        }
        upper.startsWith("MANUAL_BUY_PAPER_") -> {
            val orderType = upper.removePrefix("MANUAL_BUY_PAPER_")
            val orderLabel = when (orderType) {
                "LIMIT" -> "지정가"
                "MARKET" -> "시장가"
                else -> "기타"
            }
            "모의 매수($orderLabel)"
        }
        upper.startsWith("MANUAL_BUY_DEMO_") -> {
            val orderType = upper.removePrefix("MANUAL_BUY_DEMO_")
            val orderLabel = when (orderType) {
                "LIMIT" -> "지정가"
                "MARKET" -> "시장가"
                else -> "기타"
            }
            "모의 매수($orderLabel)"
        }
        upper.startsWith("MANUAL_BUY_PROD_") -> {
            val orderType = upper.removePrefix("MANUAL_BUY_PROD_")
            val orderLabel = when (orderType) {
                "LIMIT" -> "지정가"
                "MARKET" -> "시장가"
                else -> "기타"
            }
            "실전 매수($orderLabel)"
        }
        upper.startsWith("DRY_RUN_MANUAL_SELL") -> "모의 점검(주문 없음)"
        upper.startsWith("DRY_RUN_MANUAL_BUY") -> "모의 점검(주문 없음)"
        upper == "REQUEST_PRICE_REQUIRED" -> "매도가 입력 필요"
        upper == "PRICE_UNAVAILABLE" -> "주문가 확인 불가"
        upper == "QTY_ZERO" -> "수량이 0입니다"
        upper == "SELLABLE_QTY_ZERO" -> "매도 가능 수량이 없어 주문이 거부되었습니다"
        upper == "PENDING_SELL_ORDER" -> "같은 종목 매도 주문이 이미 대기 중입니다"
        upper == "PENDING_BUY_ORDER" -> "같은 종목 매수 주문이 이미 대기 중입니다"
        upper == "SEED_LIMIT_EXCEEDED" -> "설정한 투자 한도를 초과했습니다"
        upper == "ALREADY_OPEN_POSITION" -> "이미 보유 중인 종목이라 추가 진입이 제한됩니다"
        upper == "STOPLOSS_REENTRY_COOLDOWN" -> "재진입 대기 시간 중이라 지금은 주문할 수 없습니다"
        upper == "KIS_TRADING_DISABLED" -> "실주문 비활성화"
        upper == "BROKER_CREDENTIAL_MISSING" -> "증권사 계정정보 없음"
        upper == "BROKER_ORDER_FAILED" -> "증권사 주문 실패"
        upper == "BROKER_REJECTED" -> "증권사 기준 잔고 또는 주문가능수량 불일치로 거부되었습니다"
        upper.startsWith("MANUAL_ORDER_REJECTED") -> "수동 주문이 증권사에서 거부되었습니다"
        upper == "MANUAL_ORDER_SUBMITTED" -> "수동 주문이 정상 접수되었습니다"
        upper == "RESERVATION_EXECUTED" -> "예약 주문이 실행되어 증권사로 접수되었습니다"
        upper == "RESERVATION_PARTIAL" -> "예약 주문 일부만 접수되었습니다"
        upper == "RESERVATION_NO_SUBMISSION" -> "예약 주문이 실행되었지만 접수된 주문이 없습니다"
        upper == "RESERVATION_EMPTY_AFTER_ITEM_CANCEL" -> "예약 항목이 모두 취소되어 예약이 종료되었습니다"
        upper == "USER_CANCELED" -> "사용자가 예약을 취소했습니다"
        upper.startsWith("MARKET_") && upper.endsWith("_RESERVED") -> "${marketPhaseLabel(upper)} 자동 예약 완료"
        upper.startsWith("MARKET_") && upper.endsWith("_RESERVATION_AVAILABLE") -> "${marketPhaseLabel(upper)}라 예약 주문으로 전환 가능"
        upper.startsWith("MARKET_") && upper.endsWith("_BLOCKED") -> "${marketPhaseLabel(upper)}이며 예약 기능이 꺼져 있어 실행 차단"
        else -> "증권사 응답 사유 확인 필요"
    }
}

private fun resolveReasonActionLabel(reason: String?): String? {
    val upper = reason?.trim()?.uppercase().orEmpty()
    if (upper.isBlank()) return null
    return when {
        upper == "REQUEST_PRICE_REQUIRED" -> "매도가를 입력한 뒤 다시 시도하세요."
        upper == "QTY_ZERO" || upper == "SELLABLE_QTY_ZERO" -> "미체결 주문 여부를 확인하고 잔고를 새로고침하세요."
        upper == "PENDING_SELL_ORDER" || upper == "PENDING_BUY_ORDER" -> "기존 대기 주문의 상태가 바뀐 뒤 다시 주문하세요."
        upper == "SEED_LIMIT_EXCEEDED" -> "주문 금액을 줄이거나 투자 한도를 조정하세요."
        upper == "ALREADY_OPEN_POSITION" -> "기존 보유 종목을 확인한 뒤 추가 주문 여부를 결정하세요."
        upper == "STOPLOSS_REENTRY_COOLDOWN" -> "재진입 대기 시간이 끝난 뒤 다시 주문하세요."
        upper == "BROKER_REJECTED" || upper == "MANUAL_ORDER_REJECTED" -> "잔고를 새로고침한 뒤 다시 시도하세요."
        upper == "BROKER_CREDENTIAL_MISSING" -> "증권사 계정 연동 정보를 먼저 확인하세요."
        upper == "KIS_TRADING_DISABLED" -> "증권사 주문 연동 설정을 활성화하세요."
        upper.startsWith("MARKET_") && upper.endsWith("_RESERVED") -> "거래 이력의 예약 카드에서 실행 시각을 확인하세요."
        upper.startsWith("MARKET_") && upper.endsWith("_BLOCKED") -> "예약 기능을 켜거나 장중에 다시 주문하세요."
        upper == "RESERVATION_PARTIAL" -> "실패 항목을 확인해 수량/가격을 조정한 뒤 재예약하세요."
        upper == "RESERVATION_NO_SUBMISSION" -> "예약 조건과 계좌 상태를 다시 확인하세요."
        else -> null
    }
}

private fun marketPhaseLabel(reasonCode: String): String {
    val up = reasonCode.uppercase()
    return when {
        up.contains("PREOPEN") -> "장 시작 전"
        up.contains("BREAK") -> "장중 휴장 구간"
        up.contains("CLOSED") -> "장 종료"
        up.contains("HOLIDAY") -> "휴장일"
        else -> "주문 불가 시간"
    }
}

private fun extractReasonFromMessage(message: String?): String? {
    val raw = message?.trim().orEmpty()
    if (raw.isBlank()) return null
    if (raw.contains(":")) {
        return raw.split(":").lastOrNull()?.trim().takeIf { !it.isNullOrBlank() }
    }
    return raw.takeIf { it.isNotBlank() }
}

private fun extractSideFromMessage(message: String?): String? {
    val raw = message?.trim()?.uppercase().orEmpty()
    return when {
        raw.startsWith("MANUAL_SELL") -> "SELL"
        raw.startsWith("MANUAL_BUY") -> "BUY"
        else -> null
    }
}
