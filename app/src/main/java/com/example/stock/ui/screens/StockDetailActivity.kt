package com.example.stock.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.stock.MainActivity
import com.example.stock.ServiceLocator
import com.example.stock.data.api.ChartDailyDto
import com.example.stock.data.api.NewsArticleItemDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.api.StockInvestorDailyItemDto
import com.example.stock.data.api.StockTrendIntradayItemDto
import com.example.stock.navigation.AppTab
import com.example.stock.ui.common.ChartRange
import com.example.stock.ui.common.GlossaryPresets
import com.example.stock.ui.common.StockChartSheet
import com.example.stock.ui.common.TossBottomBar
import com.example.stock.ui.theme.StockTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.math.abs
import kotlin.math.roundToLong

class StockDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ticker = intent.getStringExtra(EXTRA_TICKER).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()
        val eventTags = intent.getStringArrayExtra(EXTRA_EVENT_TAGS)?.toList().orEmpty()
        setContent {
            StockTheme {
                StockDetailScreen(
                    ticker = ticker,
                    name = name,
                    origin = origin,
                    eventTags = eventTags,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TICKER = "ticker"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_ORIGIN = "origin"
        private const val EXTRA_EVENT_TAGS = "event_tags"

        fun open(
            context: Context,
            ticker: String,
            name: String,
            origin: String,
            eventTags: List<String>,
        ) {
            val intent = Intent(context, StockDetailActivity::class.java).apply {
                putExtra(EXTRA_TICKER, ticker)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_ORIGIN, origin)
                putExtra(EXTRA_EVENT_TAGS, eventTags.toTypedArray())
            }
            context.startActivity(intent)
        }
    }
}

private const val DETAIL_NEWS_INITIAL_LIMIT = 120
private const val DETAIL_COMMUNITY_INITIAL_LIMIT = 80

