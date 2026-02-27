package com.example.stock.ui.screens

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.ServiceLocator
import com.example.stock.data.api.NewsArticleItemDto
import com.example.stock.data.api.NewsArticlesResponseDto
import com.example.stock.data.api.NewsClusterListItemDto
import com.example.stock.data.api.NewsClustersResponseDto
import com.example.stock.data.api.NewsMetaDto
import com.example.stock.data.api.NewsThemeCardDto
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.CommonFilterCard
import com.example.stock.ui.common.CommonPillChip
import com.example.stock.ui.common.CommonPillMenu
import com.example.stock.ui.common.CommonReportItemCard
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.common.CommonReportList
import com.example.stock.ui.common.GlossaryPresets
import com.example.stock.ui.common.MetricUi
import com.example.stock.ui.common.SelectOptionUi
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.NewsViewModel
import com.example.stock.viewmodel.UiState
import java.time.OffsetDateTime

private enum class NewsMode { THEMES, CLUSTERS, ARTICLES, CLUSTER_ARTICLES }

private fun themeLabel(key: String): String = when (key.uppercase()) {
    "SEMICON" -> "반도체"
    "BATTERY" -> "2차전지"
    "AI_DC" -> "AI/데이터센터"
    "BIO" -> "바이오"
    "DEFENSE" -> "방산"
    "POWER" -> "원전/전력"
    "AUTO" -> "자동차"
    "SHIP" -> "조선/해운"
    "CHEM" -> "화학/소재"
    "GAME" -> "게임/콘텐츠"
    "FIN" -> "금융"
    "PLATFORM" -> "플랫폼"
    "CHINA_FX" -> "중국/환율"
    "POLICY" -> "정책/규제"
    else -> key.ifBlank { "기타" }
}

private fun sourceFilterToParam(sourceFilter: String): String = when (sourceFilter) {
    "dart" -> "dart"
    "rss" -> "rss"
    else -> "all"
}

private fun modeFromPref(value: String): NewsMode = when (value.trim().uppercase()) {
    "CLUSTERS", "CLUSTER" -> NewsMode.CLUSTERS
    "ARTICLES", "ARTICLE" -> NewsMode.ARTICLES
    else -> NewsMode.THEMES
}

private fun modeToPref(mode: NewsMode): String = when (mode) {
    NewsMode.THEMES -> "HOT"
    NewsMode.CLUSTERS -> "CLUSTERS"
    NewsMode.ARTICLES -> "ARTICLES"
    NewsMode.CLUSTER_ARTICLES -> "ARTICLES"
}

private fun normalizeWindow(window: String): String = when (window.trim().lowercase()) {
    "10m", "1h", "24h", "7d" -> window.trim().lowercase()
    else -> "24h"
}

private fun fmtShortTs(raw: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.isBlank()) return ""
    return runCatching {
        val dt = OffsetDateTime.parse(s)
        val hh = dt.hour.toString().padStart(2, '0')
        val mm = dt.minute.toString().padStart(2, '0')
        "${dt.monthValue}/${dt.dayOfMonth} $hh:$mm"
    }.getOrElse { s.take(16) }
}

