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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.repository.UiSource
import com.example.stock.data.api.ChartDailyDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.ChartRange
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.common.CommonReportList
import com.example.stock.ui.common.StockChartSheet
import com.example.stock.ui.common.MetricUi
import com.example.stock.ui.common.SortOptions
import com.example.stock.ui.common.SelectOptionUi
import com.example.stock.ui.common.CommonSortThemeBar
import com.example.stock.ui.common.rememberFavoritesController
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.MoversViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoversScreen() {
    val context = LocalContext.current
    val repo = ServiceLocator.repository(context)
    val vm: MoversViewModel = viewModel(factory = AppViewModelFactory(repo))
    val settings = repo.getSettings()
    val state = vm.state.value
    val miniCharts = vm.miniChartState
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf("chg") } // chg|chg_down|value|volume|value_ratio|popular
    var period by remember { mutableStateOf("1d") } // 1d|1w|1m|3m|6m|1y
    var sortId by remember { mutableStateOf(SortOptions.DEFAULT) }
    var query by remember { mutableStateOf("") }
    var selectedThemeTag by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)

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

    LaunchedEffect(mode, period, settings.quoteRefreshSec) {
        vm.noteUserInteraction()
        vm.load(mode = mode, period = period)
        vm.startPolling(mode = mode, period = period, intervalSec = settings.quoteRefreshSec)
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
        val chgPct = m.chgPct ?: 0.0
        val value = m.value ?: 0.0
        val volume = m.volume ?: 0.0
        val prevClose = m.prevClose ?: 0.0
        val rank = m.rank
        val searchRatio = m.searchRatio
        val ratio = m.valueRatio
        val ratioPct = if (ratio != null) ratio * 100.0 else null
        val tags = m.tags.orEmpty().filter { it.isNotBlank() }
        val tagLine = if (tags.isNotEmpty()) "테마: " + tags.take(3).joinToString(" · ") else null
        val quote = RealtimeQuoteItemDto(
            ticker = ticker,
            price = price,
            prevClose = prevClose,
            chgPct = chgPct,
            asOf = m.asOf ?: state.data?.asOf ?: "",
            source = m.source ?: "",
            isLive = m.isLive ?: false,
        )
        val metrics = if (mode == "popular") {
            listOf(
                MetricUi("인기순위", (rank ?: 0).toDouble()),
                MetricUi("등락%", chgPct),
                MetricUi("거래량", volume),
            )
        } else {
            listOf(
                MetricUi("등락%", chgPct),
                MetricUi("거래대금", value),
                MetricUi("거래량", volume),
            )
        }
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
            metrics = metrics,
            extraLines = listOfNotNull(
                tagLine,
                searchRatio?.let { "네이버 인기 검색비율 ${"%.2f".format(it)}%" },
                ratioPct?.let { "전일 거래대금 대비 ${(it).toInt()}% 수준" }
            ),
            thesis = null,
            sortPrice = price,
            sortChangePct = chgPct,
            sortName = name,
            miniPoints = miniCharts[ticker],
            badgeLabel = "급등 포켓",
            displayReturnPct = chgPct,
            eventTags = tags.take(5),
        )
    }

    val status = when {
        state.loading -> "불러오는 중..."
        !state.error.isNullOrBlank() -> "오류: ${state.error}"
        else -> {
            val modeLabel = when (mode) {
                "chg_down" -> "급하락TOP"
                "value" -> "거래대금TOP"
                "volume" -> "거래량TOP"
                "value_ratio" -> "대금급증TOP"
                "popular" -> "인기TOP"
                else -> "등락TOP"
            }
            "모드 $modeLabel · 기간 ${period.uppercase()} (기준일 ${state.data?.basDd ?: "-"})"
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            val doRefresh = {
                refreshToken += 1
                vm.load(mode = mode, period = period)
            }
            AppTopBar(
                title = "급등주",
                showRefresh = true,
                onRefresh = {
                    doRefresh()
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        CommonReportList(
            source = UiSource.LIVE,
            statusMessage = status,
            updatedAt = state.refreshedAt,
            header = "실시간 TOP100 무버",
            items = uiItems,
            emptyText = if (!state.error.isNullOrBlank()) "데이터를 불러오지 못했습니다." else "표시할 종목이 없습니다.",
            initialDisplayCount = 100,
            refreshToken = refreshToken,
            refreshLoading = state.loading,
            onRefresh = {
                vm.noteUserInteraction()
                refreshToken += 1
                vm.load(mode = mode, period = period)
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
                        val reqDays = 2 // D1 needs prev close too
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
                    fun ModePill(text: String, selected: Boolean, onClick: () -> Unit) {
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
                        ModePill("등락TOP", mode == "chg") { vm.noteUserInteraction(); mode = "chg" }
                        ModePill("급하락TOP", mode == "chg_down") { vm.noteUserInteraction(); mode = "chg_down" }
                        ModePill("거래대금", mode == "value") { vm.noteUserInteraction(); mode = "value" }
                        ModePill("거래량", mode == "volume") { vm.noteUserInteraction(); mode = "volume" }
                        ModePill("대금급증", mode == "value_ratio") { vm.noteUserInteraction(); mode = "value_ratio" }
                        ModePill("인기TOP", mode == "popular") { vm.noteUserInteraction(); mode = "popular" }
                    }
                }
                item {
                    @Composable
                    fun PeriodPill(text: String, selected: Boolean, onClick: () -> Unit) {
                        val bg = if (selected) Color(0xFF111827) else Color(0xFFE5E7EB)
                        val fg = if (selected) Color.White else Color(0xFF374151)
                        Box(
                            modifier = Modifier
                                .background(bg, RoundedCornerShape(999.dp))
                                .clickable { onClick() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text,
                                color = fg,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PeriodPill("1일", period == "1d") { vm.noteUserInteraction(); period = "1d" }
                        PeriodPill("1주", period == "1w") { vm.noteUserInteraction(); period = "1w" }
                        PeriodPill("1개월", period == "1m") { vm.noteUserInteraction(); period = "1m" }
                        PeriodPill("3개월", period == "3m") { vm.noteUserInteraction(); period = "3m" }
                        PeriodPill("6개월", period == "6m") { vm.noteUserInteraction(); period = "6m" }
                        PeriodPill("1년", period == "1y") { vm.noteUserInteraction(); period = "1y" }
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
