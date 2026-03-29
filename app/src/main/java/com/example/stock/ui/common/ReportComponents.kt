package com.example.stock.ui.common

import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.produceState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.URL
import java.util.Locale
import androidx.compose.ui.unit.dp
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.api.ChartPointDto
import com.example.stock.data.repository.UiSource
import com.example.stock.ServiceLocator
import com.example.stock.ui.screens.StockDetailActivity
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class MetricUi(
    val label: String,
    val value: Double,
    val formatted: String? = null,
)

data class CommonReportItemUi(
    val ticker: String? = null,
    val name: String? = null,
    val market: String? = null,
    val logoUrl: String? = null,
    val title: String,
    val quote: RealtimeQuoteItemDto?,
    val fallbackPrice: Double? = null,
    val fallbackChangePct: Double? = null,
    val fallbackLabel: String? = null,
    val metrics: List<MetricUi>,
    val extraLines: List<String> = emptyList(),
    val thesis: String? = null,
    val sortPrice: Double? = null,
    val sortChangePct: Double? = null,
    val sortName: String? = null,
    val sortAiSignal: Double? = null,
    val miniPoints: List<ChartPointDto>? = null,
    val actionLinkLabel: String? = null,
    val actionLinkUrl: String? = null,
    val currencyCode: String = "KRW",
    val titleMaxLines: Int = 1,
    val extraLinesMaxLines: Int = 1,
    val thesisMaxLines: Int = 2,
    val thesisFontSizeSp: Float = 12f,
    val thesisLineHeightSp: Float = 18f,
    val badgeLabel: String? = null,
    val displayReturnPct: Double? = null,
    val sparklineRangeLabel: String = "1 Month",
    val statusTag: String? = null,
    val eventTags: List<String> = emptyList(),
    val cardVariant: String = "default", // default | favorite_tracking
    val interestDays: Long? = null,
    val trackingBaselinePrice: Double? = null,
    val trackingPnlAmount: Double? = null,
    val trackingStartedAtLabel: String? = null,
    val trackingDurationLabel: String? = null,
)

enum class CardUiVersion {
    V1,
    V2;

    companion object {
        fun fromRaw(raw: String?): CardUiVersion = if ((raw ?: "").uppercase() == "V1") V1 else V2
    }
}

data class SortOption(val id: String, val label: String)

object SortOptions {
    const val DEFAULT = "default"
    const val PRICE_ASC = "price_asc"
    const val PRICE_DESC = "price_desc"
    const val CHANGE_ASC = "chg_asc"
    const val CHANGE_DESC = "chg_desc"
    const val NAME_ASC = "name_asc"
    const val NAME_DESC = "name_desc"
    const val AI_SIGNAL_DESC = "ai_signal_desc"
    const val AI_STRONG = "ai_strong"
    const val AI_BUY = "ai_buy"
    const val AI_WATCH = "ai_watch"
    const val AI_CAUTION = "ai_caution"
    const val AI_AVOID = "ai_avoid"
}

fun aiBuySignalSortScoreFromText(text: String?): Double? {
    val raw = text?.trim().orEmpty()
    if (raw.isBlank()) return null
    val compact = raw.replace(" ", "").lowercase(Locale.ROOT)
    return when {
        raw.contains("강한 매수") || raw.contains("강한매수") || raw.contains("강함") || compact.contains("strongbuy") -> 5.0
        raw.contains("매수") -> 4.0
        // "관망" 문구는 실행상태/설명 문맥에 섞여 오탐이 많아서
        // 카드 고정 라벨로 사용하지 않는다.
        raw.contains("관망") -> null
        raw.contains("주의") -> 2.0
        raw.contains("회피") -> 1.0
        else -> null
    }
}

fun aiBuySignalSortScoreFromMarketData(
    quote: RealtimeQuoteItemDto?,
    miniPoints: List<ChartPointDto>?,
): Double? {
    val price = quote?.price?.takeIf { it > 0.0 } ?: return null
    val prevClose = quote.prevClose?.takeIf { it > 0.0 }
    val changePct = quote.chgPct
        ?: prevClose?.let { base -> ((price / base) - 1.0) * 100.0 }
        ?: 0.0
    val closes = miniPoints
        .orEmpty()
        .mapNotNull { point ->
            (point.close ?: point.open ?: point.high ?: point.low)?.takeIf { it > 0.0 }
        }
    val trendPct = if (closes.size >= 2) {
        val first = closes.first()
        val last = closes.last()
        if (first > 0.0) ((last / first) - 1.0) * 100.0 else null
    } else {
        null
    }
    val high = closes.maxOrNull()
    val low = closes.minOrNull()
    var score = 3.0
    score += (changePct / 8.0).coerceIn(-1.0, 1.0) * 1.1
    if (trendPct != null) {
        score += (trendPct / 10.0).coerceIn(-1.0, 1.0) * 0.9
    }
    if (high != null && price >= high * 0.995) {
        score += 0.2
    }
    if (low != null && price <= low * 1.005) {
        score -= 0.2
    }
    return score.coerceIn(1.0, 5.0)
}

