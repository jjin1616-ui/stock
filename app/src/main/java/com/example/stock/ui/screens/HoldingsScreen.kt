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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val pnlPct: Double,
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
)

private data class HoldingPeriodSummary(
    val periodPnl: Double,
    val periodPnlPct: Double,
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val todayPnl: Double,
)

private data class HoldingContributionRow(
    val ticker: String,
    val name: String,
    val pnlAmount: Double,
    val pnlPct: Double,
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
    val today = LocalDate.now()
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
    var partialSellQtyText by remember { mutableStateOf("") }
    var sellPriceInput by remember { mutableStateOf("") }
    var holdingsExpanded by rememberSaveable { mutableStateOf(false) }
    var showCurrentPrice by remember { mutableStateOf(false) }

    val paperState = vm.accountPaperState.value
    val demoState = vm.accountDemoState.value
    val prodState = vm.accountProdState.value
    val symbolRulesState = vm.symbolRulesState.value
    val ordersState = vm.ordersState.value
    val performanceState = vm.performanceState.value
    val actionState = vm.actionState.value
    val quoteMap = vm.holdingQuoteState.value

    LaunchedEffect(Unit) { vm.loadAll() }

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
    LaunchedEffect(actionMessage) {
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
    val statusMessage = actionState.error ?: paperState.error ?: demoState.error ?: prodState.error ?: performanceState.error ?: symbolRulesState.error
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
    }

    val ruleMap = remember(symbolRulesState.data) {
        symbolRulesState.data?.items.orEmpty().associateBy { it.ticker?.trim().orEmpty() }
    }

    val refreshAction = {
        vm.loadAll()
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
        val daysToFetch = ((today.toEpochDay() - range.start.toEpochDay()).toInt() + 1).coerceIn(1, 365)
        vm.loadPerformance(days = daysToFetch)
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
                    rangeLabel = rangeLabel,
                    loading = ordersState.loading,
                    error = ordersState.error,
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
                    sellPriceInput = selectedHolding?.currentPrice?.roundToLong()?.toString().orEmpty()
                    showFullSellDialog = true
                },
                onSellPartial = {
                    partialSellQtyText = selectedHolding?.qty?.toString().orEmpty()
                    sellPriceInput = selectedHolding?.currentPrice?.roundToLong()?.toString().orEmpty()
                    showPartialSellDialog = true
                },
            )
        }
    }

    if (showFullSellDialog && selectedHolding != null) {
        val detail = selectedHolding!!
        val sellPrice = parsePriceInput(sellPriceInput)
        AlertDialog(
            onDismissRequest = { showFullSellDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    if (sellPrice != null) {
                        vm.runManualSell(
                            ticker = detail.ticker,
                            name = detail.name,
                            mode = detail.env,
                            qty = detail.qty,
                            requestPrice = sellPrice,
                            marketOrder = false,
                        )
                        showFullSellDialog = false
                    }
                }, enabled = sellPrice != null) { Text("전량 매도") }
            },
            dismissButton = {
                TextButton(onClick = { showFullSellDialog = false }) { Text("취소") }
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
                }
            },
        )
    }

    if (showPartialSellDialog && selectedHolding != null) {
        val detail = selectedHolding!!
        val sellPrice = parsePriceInput(sellPriceInput)
        AlertDialog(
            onDismissRequest = { showPartialSellDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val qty = partialSellQtyText.trim().toIntOrNull()?.coerceIn(1, detail.qty) ?: 0
                    if (qty > 0 && sellPrice != null) {
                        vm.runManualSell(
                            ticker = detail.ticker,
                            name = detail.name,
                            mode = detail.env,
                            qty = qty,
                            requestPrice = sellPrice,
                            marketOrder = false,
                        )
                    }
                    showPartialSellDialog = false
                }, enabled = (partialSellQtyText.trim().toIntOrNull() ?: 0) > 0 && sellPrice != null) { Text("부분 매도") }
            },
            dismissButton = {
                TextButton(onClick = { showPartialSellDialog = false }) { Text("취소") }
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
    holdingSummary: HoldingSummary,
    rangeLabel: String,
    periodLabel: String,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    CommonFilterCard(title = "수익률 현황", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "기준: $periodLabel · $rangeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            if (!error.isNullOrBlank()) {
                Text(
                    "기간 손익 로딩 실패: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFDC2626),
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "기간 손익", value = formatSignedWon(summary.periodPnl))
                SummaryMetricCell(label = "기간 수익률", value = formatSignedPct(summary.periodPnlPct))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "실현 손익", value = formatSignedWon(summary.realizedPnl))
                SummaryMetricCell(label = "미실현 손익", value = formatSignedWon(summary.unrealizedPnl))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryMetricCell(label = "오늘 손익", value = formatSignedWon(summary.todayPnl))
                SummaryMetricCell(label = "보유 수익률", value = formatSignedPct(holdingSummary.pnlPct))
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
                SummaryMetricCell(label = "수익률", value = formatSignedPct(summary.pnlPct))
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
        return HoldingSummary(count = 0, totalCost = 0.0, totalEval = 0.0, totalPnl = 0.0, pnlPct = 0.0)
    }
    val totalCost = rows.sumOf { it.avgPrice * it.qty.toDouble() }
    val totalEval = rows.sumOf { row ->
        val current = quoteMap[row.ticker]?.price?.takeIf { it > 0.0 } ?: row.currentPrice
        current * row.qty.toDouble()
    }
    val totalPnl = totalEval - totalCost
    val pnlPct = if (totalCost > 0.0) (totalPnl / totalCost) * 100.0 else 0.0
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
    return HoldingAccountSnapshotSummary(
        orderableCash = sumOf { it.orderableCashKrw },
        cash = sumOf { it.cashKrw },
        stockEval = sumOf { it.stockEvalKrw },
        totalAsset = sumOf { it.totalAssetKrw },
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
            realizedPnl = 0.0,
            unrealizedPnl = 0.0,
            todayPnl = 0.0,
        )
    }
    val inRange = dated.filter { (date, _) ->
        !date.isBefore(range.start) && !date.isAfter(range.end)
    }
    if (inRange.isEmpty()) {
        return HoldingPeriodSummary(
            periodPnl = 0.0,
            periodPnlPct = 0.0,
            realizedPnl = 0.0,
            unrealizedPnl = 0.0,
            todayPnl = 0.0,
        )
    }
    val lastPair = inRange.last()
    val beforeRange = dated.lastOrNull { (date, _) -> date.isBefore(range.start) }?.second
    val beforeLast = dated.lastOrNull { (date, _) -> date.isBefore(lastPair.first) }?.second
    val lastItem = lastPair.second
    val periodPnl = totalPnl(lastItem) - totalPnl(beforeRange)
    val periodPnlPct = (lastItem.roiPct ?: 0.0) - (beforeRange?.roiPct ?: 0.0)
    val realizedPnl = (lastItem.realizedPnlKrw ?: 0.0) - (beforeRange?.realizedPnlKrw ?: 0.0)
    val unrealizedPnl = lastItem.unrealizedPnlKrw ?: 0.0
    val todayBase = beforeLast ?: beforeRange
    val todayPnl = totalPnl(lastItem) - totalPnl(todayBase)
    val clampedToday = if (lastPair.first == today) todayPnl else 0.0
    return HoldingPeriodSummary(
        periodPnl = periodPnl,
        periodPnlPct = periodPnlPct,
        realizedPnl = realizedPnl,
        unrealizedPnl = unrealizedPnl,
        todayPnl = clampedToday,
    )
}