@Composable
fun NewsScreen() {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: NewsViewModel = viewModel(factory = AppViewModelFactory(repo))
    val appSettings = remember(context) { repo.getSettings() }

    val initialWindow = remember(appSettings) {
        normalizeWindow(if (appSettings.newsRestoreLastFilters) appSettings.newsLastWindow else appSettings.newsDefaultWindow)
    }
    val initialMode = remember(appSettings) {
        if (appSettings.newsRestoreLastFilters) modeFromPref(appSettings.newsLastMode) else modeFromPref(appSettings.newsDefaultMode)
    }
    val initialSource = remember(appSettings) {
        val raw = if (appSettings.newsRestoreLastFilters) appSettings.newsLastSource else appSettings.newsDefaultSource
        when (raw.trim()) {
            "dart", "rss", "ALL", "all" -> if (raw.trim().equals("all", ignoreCase = true)) "ALL" else raw.trim()
            else -> "ALL"
        }
    }
    val initialEvent = remember(appSettings) {
        if (appSettings.newsRestoreLastFilters) appSettings.newsLastEvent else ""
    }
    val initialHideRisk = remember(appSettings) {
        if (appSettings.newsRestoreLastFilters) appSettings.newsLastHideRisk else appSettings.newsDefaultHideRisk
    }

    var window by remember { mutableStateOf(initialWindow) }
    var mode by remember { mutableStateOf(initialMode) }
    var previousListMode by remember { mutableStateOf(initialMode) }
    var selectedThemeKey by remember { mutableStateOf<String?>(null) }
    var selectedClusterId by remember { mutableStateOf<Int?>(null) }
    var selectedClusterTitle by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableStateOf(0) }
    var scrollToTopToken by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }

    // Detail filters
    var selectedMentionTicker by remember { mutableStateOf<String?>(null) }
    var sourceFilter by remember { mutableStateOf(initialSource) } // ALL|dart|rss
    var eventFilter by remember { mutableStateOf(initialEvent) } // empty = ALL
    var hideRisk by remember { mutableStateOf(initialHideRisk) }
    var showAllMentionStocks by remember { mutableStateOf(false) }

    val themesState = vm.themesState.value
    val clustersState = vm.clustersState.value
    val articlesState = vm.articlesState.value
    val clusterState = vm.clusterState.value

    val sourceParam = sourceFilterToParam(sourceFilter)
    val eventParam = eventFilter.trim().ifBlank { null }
    val themeKeyParam = selectedThemeKey?.trim()?.takeIf { it.isNotBlank() }
    val showBackPill = mode == NewsMode.CLUSTER_ARTICLES ||
        ((mode == NewsMode.CLUSTERS || mode == NewsMode.ARTICLES) && !themeKeyParam.isNullOrBlank())
    val articleTextSizeSp = appSettings.newsArticleTextSizeSp.toFloat().coerceIn(13f, 18f)

    fun handleBack() {
        when (mode) {
            NewsMode.CLUSTER_ARTICLES -> {
                mode = previousListMode
                selectedClusterId = null
                selectedClusterTitle = null
                scrollToTopToken += 1
            }

            NewsMode.CLUSTERS,
            NewsMode.ARTICLES -> {
                if (!themeKeyParam.isNullOrBlank()) {
                    selectedThemeKey = null
                    mode = NewsMode.THEMES
                    scrollToTopToken += 1
                }
            }

            else -> {}
        }
    }

    // Android system back: keep navigation within the News drill-down first.
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestHandleBack by rememberUpdatedState(newValue = { handleBack() })
    val newsBackCallback = remember {
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                latestHandleBack()
            }
        }
    }
    LaunchedEffect(backDispatcher, lifecycleOwner) {
        val dispatcher = backDispatcher ?: return@LaunchedEffect
        withFrameNanos { /* one frame */ }
        newsBackCallback.remove()
        dispatcher.addCallback(lifecycleOwner, newsBackCallback)
    }
    LaunchedEffect(showBackPill, backDispatcher, lifecycleOwner) {
        val dispatcher = backDispatcher ?: return@LaunchedEffect
        newsBackCallback.isEnabled = showBackPill
        if (showBackPill) {
            newsBackCallback.remove()
            dispatcher.addCallback(lifecycleOwner, newsBackCallback)
        }
    }
    DisposableEffect(lifecycleOwner) {
        onDispose { newsBackCallback.remove() }
    }

    LaunchedEffect(mode, window, sourceParam, eventParam, hideRisk, themeKeyParam) {
        when (mode) {
            NewsMode.THEMES -> vm.loadThemes(
                window = window,
                source = sourceParam,
                eventType = eventParam,
                hideRisk = hideRisk,
            )

            NewsMode.CLUSTERS -> vm.loadClusters(
                window = window,
                themeKey = themeKeyParam,
                source = sourceParam,
                eventType = eventParam,
                hideRisk = hideRisk,
                sort = "hot",
                limit = 300,
            )

            NewsMode.ARTICLES -> vm.loadArticles(
                window = window,
                themeKey = themeKeyParam,
                source = sourceParam,
                eventType = eventParam,
                hideRisk = hideRisk,
                sort = "latest",
                limit = 300,
            )

            NewsMode.CLUSTER_ARTICLES -> {}
        }
    }

    LaunchedEffect(mode, selectedClusterId) {
        if (mode != NewsMode.CLUSTER_ARTICLES) {
            selectedMentionTicker = null
            showAllMentionStocks = false
            return@LaunchedEffect
        }
        selectedMentionTicker = null
        showAllMentionStocks = false
        val id = selectedClusterId
        if (id != null && id > 0) {
            vm.loadCluster(id)
        }
    }

    LaunchedEffect(mode, selectedThemeKey) {
        query = ""
    }

    LaunchedEffect(window, mode, sourceFilter, eventFilter, hideRisk, appSettings.newsRestoreLastFilters) {
        if (!appSettings.newsRestoreLastFilters) return@LaunchedEffect
        vm.saveNewsLastFilters(
            window = window,
            mode = modeToPref(mode),
            source = sourceFilter,
            event = eventFilter,
            hideRisk = hideRisk,
        )
    }

    val windowOptions = remember {
        listOf(
            SelectOptionUi("10m", "10분"),
            SelectOptionUi("1h", "1시간"),
            SelectOptionUi("24h", "24시간"),
            SelectOptionUi("7d", "7일"),
        )
    }
    val eventOptions = remember {
        listOf(
            SelectOptionUi("", "이벤트:전체"),
            SelectOptionUi("earnings", "실적"),
            SelectOptionUi("contract", "계약/수주"),
            SelectOptionUi("buyback", "자사주"),
            SelectOptionUi("offering", "증자/CB"),
            SelectOptionUi("mna", "M&A"),
            SelectOptionUi("regulation", "규제/제재"),
            SelectOptionUi("lawsuit", "소송"),
            SelectOptionUi("report", "리포트"),
            SelectOptionUi("misc", "기타"),
        )
    }

    val doRefresh: () -> Unit = {
        refreshToken += 1
        when (mode) {
            NewsMode.THEMES -> vm.loadThemes(
                window = window,
                source = sourceParam,
                eventType = eventParam,
                hideRisk = hideRisk,
            )

            NewsMode.CLUSTERS -> vm.loadClusters(
                window = window,
                themeKey = themeKeyParam,
                source = sourceParam,
                eventType = eventParam,
                hideRisk = hideRisk,
                sort = "hot",
                limit = 300,
            )

            NewsMode.ARTICLES -> vm.loadArticles(
                window = window,
                themeKey = themeKeyParam,
                source = sourceParam,
                eventType = eventParam,
                hideRisk = hideRisk,
                sort = "latest",
                limit = 300,
            )

            NewsMode.CLUSTER_ARTICLES -> {
                val id = selectedClusterId
                if (id != null && id > 0) vm.loadCluster(id)
            }
        }
    }

    val allArticles = clusterState.data?.articles.orEmpty()
    val riskEventTypes = remember { setOf("offering", "regulation", "lawsuit") }

    data class MentionStat(val ticker: String, val mentionCount: Int, val impactSum: Int)

    val mentionStats = remember(allArticles) {
        val map = linkedMapOf<String, Pair<Int, Int>>() // ticker -> (count, impactSum)
        for (a in allArticles) {
            val impact = a.impact ?: 0
            for (t in a.tickers.orEmpty()) {
                val tk = t.trim()
                if (tk.isBlank()) continue
                val prev = map[tk] ?: (0 to 0)
                map[tk] = (prev.first + 1) to (prev.second + impact)
            }
        }
        map.entries
            .map { MentionStat(it.key, it.value.first, it.value.second) }
            .sortedWith(
                compareByDescending<MentionStat> { it.impactSum }
                    .thenByDescending { it.mentionCount }
                    .thenBy { it.ticker }
            )
    }
    val mentionTickers = remember(mentionStats) { mentionStats.map { it.ticker } }
    val quoteKey = remember(mentionTickers) { mentionTickers.take(18).joinToString(",") }
    var mentionQuotes by remember {
        mutableStateOf<Map<String, com.example.stock.data.api.RealtimeQuoteItemDto>>(emptyMap())
    }
    LaunchedEffect(quoteKey) {
        mentionQuotes = emptyMap()
        val target = mentionTickers.take(18)
        if (target.isEmpty()) return@LaunchedEffect
        repo.getRealtimeQuotes(target)
            .onSuccess { map -> mentionQuotes = map }
            .onFailure { /* keep empty */ }
    }

    val filteredClusterArticles = remember(allArticles, selectedMentionTicker, sourceFilter, eventFilter, hideRisk) {
        allArticles.filter { a ->
            val tickers = a.tickers.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
            val source = a.source?.trim().orEmpty()
            val eventType = a.eventType?.trim().orEmpty()
            val isRisk = riskEventTypes.contains(eventType)
            val passesTicker = selectedMentionTicker.isNullOrBlank() || tickers.contains(selectedMentionTicker)
            val passesSource = when (sourceFilter) {
                "dart" -> source == "dart"
                "rss" -> source.isNotBlank() && source != "dart"
                else -> true
            }
            val passesEvent = eventFilter.isBlank() || eventType == eventFilter
            val passesRisk = !hideRisk || !isRisk
            passesTicker && passesSource && passesEvent && passesRisk
        }
    }

    val appBarTitle = "뉴스"
    val headerTitle = "뉴스"

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = appBarTitle, showRefresh = true, onRefresh = { doRefresh() }) },
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize()) {
            val (source, statusMessage, updatedAt, items, emptyTextRaw, onItemClick) = when (mode) {
                NewsMode.THEMES -> buildThemeListState(themesState) { theme ->
                    selectedThemeKey = theme.themeKey?.trim().orEmpty()
                    mode = NewsMode.CLUSTERS
                    scrollToTopToken += 1
                }

                NewsMode.CLUSTERS -> buildClustersListState(clustersState) { cluster ->
                    val id = cluster.id ?: 0
                    if (id > 0) {
                        previousListMode = NewsMode.CLUSTERS
                        selectedClusterId = id
                        selectedClusterTitle = cluster.title?.trim().orEmpty()
                        mode = NewsMode.CLUSTER_ARTICLES
                        scrollToTopToken += 1
                    }
                }

                NewsMode.ARTICLES -> buildArticlesListState(articlesState, articleTextSizeSp = articleTextSizeSp)
                NewsMode.CLUSTER_ARTICLES -> buildClusterArticlesState(
                    clusterState,
                    articlesOverride = filteredClusterArticles,
                    articleTextSizeSp = articleTextSizeSp,
                )
            }

            val appliedCount = run {
                var n = 0
                if (window != "24h") n += 1
                if (mode != NewsMode.THEMES) n += 1
                if (sourceFilter != "ALL") n += 1
                if (eventFilter.isNotBlank()) n += 1
                if (hideRisk) n += 1
                if (!themeKeyParam.isNullOrBlank()) n += 1
                if (!selectedMentionTicker.isNullOrBlank()) n += 1
                n
            }
            val filterSummary = buildString {
                append("기간 ")
                append(windowOptions.firstOrNull { it.id == window }?.label ?: "24시간")
                if (sourceFilter != "ALL") append(" · ${if (sourceFilter == "dart") "공시" else "언론"}")
                if (eventFilter.isNotBlank()) {
                    val eventLabel = eventOptions.firstOrNull { it.id == eventFilter }?.label ?: eventFilter
                    append(" · $eventLabel")
                }
                if (hideRisk) append(" · 악재숨김")
            }
            val emptyText = if (items.isEmpty()) "$emptyTextRaw\n현재 필터: $filterSummary" else emptyTextRaw

            fun resetFilters() {
                window = normalizeWindow(appSettings.newsDefaultWindow)
                mode = modeFromPref(appSettings.newsDefaultMode)
                previousListMode = mode
                sourceFilter = if (appSettings.newsDefaultSource.equals("all", ignoreCase = true)) "ALL" else appSettings.newsDefaultSource
                eventFilter = ""
                hideRisk = appSettings.newsDefaultHideRisk
                selectedThemeKey = null
                selectedClusterId = null
                selectedClusterTitle = null
                selectedMentionTicker = null
                scrollToTopToken += 1
            }

            val relatedStocksContent: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? =
                if (mode == NewsMode.CLUSTER_ARTICLES && mentionStats.isNotEmpty()) {
                    {
                        item {
                            val visible = if (showAllMentionStocks) mentionStats else mentionStats.take(6)
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "언급 기업",
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF111827),
                                    )
                                    if (mentionStats.size > 6) {
                                        TextButton(onClick = { showAllMentionStocks = !showAllMentionStocks }) {
                                            Text(if (showAllMentionStocks) "접기" else "더보기")
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                visible.forEachIndexed { idx, st ->
                                    val tk = st.ticker
                                    val selected = selectedMentionTicker == tk
                                    val extra = buildList {
                                        if (selected) add("선택됨: 이 기업 관련 기사만 표시")
                                    }
                                    val ui = CommonReportItemUi(
                                        ticker = tk,
                                        name = null,
                                        title = tk,
                                        quote = mentionQuotes[tk],
                                        metrics = listOf(
                                            MetricUi("언급", st.mentionCount.toDouble(), formatted = st.mentionCount.toString()),
                                            MetricUi("영향도", st.impactSum.toDouble(), formatted = st.impactSum.toString()),
                                        ),
                                        extraLines = extra,
                                        sortPrice = mentionQuotes[tk]?.price,
                                        sortChangePct = mentionQuotes[tk]?.chgPct,
                                        sortName = tk,
                                        currencyCode = "KRW",
                                    )
                                    CommonReportItemCard(
                                        item = ui,
                                        onClick = {
                                            selectedMentionTicker = if (selected) null else tk
                                        }
                                    )
                                    if (idx != visible.lastIndex) {
                                        Spacer(Modifier.height(10.dp))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    null
                }

            CommonReportList(
                source = source,
                statusMessage = statusMessage,
                updatedAt = updatedAt,
                header = headerTitle,
                glossaryDialogTitle = "뉴스 용어 설명집",
                glossaryItems = GlossaryPresets.NEWS,
                items = items,
                emptyText = emptyText,
                refreshToken = refreshToken,
                scrollToTopToken = scrollToTopToken,
                query = query,
                onQueryChange = { query = it },
                refreshLoading = when (mode) {
                    NewsMode.THEMES -> themesState.loading
                    NewsMode.CLUSTERS -> clustersState.loading
                    NewsMode.ARTICLES -> articlesState.loading
                    NewsMode.CLUSTER_ARTICLES -> clusterState.loading
                },
                onRefresh = doRefresh,
                onItemClick = onItemClick,
                topContent = relatedStocksContent,
                stickyFilters = true,
                filtersContent = {
                    CommonFilterCard(
                        title = if (appliedCount > 0) "필터 · 적용 ${appliedCount}개" else "필터",
                    ) {
                        val filterScroll = androidx.compose.foundation.rememberScrollState()
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(filterScroll),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (showBackPill) {
                                BackPill(onClick = { handleBack() })
                            }
                            CommonPillMenu(
                                label = "기간",
                                options = windowOptions,
                                selectedId = window,
                                onSelect = { next ->
                                    window = next
                                    scrollToTopToken += 1
                                    mode = NewsMode.THEMES
                                    previousListMode = NewsMode.THEMES
                                    selectedThemeKey = null
                                    selectedClusterId = null
                                    selectedClusterTitle = null
                                }
                            )
                            CommonPillChip(
                                text = "핫지수",
                                selected = mode == NewsMode.THEMES,
                                onClick = {
                                    if (mode != NewsMode.THEMES || !themeKeyParam.isNullOrBlank()) {
                                        mode = NewsMode.THEMES
                                        previousListMode = NewsMode.THEMES
                                        selectedThemeKey = null
                                        selectedClusterId = null
                                        selectedClusterTitle = null
                                        scrollToTopToken += 1
                                    }
                                }
                            )
                            CommonPillChip(
                                text = "클러스터",
                                selected = mode == NewsMode.CLUSTERS,
                                onClick = {
                                    if (mode != NewsMode.CLUSTERS) {
                                        mode = NewsMode.CLUSTERS
                                        previousListMode = NewsMode.CLUSTERS
                                        selectedClusterId = null
                                        selectedClusterTitle = null
                                        scrollToTopToken += 1
                                    }
                                }
                            )
                            CommonPillChip(
                                text = "기사",
                                selected = mode == NewsMode.ARTICLES,
                                onClick = {
                                    if (mode != NewsMode.ARTICLES) {
                                        mode = NewsMode.ARTICLES
                                        previousListMode = NewsMode.ARTICLES
                                        selectedClusterId = null
                                        selectedClusterTitle = null
                                        scrollToTopToken += 1
                                    }
                                }
                            )
                            CommonPillChip(text = "전체", selected = sourceFilter == "ALL", onClick = { sourceFilter = "ALL" })
                            CommonPillChip(text = "공시", selected = sourceFilter == "dart", onClick = { sourceFilter = "dart" })
                            CommonPillChip(text = "언론", selected = sourceFilter == "rss", onClick = { sourceFilter = "rss" })
                            CommonPillChip(text = "악재숨김", selected = hideRisk, onClick = { hideRisk = !hideRisk })
                            CommonPillMenu(
                                label = "이벤트",
                                options = eventOptions,
                                selectedId = eventFilter,
                                onSelect = { eventFilter = it },
                            )
                        }

                        if (items.isEmpty() && appliedCount > 0) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { resetFilters() }) { Text("필터 초기화") }
                        }

                        if (mode != NewsMode.CLUSTER_ARTICLES && !themeKeyParam.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "테마: ${themeLabel(themeKeyParam.orEmpty())}",
                                    color = Color(0xFF111827),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                TextButton(
                                    onClick = {
                                        selectedThemeKey = null
                                        mode = NewsMode.THEMES
                                        scrollToTopToken += 1
                                    }
                                ) {
                                    Text("해제")
                                }
                            }
                        }

                        if (mode == NewsMode.CLUSTER_ARTICLES && !selectedMentionTicker.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "기업: ${selectedMentionTicker.orEmpty()}",
                                    color = Color(0xFF111827),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                TextButton(onClick = { selectedMentionTicker = null }) {
                                    Text("해제")
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            val showSpinner = when (mode) {
                NewsMode.THEMES -> themesState.loading && themesState.data == null
                NewsMode.CLUSTERS -> clustersState.loading && clustersState.data == null
                NewsMode.ARTICLES -> articlesState.loading && articlesState.data == null
                NewsMode.CLUSTER_ARTICLES -> clusterState.loading && clusterState.data == null
            }
            if (showSpinner) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("뉴스 데이터를 불러오는 중...", color = Color(0xFF64748B))
                    }
                }
            }
        }
    }
}