fun resolveAiSignalSortScore(
    thesis: String?,
    quote: RealtimeQuoteItemDto?,
    miniPoints: List<ChartPointDto>?,
): Double? {
    val thesisScore = aiBuySignalSortScoreFromText(thesis)
    val marketScore = aiBuySignalSortScoreFromMarketData(quote, miniPoints)
    return when {
        thesisScore != null && marketScore != null -> maxOf(thesisScore, marketScore)
        marketScore != null -> marketScore
        thesisScore != null -> thesisScore
        else -> null
    }
}

fun aiSignalSortIdFromScore(score: Double?): String? {
    val s = score ?: return null
    return when {
        s >= 4.75 -> SortOptions.AI_STRONG
        s >= 3.75 -> SortOptions.AI_BUY
        s >= 2.75 -> SortOptions.AI_WATCH
        s >= 1.75 -> SortOptions.AI_CAUTION
        else -> SortOptions.AI_AVOID
    }
}

fun aiSignalLabelFromScore(score: Double?): String? {
    return when (aiSignalSortIdFromScore(score)) {
        SortOptions.AI_STRONG -> "강함"
        SortOptions.AI_BUY -> "매수"
        SortOptions.AI_WATCH -> null
        SortOptions.AI_CAUTION -> "주의"
        SortOptions.AI_AVOID -> "회피"
        else -> null
    }
}

