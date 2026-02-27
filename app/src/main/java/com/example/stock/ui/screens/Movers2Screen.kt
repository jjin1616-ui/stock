package com.example.stock.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.ChartDailyDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.repository.UiSource
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.ChartRange
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.common.CommonReportList
import com.example.stock.ui.common.CommonSortThemeBar
import com.example.stock.ui.common.GlossaryPresets
import com.example.stock.ui.common.MetricUi
import com.example.stock.ui.common.SelectOptionUi
import com.example.stock.ui.common.SortOptions
import com.example.stock.ui.common.StockChartSheet
import com.example.stock.ui.common.rememberFavoritesController
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.Movers2ViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

private val movers2SessionDefs = listOf(
    Triple("preopen", "장전", "장전(예상체결)"),
    Triple("regular", "정규장 급등", "정규장 급등"),
    Triple("spike", "급등 스파이크", "정규장 급등 스파이크"),
    Triple("closecall", "장마감", "장마감(예상체결)"),
    Triple("afterclose", "시간외 종가", "시간외 종가(장후)"),
    Triple("afterauction", "시간외 단일가", "시간외 단일가"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Movers2Screen() {
    val context = LocalContext.current
    val repo = ServiceLocator.repository(context)
    val vm: Movers2ViewModel = viewModel(factory = AppViewModelFactory(repo))
    val settings = repo.getSettings()
    val state = vm.state.value
    val miniCharts = vm.miniChartState
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)

    var session by remember { mutableStateOf("regular") }
    var direction by remember { mutableStateOf("up") }
    var sortId by remember { mutableStateOf(SortOptions.DEFAULT) }
    var query by remember { mutableStateOf("") }
    var selectedThemeTag by remember { mutableStateOf("") }
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

    LaunchedEffect(session, direction, settings.quoteRefreshSec) {
        vm.noteUserInteraction()
        vm.load(session = session, direction = direction)
        vm.startPolling(session = session, direction = direction, intervalSec = settings.quoteRefreshSec)
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
    val themeCounts = remember(itemsAll) {
        val counts = mutableMapOf<String, Int>()
        for (it in itemsAll) {
            for (t in it.tags.orEmpty()) {
                val key = t.trim()
                if (key.isBlank()) continue
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(20)
    }
    val themeOptions = remember(themeCounts) {
        buildList {
            add(SelectOptionUi("", "전체"))
            themeCounts.forEach { e -> add(SelectOptionUi(e.key, "${e.key} (${e.value})")) }
        }
    }
    val items = remember(itemsAll, selectedThemeTag) {
        if (selectedThemeTag.isBlank()) itemsAll else itemsAll.filter { it.tags.orEmpty().any { t -> t == selectedThemeTag } }
    }

    val uiItems = items.map { m ->
        val ticker = m.ticker.orEmpty()
        val name = m.name.orEmpty()
        val price = m.price ?: 0.0
        val prevClose = m.prevClose ?: 0.0
        val chgPct = m.chgPct ?: 0.0
        val value = m.value ?: 0.0
        val volume = m.volume ?: 0.0
        val flowSource = m.flowSource.orEmpty()
        val metricName = m.metricName.orEmpty()
        val metricValue = m.metricValue ?: 0.0
        val metric = if (metricName == "value_ratio_adj") {
            MetricUi("스파이크", metricValue, "${"%.2f".format(metricValue)}x")
        } else {
            MetricUi("기준%", metricValue)
        }
        val valueMetric = if (value > 0.0) MetricUi("거래대금", value) else MetricUi("거래대금", 0.0, "미수신")
        val volumeMetric = if (volume > 0.0) MetricUi("거래량", volume) else MetricUi("거래량", 0.0, "미수신")
        val quote = RealtimeQuoteItemDto(
            ticker = ticker,
            price = price,
            prevClose = prevClose,
            chgPct = chgPct,
            asOf = m.asOf ?: state.data?.asOf ?: "",
            source = m.source ?: "",
            isLive = m.isLive ?: false,
        )
        val tags = m.tags.orEmpty().filter { it.isNotBlank() }
        val tagLine = when {
            tags.isEmpty() -> null
            tags.size == 1 -> "테마:${tags.first()}"
            else -> "테마:${tags.first()}+${tags.size - 1}"
        }
        val quality = m.quality.orEmpty()
        val qualityLabel = if (quality.isNotBlank()) "품질:$quality" else null
        val sessionKey = m.session.orEmpty()
        val priceLabel = if (sessionKey == "regular" || sessionKey == "spike") "현재" else "세션"
        val basisLine = if ((m.basisPrice ?: 0.0) > 0.0 && (m.sessionPrice ?: 0.0) > 0.0) {
            "기준 ${fmtShortNum(m.basisPrice ?: 0.0)}→$priceLabel ${fmtShortNum(m.sessionPrice ?: 0.0)}"
        } else null
        val ratioLine = (m.valueRatio ?: 0.0).takeIf { it > 0.0 }?.let { "대금x${"%.1f".format(it)}" }
        val flowLine = when (flowSource) {
            "SESSION" -> "지표:세션체결"
            "REGULAR_FALLBACK" -> "지표:정규장대체"
            "REGULAR" -> "지표:정규장누적"
            "MISSING" -> "지표:미수신"
            else -> null
        }
        val compactLine1 = listOfNotNull(tagLine, basisLine).joinToString(" · ").takeIf { it.isNotBlank() }
        val compactLine2 = listOfNotNull(ratioLine, flowLine, qualityLabel).joinToString(" · ").takeIf { it.isNotBlank() }
        CommonReportItemUi(
            ticker = ticker,
            name = name,
            market = m.market,
            logoUrl = m.logoPngUrl ?: m.logoUrl,
            title = "$name ($ticker)",
            quote = quote,
            fallbackPrice = price,
            fallbackChangePct = chgPct,
            fallbackLabel = "현재가",
            metrics = listOf(metric, valueMetric, volumeMetric),
            extraLines = listOfNotNull(compactLine1, compactLine2),
            thesis = null,
            sortPrice = price,
            sortChangePct = chgPct,
            sortName = name,
            miniPoints = miniCharts[ticker],
            badgeLabel = "세션 포켓",
            displayReturnPct = chgPct,
            eventTags = tags.take(5),
        )
    }

    val sessionLabel = state.data?.sessionLabel ?: movers2SessionDefs.firstOrNull { it.first == session }?.third ?: ""
    val dataState = (state.data?.dataState ?: "LIVE").uppercase()
    val stateLabel = when (dataState) {
        "SNAPSHOT" -> "스냅샷"
        "APPROX" -> "추정"
        else -> "실시간"
    }
    val snapshotLabel = state.data?.snapshotAsOf?.takeIf { it.isNotBlank() }?.replace("T", " ")?.take(16)
    val status = when {
        state.loading -> "불러오는 중..."
        !state.error.isNullOrBlank() -> "오류: ${state.error}"
        else -> {
            val dirLabel = if (direction == "down") "급락" else "급등"
            buildString {
                append("$stateLabel · 세션 $sessionLabel · 방향 $dirLabel · 기준일 ${state.data?.basDd ?: "-"} · 유니버스 ${state.data?.universeCount ?: 0}")
                if (stateLabel == "스냅샷" && !snapshotLabel.isNullOrBlank()) {
                    append(" · 스냅샷 $snapshotLabel")
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopBar(
                title = "급등",
                showRefresh = true,
                onRefresh = {
                    refreshToken += 1
                    vm.load(session = session, direction = direction)
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        CommonReportList(
            source = UiSource.LIVE,
            statusMessage = status,
            updatedAt = state.refreshedAt,
            header = "세션 기반 급등 탐지",
            glossaryDialogTitle = "급등 용어 설명집",
            glossaryItems = GlossaryPresets.MOVERS,
            items = uiItems,
            emptyText = if (!state.error.isNullOrBlank()) "데이터를 불러오지 못했습니다." else "표시할 종목이 없습니다.",
            initialDisplayCount = 100,
            refreshToken = refreshToken,
            refreshLoading = state.loading,
            onRefresh = {
                vm.noteUserInteraction()
                refreshToken += 1
                vm.load(session = session, direction = direction)
            },
            snackbarHostState = snackbarHostState,
            receivedCount = uiItems.size,
            query = query,
            onQueryChange = {
                vm.noteUserInteraction()
                query = it
            },
            selectedSortId = sortId,
            onSortChange = { next ->
                vm.noteUserInteraction()
                sortId = next
                when (next) {
                    SortOptions.CHANGE_ASC -> direction = "down"
                    SortOptions.CHANGE_DESC,
                    SortOptions.DEFAULT -> direction = "up"
                }
            },
            favoriteTickers = favorites.favoriteTickers,
            onToggleFavorite = { item, desired ->
                favorites.setFavorite(item = item, sourceTab = "급등", desiredFavorite = desired)
            },
            onVisibleTickersChanged = { tickers ->
                vm.ensureMiniCharts(tickers, days = 7)
            },
            filtersContent = {
                val sortOptions = listOf(
                    SelectOptionUi(SortOptions.DEFAULT, "기본"),
                    SelectOptionUi(SortOptions.PRICE_ASC, "가격↑"),
                    SelectOptionUi(SortOptions.PRICE_DESC, "가격↓"),
                    SelectOptionUi(SortOptions.CHANGE_ASC, "등락↑"),
                    SelectOptionUi(SortOptions.CHANGE_DESC, "등락↓"),
                    SelectOptionUi(SortOptions.NAME_ASC, "이름↑"),
                    SelectOptionUi(SortOptions.NAME_DESC, "이름↓"),
                )
                CommonSortThemeBar(
                    sortOptions = sortOptions,
                    selectedSortId = sortId,
                    onSortChange = {
                        vm.noteUserInteraction()
                        sortId = it
                    },
                    themeOptions = if (themeCounts.isNotEmpty()) themeOptions else null,
                    selectedThemeId = selectedThemeTag,
                    onThemeChange = {
                        vm.noteUserInteraction()
                        selectedThemeTag = it
                    },
                )
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
                        val reqDays = 2
                        repo.getChartDaily(ticker, reqDays)
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
            topContent = {
                item {
                    @Composable
                    fun SessionPill(text: String, selected: Boolean, onClick: () -> Unit) {
                        val bg = if (selected) Color.White else Color.Transparent
                        val fg = if (selected) Color(0xFF111827) else Color(0xFF9AA5B1)
                        Box(
                            modifier = Modifier
                                .background(bg, RoundedCornerShape(999.dp))
                                .clickable { onClick() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text,
                                color = fg,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFEEF1F4), RoundedCornerShape(14.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        movers2SessionDefs.forEach { def ->
                            SessionPill(
                                text = def.second,
                                selected = session == def.first,
                                onClick = {
                                    vm.noteUserInteraction()
                                    session = def.first
                                },
                            )
                        }
                    }
                }
            },
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

private fun fmtShortNum(v: Double): String {
    val n = abs(v)
    return when {
        n >= 100_000_000.0 -> "${"%.1f".format(v / 100_000_000.0)}억"
        n >= 10_000.0 -> "${"%.1f".format(v / 10_000.0)}만"
        else -> "%,.0f".format(v)
    }
}
