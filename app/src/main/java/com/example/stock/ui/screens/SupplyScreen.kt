package com.example.stock.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.ChartDailyDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.ChartRange
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.common.CommonReportList
import com.example.stock.ui.common.CommonPillMenu
import com.example.stock.ui.common.CommonSortThemeBar
import com.example.stock.ui.common.GlossaryPresets
import com.example.stock.ui.common.MetricUi
import com.example.stock.ui.common.SelectOptionUi
import com.example.stock.ui.common.SortOptions
import com.example.stock.ui.common.StockChartSheet
import com.example.stock.ui.common.rememberFavoritesController
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.SupplyViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplyScreen() {
    val context = LocalContext.current
    val repo = ServiceLocator.repository(context)
    val vm: SupplyViewModel = viewModel(factory = AppViewModelFactory(repo))
    val settings = repo.getSettings()
    val state = vm.state.value
    val miniCharts = vm.miniChartState
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)

    var sortId by remember { mutableStateOf(SortOptions.AI_SIGNAL_DESC) }
    var query by remember { mutableStateOf("") }
    var selectedFlow by remember { mutableStateOf("") }
    var selectedStrength by remember { mutableStateOf("mid_plus") }
    var selectedConfidence by remember { mutableStateOf("high_mid") }
    var selectedMinValue by remember { mutableStateOf("100m") }
    var selectedContrarian by remember { mutableStateOf("exclude") }
    var selectedDualBuy by remember { mutableStateOf("all") }
    var refreshToken by remember { mutableStateOf(0) }

    var chartOpen by remember { mutableStateOf(false) }
    var chartLoading by remember { mutableStateOf(false) }
    var chartError by remember { mutableStateOf<String?>(null) }
    var chartData by remember { mutableStateOf<ChartDailyDto?>(null) }
    var chartCache by remember { mutableStateOf<Map<ChartRange, ChartDailyDto>>(emptyMap()) }
    var chartQuote by remember { mutableStateOf<RealtimeQuoteItemDto?>(null) }
    var chartTitle by remember { mutableStateOf("") }
    var chartTicker by remember { mutableStateOf("") }
    var chartRange by remember { mutableStateOf(ChartRange.D1) }
    val chartSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(settings.quoteRefreshSec) {
        vm.noteUserInteraction()
        vm.load()
        vm.startPolling(intervalSec = settings.quoteRefreshSec)
        favorites.refresh()
    }
    DisposableEffect(Unit) {
        vm.setScreenActive(true)
        onDispose {
            vm.setScreenActive(false)
            vm.stopPolling()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> vm.setAppForeground(true)
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_PAUSE -> vm.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val itemsAll = state.data?.items.orEmpty()
    val flowOrder = listOf("동반 매수", "외국인 주도", "기관 주도", "개인 역추세")
    fun flowStrengthId(score: Double?): String {
        val s = score ?: 0.0
        return when {
            s >= 78.0 -> "strong"
            s >= 62.0 -> "mid"
            else -> "weak"
        }
    }
    fun minValueThreshold(id: String): Double = when (id) {
        "50m" -> 50_000_000.0
        "100m" -> 100_000_000.0
        "300m" -> 300_000_000.0
        else -> 0.0
    }

    val preFiltered = remember(itemsAll, selectedStrength, selectedConfidence, selectedMinValue, selectedContrarian, selectedDualBuy) {
        itemsAll.filter { item ->
            val score = item.flowScore ?: 0.0
            val strengthPass = when (selectedStrength) {
                "strong" -> flowStrengthId(score) == "strong"
                "mid_plus" -> score >= 62.0
                "weak_cut" -> score >= 50.0
                else -> true
            }
            if (!strengthPass) return@filter false

            val confidence = (item.confidence ?: "").uppercase()
            val confidencePass = when (selectedConfidence) {
                "high_mid" -> confidence == "HIGH" || confidence == "MID"
                "high" -> confidence == "HIGH"
                "mid" -> confidence == "MID"
                "low" -> confidence == "LOW"
                else -> true
            }
            if (!confidencePass) return@filter false

            val minValue = minValueThreshold(selectedMinValue)
            if ((item.value ?: 0.0) < minValue) return@filter false

            if (selectedContrarian == "exclude" && (item.flowLabel ?: "") == "개인 역추세") return@filter false

            if (selectedDualBuy == "only") {
                val foreign3d = item.foreign3d ?: 0
                val institution3d = item.institution3d ?: 0
                if (!(foreign3d > 0 && institution3d > 0)) return@filter false
            }
            true
        }
    }

    val flowCounts = remember(preFiltered) {
        val counts = mutableMapOf<String, Int>()
        preFiltered.forEach { item ->
            val key = item.flowLabel?.trim().orEmpty()
            if (key.isBlank()) return@forEach
            counts[key] = (counts[key] ?: 0) + 1
        }
        flowOrder.mapNotNull { key ->
            val cnt = counts[key] ?: 0
            if (cnt <= 0) null else key to cnt
        }
    }
    val flowOptions = remember(flowCounts) {
        buildList {
            add(SelectOptionUi("", "전체"))
            flowCounts.forEach { (label, cnt) -> add(SelectOptionUi(label, "$label ($cnt)")) }
        }
    }
    val items = remember(preFiltered, selectedFlow) {
        if (selectedFlow.isBlank()) preFiltered else preFiltered.filter { (it.flowLabel ?: "") == selectedFlow }
    }

    val uiItems = items.map { row ->
        val ticker = row.ticker.orEmpty()
        val name = row.name.orEmpty()
        val quote = RealtimeQuoteItemDto(
            ticker = ticker,
            price = row.price ?: 0.0,
            prevClose = row.prevClose ?: 0.0,
            chgPct = row.chgPct ?: 0.0,
            asOf = row.asOf ?: state.data?.asOf ?: "",
            source = row.source ?: "",
            isLive = row.isLive ?: false,
        )
        val tags = row.tags.orEmpty().filter { it.isNotBlank() }
        val themeLine = if (tags.isNotEmpty()) "테마: " + tags.take(3).joinToString(" · ") else null
        val flowLine = listOfNotNull(
            row.flowLabel?.takeIf { it.isNotBlank() },
            row.confidence?.takeIf { it.isNotBlank() }?.let { "신뢰:$it" },
            row.investorSource?.takeIf { it.isNotBlank() }?.let { "원천:$it" },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        val summaryLine = "3일 합 ${fmtSignedQty(row.net3d ?: 0)} · 5일 합 ${fmtSignedQty(row.net5d ?: 0)} · 연속 ${row.buyStreakDays ?: 0}일"
        CommonReportItemUi(
            ticker = ticker,
            name = name,
            market = row.market,
            logoUrl = row.logoPngUrl ?: row.logoUrl,
            title = "$name ($ticker)",
            quote = quote,
            miniPoints = miniCharts[ticker],
            fallbackPrice = row.price,
            fallbackChangePct = row.chgPct,
            fallbackLabel = "현재가",
            metrics = listOf(
                MetricUi("외인3일", (row.foreign3d ?: 0).toDouble(), fmtSignedQty(row.foreign3d ?: 0)),
                MetricUi("기관3일", (row.institution3d ?: 0).toDouble(), fmtSignedQty(row.institution3d ?: 0)),
                MetricUi("합계3일", (row.net3d ?: 0).toDouble(), fmtSignedQty(row.net3d ?: 0)),
            ),
            extraLines = listOfNotNull(themeLine, flowLine, summaryLine),
            sortPrice = row.price,
            sortChangePct = row.chgPct,
            sortName = name,
            sortAiSignal = row.flowScore,
            badgeLabel = "수급 포켓",
            statusTag = row.flowLabel,
            displayReturnPct = row.chgPct,
            eventTags = tags.take(5),
        )
    }

    val sourceLabel = when (state.source) {
        com.example.stock.data.repository.UiSource.CACHE -> "CACHE"
        com.example.stock.data.repository.UiSource.FALLBACK -> "FALLBACK"
        else -> "LIVE"
    }
    val status = when {
        state.loading -> "수급 데이터를 불러오는 중..."
        !state.error.isNullOrBlank() -> "오류: ${state.error}"
        else -> {
            val dto = state.data
            val firstNote = dto?.notes?.firstOrNull()?.takeIf { it.isNotBlank() }
            listOfNotNull(
                "원천 $sourceLabel",
                dto?.basDd?.takeIf { it.isNotBlank() }?.let { "기준일 $it" },
                dto?.universeCount?.let { "유니버스 $it" },
                dto?.candidateQuotes?.let { "시세 $it" },
                firstNote,
            ).joinToString(" · ")
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopBar(
                title = "수급",
                showRefresh = true,
                onRefresh = {
                    refreshToken += 1
                    vm.noteUserInteraction()
                    vm.load()
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        CommonReportList(
            source = state.source,
            statusMessage = status,
            updatedAt = state.refreshedAt,
            header = "오늘의 수급 관찰",
            glossaryDialogTitle = "수급 용어 설명집",
            glossaryItems = GlossaryPresets.SUPPLY,
            items = uiItems,
            emptyText = if (!state.error.isNullOrBlank()) "데이터를 불러오지 못했습니다." else "조건을 충족한 수급 종목이 없습니다.",
            initialDisplayCount = 60,
            refreshToken = refreshToken,
            refreshLoading = state.loading,
            onRefresh = {
                vm.noteUserInteraction()
                refreshToken += 1
                vm.load()
            },
            snackbarHostState = snackbarHostState,
            receivedCount = uiItems.size,
            query = query,
            onQueryChange = {
                vm.noteUserInteraction()
                query = it
            },
            selectedSortId = sortId,
            onSortChange = {
                vm.noteUserInteraction()
                sortId = it
            },
            favoriteTickers = favorites.favoriteTickers,
            onToggleFavorite = { item, desired ->
                favorites.setFavorite(item = item, sourceTab = "수급", desiredFavorite = desired)
            },
            onVisibleTickersChanged = { tickers ->
                vm.ensureMiniCharts(tickers, days = 7)
            },
            filtersContent = {
                val sortOptions = listOf(
                    SelectOptionUi(SortOptions.AI_SIGNAL_DESC, "수급점수↓"),
                )
                CommonSortThemeBar(
                    sortOptions = sortOptions,
                    selectedSortId = sortId,
                    onSortChange = {
                        vm.noteUserInteraction()
                        sortId = it
                    },
                    themeOptions = if (flowOptions.size > 1) flowOptions else null,
                    selectedThemeId = selectedFlow,
                    onThemeChange = {
                        vm.noteUserInteraction()
                        selectedFlow = it
                    },
                    themeLabel = "주체",
                )
                val extraScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .horizontalScroll(extraScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CommonPillMenu(
                        label = "정렬",
                        options = listOf(
                            SelectOptionUi(SortOptions.AI_SIGNAL_DESC, "수급점수↓"),
                            SelectOptionUi(SortOptions.DEFAULT, "기본"),
                            SelectOptionUi(SortOptions.CHANGE_DESC, "등락↓"),
                            SelectOptionUi(SortOptions.CHANGE_ASC, "등락↑"),
                            SelectOptionUi(SortOptions.PRICE_DESC, "가격↓"),
                            SelectOptionUi(SortOptions.PRICE_ASC, "가격↑"),
                            SelectOptionUi(SortOptions.NAME_ASC, "이름↑"),
                            SelectOptionUi(SortOptions.NAME_DESC, "이름↓"),
                        ),
                        selectedId = sortId,
                        onSelect = {
                            vm.noteUserInteraction()
                            sortId = it
                        },
                    )
                    CommonPillMenu(
                        label = "강도",
                        options = listOf(
                            SelectOptionUi("mid_plus", "중간+"),
                            SelectOptionUi("strong", "강함"),
                            SelectOptionUi("weak_cut", "약함제외"),
                            SelectOptionUi("all", "전체"),
                        ),
                        selectedId = selectedStrength,
                        onSelect = {
                            vm.noteUserInteraction()
                            selectedStrength = it
                        },
                    )
                    CommonPillMenu(
                        label = "신뢰",
                        options = listOf(
                            SelectOptionUi("high_mid", "HIGH+MID"),
                            SelectOptionUi("high", "HIGH"),
                            SelectOptionUi("mid", "MID"),
                            SelectOptionUi("low", "LOW"),
                            SelectOptionUi("all", "전체"),
                        ),
                        selectedId = selectedConfidence,
                        onSelect = {
                            vm.noteUserInteraction()
                            selectedConfidence = it
                        },
                    )
                    CommonPillMenu(
                        label = "유동성",
                        options = listOf(
                            SelectOptionUi("100m", "1억+"),
                            SelectOptionUi("50m", "5천만+"),
                            SelectOptionUi("300m", "3억+"),
                            SelectOptionUi("all", "전체"),
                        ),
                        selectedId = selectedMinValue,
                        onSelect = {
                            vm.noteUserInteraction()
                            selectedMinValue = it
                        },
                    )
                    CommonPillMenu(
                        label = "역추세",
                        options = listOf(
                            SelectOptionUi("exclude", "제외"),
                            SelectOptionUi("include", "포함"),
                        ),
                        selectedId = selectedContrarian,
                        onSelect = {
                            vm.noteUserInteraction()
                            selectedContrarian = it
                        },
                    )
                    CommonPillMenu(
                        label = "동반매수",
                        options = listOf(
                            SelectOptionUi("all", "전체"),
                            SelectOptionUi("only", "만"),
                        ),
                        selectedId = selectedDualBuy,
                        onSelect = {
                            vm.noteUserInteraction()
                            selectedDualBuy = it
                        },
                    )
                }
            },
            onItemClick = { item ->
                val ticker = item.ticker.orEmpty()
                chartTitle = item.name ?: ticker
                chartTicker = ticker
                chartQuote = null
                chartOpen = true
                chartRange = ChartRange.D1
                chartCache[chartRange]?.let { cached ->
                    chartData = cached
                    chartLoading = false
                    chartError = null
                } ?: run {
                    chartLoading = true
                    chartError = null
                    chartData = null
                    scope.launch {
                        repo.getChartDaily(ticker, 2)
                            .onSuccess { data ->
                                chartData = data
                                chartCache = chartCache + (chartRange to data)
                            }
                            .onFailure { e -> chartError = e.message ?: "차트 데이터를 불러오지 못했습니다." }
                        chartLoading = false
                    }
                }
                scope.launch {
                    repo.getRealtimeQuotes(listOf(ticker)).onSuccess { map ->
                        chartQuote = map[ticker]
                    }
                }
            },
            modifier = Modifier.padding(inner).fillMaxSize(),
        )
    }

    val sheetMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    if (chartOpen) {
        ModalBottomSheet(
            onDismissRequest = { chartOpen = false },
            sheetState = chartSheetState,
        ) {
            StockChartSheet(
                title = chartTitle,
                ticker = chartTicker,
                quote = chartQuote,
                loading = chartLoading,
                error = chartError,
                data = chartData,
                range = chartRange,
                onRangeChange = { next ->
                    chartRange = next
                    chartCache[next]?.let { cached ->
                        chartData = cached
                        chartLoading = false
                        chartError = null
                    } ?: run {
                        chartLoading = true
                        chartError = null
                        chartData = null
                        val reqDays = if (next == ChartRange.D1) 2 else next.days
                        scope.launch {
                            repo.getChartDaily(chartTicker, reqDays)
                                .onSuccess { data ->
                                    chartData = data
                                    chartCache = chartCache + (next to data)
                                }
                                .onFailure { e -> chartError = e.message ?: "차트 데이터를 불러오지 못했습니다." }
                            chartLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight).padding(16.dp),
            )
        }
    }
}

private fun fmtSignedQty(v: Int): String {
    val absValue = abs(v.toDouble())
    val body = when {
        absValue >= 100_000_000 -> "${"%.2f".format(absValue / 100_000_000)}억주"
        absValue >= 10_000 -> "${"%.1f".format(absValue / 10_000)}만주"
        else -> "%,.0f주".format(absValue)
    }
    return if (v > 0) "+$body" else if (v < 0) "-$body" else "0주"
}