@Composable
@OptIn(ExperimentalMaterialApi::class, ExperimentalFoundationApi::class)
fun CommonReportList(
    source: UiSource,
    statusMessage: String?,
    updatedAt: String? = null,
    header: String,
    glossaryDialogTitle: String = "용어 설명집",
    glossaryItems: List<GlossaryItem> = emptyList(),
    items: List<CommonReportItemUi>,
    emptyText: String,
    initialDisplayCount: Int = 0,
    refreshToken: Int = 0,
    refreshLoading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
    receivedCount: Int? = null,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    showSearchBar: Boolean = true,
    showSortBar: Boolean = true,
    onItemClick: ((CommonReportItemUi) -> Unit)? = null,
    showRiskRules: Boolean = false,
    riskRules: List<String> = emptyList(),
    selectedSortId: String = SortOptions.DEFAULT,
    onSortChange: (String) -> Unit = {},
    favoriteTickers: Set<String> = emptySet(),
    onToggleFavorite: ((CommonReportItemUi, Boolean) -> Unit)? = null,
    filtersContent: (@Composable () -> Unit)? = null,
    topContent: (LazyListScope.() -> Unit)? = null,
    extraContent: (LazyListScope.() -> Unit)? = null,
    onVisibleTickersChanged: ((List<String>) -> Unit)? = null,
    scrollToTopToken: Int = 0,
    stickyFilters: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val pageBg = Color(0xFFF5F6F8)
    val normalizedQuery = query.trim().lowercase()
    val effectiveQuery = if (showSearchBar) normalizedQuery else ""
    val effectiveSortId = if (showSortBar) selectedSortId else SortOptions.DEFAULT
    val sortedItems = remember(items, effectiveQuery, effectiveSortId) {
        val filtered = if (effectiveQuery.isBlank()) {
            items
        } else {
            items.filter {
                (it.name ?: "").lowercase().contains(effectiveQuery) ||
                (it.ticker ?: "").lowercase().contains(effectiveQuery) ||
                it.title.lowercase().contains(effectiveQuery)
            }
        }
        val aiStageTarget = when (effectiveSortId) {
            SortOptions.AI_STRONG -> SortOptions.AI_STRONG
            SortOptions.AI_BUY -> SortOptions.AI_BUY
            SortOptions.AI_WATCH -> SortOptions.AI_WATCH
            SortOptions.AI_CAUTION -> SortOptions.AI_CAUTION
            SortOptions.AI_AVOID -> SortOptions.AI_AVOID
            else -> null
        }
        val aiStageFiltered = if (aiStageTarget != null) {
            filtered.filter { aiSignalSortIdFromScore(it.sortAiSignal) == aiStageTarget }
        } else {
            filtered
        }
        when (effectiveSortId) {
            SortOptions.PRICE_ASC -> aiStageFiltered.sortedBy { it.sortPrice ?: Double.POSITIVE_INFINITY }
            SortOptions.PRICE_DESC -> aiStageFiltered.sortedByDescending { it.sortPrice ?: Double.NEGATIVE_INFINITY }
            SortOptions.CHANGE_ASC -> aiStageFiltered.sortedBy { it.sortChangePct ?: Double.POSITIVE_INFINITY }
            SortOptions.CHANGE_DESC -> aiStageFiltered.sortedByDescending { it.sortChangePct ?: Double.NEGATIVE_INFINITY }
            SortOptions.NAME_ASC -> aiStageFiltered.sortedBy { it.sortName ?: "" }
            SortOptions.NAME_DESC -> aiStageFiltered.sortedByDescending { it.sortName ?: "" }
            SortOptions.AI_SIGNAL_DESC,
            SortOptions.AI_STRONG,
            SortOptions.AI_BUY,
            SortOptions.AI_WATCH,
            SortOptions.AI_CAUTION,
            SortOptions.AI_AVOID -> aiStageFiltered.sortedByDescending { it.sortAiSignal ?: Double.NEGATIVE_INFINITY }
            else -> aiStageFiltered
        }
    }
    var displayCount by remember(sortedItems.size, initialDisplayCount) {
        mutableStateOf(
            if (initialDisplayCount > 0) minOf(initialDisplayCount, sortedItems.size) else sortedItems.size
        )
    }
    val visibleItems by remember(displayCount, sortedItems) {
        derivedStateOf { sortedItems.take(displayCount) }
    }
    val totalCount = receivedCount ?: items.size
    LaunchedEffect(refreshToken, refreshLoading, totalCount) {
        if (refreshToken > 0 && !refreshLoading && snackbarHostState != null) {
            val desired = if (initialDisplayCount > 0) initialDisplayCount else totalCount
            val msg = if (totalCount < desired) {
                "새로고침: 서버 ${totalCount}개 (설정 ${desired}) · 오늘 조건 통과 종목이 부족할 수 있음"
            } else {
                "새로고침: 서버 ${totalCount}개 수신"
            }
            snackbarHostState.showSnackbar(msg)
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopToken) {
        if (scrollToTopToken > 0) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState, sortedItems.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collectLatest { last ->
                if (displayCount < sortedItems.size && last >= displayCount - 3) {
                    displayCount = minOf(displayCount + 10, sortedItems.size)
                }
            }
    }
    LaunchedEffect(listState, visibleItems, onVisibleTickersChanged) {
        if (onVisibleTickersChanged == null) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { info ->
                    val raw = info.key as? String ?: return@mapNotNull null
                    if (!raw.startsWith("ticker:")) return@mapNotNull null
                    val ticker = raw.removePrefix("ticker:").substringBeforeLast(":")
                    ticker.takeIf { it.isNotBlank() }
                }
                .distinct()
        }
            .distinctUntilChanged()
            .collectLatest { tickers ->
                if (tickers.isNotEmpty()) {
                    onVisibleTickersChanged.invoke(tickers)
                }
            }
    }
    val pullState = if (onRefresh != null) {
        rememberPullRefreshState(
            refreshing = refreshLoading,
            onRefresh = {
                // Avoid re-entrant refresh calls while already refreshing.
                if (!refreshLoading) onRefresh()
            },
        )
    } else {
        null
    }
    var refreshStartedAtMs by remember { mutableStateOf(0L) }
    LaunchedEffect(refreshLoading) {
        if (refreshLoading) {
            if (refreshStartedAtMs <= 0L) refreshStartedAtMs = System.currentTimeMillis()
        } else {
            refreshStartedAtMs = 0L
        }
    }
    val loadingElapsedSec by produceState(initialValue = 0L, refreshLoading, refreshStartedAtMs) {
        if (!refreshLoading || refreshStartedAtMs <= 0L) {
            value = 0L
            return@produceState
        }
        while (refreshLoading) {
            value = ((System.currentTimeMillis() - refreshStartedAtMs).coerceAtLeast(0L) / 1000L)
            delay(500L)
        }
    }
    val inGenerationQueue = (statusMessage ?: "")
        .replace(" ", "")
        .let { it.contains("생성대기") || it.contains("생성중") }
    val activeLoadingMessage = when {
        !refreshLoading -> null
        inGenerationQueue -> "추천 데이터 생성 중입니다(보통 10~30초)."
        source == UiSource.FALLBACK -> "대체 데이터를 표시 중입니다. 안정화 후 자동 갱신됩니다."
        source == UiSource.CACHE -> "최신 캐시를 표시 중이며 백그라운드에서 갱신 중입니다."
        loadingElapsedSec >= 10L -> "지연되고 있어요. 이전 데이터는 계속 볼 수 있습니다."
        loadingElapsedSec >= 3L -> "데이터 계산 중입니다. 잠시만 기다려주세요."
        else -> "데이터 불러오는 중..."
    }

    @Composable
    fun ListBody(listModifier: Modifier) {
        val context = LocalContext.current
        LazyColumn(
            state = listState,
            modifier = listModifier.fillMaxWidth().background(pageBg).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ReportHeaderCard(
                    title = header,
                    statusMessage = activeLoadingMessage ?: statusMessage ?: "데이터 로드 완료",
                    updatedAt = updatedAt,
                    source = source,
                    glossaryDialogTitle = glossaryDialogTitle,
                    glossaryItems = glossaryItems,
                )
            }
            if (refreshLoading && sortedItems.isNotEmpty()) {
                item {
                    InlineRefreshRow(
                        message = activeLoadingMessage ?: "실시간 데이터 갱신 중...",
                    )
                }
            }
            if (showSearchBar) {
                item {
                    SearchBar(
                        query = query,
                        onQueryChange = onQueryChange,
                    )
                }
            }
            if (filtersContent != null || showSortBar) {
                if (stickyFilters) {
                    stickyHeader {
                        // Sticky headers should paint the background to avoid content showing through while pinned.
                        Box(Modifier.fillMaxWidth().background(pageBg).padding(bottom = 10.dp)) {
                            if (filtersContent != null) {
                                filtersContent()
                            } else {
                                SortSelect(selectedSortId = selectedSortId, onSortChange = onSortChange)
                            }
                        }
                    }
                } else {
                    item {
                        if (filtersContent != null) {
                            filtersContent()
                        } else {
                            SortSelect(selectedSortId = selectedSortId, onSortChange = onSortChange)
                        }
                    }
                }
            }

            topContent?.invoke(this)

            if (sortedItems.isEmpty()) {
                if (refreshLoading) {
                    item {
                        LoadingStateCard(
                            message = activeLoadingMessage ?: "데이터 불러오는 중...",
                        )
                    }
                    items(4) { LoadingSkeletonCard() }
                } else {
                    item { Text(emptyText, color = Color(0xFF7B8794)) }
                }
            } else {
                items(
                    count = visibleItems.size,
                    key = { idx -> "ticker:${visibleItems[idx].ticker.orEmpty()}:$idx" },
                ) { idx ->
                    val item = visibleItems[idx]
                    val ticker = item.ticker.orEmpty()
                    val cardClick: (() -> Unit)? = if (ticker.isNotBlank()) {
                        {
                            StockDetailActivity.open(
                                context = context,
                                ticker = ticker,
                                name = item.name ?: item.title,
                                origin = header,
                                eventTags = item.eventTags,
                            )
                        }
                    } else {
                        onItemClick?.let { { it(item) } }
                    }
                    CommonReportItemCard(
                        item = item,
                        isFavorite = ticker.isNotBlank() && favoriteTickers.contains(ticker),
                        onToggleFavorite = if (onToggleFavorite != null) { next ->
                            onToggleFavorite(item, next)
                        } else null,
                        onClick = cardClick,
                    )
                }
                if (displayCount < sortedItems.size) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "더 불러오는 중...",
                                color = Color(0xFF6B7280),
                                modifier = Modifier.clickable {
                                    displayCount = minOf(displayCount + 10, sortedItems.size)
                                }
                            )
                        }
                    }
                }
            }

            if (showRiskRules) {
                item { RiskFooter(riskRules) }
            }

            extraContent?.invoke(this)
        }
    }

    if (pullState != null) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(pageBg)
                .pullRefresh(pullState),
        ) {
            if (refreshLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .zIndex(2f),
                    color = Color(0xFF2F7BF6),
                    trackColor = Color(0xFFDDE6F2),
                )
            }
            ListBody(listModifier = Modifier.fillMaxWidth())
        }
    } else {
        ListBody(listModifier = modifier)
    }
}