@Composable
private fun BackPill(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEEF1F4)),
        shape = RoundedCornerShape(999.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.clickable { onClick() },
    ) {
        Text(
            "뒤로",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = Color(0xFF111827),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private data class BuiltState(
    val source: com.example.stock.data.repository.UiSource,
    val statusMessage: String?,
    val updatedAt: String?,
    val items: List<CommonReportItemUi>,
    val emptyText: String,
    val onItemClick: ((CommonReportItemUi) -> Unit)?,
)

private fun buildThemeListState(
    state: UiState<com.example.stock.data.api.NewsThemesResponseDto>,
    onSelectTheme: (NewsThemeCardDto) -> Unit,
): BuiltState {
    val dto = state.data
    val themes = dto?.themes.orEmpty()
    val sortedThemes = themes.sortedWith(
        compareByDescending<NewsThemeCardDto> { it.hotScore ?: 0.0 }
            .thenByDescending { it.clusterCount ?: 0 }
            .thenByDescending { it.articleCount ?: 0 }
    )
    val msg = buildNewsStatusMessage(dto?.meta, themes.size, "테마")
    val items = sortedThemes.map { th ->
        val key = th.themeKey.orEmpty()
        val hot = th.hotScore ?: 0.0
        val clusters = th.clusterCount ?: 0
        val articles = th.articleCount ?: 0
        val latest = fmtShortTs(th.latestPublishedAt)
        val top = th.topClusters.orEmpty().take(2).mapNotNull { it.title?.trim() }.filter { it.isNotBlank() }
        val extras = buildList {
            if (latest.isNotBlank()) add("최근: $latest")
            if (top.isNotEmpty()) add("상위: " + top.joinToString(" · "))
        }
        CommonReportItemUi(
            title = themeLabel(key),
            quote = null,
            metrics = listOf(
                MetricUi("핫지수", hot, formatted = "%.1f".format(hot)),
                MetricUi("클러스터", clusters.toDouble(), formatted = clusters.toString()),
                MetricUi("기사", articles.toDouble(), formatted = articles.toString()),
            ),
            extraLines = extras,
        )
    }
    return BuiltState(
        source = state.source,
        statusMessage = msg,
        updatedAt = state.refreshedAt,
        items = items,
        emptyText = "뉴스 테마가 없습니다.",
        onItemClick = { clicked ->
            val idx = items.indexOf(clicked)
            if (idx in sortedThemes.indices) {
                onSelectTheme(sortedThemes[idx])
            }
        },
    )
}

private fun buildClustersListState(
    state: UiState<NewsClustersResponseDto>,
    onSelectCluster: (NewsClusterListItemDto) -> Unit,
): BuiltState {
    val dto = state.data
    val clusters = dto?.clusters.orEmpty()
    val msg = buildNewsStatusMessage(dto?.meta, clusters.size, "클러스터")
    val items = clusters.map { c ->
        val count = c.articleCount ?: 0
        val end = fmtShortTs(c.publishedEnd)
        val hot = c.hotScore ?: 0.0
        val impact = c.impactSum ?: 0
        val tickers = c.topTickers.orEmpty().take(5)
        val extras = buildList {
            if (end.isNotBlank()) add("최근: $end")
            c.summary?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (tickers.isNotEmpty()) add("티커: " + tickers.joinToString(", "))
        }
        CommonReportItemUi(
            title = c.title?.trim().orEmpty(),
            quote = null,
            metrics = listOf(
                MetricUi("핫지수", hot, formatted = "%.1f".format(hot)),
                MetricUi("기사", count.toDouble(), formatted = count.toString()),
                MetricUi("영향도", impact.toDouble(), formatted = impact.toString()),
            ),
            extraLines = extras,
        )
    }
    return BuiltState(
        source = state.source,
        statusMessage = msg,
        updatedAt = state.refreshedAt,
        items = items,
        emptyText = "클러스터가 없습니다.",
        onItemClick = { clicked ->
            val idx = items.indexOf(clicked)
            if (idx in clusters.indices) {
                onSelectCluster(clusters[idx])
            }
        },
    )
}

private fun buildArticlesListState(
    state: UiState<NewsArticlesResponseDto>,
    articleTextSizeSp: Float = 15f,
): BuiltState {
    val dto = state.data
    val articles = dto?.articles.orEmpty()
    val msg = buildNewsStatusMessage(dto?.meta, articles.size, "기사")
    return BuiltState(
        source = state.source,
        statusMessage = msg,
        updatedAt = state.refreshedAt,
        items = articles.map { toArticleUi(it, articleTextSizeSp = articleTextSizeSp) },
        emptyText = "뉴스 기사가 없습니다.",
        onItemClick = null,
    )
}

private fun buildClusterArticlesState(
    state: UiState<com.example.stock.data.api.NewsClusterResponseDto>,
    articlesOverride: List<NewsArticleItemDto>? = null,
    articleTextSizeSp: Float = 15f,
): BuiltState {
    val dto = state.data
    val cluster = dto?.cluster
    val articlesAll = dto?.articles.orEmpty()
    val articles = articlesOverride ?: articlesAll
    val msg = if (articlesOverride != null) {
        "기사 ${articles.size}개 (전체 ${articlesAll.size})"
    } else {
        buildNewsStatusMessage(dto?.meta, articles.size, "기사")
    }
    val items = articles.map { a -> toArticleUi(a, articleTextSizeSp = articleTextSizeSp) }
    val header = cluster?.title?.trim().orEmpty()
    return BuiltState(
        source = state.source,
        statusMessage = msg,
        updatedAt = state.refreshedAt,
        items = items,
        emptyText = if (header.isNotBlank()) "기사 없음: $header" else "기사 없습니다.",
        onItemClick = null,
    )
}

private fun toArticleUi(a: NewsArticleItemDto, articleTextSizeSp: Float = 15f): CommonReportItemUi {
    val impact = a.impact ?: 0
    val ts = fmtShortTs(a.publishedAt)
    val tickers = a.tickers.orEmpty().take(6).filter { it.isNotBlank() }
    val extras = buildList {
        if (ts.isNotBlank()) add(ts)
        val meta = listOf(
            a.source?.trim().orEmpty(),
            a.eventType?.trim().orEmpty(),
            a.polarity?.trim().orEmpty(),
        ).filter { it.isNotBlank() }
        if (meta.isNotEmpty()) add(meta.joinToString(" · "))
        if (tickers.isNotEmpty()) add("티커: " + tickers.joinToString(", "))
    }
    return CommonReportItemUi(
        title = a.title?.trim().orEmpty(),
        quote = null,
        metrics = listOf(MetricUi("영향도", impact.toDouble(), formatted = impact.toString())),
        extraLines = extras,
        thesis = formatNewsBodyForReadability(a.summary),
        actionLinkLabel = "원문 열기",
        actionLinkUrl = a.url,
        currencyCode = "KRW",
        titleMaxLines = Int.MAX_VALUE,
        extraLinesMaxLines = Int.MAX_VALUE,
        thesisMaxLines = Int.MAX_VALUE,
        thesisFontSizeSp = articleTextSizeSp.coerceIn(13f, 18f),
        thesisLineHeightSp = (articleTextSizeSp.coerceIn(13f, 18f) * 1.57f),
    )
}

private fun buildNewsStatusMessage(meta: NewsMetaDto?, count: Int, label: String): String {
    val raw = meta?.message?.trim().orEmpty()
    val issue = when {
        raw.contains("opendart_api_key missing", ignoreCase = true) ->
            "공시 키 미설정 · 언론 데이터 중심으로 표시 중"
        raw.contains("api status=013", ignoreCase = true) ->
            "공시 신규 건이 없어 언론 데이터 중심으로 표시 중"
        raw.contains("not_rss_xml", ignoreCase = true) ||
            raw.contains("parse_failed", ignoreCase = true) ||
            raw.contains("fetch failed", ignoreCase = true) ->
            "일부 언론 소스 지연 · 수집 가능한 데이터로 표시 중"
        raw.contains("db_write_failed", ignoreCase = true) ->
            "수집 지연이 감지되어 자동 복구 중"
        else -> ""
    }
    if (count > 0) {
        return if (issue.isNotBlank()) "$label ${count}개 · $issue" else "$label ${count}개 수집"
    }
    if (issue.isNotBlank()) return issue
    return "$label 데이터 수집 대기 중"
}

private fun formatNewsBodyForReadability(raw: String?): String? {
    val src = raw?.trim().orEmpty()
    if (src.isBlank()) return null
    val normalized = src.replace("\r\n", "\n").replace('\r', '\n')
    val out = StringBuilder(normalized.length + 32)
    var i = 0
    while (i < normalized.length) {
        val ch = normalized[i]
        out.append(ch)
        if (ch == '.' || ch == '?' || ch == '!' || (ch == '다' && i + 1 < normalized.length && normalized[i + 1] == '.')) {
            val prev = if (i > 0) normalized[i - 1] else '\u0000'
            val next = if (i + 1 < normalized.length) normalized[i + 1] else '\u0000'
            val isDecimal = prev.isDigit() && next.isDigit()
            if (!isDecimal && next != '\n' && next != '\u0000') {
                out.append('\n')
                var j = i + 1
                while (j < normalized.length && normalized[j].isWhitespace() && normalized[j] != '\n') {
                    j += 1
                }
                i = j - 1
            }
        }
        i += 1
    }
    return out.toString()
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