private fun totalPnl(item: AutoTradePerformanceItemDto?): Double {
    val realized = item?.realizedPnlKrw ?: 0.0
    val unrealized = item?.unrealizedPnlKrw ?: 0.0
    return realized + unrealized
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
private fun HoldingsOrderHistoryCard(
    orders: List<AutoTradeOrderItemDto>,
    rangeLabel: String,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    CommonFilterCard(title = "거래 이력", modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "기준 기간: $rangeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
            )
            when {
                loading && orders.isEmpty() -> {
                    Text("거래 이력 불러오는 중...", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
                !error.isNullOrBlank() && orders.isEmpty() -> {
                    Text("거래 이력 로딩 실패: $error", color = Color(0xFFDC2626), style = MaterialTheme.typography.bodySmall)
                }
                orders.isEmpty() -> {
                    Text("거래 이력이 없습니다.", color = Color(0xFF6B7280), style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    orders.take(15).forEach { order ->
                        HoldingsOrderRow(order = order)
                    }
                    if (orders.size > 15) {
                        Text("외 ${orders.size - 15}건", color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldingsOrderRow(order: AutoTradeOrderItemDto) {
    val sideLabel = orderSideLabel(order.side)
    val statusLabel = orderStatusLabel(order.status)
    val price = order.requestedPrice?.takeIf { it > 0.0 }
        ?: order.filledPrice?.takeIf { it > 0.0 }
        ?: order.currentPrice?.takeIf { it > 0.0 }
    val priceLabel = price?.let { formatWonExact(it) } ?: "-"
    val qtyLabel = order.qty?.takeIf { it > 0 }?.let { "${it}주" } ?: "-"
    val timeLabel = formatOrderTime(order.requestedAt ?: order.filledAt)
    val sideColor = if (sideLabel == "매도") Color(0xFFE11D48) else Color(0xFF2563EB)
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
                    Text(sideLabel, color = sideColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text(order.name?.ifBlank { order.ticker.orEmpty() } ?: order.ticker.orEmpty(), fontWeight = FontWeight.SemiBold)
                }
                Text("${order.ticker.orEmpty()} · $qtyLabel · $priceLabel", color = Color(0xFF64748B), style = MaterialTheme.typography.labelSmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(statusLabel, color = Color(0xFF334155), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text(timeLabel, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
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
    val reasonLabel = resolveOrderReasonLabel(order?.reason, rawMessage)
    val parts = mutableListOf<String>()
    parts += if (side.isNotBlank()) "$side 요청 완료" else "요청 완료"
    if (!statusLabel.isNullOrBlank()) parts += statusLabel
    if (!reasonLabel.isNullOrBlank()) parts += reasonLabel
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

private fun orderSideLabel(raw: String?): String {
    val s = raw?.trim()?.uppercase().orEmpty()
    return if (s == "SELL") "매도" else "매수"
}

private fun orderStatusLabel(raw: String?): String {
    return when (raw?.trim()?.uppercase()) {
        "PAPER_FILLED" -> "내부모의체결"
        "BROKER_SUBMITTED" -> "증권사접수"
        "BROKER_FILLED" -> "증권사체결"
        "BROKER_REJECTED" -> "증권사거부"
        "SKIPPED" -> "스킵"
        "ERROR" -> "오류"
        else -> "기타"
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
        upper == "KIS_TRADING_DISABLED" -> "실주문 비활성화"
        upper == "BROKER_CREDENTIAL_MISSING" -> "증권사 계정정보 없음"
        upper == "BROKER_ORDER_FAILED" -> "증권사 주문 실패"
        upper == "BROKER_REJECTED" -> "증권사 거부"
        else -> "처리 사유 확인 필요"
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