@Composable
private fun InlineRefreshRow(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text = message,
            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
            color = Color(0xFF64748B),
        )
    }
}

@Composable
private fun LoadingStateCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = message,
                style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
                color = Color(0xFF475569),
            )
        }
    }
}

@Composable
private fun LoadingSkeletonCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE2E8F0)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE2E8F0)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF1F5F9)),
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                "종목 검색 (이름/티커)",
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            )
        },
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        shape = RoundedCornerShape(11.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFFFFFFF),
            unfocusedContainerColor = Color(0xFFFFFFFF),
            disabledContainerColor = Color(0xFFFFFFFF),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SortSelect(selectedSortId: String, onSortChange: (String) -> Unit) {
    val options = listOf(
        SelectOptionUi(SortOptions.DEFAULT, "기본"),
        SelectOptionUi(SortOptions.PRICE_ASC, "가격↑"),
        SelectOptionUi(SortOptions.PRICE_DESC, "가격↓"),
        SelectOptionUi(SortOptions.CHANGE_ASC, "등락↑"),
        SelectOptionUi(SortOptions.CHANGE_DESC, "등락↓"),
        SelectOptionUi(SortOptions.NAME_ASC, "이름↑"),
        SelectOptionUi(SortOptions.NAME_DESC, "이름↓"),
    )
    CommonSortThemeBar(
        sortOptions = options,
        selectedSortId = selectedSortId,
        onSortChange = onSortChange,
        themeOptions = null,
        selectedThemeId = "",
        onThemeChange = {},
    )
}