@Composable
private fun StockDetailScreen(
    ticker: String,
    name: String,
    origin: String,
    eventTags: List<String>,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) } // 0 home, 1 AI, 2 investor, 3 trend
    var chartRange by remember { mutableStateOf(ChartRange.D1) }
    var chartData by remember { mutableStateOf<ChartDailyDto?>(null) }
    var chartLoading by remember { mutableStateOf(true) }
    var chartError by remember { mutableStateOf<String?>(null) }
    var quote by remember { mutableStateOf<RealtimeQuoteItemDto?>(null) }
    var favorite by remember { mutableStateOf(false) }
    var relatedNews by remember { mutableStateOf<List<NewsArticleItemDto>>(emptyList()) }
    var communityNews by remember { mutableStateOf<List<NewsArticleItemDto>>(emptyList()) }
    var relatedNewsVisibleCount by remember { mutableStateOf(5) }
    var expandedNewsKey by remember { mutableStateOf<String?>(null) }
    var selectedIssueFilter by remember { mutableStateOf(DetailNewsFilter.ALL) }
    var selectedNewsSort by remember { mutableStateOf(DetailNewsSort.LATEST) }
    var newsLoading by remember { mutableStateOf(false) }
    var newsError by remember { mutableStateOf<String?>(null) }
    var aiChartData by remember { mutableStateOf<ChartDailyDto?>(null) }
    var aiNewsHotScore by remember { mutableStateOf(0.0) }
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiGlossaryOpen by remember { mutableStateOf(false) }
    var investorDailyRows by remember { mutableStateOf<List<StockInvestorDailyItemDto>>(emptyList()) }
    var investorLoading by remember { mutableStateOf(false) }
    var investorError by remember { mutableStateOf<String?>(null) }
    var investorSource by remember { mutableStateOf("FALLBACK") }
    var investorMessage by remember { mutableStateOf<String?>(null) }
    var intradayTrendRows by remember { mutableStateOf<List<StockTrendIntradayItemDto>>(emptyList()) }
    var intradayTrendLoading by remember { mutableStateOf(false) }
    var intradayTrendError by remember { mutableStateOf<String?>(null) }
    var intradayTrendSource by remember { mutableStateOf("FALLBACK") }
    var intradayTrendMessage by remember { mutableStateOf<String?>(null) }
    var tradeEnv by remember { mutableStateOf(DetailTradeEnv.DEMO) }
    var tradeQty by remember { mutableStateOf(1) }
    var tradePriceInput by remember { mutableStateOf("") }
    var tradeMarketOrder by remember { mutableStateOf(true) }
    var tradeDialogOpen by remember { mutableStateOf(false) }
    var tradeLoading by remember { mutableStateOf(false) }
    var tabs by remember { mutableStateOf(resolveBottomTabs(repo.getSettings().bottomTabOrderCsv)) }
    val newsWindow = remember(chartRange) { chartRangeToNewsWindow(chartRange) }
    val newsWindowLabel = remember(chartRange) { chartRangeToNewsLabel(chartRange) }
    val currentBottomRoute = remember(origin) { mapOriginToRoute(origin) }
    val normalizedTicker = remember(ticker) { normalizeTickerCode(ticker) }
    val fetchIntradayTrend: suspend () -> Unit = {
        intradayTrendLoading = true
        intradayTrendError = null
        repo.getStockIntradayTrend(ticker = ticker, limit = 90)
            .onSuccess { dto ->
                intradayTrendRows = dto.items.orEmpty()
                intradayTrendSource = dto.source?.uppercase() ?: "FALLBACK"
                intradayTrendMessage = dto.message
            }
            .onFailure {
                intradayTrendError = it.message ?: "종목거래동향을 불러오지 못했습니다."
            }
        intradayTrendLoading = false
    }

    LaunchedEffect(ticker, chartRange) {
        chartLoading = true
        chartError = null
        val reqDays = if (chartRange == ChartRange.D1) 2 else chartRange.days
        repo.getChartDaily(ticker, reqDays)
            .onSuccess { chartData = it }
            .onFailure { chartError = it.message ?: "차트 데이터를 불러오지 못했습니다." }
        chartLoading = false
    }

    LaunchedEffect(ticker) {
        supervisorScope {
            val quoteDeferred = async { repo.getRealtimeQuotes(listOf(ticker), mode = "light") }
            val favoriteDeferred = async { repo.getFavorites() }
            quoteDeferred.await().onSuccess { quote = it[ticker] }
            favoriteDeferred.await().onSuccess { list -> favorite = list.any { f -> f.ticker == ticker } }
        }
    }

    LaunchedEffect(ticker, name, newsWindow, normalizedTicker) {
        newsLoading = true
        newsError = null
        val key = name.trim()
        val keyOrNull = key.takeIf { it.isNotBlank() }

        supervisorScope {
            var fatalNewsError: String? = null
            var communityError: String? = null

            val byTickerDeferred = if (!normalizedTicker.isNullOrBlank()) {
                async {
                    repo.getNewsArticles(
                        window = newsWindow,
                        sort = "latest",
                        limit = DETAIL_NEWS_INITIAL_LIMIT,
                        ticker = normalizedTicker,
                        query = keyOrNull,
                    )
                }
            } else {
                null
            }
            val communityDeferred = if (!normalizedTicker.isNullOrBlank()) {
                async {
                    repo.getNewsArticles(
                        window = newsWindow,
                        sort = "latest",
                        limit = DETAIL_COMMUNITY_INITIAL_LIMIT,
                        ticker = normalizedTicker,
                        eventType = "community",
                    )
                }
            } else {
                null
            }

            val byTicker = byTickerDeferred
                ?.await()
                ?.onFailure {
                    fatalNewsError = it.message ?: "관련 기사(종목) 로딩 실패"
                }
                ?.getOrNull()
                ?.articles
                .orEmpty()

            val fallbackByName = if (byTicker.isEmpty() && !keyOrNull.isNullOrBlank()) {
                repo.getNewsArticles(
                    window = newsWindow,
                    sort = "latest",
                    limit = DETAIL_NEWS_INITIAL_LIMIT,
                    query = keyOrNull,
                ).onFailure {
                    if (fatalNewsError.isNullOrBlank()) fatalNewsError = it.message ?: "관련 기사(이름) 로딩 실패"
                }.getOrNull()?.articles.orEmpty()
            } else {
                emptyList()
            }

            val selected = byTicker.ifEmpty { fallbackByName }
                .distinctBy { it.url.orEmpty() + "|" + it.title.orEmpty() }
            relatedNews = selected.filterNot { isNaverBackfillSource(it.source.orEmpty()) }

            val communityRows = communityDeferred
                ?.await()
                ?.onFailure {
                    communityError = it.message ?: "커뮤니티 기사 로딩 실패"
                }
                ?.getOrNull()
                ?.articles
                .orEmpty()
            communityNews = communityRows
                .distinctBy { it.url.orEmpty() + "|" + it.title.orEmpty() }

            newsError = if (relatedNews.isEmpty() && communityNews.isEmpty()) {
                fatalNewsError ?: communityError
            } else {
                null
            }
        }

        newsLoading = false
        relatedNewsVisibleCount = 5
        expandedNewsKey = null
        selectedIssueFilter = DetailNewsFilter.ALL
        selectedNewsSort = DetailNewsSort.LATEST
    }
    LaunchedEffect(ticker) {
        aiLoading = true
        aiError = null
        repo.getChartDaily(ticker, 120)
            .onSuccess { aiChartData = it }
            .onFailure {
                aiError = it.message ?: "AI 분석용 차트를 불러오지 못했습니다."
                aiChartData = null
            }
        aiLoading = false
    }
    LaunchedEffect(ticker, newsWindow, normalizedTicker) {
        aiError = null
        repo.getNewsStocks(window = newsWindow, sort = "hot")
            .onSuccess { dto ->
                val row = dto.stocks.orEmpty().firstOrNull { (it.ticker ?: "") == normalizedTicker.orEmpty() }
                aiNewsHotScore = row?.hotScore ?: 0.0
            }
            .onFailure {
                if (aiError == null) aiError = it.message ?: "AI 분석용 뉴스 점수를 불러오지 못했습니다."
                aiNewsHotScore = 0.0
            }
    }
    LaunchedEffect(ticker) {
        investorLoading = true
        investorError = null
        repo.getStockInvestorDaily(ticker = ticker, days = 60)
            .onSuccess { dto ->
                investorDailyRows = dto.items.orEmpty()
                investorSource = dto.source?.uppercase() ?: "FALLBACK"
                investorMessage = dto.message
            }
            .onFailure {
                investorError = it.message ?: "종목투자자 데이터를 불러오지 못했습니다."
            }
        investorLoading = false
    }
    LaunchedEffect(ticker) { fetchIntradayTrend() }
    LaunchedEffect(ticker, selectedTab) {
        if (selectedTab != 3) return@LaunchedEffect
        while (isActive) {
            delay(8000L)
            fetchIntradayTrend()
        }
    }

    val price = quote?.price ?: chartData?.points?.lastOrNull()?.close ?: 0.0
    val prevClose = quote?.prevClose ?: chartData?.points?.dropLast(1)?.lastOrNull()?.close ?: 0.0
    val chgAbs = if (price > 0 && prevClose > 0) price - prevClose else 0.0
    val chgPct = if (price > 0 && prevClose > 0) ((price / prevClose) - 1.0) * 100.0 else (quote?.chgPct ?: 0.0)
    val chgColor = if (chgPct >= 0) Color(0xFFE95A68) else Color(0xFF2F6BFF)
    val displayName = name.ifBlank { ticker }
    val filters = DetailNewsFilter.entries
    val filteredRelatedNews = if (selectedIssueFilter == DetailNewsFilter.COMMUNITY) {
        communityNews
    } else {
        relatedNews.filter { article ->
            matchesDetailNewsFilter(
                filter = selectedIssueFilter,
                article = article,
            )
        }
    }
    val sortedRelatedNews = when (selectedNewsSort) {
        DetailNewsSort.LATEST -> filteredRelatedNews.sortedWith(
            compareByDescending<NewsArticleItemDto> { it.publishedAt.orEmpty() }
                .thenByDescending { it.impact ?: 0 }
        )
        DetailNewsSort.IMPACT -> filteredRelatedNews.sortedWith(
            compareByDescending<NewsArticleItemDto> { it.impact ?: 0 }
                .thenByDescending { it.publishedAt.orEmpty() }
        )
    }
    val aiResult = remember(price, prevClose, aiChartData, relatedNews, aiNewsHotScore, ticker) {
        buildAiSignalResult(
            ticker = ticker,
            price = price,
            prevClose = prevClose,
            chart = aiChartData,
            relatedNews = relatedNews,
            newsHotScore = aiNewsHotScore,
            newsWindowLabel = newsWindowLabel,
        )
    }
    val enteredLimitPrice = parseManualPriceInput(tradePriceInput)
    val requestPrice = if (tradeMarketOrder) {
        if (price > 0.0) price else 0.0
    } else {
        enteredLimitPrice ?: 0.0
    }
    val estimatedAmount = if (requestPrice > 0.0) requestPrice * tradeQty.toDouble() else 0.0
    val orderTypeLabel = if (tradeMarketOrder) "시장가" else "지정가"
    val tradeMode = if (tradeEnv == DetailTradeEnv.DEMO) "demo" else "prod"
    val canSubmitTrade = !tradeLoading && tradeQty > 0 && requestPrice > 0.0 && (tradeMarketOrder || enteredLimitPrice != null)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            TossBottomBar(
                currentRoute = currentBottomRoute,
                tabs = tabs,
                onSelect = { tab ->
                    openMainRoute(context, tab.route)
                    if (context is Activity) context.finish()
                },
                onReorder = { reordered ->
                    tabs = reordered
                    val s = repo.getSettings()
                    repo.saveSettings(
                        baseUrl = s.baseUrl,
                        lookbackDays = s.lookbackDays,
                        riskPreset = s.riskPreset,
                        themeCap = s.themeCap,
                        daytradeDisplayCount = s.daytradeDisplayCount,
                        longtermDisplayCount = s.longtermDisplayCount,
                        quoteRefreshSec = s.quoteRefreshSec,
                        daytradeVariant = s.daytradeVariant,
                        bottomTabOrderCsv = toBottomTabOrderCsv(reordered),
                        cardUiVersion = s.cardUiVersion,
                    )
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF5F6F8))
                .safeDrawingPadding()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val toggleFavorite: () -> Unit = {
                scope.launch {
                    if (favorite) {
                        repo.deleteFavorite(ticker)
                        favorite = false
                    } else {
                        val baseline = if (price > 0) price else (prevClose.takeIf { it > 0 } ?: 0.0)
                        if (baseline > 0.0) {
                            repo.upsertFavorite(ticker = ticker, name = displayName, baselinePrice = baseline, sourceTab = origin.ifBlank { "상세" })
                            favorite = true
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로") }
                Row {
                    IconButton(onClick = {
                        openNaverStockPage(context, ticker)
                    }) { Icon(Icons.Filled.Search, contentDescription = "검색") }
                }
            }

            val signalLabel = aiResult?.signal ?: "신호 계산중"
            val signalScore = aiResult?.score
            val signalBg = when (signalLabel) {
                "강한 매수" -> Color(0xFFFFEEF1)
                "매수" -> Color(0xFFFFF4ED)
                "관망" -> Color(0xFFF5F6F8)
                "주의" -> Color(0xFFEEF4FF)
                "회피" -> Color(0xFFEEF3F8)
                else -> Color(0xFFF1F5F9)
            }
            val signalFg = when (signalLabel) {
                "강한 매수" -> Color(0xFFD9364A)
                "매수" -> Color(0xFFB45309)
                "관망" -> Color(0xFF475467)
                "주의" -> Color(0xFF1D4ED8)
                "회피" -> Color(0xFF374151)
                else -> Color(0xFF475569)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .background(signalBg, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = signalLabel,
                                color = signalFg,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = if (signalScore != null) "종합 ${"%.1f".format(signalScore)}점" else "종합 점수 계산중",
                            color = Color(0xFF6B7280),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = toggleFavorite) {
                            Icon(
                                if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "관심",
                                tint = if (favorite) Color(0xFFE95A68) else Color(0xFF9AA5B1),
                            )
                        }
                    }
                }
            }
            Text(
                ticker,
                color = Color(0xFF8B9098),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(fmtPrice(price), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        text = "${if (chgAbs >= 0) "▲" else "▼"}${fmtPrice(abs(chgAbs))} ${if (chgPct >= 0) "+" else ""}${"%.2f".format(chgPct)}%",
                        color = chgColor,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFEEF4FF), RoundedCornerShape(999.dp))
                            .clickable(enabled = !tradeLoading) {
                                tradeEnv = DetailTradeEnv.DEMO
                                tradeQty = 1
                                tradeMarketOrder = true
                                tradePriceInput = if (price > 0.0) price.roundToLong().toString() else ""
                                tradeDialogOpen = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (tradeLoading && tradeEnv == DetailTradeEnv.DEMO) "요청중..." else "모의구매",
                            color = Color(0xFF1D4ED8),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFFFEEF1), RoundedCornerShape(999.dp))
                            .clickable(enabled = !tradeLoading) {
                                tradeEnv = DetailTradeEnv.PROD
                                tradeQty = 1
                                tradeMarketOrder = true
                                tradePriceInput = if (price > 0.0) price.roundToLong().toString() else ""
                                tradeDialogOpen = true
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (tradeLoading && tradeEnv == DetailTradeEnv.PROD) "요청중..." else "실전구매",
                            color = Color(0xFFD9364A),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailTopTab("종목홈", selectedTab == 0, Modifier.widthIn(min = 96.dp)) { selectedTab = 0 }
                DetailTopTab("인공지능 매매신호", selectedTab == 1, Modifier.widthIn(min = 150.dp)) { selectedTab = 1 }
                DetailTopTab("종목투자자", selectedTab == 2, Modifier.widthIn(min = 118.dp)) { selectedTab = 2 }
                DetailTopTab("종목거래동향", selectedTab == 3, Modifier.widthIn(min = 132.dp)) { selectedTab = 3 }
            }
            Spacer(Modifier.height(10.dp))

            if (selectedTab == 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (chartLoading && chartData == null) {
                        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text("차트 데이터를 불러오는 중...", color = Color(0xFF64748B))
                            }
                        }
                    } else {
                        StockChartSheet(
                            title = displayName,
                            ticker = ticker,
                            quote = quote,
                            loading = chartLoading,
                            error = chartError,
                            data = chartData,
                            range = chartRange,
                            onRangeChange = { chartRange = it },
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filters.forEach { filter ->
                        Box(
                            modifier = Modifier
                                .background(Color.White, RoundedCornerShape(999.dp))
                                .clickable {
                                    selectedIssueFilter = filter
                                    relatedNewsVisibleCount = 5
                                    expandedNewsKey = null
                                }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) {
                            Text(
                                filter.label,
                                color = if (selectedIssueFilter == filter) Color(0xFF1D4ED8) else Color(0xFF5F6670),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (selectedIssueFilter == filter) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailNewsSort.entries.forEach { sort ->
                        Box(
                            modifier = Modifier
                                .background(Color.White, RoundedCornerShape(999.dp))
                                .clickable {
                                    selectedNewsSort = sort
                                    relatedNewsVisibleCount = 5
                                    expandedNewsKey = null
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                sort.label,
                                color = if (selectedNewsSort == sort) Color(0xFF1D4ED8) else Color(0xFF5F6670),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedNewsSort == sort) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    val first = sortedRelatedNews.firstOrNull()
                    if (first != null) {
                        val key = first.url.orEmpty() + "|" + first.title.orEmpty()
                        expandedNewsKey = if (expandedNewsKey == key) null else key
                    } else {
                        Toast.makeText(context, "표시할 기사 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("종목이슈 보기") }

                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("관련 기사", fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(8.dp))
                        when {
                            newsLoading -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                                    Text("관련 기사 불러오는 중...", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            !newsError.isNullOrBlank() -> Text("기사 로딩 오류: $newsError", color = Color(0xFFDC2626))
                            sortedRelatedNews.isEmpty() -> Text("최근 $newsWindowLabel 내 연결된 기사가 없습니다.", color = Color(0xFF6B7280))
                            else -> {
                                sortedRelatedNews.take(relatedNewsVisibleCount).forEach { article ->
                                    val itemKey = article.url.orEmpty() + "|" + article.title.orEmpty()
                                    val isExpanded = expandedNewsKey == itemKey
                                    val eventLabel = newsEventLabel(article.eventType.orEmpty())
                                    val sourceLabel = newsSourceLabel(article.source.orEmpty())
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                expandedNewsKey = if (isExpanded) null else itemKey
                                            }
                                            .padding(vertical = 6.dp),
                                    ) {
                                        Text(
                                            article.title.orEmpty().ifBlank { "(제목 없음)" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "$eventLabel · $sourceLabel · ${article.publishedAt.orEmpty().take(16)} · 영향도 ${article.impact ?: 0}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF8B9098),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (isExpanded) {
                                            Spacer(Modifier.height(6.dp))
                                            val formatted = formatNewsSummaryForDisplay(article.summary.orEmpty())
                                            val highlightKeywords = listOf(
                                                displayName.trim(),
                                                normalizeTickerCode(ticker).orEmpty(),
                                            )
                                            Text(
                                                text = buildHighlightedSummary(
                                                    text = formatted.ifBlank { "요약 본문이 없는 기사입니다." },
                                                    keywords = highlightKeywords,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF475569),
                                            )
                                        }
                                    }
                                }
                                if (sortedRelatedNews.size > relatedNewsVisibleCount) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            relatedNewsVisibleCount = (relatedNewsVisibleCount + 10).coerceAtMost(sortedRelatedNews.size)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("더보기 (${sortedRelatedNews.take(relatedNewsVisibleCount).size}/${sortedRelatedNews.size})")
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (selectedTab == 1) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("인공지능 매매신호", fontWeight = FontWeight.ExtraBold)
                            Text(
                                text = "용어설명",
                                color = Color(0xFF2F5BEA),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clickable { aiGlossaryOpen = true }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        when {
                            aiLoading && aiResult == null -> {
                                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                                Text(
                                    "실시간 데이터 기반으로 점수 계산 중...",
                                    color = Color(0xFF6B7280),
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            aiResult == null -> {
                                Text(
                                    aiError ?: "분석 데이터가 부족하여 신호를 계산할 수 없습니다.",
                                    color = Color(0xFF6B7280),
                                )
                            }
                            else -> {
                                val signal = aiResult
                                val signalBg = when (signal.signal) {
                                    "강한 매수" -> Color(0xFFFFEEF1)
                                    "매수" -> Color(0xFFFFF4ED)
                                    "관망" -> Color(0xFFF5F6F8)
                                    "주의" -> Color(0xFFEEF4FF)
                                    else -> Color(0xFFEEF3F8)
                                }
                                val signalFg = when (signal.signal) {
                                    "강한 매수" -> Color(0xFFD9364A)
                                    "매수" -> Color(0xFFB45309)
                                    "관망" -> Color(0xFF475467)
                                    "주의" -> Color(0xFF1D4ED8)
                                    else -> Color(0xFF374151)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            signal.signal,
                                            color = signalFg,
                                            fontWeight = FontWeight.ExtraBold,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        Text(
                                            "종합점수 ${"%.1f".format(signal.score)} / 100",
                                            color = Color(0xFF6B7280),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .background(signalBg, RoundedCornerShape(999.dp))
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            "R/R ${"%.2f".format(signal.rr)}",
                                            color = signalFg,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ScoreChip("기술 ${"%.1f".format(signal.techScore)}")
                                    ScoreChip("뉴스 ${"%.1f".format(signal.newsScore)}")
                                    ScoreChip("리스크 ${"%.1f".format(signal.riskScore)}")
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "진입 ${fmtPrice(signal.entry)} · 손절 ${fmtPrice(signal.stop)} · 목표 ${fmtPrice(signal.target1)}",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "진입 이후 손절 이탈 시 즉시 종료, 목표 도달 시 분할 익절을 권장합니다.",
                                    color = Color(0xFF6B7280),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                signal.reasons.forEach { reason ->
                                    Text(
                                        "• $reason",
                                        color = Color(0xFF6B7280),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                if (signal.riskScore >= 70.0) {
                                    Text(
                                        "고변동 구간: 추격매수보다 분할/눌림 진입을 권장",
                                        color = Color(0xFFB45309),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (aiGlossaryOpen) {
                    AlertDialog(
                        onDismissRequest = { aiGlossaryOpen = false },
                        title = { Text("인공지능 매매신호 용어 설명집", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                GlossaryPresets.AI_SIGNAL.forEach { item ->
                                    Text(
                                        text = item.term,
                                        color = Color(0xFF111827),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = item.description,
                                        color = Color(0xFF475569),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { aiGlossaryOpen = false }) {
                                Text("닫기")
                            }
                        },
                    )
                }
            } else if (selectedTab == 2) {
                InvestorDailyCard(
                    rows = investorDailyRows,
                    loading = investorLoading,
                    error = investorError,
                    source = investorSource,
                    message = investorMessage,
                )
            } else {
                IntradayTrendCard(
                    rows = intradayTrendRows,
                    loading = intradayTrendLoading,
                    error = intradayTrendError,
                    source = intradayTrendSource,
                    message = intradayTrendMessage,
                    onRefresh = {
                        scope.launch { fetchIntradayTrend() }
                    },
                )
            }

            if (tradeDialogOpen) {
                val requestTicker = normalizedTicker ?: ticker
                val envLabel = if (tradeEnv == DetailTradeEnv.DEMO) "모의구매" else "실전구매"
                val requestPriceLabel = if (requestPrice > 0.0) fmtPrice(requestPrice) else "-"
                val estimatedLabel = if (estimatedAmount > 0.0) fmtPrice(estimatedAmount) else "-"
                AlertDialog(
                    onDismissRequest = {
                        if (!tradeLoading) tradeDialogOpen = false
                    },
                    title = { Text("$envLabel 설정", fontWeight = FontWeight.Bold) },
                    text = {
                        Column {
                            Text("$displayName ($requestTicker)")
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TradeOrderTypeChip(
                                    label = "시장가",
                                    selected = tradeMarketOrder,
                                    enabled = !tradeLoading,
                                    onClick = { tradeMarketOrder = true },
                                )
                                TradeOrderTypeChip(
                                    label = "지정가",
                                    selected = !tradeMarketOrder,
                                    enabled = !tradeLoading,
                                    onClick = {
                                        tradeMarketOrder = false
                                        if (tradePriceInput.isBlank() && price > 0.0) {
                                            tradePriceInput = price.roundToLong().toString()
                                        }
                                    },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            if (!tradeMarketOrder) {
                                TextField(
                                    value = tradePriceInput,
                                    onValueChange = { raw ->
                                        tradePriceInput = raw.filter { it.isDigit() }.take(12)
                                    },
                                    enabled = !tradeLoading,
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    placeholder = { Text("지정가 입력", style = MaterialTheme.typography.labelSmall) },
                                    suffix = { Text("원", style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B7280)) },
                                    colors = TextFieldDefaults.colors(
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                        focusedContainerColor = Color(0xFFF8FAFC),
                                        unfocusedContainerColor = Color(0xFFF8FAFC),
                                        disabledContainerColor = Color(0xFFF8FAFC),
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("수량", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier
                                        .background(Color(0xFFF1F5F9), RoundedCornerShape(999.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    TextButton(
                                        enabled = !tradeLoading && tradeQty > 1,
                                        onClick = { tradeQty = (tradeQty - 1).coerceAtLeast(1) },
                                    ) { Text("-") }
                                    Text("${"%,d".format(tradeQty)}주", fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                                    TextButton(
                                        enabled = !tradeLoading,
                                        onClick = { tradeQty = (tradeQty + 1).coerceAtMost(1_000_000) },
                                    ) { Text("+") }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("유형: $orderTypeLabel", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                            Text("주문가격: $requestPriceLabel", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                            Text("예상 주문금액: $estimatedLabel", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                            if (tradeMarketOrder && price <= 0.0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "현재가 수신 전입니다. 지정가로 전환하거나 잠시 후 다시 시도하세요.",
                                    color = Color(0xFFB45309),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!tradeMarketOrder && enteredLimitPrice == null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "지정가를 입력해야 주문할 수 있습니다.",
                                    color = Color(0xFFB45309),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (tradeEnv == DetailTradeEnv.PROD) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "실전 주문은 서버 실주문 활성화 및 증권사 계정정보가 준비되어야 접수됩니다.",
                                    color = Color(0xFFB45309),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = canSubmitTrade,
                            onClick = {
                                scope.launch {
                                    tradeLoading = true
                                    val result = repo.runAutoTradeManualBuy(
                                        ticker = requestTicker,
                                        name = displayName,
                                        mode = tradeMode,
                                        qty = tradeQty,
                                        requestPrice = if (tradeMarketOrder) null else requestPrice,
                                        marketOrder = tradeMarketOrder,
                                    )
                                    result.onSuccess { dto ->
                                        val order = dto.orders.orEmpty().firstOrNull()
                                        val statusLabel = order?.status?.takeIf { it.isNotBlank() }?.let { orderStatusLabel(it) }
                                        val reasonLabel = resolveOrderReasonLabel(order?.reason, dto.message)
                                        val parts = mutableListOf(
                                            "매수 완료",
                                            orderTypeLabel,
                                            "${tradeQty}주",
                                        )
                                        if (!statusLabel.isNullOrBlank()) parts += statusLabel
                                        if (!reasonLabel.isNullOrBlank()) parts += reasonLabel
                                        val msg = parts.joinToString(" · ")
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }.onFailure {
                                        Toast.makeText(
                                            context,
                                            "매수 실패: ${it.message ?: "요청 오류"}",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    tradeLoading = false
                                    tradeDialogOpen = false
                                }
                            },
                        ) {
                            Text(if (tradeLoading) "처리중..." else "매수 실행")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !tradeLoading,
                            onClick = { tradeDialogOpen = false },
                        ) {
                            Text("취소")
                        }
                    },
                )
            }

        }
    }
}

private fun parseManualPriceInput(raw: String): Double? {
    val digits = raw.filter(Char::isDigit)
    if (digits.isBlank()) return null
    return digits.toDoubleOrNull()?.takeIf { it > 0.0 }
}

private fun orderStatusLabel(raw: String): String {
    return when (raw.trim().uppercase()) {
        "PAPER_FILLED" -> "내부모의체결"
        "BROKER_SUBMITTED" -> "증권사접수"
        "BROKER_FILLED" -> "증권사체결"
        "BROKER_REJECTED" -> "증권사거부"
        "SKIPPED" -> "스킵"
        "ERROR" -> "오류"
        else -> "기타"
    }
}

private fun resolveOrderReasonLabel(reason: String?, message: String?): String? {
    val raw = reason?.trim().orEmpty().ifBlank { extractReasonFromMessage(message).orEmpty() }
    if (raw.isBlank()) return null
    val upper = raw.uppercase()
    return when {
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
        upper.startsWith("MANUAL_SELL_PAPER_") -> {
            val orderType = upper.removePrefix("MANUAL_SELL_PAPER_")
            val orderLabel = when (orderType) {
                "LIMIT" -> "지정가"
                "MARKET" -> "시장가"
                else -> "기타"
            }
            "모의 매도($orderLabel)"
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
        upper.startsWith("MANUAL_BUY_PAPER_") -> {
            val orderType = upper.removePrefix("MANUAL_BUY_PAPER_")
            val orderLabel = when (orderType) {
                "LIMIT" -> "지정가"
                "MARKET" -> "시장가"
                else -> "기타"
            }
            "모의 매수($orderLabel)"
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
        upper.startsWith("MARKET_") && upper.endsWith("_RESERVED") -> "${marketPhaseLabel(upper)} 자동 예약 완료"
        upper.startsWith("MARKET_") && upper.endsWith("_RESERVATION_AVAILABLE") -> "${marketPhaseLabel(upper)}라 예약 주문으로 전환 가능"
        upper.startsWith("MARKET_") && upper.endsWith("_BLOCKED") -> "${marketPhaseLabel(upper)}이며 예약 기능이 꺼져 있어 실행 차단"
        else -> "처리 사유 확인 필요"
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

private fun normalizeTickerCode(rawTicker: String): String? {
    val digits = rawTicker.filter(Char::isDigit)
    val ticker = when {
        digits.length >= 6 -> digits.takeLast(6)
        digits.isNotBlank() -> digits.padStart(6, '0')
        else -> ""
    }
    return ticker.takeIf { it.length == 6 && it.all(Char::isDigit) }
}

private fun openNaverStockPage(context: Context, rawTicker: String) {
    val ticker = normalizeTickerCode(rawTicker)
    if (ticker.isNullOrBlank()) {
        Toast.makeText(context, "유효한 종목코드가 없습니다.", Toast.LENGTH_SHORT).show()
        return
    }
    val urls = listOf(
        "https://m.stock.naver.com/domestic/stock/$ticker/total",
        "https://finance.naver.com/item/main.naver?code=$ticker",
    )
    for (url in urls) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next URL candidate.
        } catch (_: Exception) {
            // Ignore and continue fallback URL attempts.
        }
    }
    Toast.makeText(context, "브라우저를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
}

@Composable
private fun DetailTopTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF111827) else Color(0xFF9AA5B1),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(if (selected) Color.Black else Color.Transparent)
        )
    }
}

@Composable
private fun TradeOrderTypeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color(0xFF111827) else Color(0xFFF1F5F9)
    val fg = if (selected) Color.White else Color(0xFF334155)
    Box(
        modifier = Modifier
            .background(
                color = if (enabled) bg else bg.copy(alpha = 0.5f),
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ScoreChip(text: String) {
    Box(
        modifier = Modifier
            .background(Color(0xFFF1F5F9), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = Color(0xFF334155),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun InvestorDailyCard(
    rows: List<StockInvestorDailyItemDto>,
    loading: Boolean,
    error: String?,
    source: String,
    message: String?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("종목투자자(일별)", fontWeight = FontWeight.ExtraBold)
                ScoreChip(source)
            }
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(message, color = Color(0xFF6B7280), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text("투자자 수급 데이터 계산 중...", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                    }
                }
                !error.isNullOrBlank() -> Text("데이터 로딩 오류: $error", color = Color(0xFFDC2626))
                rows.isEmpty() -> Text("표시할 일별 투자자 수급 데이터가 없습니다.", color = Color(0xFF6B7280))
                else -> {
                    val tableRows = rows.take(90)
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 4.dp),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFFF3F4F6))
                                    .padding(vertical = 8.dp),
                            ) {
                                DetailTableCell("일자", 112.dp, true)
                                DetailTableCell("개인", 108.dp, true)
                                DetailTableCell("외국인", 108.dp, true)
                                DetailTableCell("기관계", 108.dp, true)
                                DetailTableCell("사모", 108.dp, true)
                                DetailTableCell("기타법인", 112.dp, true)
                            }
                            tableRows.forEachIndexed { idx, row ->
                                val bg = if (idx % 2 == 0) Color.White else Color(0xFFFAFAFB)
                                Row(
                                    modifier = Modifier
                                        .background(bg)
                                        .padding(vertical = 8.dp),
                                ) {
                                    DetailTableCell(row.date.orEmpty(), 112.dp, true)
                                    DetailTableCell(
                                        fmtSignedInt(row.individualQty ?: 0L),
                                        108.dp,
                                        false,
                                        investorColor(row.individualQty ?: 0L),
                                    )
                                    DetailTableCell(
                                        fmtSignedInt(row.foreignQty ?: 0L),
                                        108.dp,
                                        false,
                                        investorColor(row.foreignQty ?: 0L),
                                    )
                                    DetailTableCell(
                                        fmtSignedInt(row.institutionQty ?: 0L),
                                        108.dp,
                                        false,
                                        investorColor(row.institutionQty ?: 0L),
                                    )
                                    DetailTableCell(
                                        fmtSignedInt(row.privateFundQty ?: 0L),
                                        108.dp,
                                        false,
                                        investorColor(row.privateFundQty ?: 0L),
                                    )
                                    DetailTableCell(
                                        fmtSignedInt(row.corporateQty ?: 0L),
                                        112.dp,
                                        false,
                                        investorColor(row.corporateQty ?: 0L),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntradayTrendCard(
    rows: List<StockTrendIntradayItemDto>,
    loading: Boolean,
    error: String?,
    source: String,
    message: String?,
    onRefresh: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("종목거래동향(시간별)", fontWeight = FontWeight.ExtraBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ScoreChip(source)
                    TextButton(onClick = onRefresh) { Text("새로고침") }
                }
            }
            if (!message.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(message, color = Color(0xFF6B7280), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(10.dp))
            when {
                loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                        Text("거래동향 데이터 계산 중...", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                    }
                }
                !error.isNullOrBlank() -> Text("데이터 로딩 오류: $error", color = Color(0xFFDC2626))
                rows.isEmpty() -> Text("표시할 시간별 거래동향 데이터가 없습니다.", color = Color(0xFF6B7280))
                else -> {
                    val tableRows = rows.take(120)
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 4.dp),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFFF3F4F6))
                                    .padding(vertical = 8.dp),
                            ) {
                                DetailTableCell("시간", 100.dp, true)
                                DetailTableCell("현재가", 118.dp, true)
                                DetailTableCell("등락(대비)", 162.dp, true)
                                DetailTableCell("순매수(추정)", 128.dp, true)
                                DetailTableCell("체결량증가", 128.dp, true)
                            }
                            tableRows.forEachIndexed { idx, row ->
                                val bg = if (idx % 2 == 0) Color.White else Color(0xFFFAFAFB)
                                val chgPct = row.changePct ?: 0.0
                                val chgAbs = row.changeAbs ?: 0.0
                                val netBuy = row.netBuyQtyEstimate ?: 0L
                                val deltaVol = row.volumeDelta ?: 0L
                                Row(
                                    modifier = Modifier
                                        .background(bg)
                                        .padding(vertical = 8.dp),
                                ) {
                                    DetailTableCell(row.time.orEmpty(), 100.dp, true)
                                    DetailTableCell(fmtPrice(row.currentPrice ?: 0.0), 118.dp, true)
                                    DetailTableCell(
                                        "${if (chgPct >= 0) "+" else ""}${"%.2f".format(chgPct)}% (${fmtSignedInt(chgAbs.roundToLong())})",
                                        162.dp,
                                        false,
                                        if (chgPct >= 0) Color(0xFFE95A68) else Color(0xFF2F6BFF),
                                    )
                                    DetailTableCell(
                                        fmtSignedInt(netBuy),
                                        128.dp,
                                        false,
                                        investorColor(netBuy),
                                    )
                                    DetailTableCell(
                                        fmtSignedInt(deltaVol),
                                        128.dp,
                                        false,
                                        investorColor(deltaVol),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean,
    color: Color = Color(0xFF111827),
) {
    Text(
        text = text,
        color = color,
        style = if (header) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
        fontWeight = if (header) FontWeight.ExtraBold else FontWeight.SemiBold,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 6.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private data class AiSignalResult(
    val score: Double,
    val signal: String,
    val techScore: Double,
    val newsScore: Double,
    val riskScore: Double,
    val entry: Double,
    val stop: Double,
    val target1: Double,
    val rr: Double,
    val reasons: List<String>,
)

private fun buildAiSignalResult(
    ticker: String,
    price: Double,
    prevClose: Double,
    chart: ChartDailyDto?,
    relatedNews: List<NewsArticleItemDto>,
    newsHotScore: Double,
    newsWindowLabel: String,
): AiSignalResult? {
    if (price <= 0.0 || prevClose <= 0.0) return null
    val points = chart?.points.orEmpty()
    if (points.size < 5) return null

    val closes = points.mapNotNull { it.close?.takeIf { v -> v > 0.0 } }
    val highs = points.mapNotNull { it.high?.takeIf { v -> v > 0.0 } }
    val lows = points.mapNotNull { it.low?.takeIf { v -> v > 0.0 } }
    val volumes = points.mapNotNull { it.volume?.takeIf { v -> v > 0.0 } }
    if (closes.size < 5) return null

    fun avgLast(list: List<Double>, n: Int): Double? {
        if (list.size < n) return null
        return list.takeLast(n).average()
    }

    fun pct(now: Double, base: Double): Double = if (base <= 0.0) 0.0 else ((now / base) - 1.0) * 100.0
    fun clamp(v: Double, low: Double, high: Double): Double = v.coerceIn(low, high)

    val chgPct = pct(price, prevClose)
    val sma20 = avgLast(closes, 20)
    val sma60 = avgLast(closes, 60)
    val hi20 = highs.takeLast(20).maxOrNull() ?: highs.maxOrNull() ?: price
    val lo20 = lows.takeLast(20).minOrNull() ?: lows.minOrNull() ?: price
    val vol20 = avgLast(volumes, 20)
    val volRatio = if (!volumes.isEmpty() && (vol20 ?: 0.0) > 0.0) volumes.last() / (vol20 ?: 1.0) else null

    val range14 = points.takeLast(14).mapNotNull { p ->
        val h = p.high ?: return@mapNotNull null
        val l = p.low ?: return@mapNotNull null
        val c = p.close ?: return@mapNotNull null
        if (h <= 0.0 || c <= 0.0) return@mapNotNull null
        ((h - l) / c) * 100.0
    }
    val avgRange14 = if (range14.isNotEmpty()) range14.average() else 0.0

    val target = normalizeTickerCode(ticker).orEmpty()
    val matchedArticles = relatedNews.filter { article ->
        val tickers = article.tickers.orEmpty()
        target.isNotBlank() && tickers.any { it == target }
    }.ifEmpty { relatedNews }
    val newsCount = matchedArticles.size
    var weighted = 0.0
    var impactSum = 0.0
    matchedArticles.forEach { article ->
        val impact = (article.impact ?: 0).toDouble()
        val polarityScore = when ((article.polarity ?: "").lowercase()) {
            "pos", "positive", "bullish", "good" -> 1.0
            "neg", "negative", "bearish", "bad" -> -1.0
            else -> 0.0
        }
        weighted += polarityScore * impact
        impactSum += impact
    }
    val sentNorm = if (impactSum > 0.0) weighted / impactSum else 0.0

    var tech = 50.0
    tech += clamp(chgPct * 1.2, -20.0, 20.0)
    if (sma20 != null) tech += clamp(pct(price, sma20) * 0.7, -12.0, 12.0)
    if (sma60 != null) tech += clamp(pct(price, sma60) * 0.5, -10.0, 10.0)
    tech += if (price >= hi20) 8.0 else if (price >= hi20 * 0.98) 4.0 else 0.0
    tech -= if (price <= lo20 * 1.02) 6.0 else 0.0
    if (volRatio != null) tech += clamp((volRatio - 1.0) * 4.0, -6.0, 6.0)
    tech = clamp(tech, 0.0, 100.0)

    var news = 50.0
    news += sentNorm * 25.0
    news += kotlin.math.min(20.0, newsCount * 2.0)
    if (newsHotScore > 0.0) news += kotlin.math.min(10.0, newsHotScore / 50.0)
    news = clamp(news, 0.0, 100.0)

    var risk = 50.0
    risk += kotlin.math.min(25.0, kotlin.math.max(0.0, avgRange14 - 4.0) * 3.0)
    risk += if (kotlin.math.abs(chgPct) >= 20.0) 10.0 else if (kotlin.math.abs(chgPct) >= 10.0) 5.0 else 0.0
    if (sma20 != null && price > sma20) risk -= 8.0
    risk = clamp(risk, 0.0, 100.0)

    val score = clamp(0.55 * tech + 0.25 * news + 0.20 * (100.0 - risk), 0.0, 100.0)
    val signal = when {
        score >= 72.0 -> "강한 매수"
        score >= 60.0 -> "매수"
        score >= 45.0 -> "관망"
        score >= 32.0 -> "주의"
        else -> "회피"
    }
    val entry = price * 0.985
    val stopRatio = kotlin.math.max(0.03, kotlin.math.min(0.10, (avgRange14 / 100.0) * 1.8))
    val stop = price * (1.0 - stopRatio)
    val targetRatio = kotlin.math.max(
        0.05,
        kotlin.math.min(0.16, (kotlin.math.abs(chgPct) / 100.0) * 0.35 + (avgRange14 / 100.0) * 1.5)
    )
    val target1 = price * (1.0 + targetRatio)
    val rr = if (price > stop) (target1 - price) / (price - stop) else 0.0

    val reasons = buildList {
        if (sma20 != null) {
            add(
                "기술: 현재가가 20일선 대비 ${"%.1f".format(pct(price, sma20))}%입니다. " +
                    "0% 이상이면 단기 추세가 상대적으로 강한 상태입니다."
            )
        }
        if (sma60 != null) {
            add(
                "기술: 현재가가 60일선 대비 ${"%.1f".format(pct(price, sma60))}%입니다. " +
                    "중기 추세 방향을 확인하는 기준입니다."
            )
        }
        if (volRatio != null) {
            add(
                "수급: 최근 거래량이 20일 평균의 ${"%.2f".format(volRatio)}배입니다. " +
                    "1배를 크게 넘으면 체결 참여가 강한 구간입니다."
            )
        }
        add(
            "뉴스: 최근 ${newsWindowLabel} 동안 연결 기사 ${newsCount}건, 핫지수 ${"%.1f".format(newsHotScore)}입니다. " +
                "기사 수와 영향도가 뉴스 점수에 반영됩니다."
        )
        add(
            "리스크: 최근 14일 평균 일중 변동폭이 ${"%.2f".format(avgRange14)}%입니다. " +
                "값이 클수록 손절 폭을 넓게 잡아야 합니다."
        )
    }

    return AiSignalResult(
        score = score,
        signal = signal,
        techScore = tech,
        newsScore = news,
        riskScore = risk,
        entry = entry,
        stop = stop,
        target1 = target1,
        rr = rr,
        reasons = reasons,
    )
}

private fun fmtPrice(v: Double): String = "%,d원".format(v.roundToLong())

private fun fmtSignedInt(v: Long): String {
    val abs = kotlin.math.abs(v)
    return if (v > 0) "+${"%,d".format(abs)}" else "-${"%,d".format(abs)}".takeIf { v < 0 } ?: "0"
}

private fun investorColor(v: Long): Color = when {
    v > 0 -> Color(0xFFE95A68)
    v < 0 -> Color(0xFF2F6BFF)
    else -> Color(0xFF6B7280)
}

private fun formatNewsSummaryForDisplay(raw: String): String {
    val normalized = raw
        .replace("\r\n", " ")
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (normalized.isBlank()) return ""
    return normalized
        .replace(Regex("(?<=[.!?。])\\s+"), "\n")
        .replace(Regex("(?<=[.!?。])(?=[^\\s\\n])"), "\n")
        .replace(Regex("(?<=다[.!?])\\s*"), "\n")
        .trim()
}

private fun buildHighlightedSummary(text: String, keywords: List<String>) = buildAnnotatedString {
    if (text.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val tokens = keywords
        .map { it.trim() }
        .filter { it.isNotBlank() && it.length >= 2 }
        .distinct()
    if (tokens.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }

    val regex = Regex(tokens.joinToString("|") { Regex.escape(it) }, setOf(RegexOption.IGNORE_CASE))
    var cursor = 0
    regex.findAll(text).forEach { m ->
        if (m.range.first > cursor) {
            append(text.substring(cursor, m.range.first))
        }
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = Color(0xFF111827))) {
            append(m.value)
        }
        cursor = m.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}

private fun chartRangeToNewsWindow(range: ChartRange): String = when (range) {
    ChartRange.D1 -> "24h"
    ChartRange.D7 -> "7d"
    ChartRange.M3 -> "30d"
    ChartRange.Y1 -> "365d"
    ChartRange.Y5 -> "1825d"
    ChartRange.ALL -> "2000d"
}

private fun chartRangeToNewsLabel(range: ChartRange): String = when (range) {
    ChartRange.D1 -> "24시간"
    ChartRange.D7 -> "7일"
    ChartRange.M3 -> "30일"
    ChartRange.Y1 -> "1년"
    ChartRange.Y5 -> "5년"
    ChartRange.ALL -> "전체 기간"
}

private fun mapOriginToRoute(origin: String): String? {
    val o = origin.trim()
    if (o.isBlank()) return null
    if (AppTab.entries.any { it.route == o }) return o
    return when {
        o.contains("단타") -> AppTab.PREMARKET.route
        o.contains("급등") -> AppTab.MOVERS.route
        o.contains("미장") -> AppTab.US.route
        o.contains("뉴스") -> AppTab.NEWS.route
        o.contains("장투") -> AppTab.LONGTERM.route
        o.contains("논문") -> AppTab.PAPERS.route
        o.contains("관심") -> AppTab.EOD.route
        o.contains("알림") -> AppTab.ALERTS.route
        o.contains("설정") -> AppTab.SETTINGS.route
        o.contains("자동") -> AppTab.AUTOTRADE.route
        else -> null
    }
}

private fun isNaverBackfillSource(source: String): Boolean {
    val s = source.trim().lowercase()
    return s.startsWith("naver_")
}

private enum class DetailNewsFilter(val label: String) {
    ALL("전체"),
    DISCLOSURE("공시"),
    EARNINGS("실적"),
    CONTRACT_MNA("계약/M&A"),
    REPORT("리포트"),
    COMMUNITY("커뮤니티"),
}

private enum class DetailNewsSort(val label: String) {
    LATEST("최신순"),
    IMPACT("영향순"),
}

private enum class DetailTradeEnv {
    DEMO,
    PROD,
}

private fun matchesDetailNewsFilter(
    filter: DetailNewsFilter,
    article: NewsArticleItemDto,
): Boolean {
    val event = article.eventType.orEmpty().lowercase()
    val source = article.source.orEmpty().lowercase()
    return when (filter) {
        DetailNewsFilter.ALL -> event != "community"
        DetailNewsFilter.DISCLOSURE -> source == "dart" || event in setOf("offering", "buyback")
        DetailNewsFilter.EARNINGS -> event == "earnings"
        DetailNewsFilter.CONTRACT_MNA -> event in setOf("contract", "mna")
        DetailNewsFilter.REPORT -> event == "report"
        DetailNewsFilter.COMMUNITY -> event == "community" || source.contains("community")
    }
}

private fun newsEventLabel(eventType: String): String = when (eventType.trim().lowercase()) {
    "earnings" -> "실적"
    "contract" -> "계약"
    "buyback" -> "자사주"
    "offering" -> "증자"
    "mna" -> "M&A"
    "regulation" -> "규제"
    "lawsuit" -> "소송"
    "report" -> "리포트"
    "community" -> "커뮤니티"
    else -> "일반"
}

private fun newsSourceLabel(source: String): String {
    val s = source.trim().lowercase()
    return when {
        s == "dart" -> "DART"
        s.contains("community") -> "커뮤니티"
        s.startsWith("naver_") -> "네이버"
        s.isBlank() -> "news"
        else -> "RSS"
    }
}

private fun resolveBottomTabs(csv: String): List<AppTab> {
    val byRoute = AppTab.entries.associateBy { it.route }
    val seen = linkedSetOf<String>()
    val ordered = mutableListOf<AppTab>()
    csv.split(",")
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { route ->
            val normalized = normalizeLegacyTabRoute(route)
            val tab = byRoute[normalized] ?: return@forEach
            if (seen.add(tab.route)) ordered += tab
        }
    if (!seen.contains(AppTab.HOLDINGS.route)) {
        val insertAt = ordered.indexOfFirst { it.route == AppTab.AUTOTRADE.route }
        val targetIndex = if (insertAt >= 0) insertAt + 1 else ordered.size
        ordered.add(targetIndex, AppTab.HOLDINGS)
        seen.add(AppTab.HOLDINGS.route)
    }
    AppTab.entries.forEach { tab ->
        if (seen.add(tab.route)) ordered += tab
    }
    return ordered
}

private fun normalizeLegacyTabRoute(route: String): String = when (route.trim()) {
    "movers2" -> "movers"
    else -> route.trim()
}

private fun toBottomTabOrderCsv(order: List<AppTab>): String =
    order.joinToString(",") { it.route }

private fun openMainRoute(context: Context, route: String) {
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            putExtra("route", route)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    )
}