@Composable
fun CommonReportItemCard(
    item: CommonReportItemUi,
    isFavorite: Boolean = false,
    onToggleFavorite: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = remember(context) { ServiceLocator.repository(context).getSettings() }
    val cardVersion = remember(settings.cardUiVersion) { CardUiVersion.fromRaw(settings.cardUiVersion) }
    val statusTagLabel = item.statusTag?.trim()?.takeIf { it.isNotBlank() }
    val aiSignalLabel = aiSignalLabelFromScore(item.sortAiSignal)?.trim()?.takeIf { it.isNotBlank() }
    val displaySignalLabel = statusTagLabel ?: aiSignalLabel
    val resolvedTitleMaxLines = if (item.titleMaxLines <= 0) Int.MAX_VALUE else item.titleMaxLines
    val resolvedExtraLinesMaxLines = if (item.extraLinesMaxLines <= 0) Int.MAX_VALUE else item.extraLinesMaxLines
    val resolvedThesisMaxLines = if (item.thesisMaxLines <= 0) Int.MAX_VALUE else item.thesisMaxLines
    val modifier = if (onClick != null) Modifier.fillMaxWidth().clickable { onClick() } else Modifier.fillMaxWidth()
    if (item.cardVariant == "favorite_tracking") {
        FavoriteTrackingCard(item = item, isFavorite = isFavorite, onToggleFavorite = onToggleFavorite, modifier = modifier)
        return
    }
    if (cardVersion == CardUiVersion.V1) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MiniCandles(item.miniPoints, modifier = Modifier.width(8.dp).height(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = resolvedTitleMaxLines,
                            overflow = if (resolvedTitleMaxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
                        )
                        val showQuoteLine = !item.ticker.isNullOrBlank() ||
                            item.quote != null ||
                            item.fallbackPrice != null ||
                            !item.fallbackLabel.isNullOrBlank()
                        if (showQuoteLine) {
                            QuoteLine(
                                item.quote,
                                item.fallbackPrice,
                                item.fallbackChangePct,
                                item.fallbackLabel,
                                item.currencyCode,
                            )
                        }
                        if (!displaySignalLabel.isNullOrBlank()) {
                            Text(
                                displaySignalLabel,
                                modifier = Modifier.padding(top = 2.dp),
                                style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold),
                                color = Color(0xFFE95A68),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!item.ticker.isNullOrBlank() && onToggleFavorite != null) {
                        IconButton(onClick = { onToggleFavorite(!isFavorite) }) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (isFavorite) "관심 해제" else "관심 추가",
                                tint = if (isFavorite) Color(0xFFE53935) else Color(0xFF94A3B8),
                            )
                        }
                    }
                }
                if (item.metrics.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        item.metrics.forEach { MetricBox(it.label, it.value, it.formatted) }
                    }
                }
                item.extraLines.forEach { line ->
                    Text(
                        line,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6B7280),
                        maxLines = resolvedExtraLinesMaxLines,
                        overflow = if (resolvedExtraLinesMaxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
                if (!item.thesis.isNullOrBlank()) {
                    Text(
                        item.thesis.orEmpty(),
                        style = TextStyle(fontSize = item.thesisFontSizeSp.sp, lineHeight = item.thesisLineHeightSp.sp),
                        color = Color(0xFF7B8794),
                        modifier = Modifier.padding(top = 10.dp),
                        maxLines = resolvedThesisMaxLines,
                        overflow = if (resolvedThesisMaxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
                if (!item.actionLinkUrl.isNullOrBlank()) {
                    Text(
                        text = item.actionLinkLabel ?: "원문 링크",
                        style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF2563EB),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.actionLinkUrl)))
                                }
                            },
                    )
                }
            }
        }
        return
    }

    val pct = item.displayReturnPct ?: item.quote?.chgPct ?: item.fallbackChangePct ?: 0.0
    val pctColor = if (pct >= 0) Color(0xFFE95A68) else Color(0xFF2F6BFF)
    val pctText = "${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%"
    val ticker = item.ticker?.takeIf { it.isNotBlank() }
    val tickerLabel = ticker ?: item.badgeLabel ?: "종목"
    val rawName = item.name ?: item.title
    val displayName = rawName.take(10)
    val tickerText = tickerLabel
    val themeText = item.extraLines
        .asSequence()
        .flatMap { line -> line.split("·").asSequence().map { it.trim() } }
        .mapNotNull { segment ->
            when {
                segment.startsWith("테마:", ignoreCase = false) ->
                    segment.substringAfter("테마:").trim().takeIf { it.isNotBlank() }
                segment.startsWith("테마 ", ignoreCase = false) ->
                    segment.substringAfter("테마 ").trim().takeIf { it.isNotBlank() }
                segment.startsWith("테마", ignoreCase = false) ->
                    segment.removePrefix("테마").trim().removePrefix(":").trim().takeIf { it.isNotBlank() }
                else -> null
            }
        }
        .firstOrNull()
        .orEmpty()
    val categoryText = listOfNotNull(
        themeText.takeIf { it.isNotBlank() },
        displaySignalLabel?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val categoryColor = Color(0xFF94A3B8)
    val categoryWeight = FontWeight.Medium
    val currentPrice = item.quote?.price ?: item.fallbackPrice
    val changePct = item.quote?.chgPct ?: item.fallbackChangePct
    val priceText = currentPrice?.let { fmtCurrency(it, item.currencyCode) } ?: "--"
    val pillBg = if (pct >= 0) Color(0xFFFCEDEF) else Color(0xFFEAF0FF)
    val rightColWidth = 92.dp
    val resolvedMiniPoints = remember(item.miniPoints, item.quote, item.fallbackPrice) {
        val raw = item.miniPoints.orEmpty().filter { (it.close ?: it.open ?: it.high ?: it.low ?: 0.0) > 0.0 }
        if (raw.size >= 2) {
            raw
        } else {
            val prev = item.quote?.prevClose ?: 0.0
            val now = item.quote?.price ?: item.fallbackPrice ?: 0.0
            if (prev > 0.0 && now > 0.0) {
                listOf(
                    ChartPointDto(date = "prev", open = prev, high = prev, low = prev, close = prev, volume = null),
                    ChartPointDto(date = "now", open = now, high = now, low = now, close = now, volume = null),
                )
            } else {
                raw
            }
        }
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chartWidth = (maxWidth * 0.26f).coerceIn(78.dp, 110.dp)
            val sparklineWidth = (chartWidth - 8.dp).coerceIn(72.dp, 102.dp)
            val hasFavoriteAction = !item.ticker.isNullOrBlank() && onToggleFavorite != null
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StockLogoBadge(
                        ticker = item.ticker.orEmpty(),
                        market = item.market,
                        logoUrl = item.logoUrl,
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .weight(1f)
                            .height(54.dp),
                    ) {
                        MiniSparkLine(
                            points = resolvedMiniPoints,
                            color = pctColor,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(sparklineWidth)
                                .height(30.dp),
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .zIndex(1f),
                        ) {
                            Text(
                                text = displayName,
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                            )
                            Text(
                                text = tickerText,
                                color = Color(0xFF7B8794),
                                style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (categoryText.isNotBlank()) {
                                Text(
                                    text = categoryText,
                                    color = categoryColor,
                                    style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                                    fontWeight = categoryWeight,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .width(rightColWidth),
                    ) {
                        if (hasFavoriteAction) {
                            IconButton(
                                onClick = { onToggleFavorite?.invoke(!isFavorite) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isFavorite) "관심 해제" else "관심 추가",
                                    tint = if (isFavorite) Color(0xFFE53935) else Color(0xFF9AA5B1),
                                )
                            }
                        }
                        Text(
                            text = priceText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pctText,
                            color = pctColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .background(pillBg, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteTrackingCard(
    item: CommonReportItemUi,
    isFavorite: Boolean,
    onToggleFavorite: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val pct = item.displayReturnPct ?: item.quote?.chgPct ?: item.fallbackChangePct ?: 0.0
    val pctColor = if (pct >= 0) Color(0xFFE95A68) else Color(0xFF2F6BFF)
    val pctText = "${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%"
    val currentPrice = item.quote?.price ?: item.fallbackPrice
    val priceText = currentPrice?.let { fmtCurrency(it, item.currencyCode) } ?: "--"
    val pillBg = if (pct >= 0) Color(0xFFFCEDEF) else Color(0xFFEAF0FF)
    val tickerText = item.ticker.orEmpty()
    val traceLine = item.badgeLabel?.takeIf { it.isNotBlank() }?.let { "출처 $it" } ?: "관심 성과"
    val baselineText = item.trackingBaselinePrice?.let { "기준 ${fmtCurrency(it, item.currencyCode)}" } ?: "기준 -"
    val pnlText = item.trackingPnlAmount?.let {
        val sign = if (it >= 0) "+" else ""
        "손익 ${sign}${fmtCurrency(it, item.currencyCode)}"
    } ?: "손익 -"
    val resolvedMiniPoints = remember(item.miniPoints, item.quote, item.fallbackPrice) {
        val raw = item.miniPoints.orEmpty().filter { (it.close ?: it.open ?: it.high ?: it.low ?: 0.0) > 0.0 }
        if (raw.size >= 2) {
            raw
        } else {
            val prev = item.quote?.prevClose ?: 0.0
            val now = item.quote?.price ?: item.fallbackPrice ?: 0.0
            if (prev > 0.0 && now > 0.0) {
                listOf(
                    ChartPointDto(date = "prev", open = prev, high = prev, low = prev, close = prev, volume = null),
                    ChartPointDto(date = "now", open = now, high = now, low = now, close = now, volume = null),
                )
            } else raw
        }
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chartWidth = (maxWidth * 0.23f).coerceIn(70.dp, 100.dp)
            val sparklineWidth = (chartWidth - 6.dp).coerceIn(64.dp, 94.dp)
            val hasFavoriteAction = !item.ticker.isNullOrBlank() && onToggleFavorite != null
            Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StockLogoBadge(
                        ticker = item.ticker.orEmpty(),
                        market = item.market,
                        logoUrl = item.logoUrl,
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .weight(1f)
                            .height(76.dp),
                    ) {
                        MiniSparkLine(
                            points = resolvedMiniPoints,
                            color = pctColor,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(sparklineWidth)
                                .height(32.dp),
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .zIndex(1f),
                        ) {
                            Text(
                                text = (item.name ?: item.title).take(10),
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = tickerText,
                                color = Color(0xFF7B8794),
                                style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = traceLine,
                                color = Color(0xFF94A3B8),
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "$baselineText · $pnlText",
                                color = Color(0xFF64748B),
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .width(104.dp),
                    ) {
                        if (hasFavoriteAction) {
                            IconButton(
                                onClick = { onToggleFavorite?.invoke(!isFavorite) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isFavorite) "관심 해제" else "관심 추가",
                                    tint = if (isFavorite) Color(0xFFE53935) else Color(0xFF9AA5B1),
                                )
                            }
                        }
                        Text(
                            text = priceText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pctText,
                            color = pctColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .background(pillBg, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StockLogoBadge(
    ticker: String,
    market: String?,
    logoUrl: String?,
    modifier: Modifier = Modifier,
) {
    val marketNorm = (market ?: "").uppercase(Locale.US)
    val ringColor: Color
    val bg: Color
    val marketLogo: String
    val fg: Color
    when {
        marketNorm.contains("KOSPI") -> {
            ringColor = Color(0xFF4B7BEC)
            bg = Color(0xFFEAF2FF)
            marketLogo = "KOSPI"
            fg = Color(0xFF2F5DBE)
        }
        marketNorm.contains("KOSDAQ") -> {
            ringColor = Color(0xFF2FAF7B)
            bg = Color(0xFFE9F8F1)
            marketLogo = "KOSDAQ"
            fg = Color(0xFF1E7B57)
        }
        else -> {
            ringColor = Color(0xFFD0D7DE)
            bg = Color(0xFFF3F4F6)
            marketLogo = "MKT"
            fg = Color(0xFF6B7280)
        }
    }
    val resolvedLogoUrl = remember(logoUrl, ticker) {
        val normalized = logoUrl
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let {
                if (it.endsWith(".svg", ignoreCase = true)) {
                    it.replace("/logo/stock/", "/logo/png/stock/").replace(".svg", ".png")
                } else {
                    it
                }
            }
        normalized ?: if (ticker.length == 6 && ticker.all(Char::isDigit)) {
            "https://ssl.pstatic.net/imgstock/fn/real/logo/png/stock/Stock$ticker.png"
        } else {
            null
        }
    }
    val logoBitmap = rememberRemoteLogoBitmap(resolvedLogoUrl)
    if (logoBitmap != null) {
        Box(
            modifier = modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = logoBitmap,
                contentDescription = "기업 로고",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(40.dp)
                .background(bg, RoundedCornerShape(999.dp))
                .border(1.dp, ringColor, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = marketLogo,
                color = fg,
                style = TextStyle(fontSize = 8.sp, lineHeight = 9.sp),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun rememberRemoteLogoBitmap(url: String?): ImageBitmap? {
    val safeUrl = url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    val state by produceState<ImageBitmap?>(initialValue = null, key1 = safeUrl) {
        value = null
        if (safeUrl.isNullOrBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                URL(safeUrl).openStream().use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    return state
}

@Composable
private fun MiniSparkLine(points: List<ChartPointDto>?, color: Color, modifier: Modifier = Modifier) {
    val data = points.orEmpty()
        .mapNotNull { point ->
            listOf(point.close, point.open, point.high, point.low).firstOrNull { (it ?: 0.0) > 0.0 }
        }
    if (data.size < 2) {
        Canvas(modifier) {
            val y = size.height * 0.62f
            drawLine(
                color = color.copy(alpha = 0.25f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 3f,
            )
        }
        return
    }
    val min = data.minOrNull() ?: 0.0
    val max = data.maxOrNull() ?: 1.0
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    Canvas(modifier) {
        val step = size.width / (data.size - 1).coerceAtLeast(1)
        val vPad = size.height * 0.12f
        val usableHeight = (size.height - (vPad * 2f)).coerceAtLeast(1f)
        val upColor = Color(0xFFE95A68)
        val downColor = Color(0xFF2F6BFF)
        val flatColor = Color(0xFF9AA5B1)
        val strokeWidth = 4f
        fun yFor(v: Double): Float = vPad + (usableHeight - (((v - min) / range).toFloat() * usableHeight))
        for (i in 0 until data.lastIndex) {
            val v1 = data[i]
            val v2 = data[i + 1]
            val x1 = i * step
            val x2 = (i + 1) * step
            val segColor = when {
                v2 > v1 -> upColor
                v2 < v1 -> downColor
                else -> flatColor
            }
            drawLine(
                color = segColor,
                start = Offset(x1, yFor(v1)),
                end = Offset(x2, yFor(v2)),
                strokeWidth = strokeWidth,
            )
        }
    }
}

@Composable
private fun MiniCandles(points: List<ChartPointDto>?, modifier: Modifier = Modifier) {
    val data = points?.filter { (it.high ?: 0.0) > 0.0 && (it.low ?: 0.0) > 0.0 } ?: emptyList()
    val p = data.lastOrNull()
    if (p == null) {
        Box(modifier)
        return
    }
    val maxV = (p.high ?: 1.0)
    val minV = (p.low ?: 0.0)
    val range = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val o = p.open ?: p.close ?: 0.0
        val c = p.close ?: o
        val hi = p.high ?: maxV
        val lo = p.low ?: minV
        val x = w * 0.25f
        fun y(v: Double): Float = (h - ((v - minV) / range).toFloat() * h)
        val yHigh = y(hi)
        val yLow = y(lo)
        val yOpen = y(o)
        val yClose = y(c)
        val up = c >= o
        val color = if (up) Color(0xFFFF4D4F) else Color(0xFF2F6BFF)
        drawLine(color, Offset(x, yHigh), Offset(x, yLow), strokeWidth = 6f)
        val top = minOf(yOpen, yClose)
        val bottom = maxOf(yOpen, yClose)
        drawLine(color, Offset(x, top), Offset(x, bottom), strokeWidth = 14f)
    }
}

@Composable
fun MetricBox(label: String, value: Double, formatted: String? = null) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9AA5B1))
        Text(
            formatted ?: fmtCompact(value),
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111827),
            style = TextStyle(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun QuoteLine(q: RealtimeQuoteItemDto?) {
    QuoteLine(q, null, null, null, "KRW")
}

@Composable
fun QuoteLine(
    q: RealtimeQuoteItemDto?,
    fallbackPrice: Double?,
    fallbackChangePct: Double?,
    fallbackLabel: String?,
    currencyCode: String = "KRW",
) {
    if (q == null) {
        if (fallbackPrice != null) {
            val label = fallbackLabel ?: "전일 종가"
            val pct = fallbackChangePct
            val pctText = if (pct != null) " (${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%)" else ""
            Text(
                "$label ${fmtCurrency(fallbackPrice, currencyCode)}$pctText",
                color = Color(0xFF7B8794),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text("현재가 등록 필요 (전일 대비 --%)", color = Color(0xFF9AA5B1), style = MaterialTheme.typography.bodySmall)
            Text("등록", color = Color(0xFF9AA5B1), style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    val price = q.price ?: 0.0
    val chgPct = q.chgPct ?: 0.0
    val color = if (chgPct >= 0) Color(0xFFFF4D4F) else Color(0xFF2F6BFF)
    val sign = if (chgPct >= 0) "+" else ""
    Text(
        "현재가 ${fmtCurrency(price, currencyCode)} ($sign${"%.2f".format(chgPct)}%)",
        color = color,
        style = MaterialTheme.typography.bodySmall,
    )
    QuoteQualityTag(q)
}

@Composable
private fun QuoteQualityTag(q: RealtimeQuoteItemDto) {
    val isLive = q.isLive == true
    val source = q.source ?: ""
    val label = if (isLive) "실시간" else "지연"
    val color = if (isLive) Color(0xFF2DBD6E) else Color(0xFF7B8794)
    val extra = if (source.isNotBlank()) "·$source" else ""
    Text(
        "$label$extra",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun RiskFooter(riskRules: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("리스크 원칙", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            riskRules.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF616E7C)) }
        }
    }
}

private fun fmt(v: Double): String = "${"%,d".format(v.roundToLong())}원"
private fun fmtCurrency(v: Double, currencyCode: String): String {
    return when (currencyCode.uppercase(Locale.US)) {
        "USD" -> "$" + String.format(Locale.US, "%,.2f", v)
        else -> fmt(v)
    }
}

private fun fmtCompact(v: Double): String {
    val abs = kotlin.math.abs(v)
    return when {
        abs >= 1_000_000_000_000.0 -> String.format("%.1f조", v / 1_000_000_000_000.0)
        abs >= 100_000_000.0 -> String.format("%.1f억", v / 100_000_000.0)
        abs >= 10_000.0 -> String.format("%.1f만", v / 10_000.0)
        else -> String.format("%,.0f", v)
    }
}

internal fun fmtUpdatedAt(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return try {
        val fmt = DateTimeFormatter.ofPattern("yy.MM.dd HH:mm").withZone(ZoneId.of("Asia/Seoul"))
        val instant = runCatching { Instant.parse(raw) }.getOrNull()
        if (instant != null) return fmt.format(instant)
        val odt = runCatching { OffsetDateTime.parse(raw) }.getOrNull()
        if (odt != null) return fmt.format(odt.toInstant())
        val ldt = runCatching { LocalDateTime.parse(raw) }.getOrNull()
        if (ldt != null) return ldt.atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yy.MM.dd HH:mm"))
        null
    } catch (_: Exception) { null }
}
