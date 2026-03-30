package com.example.stock.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import android.graphics.Paint
import android.graphics.Typeface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stock.R
import com.example.stock.ServiceLocator
import com.example.stock.BuildConfig
import com.example.stock.navigation.AppTab
import com.example.stock.data.api.AdminPushSendRequestDto
import com.example.stock.data.api.AdminPushSendResponseDto
import com.example.stock.data.api.AdminPushStatusResponseDto
import com.example.stock.data.api.AutoTradeBrokerCredentialUpdateDto
import com.example.stock.data.api.AutoTradeSettingsDto
import com.example.stock.data.api.AutoTradeSymbolRuleUpsertDto
import com.example.stock.data.api.DaytradeTopItemDto
import com.example.stock.data.api.EodReportDto
import com.example.stock.data.api.FavoriteItemDto
import com.example.stock.data.api.MenuPermissionsDto
import com.example.stock.data.api.PremarketReportDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.api.ChartDailyDto
import com.example.stock.ui.common.ChartRange
import com.example.stock.ui.common.StockChartSheet
import com.example.stock.data.api.ChartPointDto
import com.example.stock.viewmodel.AlertsViewModel
import com.example.stock.viewmodel.AutoTradeViewModel
import com.example.stock.viewmodel.AppViewModelFactory
import com.example.stock.viewmodel.EodViewModel
import com.example.stock.viewmodel.PremarketViewModel
import com.example.stock.viewmodel.PapersViewModel
import com.example.stock.viewmodel.SettingsViewModel
import com.example.stock.viewmodel.UiState
import com.example.stock.data.repository.UiSource
import com.example.stock.ui.common.CommonReportItemUi
import com.example.stock.ui.common.CommonReportList
import com.example.stock.ui.common.GlossaryItem
import com.example.stock.ui.common.GlossaryPresets
import com.example.stock.ui.common.MetricUi
import com.example.stock.ui.common.SortOptions
import com.example.stock.ui.common.AppTopBar
import com.example.stock.ui.common.resolveAiSignalSortScore
import com.example.stock.ui.common.rememberFavoritesController
import com.example.stock.util.isRemoteBuildNewer
import com.example.stock.util.toShortBuildLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import kotlin.math.min
import kotlin.math.max
import retrofit2.HttpException

private enum class SortMode(val label: String) {
    DEFAULT("기본"),
    PRICE_ASC("가격↑"),
    PRICE_DESC("가격↓"),
}

private const val ADVANCED_SECTION_AUTO_BROKER = "AUTO_BROKER"
private const val ADVANCED_SECTION_DISPLAY_NEWS = "DISPLAY_NEWS"
private const val ADVANCED_SECTION_SECURITY_TABS = "SECURITY_TABS"
private const val ADVANCED_SECTION_STRATEGY = "STRATEGY"
private const val ADVANCED_SECTION_ADMIN = "ADMIN"

private data class AdminPushRouteOption(
    val key: String,
    val label: String,
    val route: String?,
)

private val ADMIN_PUSH_ROUTE_OPTIONS = listOf(
    AdminPushRouteOption(key = "none", label = "이동 없음", route = null),
    AdminPushRouteOption(key = "settings", label = "설정", route = "settings"),
    AdminPushRouteOption(key = "premarket", label = "단타", route = "premarket"),
    AdminPushRouteOption(key = "autotrade", label = "자동", route = "autotrade"),
    AdminPushRouteOption(key = "holdings", label = "보유", route = "holdings"),
    AdminPushRouteOption(key = "supply", label = "수급", route = "supply"),
    AdminPushRouteOption(key = "movers", label = "급등", route = "movers"),
    AdminPushRouteOption(key = "us", label = "미장", route = "us"),
    AdminPushRouteOption(key = "news", label = "뉴스", route = "news"),
    AdminPushRouteOption(key = "longterm", label = "장투", route = "longterm"),
    AdminPushRouteOption(key = "papers", label = "논문", route = "papers"),
    AdminPushRouteOption(key = "eod", label = "관심", route = "eod"),
    AdminPushRouteOption(key = "alerts", label = "알림", route = "alerts"),
)

private fun isTransientNetworkError(error: Throwable?): Boolean =
    error is SocketTimeoutException || error is IOException

private fun toFriendlyNetworkMessage(error: Throwable?, fallback: String): String {
    val detail = extractHttpDetailCode(error)?.uppercase()
    if (detail == "MENU_FORBIDDEN") {
        return "관리자가 이 계정의 메뉴 접근을 제한했습니다."
    }
    return when (error) {
        is SocketTimeoutException -> "서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해주세요."
        is IOException -> "네트워크 연결이 불안정합니다. 잠시 후 다시 시도해주세요."
        else -> error?.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}

private fun extractHttpDetailCode(error: Throwable?): String? {
    val http = error as? HttpException ?: return null
    val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull().orEmpty()
    val match = Regex("\"detail\"\\s*:\\s*\"([^\"]+)\"").find(body) ?: return null
    return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
}

private fun toFriendlyAdminPushMessage(error: Throwable?): String {
    val detail = extractHttpDetailCode(error)?.uppercase()
    return when (detail) {
        "FORBIDDEN" -> "MASTER 계정에서만 푸시를 보낼 수 있습니다."
        "TITLE_REQUIRED" -> "제목을 입력해주세요."
        "TITLE_TOO_LONG" -> "제목은 60자 이하로 입력해주세요."
        "BODY_REQUIRED" -> "본문을 입력해주세요."
        "BODY_TOO_LONG" -> "본문은 300자 이하로 입력해주세요."
        "INVALID_TARGET" -> "대상 선택이 올바르지 않습니다."
        "INVALID_ALERT_TYPE" -> "알림 종류 선택이 올바르지 않습니다."
        "ROUTE_TOO_LONG" -> "이동 경로는 80자 이하로 입력해주세요."
        "ROUTE_NOT_ALLOWED" -> "허용된 이동 화면만 선택할 수 있습니다."
        "DEVICE_ID_REQUIRED" -> "디바이스 정보가 없어 발송할 수 없습니다."
        else -> toFriendlyNetworkMessage(error, "푸시 발송에 실패했습니다.")
    }
}

private fun toFriendlyAdminPushServerMessage(raw: String?): String {
    val message = raw?.trim().orEmpty()
    if (message.isBlank()) return "요청이 처리되었습니다."
    return when {
        message == "SEND_OK" -> "발송이 완료되었습니다."
        message == "SEND_PARTIAL_FAIL" -> "일부 단말 발송에 실패했습니다."
        message == "SEND_FAILED" -> "발송 대상에 전달하지 못했습니다."
        message == "NO_TARGET_DEVICE" -> "대상 디바이스가 없습니다."
        message == "NO_TARGET_TOKEN" -> "등록된 푸시 토큰이 없어 발송할 수 없습니다."
        message == "FIREBASE_NOT_READY" -> "서버 Firebase 설정이 없어 발송할 수 없습니다."
        message == "DRY_RUN_NO_TARGET_DEVICE" -> "점검 결과: 대상 디바이스가 없습니다."
        message == "DRY_RUN_NO_TARGET_TOKEN" -> "점검 결과: 푸시 토큰이 없어 발송되지 않습니다."
        message.startsWith("DRY_RUN_OK") -> "점검 결과: 발송 가능한 대상이 확인되었습니다."
        message.startsWith("DRY_RUN_FIREBASE_NOT_READY") -> "점검 결과: 서버 Firebase 설정이 필요합니다."
        else -> message
    }
}

private data class DaytradeRealtimeAction(
    val label: String,
    val priority: Int,
)

private data class RankedDaytradeItem(
    val item: DaytradeTopItemDto,
    val quote: RealtimeQuoteItemDto?,
    val tags: List<String>,
    val aiSignalScore: Double?,
    val action: DaytradeRealtimeAction,
)

private fun resolveDaytradeRealtimeAction(
    item: DaytradeTopItemDto,
    quote: RealtimeQuoteItemDto?,
    gateOn: Boolean,
): DaytradeRealtimeAction {
    val price = quote?.price?.takeIf { it > 0.0 } ?: return DaytradeRealtimeAction("시세 확인중", 5)
    val trigger = item.triggerBuy?.takeIf { it > 0.0 }
    val target = item.target1?.takeIf { it > 0.0 }
    val stop = item.stopLoss?.takeIf { it > 0.0 }

    if (stop != null && price <= stop) {
        return DaytradeRealtimeAction("무효(손절 이탈)", 6)
    }
    if (target != null && price >= target) {
        return if (gateOn) {
            DaytradeRealtimeAction("목표 도달(신규진입 주의)", 4)
        } else {
            DaytradeRealtimeAction("조건부 목표권", 4)
        }
    }
    if (trigger != null && price >= trigger) {
        return if (gateOn) {
            DaytradeRealtimeAction("진입 가능", 0)
        } else {
            DaytradeRealtimeAction("조건부 진입", 1)
        }
    }
    if (!gateOn) return DaytradeRealtimeAction("조건부 대기", 3)
    if (trigger != null) return DaytradeRealtimeAction("진입 대기", 2)
    return DaytradeRealtimeAction("조건 확인 필요", 4)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreMarketScreen() {
    val context = LocalContext.current
    val vm: PremarketViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    val state = vm.reportState.value
    val eval = vm.evalState.value.data
    val quotes = vm.quoteState
    val miniCharts = vm.miniChartState
    val repo = ServiceLocator.repository(context)
    val appSettings = repo.getSettings()
    val scope = rememberCoroutineScope()

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
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)
    var refreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        vm.load()
        favorites.refresh()
    }
    val doRefresh = {
        refreshToken += 1
        vm.load(force = true)
        favorites.refresh()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            AppTopBar(title = "제임스분석기", showRefresh = true, onRefresh = { doRefresh() })
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize()) {
            if (state.data != null) {
                PremarketBody(
                    data = state.data,
                    eval = eval,
                    quotes = quotes,
                    miniCharts = miniCharts,
                    updatedAt = state.refreshedAt ?: state.data?.generatedAt,
                    daytradeDisplayCount = appSettings.daytradeDisplayCount,
                    longtermDisplayCount = appSettings.longtermDisplayCount,
                    daySort = SortMode.DEFAULT,
                    onDaySortChange = {},
                    longSort = SortMode.DEFAULT,
                    onLongSortChange = {},
                    onOpenChart = { ticker, name ->
                        chartTitle = name
                        chartTicker = ticker
                        chartQuote = quotes[ticker]
                        chartOpen = true
                        chartRange = ChartRange.D1
                        miniCharts[ticker]?.let { points ->
                            chartData = ChartDailyDto(name = name, points = points)
                            chartLoading = true
                            chartError = null
                        }
                        chartCache[chartRange]?.let { cached ->
                            chartData = cached
                            chartLoading = false
                            chartError = null
                        } ?: run {
                            chartLoading = true
                            chartError = null
                            chartData = null
                            scope.launch {
                                val reqDays = if (chartRange == ChartRange.D1) 2 else chartRange.days
                                repo.getChartDaily(ticker, reqDays)
                                    .onSuccess { data ->
                                        chartData = data
                                        chartCache = chartCache + (chartRange to data)
                                    }
                                    .onFailure { e -> chartError = e.message ?: "차트 데이터를 불러오지 못했습니다." }
                                chartLoading = false
                            }
                        }
                    },
                    source = state.source,
                    refreshToken = refreshToken,
                    refreshLoading = state.loading,
                    onRefresh = doRefresh,
                    snackbarHostState = snackbarHostState,
                    favoriteTickers = favorites.favoriteTickers,
                    onToggleFavorite = { item, desired ->
                        favorites.setFavorite(item = item, sourceTab = "단타", desiredFavorite = desired)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("단타 데이터를 불러오는 중...", color = Color(0xFF64748B))
                    }
                }
            } else {
                if (!state.error.isNullOrBlank()) {
                    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(state.error ?: "데이터를 불러오지 못했습니다.", color = Color.Red)
                        Button(onClick = vm::load, modifier = Modifier.padding(top = 16.dp)) { Text("새로고침") }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text("화면 준비 중입니다...", color = Color(0xFF64748B))
                        }
                    }
                }
            }
        }
    }

    val sheetMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    val sheetMaxHeightPaper = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    val sheetMaxHeightLong = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
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
                        scope.launch {
                            val reqDays = if (next == ChartRange.D1) 2 else next.days
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

@Composable
private fun PremarketBody(
    data: PremarketReportDto,
    eval: com.example.stock.data.api.EvalMonthlyDto?,
    quotes: Map<String, RealtimeQuoteItemDto>,
    miniCharts: Map<String, List<ChartPointDto>>,
    updatedAt: String?,
    daytradeDisplayCount: Int,
    longtermDisplayCount: Int,
    daySort: SortMode,
    onDaySortChange: (SortMode) -> Unit,
    longSort: SortMode,
    onLongSortChange: (SortMode) -> Unit,
    onOpenChart: (String, String) -> Unit,
    source: UiSource,
    refreshToken: Int,
    refreshLoading: Boolean,
    onRefresh: () -> Unit,
    snackbarHostState: SnackbarHostState,
    favoriteTickers: Set<String>,
    onToggleFavorite: (CommonReportItemUi, Boolean) -> Unit,
    showPaperCompare: Boolean = false,
    paperUseVariant7: Boolean = true,
    onPaperToggle: (Boolean) -> Unit = {},
    modifier: Modifier,
    ) {
        var sortId by remember { mutableStateOf(SortOptions.DEFAULT) }
        var query by remember { mutableStateOf("") }
    val topItems = data.daytradeTop.orEmpty()
    val watchItems = data.daytradeWatch.orEmpty()
        // Show the full requested universe: TOP(<=10) + WATCH(<=limit-10).
        // Previous logic only showed TOP when present, which made large display-count settings appear "ignored".
        val dayItems = topItems + watchItems
    var selectedThemeTag by remember { mutableStateOf("") }
    val themeCounts = remember(dayItems) {
        val counts = mutableMapOf<String, Int>()
        for (it in dayItems) {
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
            add(com.example.stock.ui.common.SelectOptionUi("", "전체"))
            themeCounts.forEach { e ->
                add(com.example.stock.ui.common.SelectOptionUi(e.key, "${e.key} (${e.value})"))
            }
        }
    }
    val filteredDayItems = remember(dayItems, selectedThemeTag) {
        if (selectedThemeTag.isBlank()) dayItems else dayItems.filter { it.tags.orEmpty().any { t -> t == selectedThemeTag } }
    }
    val topTickerSet = remember(topItems) {
        topItems.mapNotNull { it.ticker?.trim() }.filter { it.isNotBlank() }.toSet()
    }
    val gateOn = data.daytradeGate?.on == true
    val rankedDayItems = filteredDayItems
        .map { item ->
            val tickerKey = item.ticker?.trim().orEmpty()
            val quote = quotes[tickerKey]
            val tags = item.tags.orEmpty().filter { it.isNotBlank() }
            val aiSignalScore = resolveAiSignalSortScore(
                thesis = item.thesis,
                quote = quote,
                miniPoints = miniCharts[tickerKey],
            )
            val action = resolveDaytradeRealtimeAction(item = item, quote = quote, gateOn = gateOn)
            RankedDaytradeItem(
                item = item,
                quote = quote,
                tags = tags,
                aiSignalScore = aiSignalScore,
                action = action,
            )
        }
        .sortedWith(
            compareBy<RankedDaytradeItem> { it.action.priority }
                .thenBy { if (topTickerSet.contains(it.item.ticker?.trim().orEmpty())) 0 else 1 }
                .thenByDescending { it.aiSignalScore ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.quote?.chgPct ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.item.name ?: "" }
        )

    val readyCount = rankedDayItems.count { it.action.label.startsWith("진입 가능") || it.action.label.startsWith("조건부 진입") }
    val waitingCount = rankedDayItems.count { it.action.label.startsWith("진입 대기") || it.action.label.startsWith("조건부 대기") }
    val invalidCount = rankedDayItems.count { it.action.label.startsWith("무효") }
    val liveStatusSummary = buildString {
        append("실시간 ")
        append(if (gateOn) "게이트 켜짐" else "게이트 꺼짐")
        append(" · 진입 ")
        append(readyCount)
        append(" · 대기 ")
        append(waitingCount)
        if (invalidCount > 0) {
            append(" · 무효 ")
            append(invalidCount)
        }
    }
    val statusMessage = listOfNotNull(
        data.status?.message?.takeIf { it.isNotBlank() },
        liveStatusSummary,
    ).joinToString(" · ")

    val uiItems = rankedDayItems.map { ranked ->
        val item = ranked.item
        val tags = ranked.tags
        val fallbackTheme = item.themeId?.let { "테마 ${it + 1}" }
        val tagLine = when {
            tags.isNotEmpty() -> "테마: " + tags.take(3).joinToString(" · ")
            !fallbackTheme.isNullOrBlank() -> "테마: $fallbackTheme"
            else -> null
        }
        CommonReportItemUi(
            ticker = item.ticker,
            name = item.name,
            market = item.market,
            title = "${item.name} (${item.ticker})",
            quote = ranked.quote,
            miniPoints = miniCharts[item.ticker],
            metrics = listOf(
                MetricUi("진입", item.triggerBuy ?: 0.0),
                MetricUi("목표", item.target1 ?: 0.0),
                MetricUi("손절", item.stopLoss ?: 0.0),
            ),
            extraLines = listOfNotNull(tagLine),
            thesis = item.thesis,
            sortPrice = ranked.quote?.price,
            sortChangePct = ranked.quote?.chgPct,
            sortName = item.name,
            sortAiSignal = ranked.aiSignalScore,
            badgeLabel = "오늘의 포켓",
            displayReturnPct = ranked.quote?.chgPct,
            eventTags = tags.take(5),
        )
    }

        CommonReportList(
            source = source,
            statusMessage = statusMessage,
            updatedAt = updatedAt ?: data.generatedAt,
            header = when {
                !gateOn -> "오늘의 단타 관찰"
                topItems.isNotEmpty() -> "오늘의 단타 추천"
                else -> "오늘의 단타 관찰"
            },
            glossaryDialogTitle = "단타 용어 설명집",
            glossaryItems = GlossaryPresets.DAYTRADE,
            items = uiItems,
            emptyText = if (gateOn) "추천 종목이 없습니다." else "관찰 종목이 없습니다.",
            initialDisplayCount = daytradeDisplayCount,
            refreshToken = refreshToken,
            refreshLoading = refreshLoading,
            onRefresh = onRefresh,
            snackbarHostState = snackbarHostState,
            receivedCount = (data.daytradeTop.orEmpty() + data.daytradeWatch.orEmpty()).size,
            query = query,
            onQueryChange = { query = it },
            onItemClick = { item -> onOpenChart(item.ticker.orEmpty(), item.name.orEmpty()) },
            showRiskRules = true,
            riskRules = data.hardRules.orEmpty(),
            selectedSortId = sortId,
            onSortChange = { sortId = it },
            favoriteTickers = favoriteTickers,
            onToggleFavorite = onToggleFavorite,
            filtersContent = {
                val sortOptions = listOf(
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.DEFAULT, "기본"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.PRICE_ASC, "가격↑"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.PRICE_DESC, "가격↓"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.CHANGE_ASC, "등락↑"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.CHANGE_DESC, "등락↓"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.NAME_ASC, "이름↑"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.NAME_DESC, "이름↓"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_STRONG, "강함"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_BUY, "매수"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_WATCH, "관망"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_CAUTION, "주의"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_AVOID, "회피"),
                )
                com.example.stock.ui.common.CommonSortThemeBar(
                    sortOptions = sortOptions,
                    selectedSortId = sortId,
                    onSortChange = { sortId = it },
                    themeOptions = if (themeCounts.isNotEmpty()) themeOptions else null,
                    selectedThemeId = selectedThemeTag,
                    onThemeChange = { selectedThemeTag = it },
                )
            },
            modifier = modifier,
    )
}

@Composable
private fun ChartSheet(
    title: String,
    ticker: String,
    quote: RealtimeQuoteItemDto?,
    loading: Boolean,
    error: String?,
    data: ChartDailyDto?,
    range: ChartRange,
    onRangeChange: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val context = LocalContext.current
        val points = data?.points.orEmpty()
        val latest = points.lastOrNull()
        val prev = if (points.size >= 2) points[points.size - 2] else null
        val latestClose = latest?.close ?: 0.0
        val baseClose = when {
            range == ChartRange.D1 && prev?.close != null -> prev.close
            points.firstOrNull()?.close != null -> points.firstOrNull()!!.close
            else -> latestClose
        } ?: latestClose
        val chgAbs = latestClose - baseClose
        val chgPct = if (baseClose == 0.0) 0.0 else (chgAbs / baseClose) * 100.0
        val chgColor = if (chgPct >= 0) Color.Red else Color.Blue
        val periodLabel = when (range) {
            ChartRange.D1 -> "전일 대비"
            ChartRange.D7 -> "지난 1주보다"
            ChartRange.M3 -> "지난 3달보다"
            ChartRange.Y1 -> "지난 1년보다"
            ChartRange.Y5 -> "지난 5년보다"
            ChartRange.ALL -> "전체기간 대비"
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = title.ifBlank { ticker }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                openNaverStockPage(context, ticker)
            }) { Text("네이버 가격보기") }
        }
        if (latestClose > 0) {
            if (quote != null && (quote.price ?: 0.0) > 0 && (quote.prevClose ?: 0.0) > 0) {
                val qPrice = quote.price ?: 0.0
                val qPrev = quote.prevClose ?: 0.0
                val qChg = ((qPrice / qPrev) - 1.0) * 100.0
                Text(
                    text = fmt(qPrice),
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "전일 대비 ${if (qPrice - qPrev >= 0) "+" else ""}${fmt(kotlin.math.abs(qPrice - qPrev))} (${if (qChg >= 0) "+" else ""}${"%.1f".format(qChg)}%)",
                    color = if (qChg >= 0) Color.Red else Color.Blue,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(
                    text = fmt(latestClose),
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "$periodLabel ${if (chgAbs >= 0) "+" else ""}${fmt(kotlin.math.abs(chgAbs))} (${if (chgPct >= 0) "+" else ""}${"%.1f".format(chgPct)}%)",
                    color = chgColor,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("차트 로딩 중...", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
            }
            return
        }
        if (error != null) {
            Text(error, color = Color.Red, modifier = Modifier.padding(top = 12.dp))
            return
        }
        if (points.isEmpty()) {
            Text("차트 데이터가 없습니다.", modifier = Modifier.padding(top = 12.dp))
            return
        }
        LineChart(
            points = points,
            baseline = if (range == ChartRange.D1) baseClose else null,
            modifier = Modifier.fillMaxWidth().height(210.dp).padding(top = 12.dp)
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChartRange.entries.forEach { opt ->
                if (range == opt) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFFE5E7EB), shape = RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text(opt.label, color = Color(0xFF111827), fontWeight = FontWeight.Bold) }
                } else {
                    Text(
                        opt.label,
                        color = Color(0xFF9CA3AF),
                        modifier = Modifier.clickable { onRangeChange(opt) }.padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

private fun openNaverStockPage(context: android.content.Context, rawTicker: String) {
    val digits = rawTicker.filter(Char::isDigit)
    val ticker = when {
        digits.length >= 6 -> digits.takeLast(6)
        digits.isNotBlank() -> digits.padStart(6, '0')
        else -> ""
    }
    if (ticker.length != 6) {
        Toast.makeText(context, "유효한 종목코드가 없습니다.", Toast.LENGTH_SHORT).show()
        return
    }
    val urls = listOf(
        "https://m.stock.naver.com/domestic/stock/$ticker/total",
        "https://finance.naver.com/item/main.naver?code=$ticker",
    )
    for (url in urls) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
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
private fun LineChart(points: List<ChartPointDto>, baseline: Double? = null, modifier: Modifier = Modifier) {
    val clean = points.filter { (it.close ?: 0.0) > 0.0 }
    if (clean.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("차트 데이터가 없습니다.") }
        return
    }
    val closes = clean.mapNotNull { it.close }
    val minV = closes.minOrNull() ?: 0.0
    val maxV = closes.maxOrNull() ?: 0.0
    val range = (maxV - minV).takeIf { it > 0.0 } ?: 1.0

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val count = clean.size
        val step = if (count <= 1) w else w / (count - 1)
        val path = Path()
        clean.forEachIndexed { i, p ->
            val v = p.close ?: 0.0
            val x = step * i
            val y = h - (((v - minV) / range).toFloat() * h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Color(0xFFE53935), style = Stroke(width = 3f))
        if (baseline != null) {
            val baseY = h - (((baseline - minV) / range).toFloat() * h)
            drawLine(
                color = Color(0xFF6B7280),
                start = Offset(0f, baseY),
                end = Offset(w, baseY),
                strokeWidth = 2.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            )
            val paint = Paint().apply {
                color = 0xFF6B7280.toInt()
                textSize = 22f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText("기준", 6f, baseY - 6f, paint)
        }

        val paintAxis = Paint().apply {
            color = 0xFFE53935.toInt()
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText("최고 ${fmt(maxV)}", w - paintAxis.measureText("최고 ${fmt(maxV)}") - 8f, 24f, paintAxis)
        drawContext.canvas.nativeCanvas.drawText("최저 ${fmt(minV)}", w - paintAxis.measureText("최저 ${fmt(minV)}") - 8f, h - 6f, paintAxis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FavoritesScreen() {
    val context = LocalContext.current
    val repo = ServiceLocator.repository(context)
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)
    var sortId by remember { mutableStateOf(SortOptions.DEFAULT) }
    var query by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf(0) }
    val doRefresh = {
        refreshToken += 1
        favorites.refresh()
    }

    val itemsAll = favorites.favoritesByTicker.values.sortedByDescending { it.favoritedAt.orEmpty() }
    val uiItems = itemsAll.map { fav ->
        val ticker = fav.ticker.orEmpty()
        val displayName = fav.name?.takeIf { it.isNotBlank() } ?: ticker
        val baseline = fav.baselinePrice ?: 0.0
        val current = fav.currentPrice ?: baseline
        val perfPct = fav.changeSinceFavoritePct
        val heldDays = holdingDays(fav.favoritedAt)
        val trackingDuration = favoriteDurationLabel(fav.favoritedAt)
        val registeredAt = favoriteTimeLabel(fav.favoritedAt)
        val pnlAmount = if (baseline > 0.0 && current > 0.0) current - baseline else null
        val quote = if (current > 0.0 && baseline > 0.0) {
            RealtimeQuoteItemDto(
                ticker = ticker,
                price = current,
                prevClose = baseline,
                chgPct = perfPct ?: 0.0,
                asOf = fav.asOf,
                source = fav.source,
                isLive = fav.isLive,
            )
        } else null
        CommonReportItemUi(
            ticker = ticker,
            name = displayName,
            title = "$displayName ($ticker)",
            quote = quote,
            fallbackPrice = current,
            fallbackChangePct = perfPct,
            fallbackLabel = "관심 이후",
            metrics = listOf(
                MetricUi("기준가", baseline),
                MetricUi("현재가", current),
                MetricUi("관심일", heldDays.toDouble()),
            ),
            extraLines = listOfNotNull(
                "등록 ${registeredAt}${fav.sourceTab?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}",
                perfPct?.let { "성과 ${if (it >= 0) "+" else ""}${"%.2f".format(it)}%" },
            ),
            sortPrice = current,
            sortChangePct = perfPct,
            sortName = displayName,
            badgeLabel = fav.sourceTab?.takeIf { it.isNotBlank() } ?: "관심",
            displayReturnPct = perfPct,
            cardVariant = "favorite_tracking",
            interestDays = heldDays,
            trackingBaselinePrice = if (baseline > 0.0) baseline else null,
            trackingPnlAmount = pnlAmount,
            trackingStartedAtLabel = registeredAt.takeIf { it != "-" },
            trackingDurationLabel = trackingDuration + (fav.sourceTab?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = "관심 종목", showRefresh = true, onRefresh = { doRefresh() }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        CommonReportList(
            source = UiSource.LIVE,
            statusMessage = "하트로 저장한 종목의 기준가 대비 성과를 표시합니다.",
            updatedAt = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).toString(),
            header = "내 관심 목록",
            items = uiItems,
            emptyText = "관심 종목이 없습니다. 종목 카드의 하트를 눌러 추가하세요.",
            initialDisplayCount = 30,
            refreshToken = refreshToken,
            refreshLoading = false,
            onRefresh = { doRefresh() },
            snackbarHostState = snackbarHostState,
            receivedCount = uiItems.size,
            query = query,
            onQueryChange = { query = it },
            selectedSortId = sortId,
            onSortChange = { sortId = it },
            favoriteTickers = favorites.favoriteTickers,
            onToggleFavorite = { item, desired ->
                favorites.setFavorite(item = item, sourceTab = "관심", desiredFavorite = desired)
            },
            modifier = Modifier.padding(inner).fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PapersScreen() {
    val context = LocalContext.current
    val vm: PapersViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    LaunchedEffect(Unit) { vm.load() }
    val report = vm.reportState.value
    val quotes = vm.quoteState
    val miniCharts = vm.miniChartState
    val repo = ServiceLocator.repository(context)
    val scope = rememberCoroutineScope()
    var sortId by remember { mutableStateOf(SortOptions.DEFAULT) }
    var query by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)
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
    val doRefresh = {
        refreshToken += 1
        vm.load(force = true)
        favorites.refresh()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = "논문 분석", showRefresh = true, onRefresh = { doRefresh() }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        var selectedThemeTag by remember { mutableStateOf("") }
        val primaryItems = report.data?.daytradePrimary.orEmpty().ifEmpty { report.data?.daytradeTop.orEmpty() }
        val watchItems = report.data?.daytradeWatch.orEmpty()
        val itemsAll = primaryItems + watchItems
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
                add(com.example.stock.ui.common.SelectOptionUi("", "전체"))
                themeCounts.forEach { e -> add(com.example.stock.ui.common.SelectOptionUi(e.key, "${e.key} (${e.value})")) }
            }
        }
        val items = remember(itemsAll, selectedThemeTag) {
            if (selectedThemeTag.isBlank()) itemsAll else itemsAll.filter { it.tags.orEmpty().any { t -> t == selectedThemeTag } }
        }

        val uiItems = items.map { item ->
                            val tags = item.tags.orEmpty().filter { it.isNotBlank() }
                            val fallbackTheme = item.themeId?.let { "테마 ${it + 1}" }
                            val tagLine = when {
                                tags.isNotEmpty() -> "테마: " + tags.take(3).joinToString(" · ")
                                !fallbackTheme.isNullOrBlank() -> "테마: $fallbackTheme"
                                else -> null
                            }
                            val quote = quotes[item.ticker]
                            val aiSignalScore = resolveAiSignalSortScore(
                                thesis = item.thesis,
                                quote = quote,
                                miniPoints = miniCharts[item.ticker],
                            )
                            CommonReportItemUi(
                                ticker = item.ticker,
                                name = item.name,
                                market = item.market,
                                title = "${item.name} (${item.ticker})",
                                quote = quote,
                                miniPoints = miniCharts[item.ticker],
                                metrics = listOf(
                                    MetricUi("진입", item.triggerBuy ?: 0.0),
                                    MetricUi("목표", item.target1 ?: 0.0),
                                    MetricUi("손절", item.stopLoss ?: 0.0),
                                ),
                                extraLines = listOfNotNull(tagLine),
                                thesis = item.thesis,
                                sortPrice = quotes[item.ticker]?.price,
                                sortChangePct = quotes[item.ticker]?.chgPct,
                                sortName = item.name,
                                sortAiSignal = aiSignalScore,
                                badgeLabel = "논문 포켓",
                                displayReturnPct = quotes[item.ticker]?.chgPct,
                                eventTags = tags.take(5),
            )
        }
        CommonReportList(
            source = report.source,
            statusMessage = report.data?.status?.message,
            updatedAt = report.refreshedAt ?: report.data?.generatedAt,
            header = "오늘의 논문 추천",
            glossaryDialogTitle = "논문 용어 설명집",
            glossaryItems = GlossaryPresets.PAPERS,
            items = uiItems,
            emptyText = "추천 종목이 없습니다.",
            initialDisplayCount = 10,
            refreshToken = refreshToken,
            refreshLoading = report.loading,
            onRefresh = doRefresh,
            snackbarHostState = snackbarHostState,
            receivedCount = items.size,
            query = query,
            onQueryChange = { query = it },
            selectedSortId = sortId,
            onSortChange = { sortId = it },
            favoriteTickers = favorites.favoriteTickers,
            onToggleFavorite = { item, desired ->
                favorites.setFavorite(item = item, sourceTab = "논문", desiredFavorite = desired)
            },
            filtersContent = {
                val sortOptions = listOf(
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.DEFAULT, "기본"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.PRICE_ASC, "가격↑"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.PRICE_DESC, "가격↓"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.CHANGE_ASC, "등락↑"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.CHANGE_DESC, "등락↓"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.NAME_ASC, "이름↑"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.NAME_DESC, "이름↓"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_STRONG, "강함"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_BUY, "매수"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_WATCH, "관망"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_CAUTION, "주의"),
                    com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_AVOID, "회피"),
                )
                com.example.stock.ui.common.CommonSortThemeBar(
                    sortOptions = sortOptions,
                    selectedSortId = sortId,
                    onSortChange = { sortId = it },
                    themeOptions = if (themeCounts.isNotEmpty()) themeOptions else null,
                    selectedThemeId = selectedThemeTag,
                    onThemeChange = { selectedThemeTag = it },
                )
            },
            onItemClick = { item ->
                val ticker = item.ticker.orEmpty()
                chartTitle = item.name ?: ticker
                chartTicker = ticker
                chartQuote = quotes[ticker]
                if (chartQuote == null) {
                    scope.launch {
                        repo.getRealtimeQuotes(listOf(ticker)).onSuccess { map ->
                            chartQuote = map[ticker]
                        }
                    }
                }
                chartOpen = true
                chartRange = ChartRange.D1
                miniCharts[ticker]?.let { points ->
                    chartData = ChartDailyDto(name = chartTitle, points = points)
                    chartLoading = true
                    chartError = null
                }
                chartCache[chartRange]?.let { cached ->
                    chartData = cached
                    chartLoading = false
                    chartError = null
                } ?: run {
                    chartLoading = true
                    chartError = null
                    chartData = null
                    scope.launch {
                        val reqDays = if (chartRange == ChartRange.D1) 2 else chartRange.days
                        repo.getChartDaily(ticker, reqDays)
                            .onSuccess { data ->
                                chartData = data
                                chartCache = chartCache + (chartRange to data)
                            }
                            .onFailure { e -> chartError = e.message ?: "차트 데이터를 불러오지 못했습니다." }
                        chartLoading = false
                    }
                }
            },
            modifier = Modifier.padding(inner).fillMaxSize(),
        )
    }

    val sheetMaxHeightPaper = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
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
                modifier = Modifier.fillMaxWidth().heightIn(max = sheetMaxHeightPaper).padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AlertsScreen() {
    val context = LocalContext.current
    val vm: AlertsViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    LaunchedEffect(Unit) { vm.load() }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = "알림") },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("알림 내역이 없습니다.")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LongtermScreen() {
    val context = LocalContext.current
    val vm: PremarketViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    LaunchedEffect(Unit) { vm.load() }
    val state = vm.reportState.value
    val quotes = vm.quoteState
    val miniCharts = vm.miniChartState
    val repo = ServiceLocator.repository(context)
    val appSettings = repo.getSettings()
    val scope = rememberCoroutineScope()
    var sortId by remember { mutableStateOf(SortOptions.DEFAULT) }
    var query by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)
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
    val doRefresh = {
        refreshToken += 1
        vm.load(force = true)
        favorites.refresh()
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = "장투", showRefresh = true, onRefresh = { doRefresh() }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            when {
                state.loading && state.data == null -> Loading()
                state.error != null -> ErrorBox(state.error ?: "장투 데이터를 불러오지 못했습니다.") { vm.load() }
                state.data != null -> {
                    var selectedThemeTag by remember { mutableStateOf("") }
                    val itemsAll = state.data?.longterm.orEmpty()
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
                            add(com.example.stock.ui.common.SelectOptionUi("", "전체"))
                            themeCounts.forEach { e -> add(com.example.stock.ui.common.SelectOptionUi(e.key, "${e.key} (${e.value})")) }
                        }
                    }
                    val items = remember(itemsAll, selectedThemeTag) {
                        if (selectedThemeTag.isBlank()) itemsAll else itemsAll.filter { it.tags.orEmpty().any { t -> t == selectedThemeTag } }
                    }
                    LaunchedEffect(items) {
                        vm.ensureMiniCharts(items.map { it.ticker.orEmpty() })
                    }
                        val uiItems = items.map { item ->
                            val q = quotes[item.ticker]
                            val tags = item.tags.orEmpty().filter { it.isNotBlank() }
                        val fallbackTheme = item.themeId?.let { "테마 ${it + 1}" }
                            val tagLine = when {
                                tags.isNotEmpty() -> "테마: " + tags.take(3).joinToString(" · ")
                                !fallbackTheme.isNullOrBlank() -> "테마: $fallbackTheme"
                                else -> null
                            }
                            CommonReportItemUi(
                                ticker = item.ticker,
                                name = item.name,
                            market = item.market,
                            title = "${item.name} (${item.ticker})",
                            quote = q,
                            miniPoints = miniCharts[item.ticker],
                            fallbackPrice = item.d1Close,
                            fallbackChangePct = 0.0,
                            fallbackLabel = "전일 종가",
                            metrics = listOf(
                                MetricUi("진입", item.buyZone?.low ?: 0.0),
                                MetricUi("상단", item.buyZone?.high ?: 0.0),
                                MetricUi("손절", item.stopLoss ?: 0.0),
                            ),
                            extraLines = listOfNotNull(
                                tagLine,
                                "목표: ${fmt(item.target12m ?: 0.0)}",
                            ),
                            thesis = item.thesis,
                            sortPrice = q?.price ?: item.d1Close,
                            sortChangePct = q?.chgPct ?: 0.0,
                            sortName = item.name,
                            sortAiSignal = resolveAiSignalSortScore(
                                thesis = item.thesis,
                                quote = q,
                                miniPoints = miniCharts[item.ticker],
                            ),
                            badgeLabel = "장투 포켓",
                            displayReturnPct = q?.chgPct ?: 0.0,
                            eventTags = tags.take(5),
                        )
                    }
                    CommonReportList(
                        source = state.source,
                        statusMessage = state.data?.status?.message,
                        updatedAt = state.refreshedAt ?: state.data?.generatedAt,
                        header = "오늘의 장투 추천",
                        glossaryDialogTitle = "장투 용어 설명집",
                        glossaryItems = GlossaryPresets.LONGTERM,
                        items = uiItems,
                        emptyText = "장투 후보가 없습니다.",
                        initialDisplayCount = appSettings.longtermDisplayCount,
                        refreshToken = refreshToken,
                        refreshLoading = state.loading,
                        onRefresh = doRefresh,
                        snackbarHostState = snackbarHostState,
                        receivedCount = items.size,
                        query = query,
                        onQueryChange = { query = it },
                        selectedSortId = sortId,
                        onSortChange = { sortId = it },
                        favoriteTickers = favorites.favoriteTickers,
                        onToggleFavorite = { item, desired ->
                            favorites.setFavorite(item = item, sourceTab = "장투", desiredFavorite = desired)
                        },
                        filtersContent = {
                            val sortOptions = listOf(
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.DEFAULT, "기본"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.PRICE_ASC, "가격↑"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.PRICE_DESC, "가격↓"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.CHANGE_ASC, "등락↑"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.CHANGE_DESC, "등락↓"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.NAME_ASC, "이름↑"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.NAME_DESC, "이름↓"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_STRONG, "강함"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_BUY, "매수"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_WATCH, "관망"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_CAUTION, "주의"),
                                com.example.stock.ui.common.SelectOptionUi(com.example.stock.ui.common.SortOptions.AI_AVOID, "회피"),
                            )
                            com.example.stock.ui.common.CommonSortThemeBar(
                                sortOptions = sortOptions,
                                selectedSortId = sortId,
                                onSortChange = { sortId = it },
                                themeOptions = if (themeCounts.isNotEmpty()) themeOptions else null,
                                selectedThemeId = selectedThemeTag,
                                onThemeChange = { selectedThemeTag = it },
                            )
                        },
                        onItemClick = { item ->
                            val ticker = item.ticker.orEmpty()
                            chartTitle = item.name ?: ticker
                            chartTicker = ticker
                            chartQuote = quotes[ticker]
                            if (chartQuote == null) {
                                scope.launch {
                                    repo.getRealtimeQuotes(listOf(ticker)).onSuccess { map ->
                                        chartQuote = map[ticker]
                                    }
                                }
                            }
                            chartOpen = true
                            chartRange = ChartRange.D1
                            miniCharts[ticker]?.let { points ->
                                chartData = ChartDailyDto(name = chartTitle, points = points)
                                chartLoading = true
                                chartError = null
                            }
                            chartCache[chartRange]?.let { cached ->
                                chartData = cached
                                chartLoading = false
                                chartError = null
                            } ?: run {
                                chartLoading = true
                                chartError = null
                                chartData = null
                                scope.launch {
                                    val reqDays = if (chartRange == ChartRange.D1) 2 else chartRange.days
                                    repo.getChartDaily(ticker, reqDays)
                                        .onSuccess { data ->
                                            chartData = data
                                            chartCache = chartCache + (chartRange to data)
                                        }
                                        .onFailure { e -> chartError = e.message ?: "차트 데이터를 불러오지 못했습니다." }
                                    chartLoading = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    val sheetMaxHeightLong = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
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
                modifier = Modifier.fillMaxWidth().heightIn(max = sheetMaxHeightLong).padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(
    onAppSettingsSaved: (String) -> Unit = {},
    onOpenAutoTrade: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember(context) { ServiceLocator.repository(context) }
    val vm: SettingsViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    val autoVm: AutoTradeViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    val authRepo = ServiceLocator.authRepository(context)
    val clipboard = LocalClipboardManager.current
    val strategy = vm.strategyState.value
    val autoSettingsState = autoVm.settingsState.value
    val autoSymbolRulesState = autoVm.symbolRulesState.value
    val autoBrokerState = autoVm.brokerState.value
    LaunchedEffect(Unit) { vm.loadStrategySettings() }
    LaunchedEffect(Unit) {
        autoVm.loadSettings()
        autoVm.loadSymbolRules()
        autoVm.loadBroker()
    }
    val scroll = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var daytradeCount by remember { mutableStateOf("") }
    var longtermCount by remember { mutableStateOf("") }
    var quoteRefreshSec by remember { mutableStateOf("") }
    var cardUiVersion by remember { mutableStateOf("V2") }
    var bottomTabOrder by remember { mutableStateOf(AppTab.entries.toList()) }
    var newsDefaultWindow by remember { mutableStateOf("24h") }
    var newsDefaultMode by remember { mutableStateOf("HOT") }
    var newsDefaultSource by remember { mutableStateOf("ALL") }
    var newsDefaultHideRisk by remember { mutableStateOf(false) }
    var newsRestoreLastFilters by remember { mutableStateOf(true) }
    var newsArticleTextSizeSp by remember { mutableStateOf("15") }
    var biometricEnabled by remember { mutableStateOf(authRepo.isBiometricEnabled()) }
    var autoEnabled by remember { mutableStateOf(false) }
    var autoEnvironment by remember { mutableStateOf("demo") }
    var autoIncludeDaytrade by remember { mutableStateOf(true) }
    var autoIncludeMovers by remember { mutableStateOf(true) }
    var autoIncludeSupply by remember { mutableStateOf(true) }
    var autoIncludePapers by remember { mutableStateOf(true) }
    var autoIncludeLongterm by remember { mutableStateOf(true) }
    var autoIncludeFavorites by remember { mutableStateOf(true) }
    var autoBudgetText by remember { mutableStateOf("200000") }
    var autoMaxOrdersText by remember { mutableStateOf("5") }
    var autoMaxLossText by remember { mutableStateOf("3.0") }
    var autoSeedText by remember { mutableStateOf("10000000") }
    var autoTakeProfitText by remember { mutableStateOf("7.0") }
    var autoStopLossText by remember { mutableStateOf("5.0") }
    var autoStoplossReentryPolicy by remember { mutableStateOf("cooldown") }
    var autoStoplossReentryCooldownMinText by remember { mutableStateOf("30") }
    var autoTakeprofitReentryPolicy by remember { mutableStateOf("cooldown") }
    var autoTakeprofitReentryCooldownMinText by remember { mutableStateOf("30") }
    var autoOffhoursReservationEnabled by remember { mutableStateOf(true) }
    var autoOffhoursReservationMode by remember { mutableStateOf("auto") }
    var autoOffhoursConfirmTimeoutMinText by remember { mutableStateOf("3") }
    var autoOffhoursConfirmTimeoutAction by remember { mutableStateOf("cancel") }
    var autoRuleTickerText by remember { mutableStateOf("") }
    var autoRuleNameText by remember { mutableStateOf("") }
    var autoRuleTakeProfitText by remember { mutableStateOf("7.0") }
    var autoRuleStopLossText by remember { mutableStateOf("5.0") }
    var autoRuleEnabled by remember { mutableStateOf(true) }
    var autoRuleEditingTicker by remember { mutableStateOf<String?>(null) }
    var autoUseUserCredentials by remember { mutableStateOf(false) }
    var autoDemoAppKeyText by remember { mutableStateOf("") }
    var autoDemoAppSecretText by remember { mutableStateOf("") }
    var autoProdAppKeyText by remember { mutableStateOf("") }
    var autoProdAppSecretText by remember { mutableStateOf("") }
    var autoAccountNoDemoText by remember { mutableStateOf("") }
    var autoAccountNoProdText by remember { mutableStateOf("") }
    var autoAccountProductCodeDemoText by remember { mutableStateOf("01") }
    var autoAccountProductCodeProdText by remember { mutableStateOf("01") }
    var showAutoBrokerSettings by remember { mutableStateOf(false) }
    var showDisplayAndNewsSettings by remember { mutableStateOf(false) }
    var showSecurityAndTabsSettings by remember { mutableStateOf(false) }
    var showStrategyTuning by remember { mutableStateOf(false) }
    var advancedInitDone by remember { mutableStateOf(false) }
    var serverLatestLabel by remember { mutableStateOf<String?>(null) }
    var serverLatestCode by remember { mutableStateOf<Int?>(null) }
    var serverLatestLoading by remember { mutableStateOf(true) }
    var serverLatestError by remember { mutableStateOf<String?>(null) }
    var settingsGlossaryOpen by remember { mutableStateOf(false) }

    // Auth state (for MASTER-only admin panel in Settings)
    var me by remember { mutableStateOf<com.example.stock.data.api.UserDetailDto?>(null) }
    var meLoading by remember { mutableStateOf(true) }
    var meError by remember { mutableStateOf<String?>(null) }
    var myMenuPermissions by remember { mutableStateOf(MenuPermissionsDto()) }
    var myMenuPermissionsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        meLoading = true
        meError = null
        for (attempt in 0 until 3) {
            val result = authRepo.getMe()
            if (result.isSuccess) {
                me = result.getOrNull()
                meError = null
                break
            }
            val error = result.exceptionOrNull()
            if (attempt < 2 && isTransientNetworkError(error)) {
                delay((attempt + 1) * 700L)
                continue
            }
            me = null
            meError = toFriendlyNetworkMessage(error, "관리자 정보 확인이 지연되고 있습니다.")
            break
        }
        meLoading = false
    }
    LaunchedEffect(Unit) {
        authRepo.getMyMenuPermissions()
            .onSuccess { res ->
                myMenuPermissions = res.permissions ?: MenuPermissionsDto()
            }
            .onFailure {
                myMenuPermissions = MenuPermissionsDto()
            }
        myMenuPermissionsLoaded = true
    }
    LaunchedEffect(Unit) {
        serverLatestLoading = true
        serverLatestError = null
        repo.getLatestApkInfo()
            .onSuccess { info ->
                serverLatestLabel = info.buildLabel
                serverLatestCode = info.versionCode
            }
            .onFailure { e ->
                serverLatestError = e.message ?: "최신 버전 확인 실패"
            }
        serverLatestLoading = false
    }
    // Admin UI gating policy:
    // - server role confirmed: trust server role
    // - while /auth/me loading: allow cached master role to prevent initial flicker
    // - after /auth/me failed/finished without MASTER: hide admin menu
    val cachedRole = (authRepo.getRole() ?: "").uppercase()
    val serverRole = (me?.role ?: "").uppercase()
    val isMaster = when {
        serverRole.isNotBlank() -> serverRole == "MASTER"
        meLoading -> cachedRole == "MASTER"
        else -> false
    }
    val myAutotradeMenuAllowed = myMenuPermissions.menuAutotrade != false

    // Admin: invite + user list
    var inviteUserCode by remember { mutableStateOf("") }
    var inviteName by remember { mutableStateOf("") }
    var invitePasswordMode by remember { mutableStateOf("AUTO") } // AUTO|MANUAL
    var inviteManualPassword by remember { mutableStateOf("") }
    var inviteRole by remember { mutableStateOf("USER") } // USER|MASTER
    var inviteExpiresDays by remember { mutableStateOf("7") }
    var inviteMemo by remember { mutableStateOf("") }
    var inviteDeviceBinding by remember { mutableStateOf(false) }
    var inviteLoading by remember { mutableStateOf(false) }
    var inviteError by remember { mutableStateOf<String?>(null) }
    var lastInvite by remember { mutableStateOf<com.example.stock.data.api.InviteCreateResponseDto?>(null) }

    var usersLoading by remember { mutableStateOf(false) }
    var usersError by remember { mutableStateOf<String?>(null) }
    var usersTotal by remember { mutableStateOf(0) }
    var users by remember { mutableStateOf<List<com.example.stock.data.api.UserSummaryDto>>(emptyList()) }
    var usersListExpanded by rememberSaveable { mutableStateOf(false) }
    var invitedUsersLoading by remember { mutableStateOf(false) }
    var invitedUsersError by remember { mutableStateOf<String?>(null) }
    var invitedUsersTotal by remember { mutableStateOf(0) }
    var invitedUsers by remember { mutableStateOf<List<com.example.stock.data.api.InvitedUserSummaryDto>>(emptyList()) }
    var invitedUsersExpanded by rememberSaveable { mutableStateOf(false) }
    var lastReset by remember { mutableStateOf<com.example.stock.data.api.InviteCreateResponseDto?>(null) }
    var selectedUserCode by remember { mutableStateOf<String?>(null) }
    var adminUserQuery by remember { mutableStateOf("") }
    var selectedDetailTab by remember { mutableStateOf("요약") } // 요약|접속로그|수익/종목
    var selectedUserDetailLoading by remember { mutableStateOf(false) }
    var selectedUserDetailError by remember { mutableStateOf<String?>(null) }
    var selectedUserLoginLogs by remember { mutableStateOf<com.example.stock.data.api.UserLoginLogsResponseDto?>(null) }
    var selectedUserAutoOverview by remember { mutableStateOf<com.example.stock.data.api.AdminUserAutoTradeOverviewDto?>(null) }
    var selectedUserMenuInheritedDefault by remember { mutableStateOf(true) }
    var selectedUserMenuPermissions by remember { mutableStateOf(MenuPermissionsDto()) }
    var selectedMenuError by remember { mutableStateOf<String?>(null) }
    var menuEditorVisible by remember { mutableStateOf(false) }
    var menuEditorUserCode by remember { mutableStateOf("") }
    var menuEditorUserName by remember { mutableStateOf("") }
    var menuEditorInheritedDefault by remember { mutableStateOf(true) }
    var menuEditorPermissions by remember { mutableStateOf(MenuPermissionsDto()) }
    var menuEditorLoading by remember { mutableStateOf(false) }
    var menuEditorSaving by remember { mutableStateOf(false) }
    var menuEditorError by remember { mutableStateOf<String?>(null) }
    var identityCode by remember { mutableStateOf("") }
    var identityName by remember { mutableStateOf("") }
    var identityMemo by remember { mutableStateOf("") }
    var identityPhone by remember { mutableStateOf("") }
    var identitySaving by remember { mutableStateOf(false) }
    var identityError by remember { mutableStateOf<String?>(null) }
    var adminPushTitle by remember { mutableStateOf("") }
    var adminPushBody by remember { mutableStateOf("") }
    var adminPushTarget by remember { mutableStateOf("test") } // all|active_7d|test
    var adminPushAlertType by remember { mutableStateOf("ADMIN") } // ADMIN|UPDATE
    var adminPushRouteKey by remember { mutableStateOf("settings") }
    var adminPushDryRun by remember { mutableStateOf(true) }
    var adminPushLoading by remember { mutableStateOf(false) }
    var adminPushError by remember { mutableStateOf<String?>(null) }
    var adminPushResult by remember { mutableStateOf<AdminPushSendResponseDto?>(null) }
    var adminPushStatusLoading by remember { mutableStateOf(false) }
    var adminPushStatusError by remember { mutableStateOf<String?>(null) }
    var adminPushStatus by remember { mutableStateOf<AdminPushStatusResponseDto?>(null) }

    fun loadUsers() {
        usersLoading = true
        usersError = null
        scope.launch {
            authRepo.adminListUsers(page = 1, size = 50)
                .onSuccess { res ->
                    users = res.items ?: emptyList()
                    usersTotal = res.total ?: 0
                }
                .onFailure { e -> usersError = e.message ?: "유저 목록 로드 실패" }
            usersLoading = false
        }
    }
    fun loadInvitedUsers() {
        invitedUsersLoading = true
        invitedUsersError = null
        scope.launch {
            authRepo.adminListMyInvitedUsers(page = 1, size = 50)
                .onSuccess { res ->
                    invitedUsers = res.items ?: emptyList()
                    invitedUsersTotal = res.total ?: 0
                }
                .onFailure { e -> invitedUsersError = e.message ?: "초대 사용자 목록 로드 실패" }
            invitedUsersLoading = false
        }
    }
    fun loadSelectedUserDetail(code: String) {
        selectedUserCode = code
        selectedDetailTab = "요약"
        selectedUserDetailLoading = true
        selectedUserDetailError = null
        identityError = null
        selectedMenuError = null
        selectedUserMenuInheritedDefault = true
        selectedUserMenuPermissions = MenuPermissionsDto()
        scope.launch {
            val logsRes = authRepo.adminGetUserLoginLogs(userCode = code, page = 1, size = 30)
            val autoRes = authRepo.adminGetUserAutotradeOverview(userCode = code, days = 30, orderSize = 30)
            val menuRes = authRepo.adminGetUserMenuPermissions(userCode = code)
            selectedUserLoginLogs = logsRes.getOrNull()
            selectedUserAutoOverview = autoRes.getOrNull()
            val menuPermissions = menuRes.getOrNull()
            selectedUserMenuInheritedDefault = menuPermissions?.inheritedDefault == true
            selectedUserMenuPermissions = menuPermissions?.permissions ?: MenuPermissionsDto()
            if (menuRes.isFailure) {
                selectedMenuError = toFriendlyNetworkMessage(menuRes.exceptionOrNull(), "메뉴 권한 정보를 불러오지 못했습니다.")
            }
            val selectedDetail = selectedUserAutoOverview?.user
            val selectedSummary = selectedUserLoginLogs?.user
            identityCode = selectedDetail?.userCode ?: selectedSummary?.userCode ?: code
            identityName = selectedDetail?.name ?: selectedSummary?.name.orEmpty()
            identityMemo = selectedDetail?.memo.orEmpty()
            identityPhone = selectedDetail?.phone ?: selectedSummary?.phone.orEmpty()
            if (logsRes.isFailure && autoRes.isFailure && menuRes.isFailure) {
                selectedUserDetailError =
                    toFriendlyNetworkMessage(
                        logsRes.exceptionOrNull() ?: autoRes.exceptionOrNull() ?: menuRes.exceptionOrNull(),
                        "사용자 상세 로드 실패",
                    )
            }
            selectedUserDetailLoading = false
        }
    }

    fun openMenuEditor(userCode: String, userName: String) {
        menuEditorVisible = true
        menuEditorUserCode = userCode
        menuEditorUserName = userName
        menuEditorInheritedDefault = true
        menuEditorPermissions = MenuPermissionsDto()
        menuEditorError = null
        menuEditorLoading = true
        scope.launch {
            authRepo.adminGetUserMenuPermissions(userCode = userCode)
                .onSuccess { res ->
                    menuEditorInheritedDefault = res.inheritedDefault == true
                    menuEditorPermissions = res.permissions ?: MenuPermissionsDto()
                }
                .onFailure { e ->
                    menuEditorError = toFriendlyNetworkMessage(e, "메뉴 권한 정보를 불러오지 못했습니다.")
                }
            menuEditorLoading = false
        }
    }

    fun saveMenuEditor() {
        if (menuEditorSaving || menuEditorUserCode.isBlank()) return
        menuEditorSaving = true
        menuEditorError = null
        val targetCode = menuEditorUserCode
        scope.launch {
            authRepo.adminUpdateUserMenuPermissions(
                userCode = targetCode,
                payload = menuEditorPermissions,
            ).onSuccess { res ->
                menuEditorInheritedDefault = res.inheritedDefault == true
                menuEditorPermissions = res.permissions ?: MenuPermissionsDto()
                if (selectedUserCode == targetCode) {
                    selectedUserMenuInheritedDefault = menuEditorInheritedDefault
                    selectedUserMenuPermissions = menuEditorPermissions
                }
                scope.launch { snackbarHostState.showSnackbar("메뉴 권한 저장 완료") }
            }.onFailure { e ->
                menuEditorError = toFriendlyNetworkMessage(e, "메뉴 권한 저장 실패")
            }
            menuEditorSaving = false
        }
    }

    fun loadAdminPushStatus() {
        if (adminPushStatusLoading) return
        adminPushStatusLoading = true
        adminPushStatusError = null
        scope.launch {
            authRepo.adminGetPushStatus()
                .onSuccess { res -> adminPushStatus = res }
                .onFailure { e ->
                    adminPushStatus = null
                    adminPushStatusError = toFriendlyNetworkMessage(e, "푸시 상태를 확인하지 못했습니다.")
                }
            adminPushStatusLoading = false
        }
    }

    fun sendAdminPush(dryRun: Boolean) {
        if (adminPushLoading) return
        if (!dryRun) {
            val status = adminPushStatus
            val pushReady = status?.pushReady == true
            val tokenCount = when (adminPushTarget) {
                "active_7d" -> status?.active7dTokenCount ?: 0
                "all" -> status?.allTokenCount ?: 0
                else -> status?.allTokenCount ?: 0
            }
            if (!pushReady) {
                adminPushError = "즉시 발송 불가: 서버 Firebase 설정이 준비되지 않았습니다."
                return
            }
            if (tokenCount <= 0) {
                adminPushError = "즉시 발송 불가: 선택 대상에 등록된 푸시 토큰이 없습니다."
                return
            }
        }
        val title = adminPushTitle.trim()
        val body = adminPushBody.trim()
        val selectedRouteOption = ADMIN_PUSH_ROUTE_OPTIONS.firstOrNull { it.key == adminPushRouteKey }
            ?: ADMIN_PUSH_ROUTE_OPTIONS.first()
        val route = selectedRouteOption.route
        val validationError = when {
            title.isBlank() -> "제목을 입력해주세요."
            title.length > 60 -> "제목은 60자 이하로 입력해주세요."
            body.isBlank() -> "본문을 입력해주세요."
            body.length > 300 -> "본문은 300자 이하로 입력해주세요."
            else -> null
        }
        if (validationError != null) {
            adminPushError = validationError
            return
        }
        adminPushDryRun = dryRun
        adminPushLoading = true
        adminPushError = null
        scope.launch {
            authRepo.adminSendPush(
                AdminPushSendRequestDto(
                    title = title,
                    body = body,
                    target = adminPushTarget,
                    alertType = adminPushAlertType,
                    route = route,
                    dryRun = dryRun,
                )
            ).onSuccess { res ->
                adminPushResult = res
                if (res.ok == false) {
                    adminPushError = toFriendlyAdminPushServerMessage(res.message)
                }
                loadAdminPushStatus()
                scope.launch {
                    snackbarHostState.showSnackbar(
                        toFriendlyAdminPushServerMessage(
                            res.message ?: if (dryRun) "미리 점검 완료" else "발송 완료"
                        )
                    )
                }
            }.onFailure { e ->
                adminPushResult = null
                adminPushError = toFriendlyAdminPushMessage(e)
            }
            adminPushLoading = false
        }
    }

    LaunchedEffect(isMaster) {
        if (isMaster) {
            loadUsers()
            loadInvitedUsers()
            loadAdminPushStatus()
        }
    }

    var riskPreset by remember { mutableStateOf("") }
    var useCustom by remember { mutableStateOf(false) }
    var wTa by remember { mutableStateOf("") }
    var wRe by remember { mutableStateOf("") }
    var wRs by remember { mutableStateOf("") }
    var themeCap by remember { mutableStateOf("") }
    var maxGapPct by remember { mutableStateOf("") }
    var gateThreshold by remember { mutableStateOf("") }
    var gateQuantile by remember { mutableStateOf("") }

    var lastSettingsHash by remember { mutableStateOf("") }
    LaunchedEffect(strategy.settingsHash) {
        if (lastSettingsHash.isNotBlank() && strategy.settingsHash != lastSettingsHash) {
            scope.launch { snackbarHostState.showSnackbar("전략 튜닝 저장 완료") }
        }
        lastSettingsHash = strategy.settingsHash
    }

    LaunchedEffect(vm.state.value) {
        val s = vm.state.value
        daytradeCount = s.daytradeDisplayCount.toString()
        longtermCount = s.longtermDisplayCount.toString()
        quoteRefreshSec = s.quoteRefreshSec.toString()
        cardUiVersion = s.cardUiVersion
        bottomTabOrder = parseBottomTabOrderCsv(s.bottomTabOrderCsv)
        newsDefaultWindow = s.newsDefaultWindow
        newsDefaultMode = s.newsDefaultMode
        newsDefaultSource = s.newsDefaultSource
        newsDefaultHideRisk = s.newsDefaultHideRisk
        newsRestoreLastFilters = s.newsRestoreLastFilters
        newsArticleTextSizeSp = s.newsArticleTextSizeSp.toString()
    }

    LaunchedEffect(strategy.settingsHash) {
        val s = strategy.settings
        riskPreset = s.riskPreset ?: "ADAPTIVE"
        useCustom = s.useCustomWeights ?: false
        wTa = s.wTa?.toString() ?: ""
        wRe = s.wRe?.toString() ?: ""
        wRs = s.wRs?.toString() ?: ""
        themeCap = s.themeCap?.toString() ?: "2"
        maxGapPct = s.maxGapPct?.toString() ?: "0.0"
        gateThreshold = s.gateThreshold?.toString() ?: "0.0"
        gateQuantile = s.gateQuantile?.toString() ?: ""
    }

    LaunchedEffect(autoSettingsState.data?.updatedAt) {
        val s = autoSettingsState.data?.settings ?: return@LaunchedEffect
        autoEnabled = s.enabled == true
        autoEnvironment = if ((s.environment ?: "demo") == "prod") "prod" else "demo"
        autoIncludeDaytrade = s.includeDaytrade != false
        autoIncludeMovers = s.includeMovers != false
        autoIncludeSupply = s.includeSupply != false
        autoIncludePapers = s.includePapers != false
        autoIncludeLongterm = s.includeLongterm != false
        autoIncludeFavorites = s.includeFavorites != false
        autoBudgetText = ((s.orderBudgetKrw ?: 200000.0).toInt()).toString()
        autoMaxOrdersText = (s.maxOrdersPerRun ?: 5).toString()
        autoMaxLossText = (s.maxDailyLossPct ?: 3.0).toString()
        autoSeedText = ((s.seedKrw ?: 10000000.0).toInt()).toString()
        autoTakeProfitText = (s.takeProfitPct ?: 7.0).toString()
        autoStopLossText = (s.stopLossPct ?: 5.0).toString()
        autoStoplossReentryPolicy = when ((s.stoplossReentryPolicy ?: "cooldown").lowercase()) {
            "immediate" -> "immediate"
            "day_block" -> "day_block"
            "manual_block" -> "manual_block"
            else -> "cooldown"
        }
        autoStoplossReentryCooldownMinText = (s.stoplossReentryCooldownMin ?: 30).toString()
        autoTakeprofitReentryPolicy = when ((s.takeprofitReentryPolicy ?: "cooldown").lowercase()) {
            "immediate" -> "immediate"
            "day_block" -> "day_block"
            "manual_block" -> "manual_block"
            else -> "cooldown"
        }
        autoTakeprofitReentryCooldownMinText = (s.takeprofitReentryCooldownMin ?: 30).toString()
        autoOffhoursReservationEnabled = s.offhoursReservationEnabled != false
        autoOffhoursReservationMode = if ((s.offhoursReservationMode ?: "auto").equals("confirm", ignoreCase = true)) "confirm" else "auto"
        autoOffhoursConfirmTimeoutMinText = (s.offhoursConfirmTimeoutMin ?: 3).toString()
        autoOffhoursConfirmTimeoutAction = if ((s.offhoursConfirmTimeoutAction ?: "cancel").equals("auto", ignoreCase = true)) "auto" else "cancel"
    }

    LaunchedEffect(autoBrokerState.data?.updatedAt) {
        val b = autoBrokerState.data ?: return@LaunchedEffect
        autoUseUserCredentials = b.useUserCredentials == true
        autoAccountProductCodeDemoText = (b.accountProductCodeDemo ?: b.accountProductCode ?: "01").ifBlank { "01" }
        autoAccountProductCodeProdText = (b.accountProductCodeProd ?: b.accountProductCode ?: "01").ifBlank { "01" }
        autoDemoAppKeyText = ""
        autoDemoAppSecretText = ""
        autoProdAppKeyText = ""
        autoProdAppSecretText = ""
        autoAccountNoDemoText = ""
        autoAccountNoProdText = ""
    }

    val riskLabel = when (riskPreset.uppercase()) {
        "DEFENSIVE" -> "방어"
        "ADAPTIVE" -> "균형"
        "AGGRESSIVE" -> "공격"
        else -> riskPreset
    }
    val settingsGlossary = listOf(
        GlossaryItem("실행환경 · 테스트", "증권사 모의투자 API를 사용합니다. 주문 흐름/연동 점검에 사용합니다."),
        GlossaryItem("실행환경 · 실전", "실제 계좌 주문 환경입니다. 활성화 전에 계좌/키 등록 상태를 확인하세요."),
        GlossaryItem("요청/접수/체결/스킵", "요청=검토, 접수=증권사 주문번호 발급, 체결=실제 체결, 스킵=조건/리스크로 주문 생략입니다."),
        GlossaryItem("소스 선택", "체크한 소스(단타/급등/수급/논문/장투/관심)만 자동매매 후보로 사용합니다."),
        GlossaryItem("종목당 예산", "한 종목에 투입할 최대 금액입니다. 너무 높으면 변동성 리스크가 커집니다."),
        GlossaryItem("1회 주문 수", "한 번의 실행에서 허용할 주문 개수 상한입니다."),
        GlossaryItem("일 손실한도(%)", "당일 누적 손실 비율이 기준을 넘으면 자동매매를 중단합니다."),
        GlossaryItem("익절/손절 기준(%)", "포지션별 청산 기준입니다. 익절은 이익 실현, 손절은 손실 제한에 사용합니다."),
        GlossaryItem("재진입 정책(손절/익절)", "손절/익절 직후 재진입을 즉시 허용할지, 재진입 대기시간 또는 당일/수동 차단할지 정하는 안전장치입니다."),
        GlossaryItem("장외 예약", "장시간 외 실행 시 예약 등록 후 장 시작 시 자동 또는 확인 후 실행합니다."),
    )
    val buildLabel = BuildConfig.APP_BUILD_LABEL
    val localShortBuild = toShortBuildLabel(buildLabel, BuildConfig.VERSION_CODE)
    val remoteShortBuild = toShortBuildLabel(serverLatestLabel, serverLatestCode ?: 0)
    val needsAppUpdate = if (serverLatestCode != null) {
        isRemoteBuildNewer(
            localVersionCode = BuildConfig.VERSION_CODE,
            localBuildLabel = buildLabel,
            remoteVersionCode = serverLatestCode ?: 0,
            remoteBuildLabel = serverLatestLabel,
        )
    } else {
        false
    }
    val currentVersionTopText = "현재버전 : $localShortBuild"
    val latestVersionTopText = when {
        serverLatestLoading -> "최신버전 : 확인 중..."
        !serverLatestError.isNullOrBlank() -> "최신버전 : 확인 실패"
        else -> "최신버전 : $remoteShortBuild"
    }
    val reinstallBase = vm.state.value.baseUrl.trim().ifBlank { BuildConfig.DEFAULT_BASE_URL }
    val reinstallInstallPageUrl = reinstallBase.trimEnd('/') + "/apk/install"
    val reinstallDirectUrl = reinstallBase.trimEnd('/') + "/apk/download"
    val autoParsedBudget = autoBudgetText.toDoubleOrNull()
    val autoParsedMaxOrders = autoMaxOrdersText.toIntOrNull()
    val autoParsedMaxLoss = autoMaxLossText.toDoubleOrNull()
    val autoParsedSeed = autoSeedText.toDoubleOrNull()
    val autoParsedTakeProfit = autoTakeProfitText.toDoubleOrNull()
    val autoParsedStopLoss = autoStopLossText.toDoubleOrNull()
    val autoParsedStoplossReentryCooldownMin = autoStoplossReentryCooldownMinText.toIntOrNull()
    val autoParsedTakeprofitReentryCooldownMin = autoTakeprofitReentryCooldownMinText.toIntOrNull()
    val autoParsedOffhoursConfirmTimeoutMin = autoOffhoursConfirmTimeoutMinText.toIntOrNull()
    val autoRuleParsedTakeProfit = autoRuleTakeProfitText.toDoubleOrNull()
    val autoRuleParsedStopLoss = autoRuleStopLossText.toDoubleOrNull()
    val autoRuleTickerTrimmed = autoRuleTickerText.trim()
    val autoRuleTickerValid = autoRuleTickerTrimmed.isNotBlank()
    val autoRuleValid = autoRuleTickerValid &&
        (autoRuleParsedTakeProfit != null && autoRuleParsedTakeProfit in 1.0..30.0) &&
        (autoRuleParsedStopLoss != null && autoRuleParsedStopLoss in 0.5..30.0)
    val autoRuleError = when {
        !autoRuleTickerValid -> "종목코드를 입력하세요. 예: 005930 또는 AAPL"
        autoRuleParsedTakeProfit == null || autoRuleParsedTakeProfit !in 1.0..30.0 -> "개별 익절 기준은 1.0~30.0% 범위여야 합니다."
        autoRuleParsedStopLoss == null || autoRuleParsedStopLoss !in 0.5..30.0 -> "개별 손절 기준은 0.5~30.0% 범위여야 합니다."
        else -> null
    }
    val autoRuleItems = autoSymbolRulesState.data?.items.orEmpty()
    val autoHasAnySource = autoIncludeDaytrade || autoIncludeMovers || autoIncludeSupply || autoIncludePapers || autoIncludeLongterm || autoIncludeFavorites
    val autoSettingsValid = autoHasAnySource &&
        (autoParsedBudget != null && autoParsedBudget >= 10000.0) &&
        (autoParsedMaxOrders != null && autoParsedMaxOrders in 1..100) &&
        (autoParsedMaxLoss != null && autoParsedMaxLoss in 0.1..50.0) &&
        (autoParsedSeed != null && autoParsedSeed in 10000000.0..100000000.0) &&
        (autoParsedTakeProfit != null && autoParsedTakeProfit in 1.0..30.0) &&
        (autoParsedStopLoss != null && autoParsedStopLoss in 0.5..30.0) &&
        (autoStoplossReentryPolicy != "cooldown" || (autoParsedStoplossReentryCooldownMin != null && autoParsedStoplossReentryCooldownMin in 1..1440)) &&
        (autoTakeprofitReentryPolicy != "cooldown" || (autoParsedTakeprofitReentryCooldownMin != null && autoParsedTakeprofitReentryCooldownMin in 1..1440)) &&
        (autoParsedOffhoursConfirmTimeoutMin != null && autoParsedOffhoursConfirmTimeoutMin in 1..30)
    val autoSettingsError = when {
        !autoHasAnySource -> "최소 1개 소스를 선택하세요."
        autoParsedBudget == null || autoParsedBudget < 10000.0 -> "종목당 예산은 10,000원 이상이어야 합니다."
        autoParsedMaxOrders == null || autoParsedMaxOrders !in 1..100 -> "1회 주문 수는 1~100 사이여야 합니다."
        autoParsedMaxLoss == null || autoParsedMaxLoss !in 0.1..50.0 -> "일 손실한도는 0.1~50.0% 범위여야 합니다."
        autoParsedSeed == null || autoParsedSeed !in 10000000.0..100000000.0 -> "총 시드는 10,000,000~100,000,000원 범위여야 합니다."
        autoParsedTakeProfit == null || autoParsedTakeProfit !in 1.0..30.0 -> "익절 기준은 1.0~30.0% 범위여야 합니다."
        autoParsedStopLoss == null || autoParsedStopLoss !in 0.5..30.0 -> "손절 기준은 0.5~30.0% 범위여야 합니다."
        autoStoplossReentryPolicy == "cooldown" && (autoParsedStoplossReentryCooldownMin == null || autoParsedStoplossReentryCooldownMin !in 1..1440) -> "손절 후 재진입 대기시간은 1~1440분 범위여야 합니다."
        autoTakeprofitReentryPolicy == "cooldown" && (autoParsedTakeprofitReentryCooldownMin == null || autoParsedTakeprofitReentryCooldownMin !in 1..1440) -> "익절 후 재진입 대기시간은 1~1440분 범위여야 합니다."
        autoParsedOffhoursConfirmTimeoutMin == null || autoParsedOffhoursConfirmTimeoutMin !in 1..30 -> "예약 확인 대기시간은 1~30분 범위여야 합니다."
        else -> null
    }
    val stoplossReentrySummary = when (autoStoplossReentryPolicy) {
        "immediate" -> "즉시 허용"
        "day_block" -> "당일 차단"
        "manual_block" -> "수동 해제 전 차단"
        else -> "${autoStoplossReentryCooldownMinText.ifBlank { "-" }}분 재진입 대기시간"
    }
    val takeprofitReentrySummary = when (autoTakeprofitReentryPolicy) {
        "immediate" -> "즉시 허용"
        "day_block" -> "당일 차단"
        "manual_block" -> "수동 해제 전 차단"
        else -> "${autoTakeprofitReentryCooldownMinText.ifBlank { "-" }}분 재진입 대기시간"
    }
    val autoSaved = autoSettingsState.data?.settings
    val autoHasUnsavedSettings = autoSaved != null && (
        (autoSaved.enabled == true) != autoEnabled ||
            (if ((autoSaved.environment ?: "demo") == "prod") "prod" else "demo") != autoEnvironment ||
            (autoSaved.includeDaytrade != false) != autoIncludeDaytrade ||
            (autoSaved.includeMovers != false) != autoIncludeMovers ||
            (autoSaved.includeSupply != false) != autoIncludeSupply ||
            (autoSaved.includePapers != false) != autoIncludePapers ||
            (autoSaved.includeLongterm != false) != autoIncludeLongterm ||
            (autoSaved.includeFavorites != false) != autoIncludeFavorites ||
            ((autoSaved.orderBudgetKrw ?: 200000.0).toInt().toString() != autoBudgetText.trim()) ||
            ((autoSaved.maxOrdersPerRun ?: 5).toString() != autoMaxOrdersText.trim()) ||
            ((autoSaved.maxDailyLossPct ?: 3.0).toString() != autoMaxLossText.trim()) ||
            ((autoSaved.seedKrw ?: 10000000.0).toInt().toString() != autoSeedText.trim()) ||
            ((autoSaved.takeProfitPct ?: 7.0).toString() != autoTakeProfitText.trim()) ||
            ((autoSaved.stopLossPct ?: 5.0).toString() != autoStopLossText.trim()) ||
            ((when ((autoSaved.stoplossReentryPolicy ?: "cooldown").lowercase()) {
                "immediate" -> "immediate"
                "day_block" -> "day_block"
                "manual_block" -> "manual_block"
                else -> "cooldown"
            }) != autoStoplossReentryPolicy) ||
            (((autoSaved.stoplossReentryCooldownMin ?: 30).toString()) != autoStoplossReentryCooldownMinText.trim()) ||
            ((when ((autoSaved.takeprofitReentryPolicy ?: "cooldown").lowercase()) {
                "immediate" -> "immediate"
                "day_block" -> "day_block"
                "manual_block" -> "manual_block"
                else -> "cooldown"
            }) != autoTakeprofitReentryPolicy) ||
            (((autoSaved.takeprofitReentryCooldownMin ?: 30).toString()) != autoTakeprofitReentryCooldownMinText.trim()) ||
            ((autoSaved.offhoursReservationEnabled != false) != autoOffhoursReservationEnabled) ||
            ((if ((autoSaved.offhoursReservationMode ?: "auto").equals("confirm", ignoreCase = true)) "confirm" else "auto") != autoOffhoursReservationMode) ||
            (((autoSaved.offhoursConfirmTimeoutMin ?: 3).toString()) != autoOffhoursConfirmTimeoutMinText.trim()) ||
            ((if ((autoSaved.offhoursConfirmTimeoutAction ?: "cancel").equals("auto", ignoreCase = true)) "auto" else "cancel") != autoOffhoursConfirmTimeoutAction)
        )
    val autoBrokerSavedUseUser = autoBrokerState.data?.useUserCredentials == true
    val autoBrokerSavedDemoProductCode = (autoBrokerState.data?.accountProductCodeDemo ?: autoBrokerState.data?.accountProductCode ?: "01").ifBlank { "01" }
    val autoBrokerSavedProdProductCode = (autoBrokerState.data?.accountProductCodeProd ?: autoBrokerState.data?.accountProductCode ?: "01").ifBlank { "01" }
    val autoBrokerDraftChanged = autoUseUserCredentials != autoBrokerSavedUseUser ||
        autoAccountProductCodeDemoText.trim() != autoBrokerSavedDemoProductCode ||
        autoAccountProductCodeProdText.trim() != autoBrokerSavedProdProductCode ||
        autoDemoAppKeyText.isNotBlank() ||
        autoDemoAppSecretText.isNotBlank() ||
        autoProdAppKeyText.isNotBlank() ||
        autoProdAppSecretText.isNotBlank() ||
        autoAccountNoDemoText.isNotBlank() ||
        autoAccountNoProdText.isNotBlank()
    val prodAppKeyMasked = autoBrokerState.data?.maskedProdAppKey?.takeIf { it.isNotBlank() }
        ?: if (autoBrokerState.data?.hasProdAppKey == true) "등록됨" else "미등록"
    val prodAppSecretMasked = autoBrokerState.data?.maskedProdAppSecret?.takeIf { it.isNotBlank() }
        ?: if (autoBrokerState.data?.hasProdAppSecret == true) "등록됨" else "미등록"
    val adminPushTitleTrimmed = adminPushTitle.trim()
    val adminPushBodyTrimmed = adminPushBody.trim()
    val adminPushRouteOption = ADMIN_PUSH_ROUTE_OPTIONS.firstOrNull { it.key == adminPushRouteKey }
        ?: ADMIN_PUSH_ROUTE_OPTIONS.first()
    val adminPushValidationError = when {
        adminPushTitleTrimmed.isBlank() -> "제목을 입력해주세요."
        adminPushTitleTrimmed.length > 60 -> "제목은 60자 이하로 입력해주세요."
        adminPushBodyTrimmed.isBlank() -> "본문을 입력해주세요."
        adminPushBodyTrimmed.length > 300 -> "본문은 300자 이하로 입력해주세요."
        else -> null
    }
    val adminPushStatusReady = adminPushStatus?.pushReady == true
    val adminPushTargetTokenCount = when (adminPushTarget) {
        "active_7d" -> adminPushStatus?.active7dTokenCount ?: 0
        "all" -> adminPushStatus?.allTokenCount ?: 0
        else -> adminPushStatus?.allTokenCount ?: 0
    }
    val adminPushCanDryRun = !adminPushLoading && adminPushValidationError == null
    val adminPushCanSend = adminPushCanDryRun && adminPushStatusReady && adminPushTargetTokenCount > 0
    val adminPushSendBlockedMessage = when {
        adminPushValidationError != null -> null
        !adminPushStatusReady -> "즉시 발송은 Firebase 준비 완료 후 가능합니다."
        adminPushTargetTokenCount <= 0 -> "즉시 발송은 등록 토큰 1개 이상일 때 가능합니다."
        else -> null
    }

    LaunchedEffect(advancedInitDone, isMaster, vm.state.value.lastAdvancedSection) {
        if (advancedInitDone) return@LaunchedEffect
        when (vm.state.value.lastAdvancedSection) {
            ADVANCED_SECTION_AUTO_BROKER -> showAutoBrokerSettings = true
            ADVANCED_SECTION_DISPLAY_NEWS -> showDisplayAndNewsSettings = true
            ADVANCED_SECTION_SECURITY_TABS -> showSecurityAndTabsSettings = true
            ADVANCED_SECTION_STRATEGY -> showStrategyTuning = true
            ADVANCED_SECTION_ADMIN -> if (isMaster) showAutoBrokerSettings = true else showDisplayAndNewsSettings = true
            else -> showAutoBrokerSettings = true
        }
        advancedInitDone = true
    }
    LaunchedEffect(myAutotradeMenuAllowed, myMenuPermissionsLoaded) {
        if (myMenuPermissionsLoaded && !myAutotradeMenuAllowed) {
            showAutoBrokerSettings = false
        }
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = "설정") },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .background(Color(0xFFF5F6F8))
                .padding(16.dp)
                .verticalScroll(scroll)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(currentVersionTopText, color = Color(0xFF334155), fontWeight = FontWeight.SemiBold)
                    Text(latestVersionTopText, color = if (serverLatestError.isNullOrBlank()) Color(0xFF64748B) else Color(0xFFB45309))
                    if (needsAppUpdate) {
                        Text("업데이트 필요", color = Color(0xFFB45309), fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    text = "용어설명",
                    color = Color(0xFF2F5BEA),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { settingsGlossaryOpen = true }
                        .padding(start = 8.dp, top = 2.dp),
                )
            }

            SettingsSection(title = "설정 요약", subtitle = "앱/서버 설정은 한 번에 저장됩니다.") {
                Text("단타 ${daytradeCount}개 · 장투 ${longtermCount}개", color = Color(0xFF334155), fontWeight = FontWeight.SemiBold)
                Text("실시간 갱신 ${quoteRefreshSec}초 · 리스크 $riskLabel", color = Color(0xFF64748B))
            }

            SettingsSection(title = "앱 재설치", subtitle = "설치 오류 시 설치 페이지를 먼저 여세요.") {
                Text(reinstallInstallPageUrl, color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(reinstallInstallPageUrl)))
                        }.onFailure {
                            scope.launch { snackbarHostState.showSnackbar("설치 페이지를 열 수 없습니다.") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("설치 페이지 열기", maxLines = 1, softWrap = false) }
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(reinstallDirectUrl))
                        scope.launch { snackbarHostState.showSnackbar("직접 링크 복사됨") }
                    },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("직접 링크 복사", maxLines = 1, softWrap = false) }
            }

            if (myAutotradeMenuAllowed) SettingsSection(title = "자동매매 설정", subtitle = "자동 탭의 실행 로직이 사용하는 설정값") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("자동매매 활성화", fontWeight = FontWeight.SemiBold)
                    CompactSettingsSwitch(checked = autoEnabled, onCheckedChange = { autoEnabled = it })
                }
                Text(
                    if (autoEnabled) "현재 상태: 자동매매 켜짐" else "현재 상태: 자동매매 꺼짐",
                    color = if (autoEnabled) Color(0xFF166534) else Color(0xFFB45309),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text("실행환경", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2,
                ) {
                    SettingsChip(label = "테스트(모의투자)", selected = autoEnvironment == "demo", compact = true, minWidth = 92.dp) { autoEnvironment = "demo" }
                    SettingsChip(label = "실전", selected = autoEnvironment == "prod", compact = true, minWidth = 72.dp) { autoEnvironment = "prod" }
                }
                Spacer(Modifier.height(8.dp))
                Text("소스 선택", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 3,
                ) {
                    SettingsChip(label = "단타", selected = autoIncludeDaytrade, compact = true, minWidth = 64.dp) { autoIncludeDaytrade = !autoIncludeDaytrade }
                    SettingsChip(label = "급등", selected = autoIncludeMovers, compact = true, minWidth = 64.dp) { autoIncludeMovers = !autoIncludeMovers }
                    SettingsChip(label = "수급", selected = autoIncludeSupply, compact = true, minWidth = 64.dp) { autoIncludeSupply = !autoIncludeSupply }
                    SettingsChip(label = "논문", selected = autoIncludePapers, compact = true, minWidth = 64.dp) { autoIncludePapers = !autoIncludePapers }
                    SettingsChip(label = "장투", selected = autoIncludeLongterm, compact = true, minWidth = 64.dp) { autoIncludeLongterm = !autoIncludeLongterm }
                    SettingsChip(label = "관심", selected = autoIncludeFavorites, compact = true, minWidth = 64.dp) { autoIncludeFavorites = !autoIncludeFavorites }
                }
                Spacer(Modifier.height(10.dp))
                Text("자금/한도", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactNumericField(
                        label = "종목당 예산",
                        unit = "원",
                        value = autoBudgetText,
                        onValueChange = { autoBudgetText = it.filter(Char::isDigit).take(9) },
                        placeholder = "500000",
                        modifier = Modifier.weight(1f),
                    )
                    CompactNumericField(
                        label = "총 시드",
                        unit = "원",
                        value = autoSeedText,
                        onValueChange = { autoSeedText = it.filter(Char::isDigit).take(9) },
                        placeholder = "10000000",
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "총 시드 허용 범위: 10,000,000 ~ 100,000,000원",
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text("집행/리스크", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactNumericField(
                        label = "1회 주문 수",
                        unit = "개",
                        value = autoMaxOrdersText,
                        onValueChange = { autoMaxOrdersText = it.filter(Char::isDigit).take(3) },
                        placeholder = "20",
                        modifier = Modifier.weight(1f),
                    )
                    CompactNumericField(
                        label = "일 손실한도",
                        unit = "%",
                        value = autoMaxLossText,
                        onValueChange = { autoMaxLossText = sanitizeDecimalInput(it) },
                        placeholder = "3.0",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactNumericField(
                        label = "익절 기준",
                        unit = "%",
                        value = autoTakeProfitText,
                        onValueChange = { autoTakeProfitText = sanitizeDecimalInput(it) },
                        placeholder = "7.0",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    CompactNumericField(
                        label = "손절 기준",
                        unit = "%",
                        value = autoStopLossText,
                        onValueChange = { autoStopLossText = sanitizeDecimalInput(it) },
                        placeholder = "5.0",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("재진입 정책(손절/익절)", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("손절 후 재진입", color = Color(0xFF334155), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 2,
                        ) {
                            SettingsChip(
                                label = "즉시 허용",
                                selected = autoStoplossReentryPolicy == "immediate",
                                compact = true,
                                minWidth = 82.dp,
                            ) { autoStoplossReentryPolicy = "immediate" }
                            SettingsChip(
                                label = "재진입 대기시간",
                                selected = autoStoplossReentryPolicy == "cooldown",
                                compact = true,
                                minWidth = 104.dp,
                            ) { autoStoplossReentryPolicy = "cooldown" }
                            SettingsChip(
                                label = "당일 차단",
                                selected = autoStoplossReentryPolicy == "day_block",
                                compact = true,
                                minWidth = 84.dp,
                            ) { autoStoplossReentryPolicy = "day_block" }
                            SettingsChip(
                                label = "수동 해제 전 차단",
                                selected = autoStoplossReentryPolicy == "manual_block",
                                compact = true,
                                minWidth = 120.dp,
                            ) { autoStoplossReentryPolicy = "manual_block" }
                        }
                        if (autoStoplossReentryPolicy == "cooldown") {
                            CompactNumericField(
                                label = "손절 후 재진입 대기시간",
                                unit = "분",
                                value = autoStoplossReentryCooldownMinText,
                                onValueChange = { autoStoplossReentryCooldownMinText = it.filter(Char::isDigit).take(4) },
                                placeholder = "30",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("익절 후 재진입", color = Color(0xFF334155), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 2,
                        ) {
                            SettingsChip(
                                label = "즉시 허용",
                                selected = autoTakeprofitReentryPolicy == "immediate",
                                compact = true,
                                minWidth = 82.dp,
                            ) { autoTakeprofitReentryPolicy = "immediate" }
                            SettingsChip(
                                label = "재진입 대기시간",
                                selected = autoTakeprofitReentryPolicy == "cooldown",
                                compact = true,
                                minWidth = 104.dp,
                            ) { autoTakeprofitReentryPolicy = "cooldown" }
                            SettingsChip(
                                label = "당일 차단",
                                selected = autoTakeprofitReentryPolicy == "day_block",
                                compact = true,
                                minWidth = 84.dp,
                            ) { autoTakeprofitReentryPolicy = "day_block" }
                            SettingsChip(
                                label = "수동 해제 전 차단",
                                selected = autoTakeprofitReentryPolicy == "manual_block",
                                compact = true,
                                minWidth = 120.dp,
                            ) { autoTakeprofitReentryPolicy = "manual_block" }
                        }
                        if (autoTakeprofitReentryPolicy == "cooldown") {
                            CompactNumericField(
                                label = "익절 후 재진입 대기시간",
                                unit = "분",
                                value = autoTakeprofitReentryCooldownMinText,
                                onValueChange = { autoTakeprofitReentryCooldownMinText = it.filter(Char::isDigit).take(4) },
                                placeholder = "30",
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("익절/손절 프리셋", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 3,
                ) {
                    SettingsChip(
                        label = "+5 / -5",
                        selected = autoTakeProfitText == "5.0" && autoStopLossText == "5.0",
                        compact = true,
                        minWidth = 76.dp,
                    ) {
                        autoTakeProfitText = "5.0"
                        autoStopLossText = "5.0"
                    }
                    SettingsChip(
                        label = "+7 / -5",
                        selected = autoTakeProfitText == "7.0" && autoStopLossText == "5.0",
                        compact = true,
                        minWidth = 76.dp,
                    ) {
                        autoTakeProfitText = "7.0"
                        autoStopLossText = "5.0"
                    }
                    SettingsChip(
                        label = "+10 / -5",
                        selected = autoTakeProfitText == "10.0" && autoStopLossText == "5.0",
                        compact = true,
                        minWidth = 82.dp,
                    ) {
                        autoTakeProfitText = "10.0"
                        autoStopLossText = "5.0"
                    }
                }
                Text(
                    "현재 청산 규칙: +${autoTakeProfitText.ifBlank { "-" }}% 익절 · -${autoStopLossText.ifBlank { "-" }}% 손절 · 손절 재진입 $stoplossReentrySummary · 익절 재진입 $takeprofitReentrySummary · 일손실 ${autoMaxLossText.ifBlank { "-" }}%",
                    color = Color(0xFF334155),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "익절은 보통 5~10%, 손절은 5% 전후를 권장합니다.",
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Text("장시간 외 예약", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("장외 예약 사용", color = Color(0xFF334155), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    CompactSettingsSwitch(checked = autoOffhoursReservationEnabled, onCheckedChange = { autoOffhoursReservationEnabled = it })
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2,
                ) {
                    SettingsChip(label = "자동 실행", selected = autoOffhoursReservationMode == "auto", compact = true, minWidth = 76.dp) { autoOffhoursReservationMode = "auto" }
                    SettingsChip(label = "확인 후 실행", selected = autoOffhoursReservationMode == "confirm", compact = true, minWidth = 90.dp) { autoOffhoursReservationMode = "confirm" }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactNumericField(
                        label = "확인 대기시간",
                        unit = "분",
                        value = autoOffhoursConfirmTimeoutMinText,
                        onValueChange = { autoOffhoursConfirmTimeoutMinText = it.filter(Char::isDigit).take(2) },
                        placeholder = "3",
                        modifier = Modifier.weight(1f),
                    )
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        maxItemsInEachRow = 1,
                    ) {
                        SettingsChip(label = "시간초과 시 취소", selected = autoOffhoursConfirmTimeoutAction == "cancel", compact = true, minWidth = 110.dp) { autoOffhoursConfirmTimeoutAction = "cancel" }
                        SettingsChip(label = "시간초과 시 자동실행", selected = autoOffhoursConfirmTimeoutAction == "auto", compact = true, minWidth = 128.dp) { autoOffhoursConfirmTimeoutAction = "auto" }
                    }
                }
                Text(
                    if (autoOffhoursReservationEnabled) {
                        "예약 사용 중 · 모드 ${if (autoOffhoursReservationMode == "confirm") "확인 후 실행" else "자동 실행"} · ${autoOffhoursConfirmTimeoutMinText.ifBlank { "-" }}분"
                    } else {
                        "예약 미사용: 장시간 외에는 주문을 차단합니다."
                    },
                    color = if (autoOffhoursReservationEnabled) Color(0xFF334155) else Color(0xFFB45309),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFFE2E8F0))
                Spacer(Modifier.height(8.dp))
                Text("익절/손절 종목별 설정", color = Color(0xFF334155), fontWeight = FontWeight.SemiBold)
                Text(
                    "일괄 기준을 기본으로 사용하고, 필요한 종목만 개별 기준으로 덮어씁니다.",
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (autoRuleEditingTicker.isNullOrBlank()) "개별 규칙 추가" else "개별 규칙 수정: ${autoRuleEditingTicker ?: ""}",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF1E293B),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactNumericField(
                                label = "종목코드",
                                value = autoRuleTickerText,
                                onValueChange = {
                                    autoRuleTickerText = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' }.take(12)
                                },
                                placeholder = "005930 / AAPL",
                                keyboardType = KeyboardType.Text,
                                modifier = Modifier.weight(1f),
                            )
                            CompactNumericField(
                                label = "종목명(선택)",
                                value = autoRuleNameText,
                                onValueChange = { autoRuleNameText = it.take(20) },
                                placeholder = "삼성전자",
                                keyboardType = KeyboardType.Text,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CompactNumericField(
                                label = "개별 익절",
                                unit = "%",
                                value = autoRuleTakeProfitText,
                                onValueChange = { autoRuleTakeProfitText = sanitizeDecimalInput(it) },
                                placeholder = "7.0",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(1f),
                            )
                            CompactNumericField(
                                label = "개별 손절",
                                unit = "%",
                                value = autoRuleStopLossText,
                                onValueChange = { autoRuleStopLossText = sanitizeDecimalInput(it) },
                                placeholder = "5.0",
                                keyboardType = KeyboardType.Decimal,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("해당 종목 개별 규칙 활성화", color = Color(0xFF334155), style = MaterialTheme.typography.bodySmall)
                            CompactSettingsSwitch(checked = autoRuleEnabled, onCheckedChange = { autoRuleEnabled = it })
                        }
                        if (!autoRuleError.isNullOrBlank()) {
                            Text(autoRuleError, color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (!autoRuleValid) return@Button
                                    autoVm.saveSymbolRule(
                                        AutoTradeSymbolRuleUpsertDto(
                                            ticker = autoRuleTickerTrimmed,
                                            name = autoRuleNameText.trim().ifBlank { null },
                                            takeProfitPct = autoRuleParsedTakeProfit ?: 7.0,
                                            stopLossPct = autoRuleParsedStopLoss ?: 5.0,
                                            enabled = autoRuleEnabled,
                                        )
                                    )
                                    autoRuleEditingTicker = autoRuleTickerTrimmed
                                    scope.launch { snackbarHostState.showSnackbar("종목별 익절/손절 규칙 저장 요청 완료") }
                                },
                                enabled = autoRuleValid && !autoSymbolRulesState.loading,
                                modifier = Modifier.weight(1f),
                            ) { Text(if (autoSymbolRulesState.loading) "저장 중..." else if (autoRuleEditingTicker.isNullOrBlank()) "개별 규칙 추가" else "개별 규칙 저장") }
                            Button(
                                onClick = {
                                    autoRuleEditingTicker = null
                                    autoRuleTickerText = ""
                                    autoRuleNameText = ""
                                    autoRuleTakeProfitText = "7.0"
                                    autoRuleStopLossText = "5.0"
                                    autoRuleEnabled = true
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("입력 초기화") }
                        }
                    }
                }
                if (!autoSymbolRulesState.error.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(autoSymbolRulesState.error, color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(6.dp))
                if (autoRuleItems.isEmpty()) {
                    Text(
                        if (autoSymbolRulesState.loading) "종목별 규칙을 불러오는 중입니다..." else "등록된 종목별 익절/손절 규칙이 없습니다.",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        autoRuleItems.forEach { rule ->
                            val ruleTicker = rule.ticker?.trim().orEmpty()
                            val ruleName = rule.name?.trim().orEmpty()
                            if (ruleTicker.isBlank()) return@forEach
                            Card(
                                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        if (ruleName.isBlank()) ruleTicker else "$ruleTicker · $ruleName",
                                        color = Color(0xFF0F172A),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        "개별 ${if (rule.enabled == false) "비활성" else "활성"} · 익절 +${rule.takeProfitPct ?: 0.0}% · 손절 -${rule.stopLossPct ?: 0.0}%",
                                        color = Color(0xFF475569),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(
                                            onClick = {
                                                autoRuleEditingTicker = ruleTicker
                                                autoRuleTickerText = ruleTicker
                                                autoRuleNameText = ruleName
                                                autoRuleTakeProfitText = (rule.takeProfitPct ?: 7.0).toString()
                                                autoRuleStopLossText = (rule.stopLossPct ?: 5.0).toString()
                                                autoRuleEnabled = rule.enabled != false
                                            },
                                        ) { Text("불러오기") }
                                        TextButton(
                                            onClick = {
                                                autoVm.deleteSymbolRule(ruleTicker)
                                                if (autoRuleEditingTicker == ruleTicker) {
                                                    autoRuleEditingTicker = null
                                                    autoRuleTickerText = ""
                                                    autoRuleNameText = ""
                                                    autoRuleTakeProfitText = "7.0"
                                                    autoRuleStopLossText = "5.0"
                                                    autoRuleEnabled = true
                                                }
                                                scope.launch { snackbarHostState.showSnackbar("종목별 규칙 삭제 요청 완료") }
                                            },
                                        ) { Text("삭제") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (!autoSettingsError.isNullOrBlank()) {
                    Text(autoSettingsError, color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                }
                if (autoHasUnsavedSettings) {
                    Text("저장되지 않은 자동매매 설정 변경사항이 있습니다.", color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = {
                        if (!autoSettingsValid) return@Button
                        autoVm.saveSettings(
                            AutoTradeSettingsDto(
                                enabled = autoEnabled,
                                environment = autoEnvironment,
                                includeDaytrade = autoIncludeDaytrade,
                                includeMovers = autoIncludeMovers,
                                includeSupply = autoIncludeSupply,
                                includePapers = autoIncludePapers,
                                includeLongterm = autoIncludeLongterm,
                                includeFavorites = autoIncludeFavorites,
                                orderBudgetKrw = autoParsedBudget ?: 200000.0,
                                maxOrdersPerRun = autoParsedMaxOrders ?: 5,
                                maxDailyLossPct = autoParsedMaxLoss ?: 3.0,
                                seedKrw = autoParsedSeed ?: 10000000.0,
                                takeProfitPct = autoParsedTakeProfit ?: 7.0,
                                stopLossPct = autoParsedStopLoss ?: 5.0,
                                stoplossReentryPolicy = autoStoplossReentryPolicy,
                                stoplossReentryCooldownMin = autoParsedStoplossReentryCooldownMin ?: 30,
                                takeprofitReentryPolicy = autoTakeprofitReentryPolicy,
                                takeprofitReentryCooldownMin = autoParsedTakeprofitReentryCooldownMin ?: 30,
                                allowMarketOrder = false,
                                offhoursReservationEnabled = autoOffhoursReservationEnabled,
                                offhoursReservationMode = autoOffhoursReservationMode,
                                offhoursConfirmTimeoutMin = autoParsedOffhoursConfirmTimeoutMin ?: 3,
                                offhoursConfirmTimeoutAction = autoOffhoursConfirmTimeoutAction,
                            )
                        )
                        scope.launch { snackbarHostState.showSnackbar("자동매매 설정 저장 요청 완료") }
                    },
                    enabled = autoSettingsValid && !autoSettingsState.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (autoSettingsState.loading) "저장 중..." else "자동매매 설정 저장") }
            }

            SettingsSection(title = "고급 패널", subtitle = "필요한 항목만 열어 집중해서 수정하세요.") {
                Text("기본 사용자는 자동매매 설정과 하단 저장 버튼만 사용해도 됩니다.", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (myAutotradeMenuAllowed) {
                        SettingsChip(label = if (showAutoBrokerSettings) "계정키 닫기" else "계정키 열기", selected = showAutoBrokerSettings, modifier = Modifier.weight(1f)) {
                            val opening = !showAutoBrokerSettings
                            showAutoBrokerSettings = opening
                            if (opening) vm.saveLastAdvancedSection(ADVANCED_SECTION_AUTO_BROKER)
                        }
                    }
                    SettingsChip(label = if (showDisplayAndNewsSettings) "표시/뉴스 닫기" else "표시/뉴스 열기", selected = showDisplayAndNewsSettings, modifier = Modifier.weight(1f)) {
                        val opening = !showDisplayAndNewsSettings
                        showDisplayAndNewsSettings = opening
                        if (opening) vm.saveLastAdvancedSection(ADVANCED_SECTION_DISPLAY_NEWS)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsChip(label = if (showSecurityAndTabsSettings) "보안/탭 닫기" else "보안/탭 열기", selected = showSecurityAndTabsSettings, modifier = Modifier.weight(1f)) {
                        val opening = !showSecurityAndTabsSettings
                        showSecurityAndTabsSettings = opening
                        if (opening) vm.saveLastAdvancedSection(ADVANCED_SECTION_SECURITY_TABS)
                    }
                    SettingsChip(label = if (showStrategyTuning) "전략튜닝 닫기" else "전략튜닝 열기", selected = showStrategyTuning, modifier = Modifier.weight(1f)) {
                        val opening = !showStrategyTuning
                        showStrategyTuning = opening
                        if (opening) vm.saveLastAdvancedSection(ADVANCED_SECTION_STRATEGY)
                    }
                }
            }

            if (myAutotradeMenuAllowed && showAutoBrokerSettings) SettingsSection(title = "자동매매 계정정보", subtitle = "한국투자 계정별 키/계좌 정보") {
                Text(
                    "모의 계좌: ${autoBrokerState.data?.maskedDemoAccountNo ?: autoBrokerState.data?.maskedAccountNo ?: "-"} / 상품코드 ${(autoBrokerState.data?.accountProductCodeDemo ?: autoBrokerState.data?.accountProductCode ?: "01")}",
                    color = Color(0xFF334155),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "실전 계좌: ${autoBrokerState.data?.maskedProdAccountNo ?: autoBrokerState.data?.maskedAccountNo ?: "-"} / 상품코드 ${(autoBrokerState.data?.accountProductCodeProd ?: autoBrokerState.data?.accountProductCode ?: "01")}",
                    color = Color(0xFF334155),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "적용 소스: ${if (autoBrokerState.data?.source == "USER") "사용자 계정" else "서버 공용 계정"}",
                    color = Color(0xFF334155),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "실전 앱 키: $prodAppKeyMasked",
                    color = Color(0xFF334155),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "실전 앱 시크릿: $prodAppSecretMasked",
                    color = Color(0xFF334155),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("사용자별 계정 사용", fontWeight = FontWeight.SemiBold)
                    CompactSettingsSwitch(checked = autoUseUserCredentials, onCheckedChange = { autoUseUserCredentials = it })
                }
                if (autoUseUserCredentials) {
                    Spacer(Modifier.height(6.dp))
                    Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("모의투자 섹션", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            Text(
                                if (autoBrokerState.data?.demoReadyUser == true) "준비상태: 입력 완료" else "준비상태: 미완료",
                                color = if (autoBrokerState.data?.demoReadyUser == true) Color(0xFF166534) else Color(0xFFB45309),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactNumericField(
                                    label = "모의 계좌번호(8자리)",
                                    value = autoAccountNoDemoText,
                                    onValueChange = { autoAccountNoDemoText = it.filter(Char::isDigit).take(8) },
                                    placeholder = "12345678",
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f),
                                )
                                CompactNumericField(
                                    label = "모의 상품코드",
                                    value = autoAccountProductCodeDemoText,
                                    onValueChange = { autoAccountProductCodeDemoText = it.filter(Char::isDigit).take(2).ifBlank { "01" } },
                                    placeholder = "01",
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            CompactNumericField(
                                label = "모의 앱 키 (입력 시 업데이트)",
                                value = autoDemoAppKeyText,
                                onValueChange = { autoDemoAppKeyText = it.trim() },
                                placeholder = "입력 시 저장됩니다",
                                keyboardType = KeyboardType.Text,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            CompactNumericField(
                                label = "모의 앱 시크릿 (입력 시 업데이트)",
                                value = autoDemoAppSecretText,
                                onValueChange = { autoDemoAppSecretText = it.trim() },
                                placeholder = "입력 시 저장됩니다",
                                keyboardType = KeyboardType.Text,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("실전투자 섹션", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            Text(
                                if (autoBrokerState.data?.prodReadyUser == true) "준비상태: 입력 완료" else "준비상태: 미완료",
                                color = if (autoBrokerState.data?.prodReadyUser == true) Color(0xFF166534) else Color(0xFFB45309),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CompactNumericField(
                                    label = "실전 계좌번호(8자리)",
                                    value = autoAccountNoProdText,
                                    onValueChange = { autoAccountNoProdText = it.filter(Char::isDigit).take(8) },
                                    placeholder = "12345678",
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f),
                                )
                                CompactNumericField(
                                    label = "실전 상품코드",
                                    value = autoAccountProductCodeProdText,
                                    onValueChange = { autoAccountProductCodeProdText = it.filter(Char::isDigit).take(2).ifBlank { "01" } },
                                    placeholder = "01",
                                    keyboardType = KeyboardType.Number,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            CompactNumericField(
                                label = "실전 앱 키 (입력 시 업데이트)",
                                value = autoProdAppKeyText,
                                onValueChange = { autoProdAppKeyText = it.trim() },
                                placeholder = "입력 시 저장됩니다",
                                keyboardType = KeyboardType.Text,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            CompactNumericField(
                                label = "실전 앱 시크릿 (입력 시 업데이트)",
                                value = autoProdAppSecretText,
                                onValueChange = { autoProdAppSecretText = it.trim() },
                                placeholder = "입력 시 저장됩니다",
                                keyboardType = KeyboardType.Text,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("보안을 위해 저장된 키 원문은 조회되지 않습니다.", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
                if (!autoBrokerState.error.isNullOrBlank()) {
                    Text(
                        text = "자동매매 계정정보 조회가 지연되고 있습니다. 잠시 후 다시 시도해주세요.",
                        color = Color(0xFFB45309),
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = { autoVm.loadBroker() }) { Text("다시 시도") }
                }
                if (autoBrokerDraftChanged) {
                    Text("저장되지 않은 계정정보 변경사항이 있습니다.", color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        autoVm.saveBroker(
                            AutoTradeBrokerCredentialUpdateDto(
                                useUserCredentials = autoUseUserCredentials,
                                appKeyDemo = autoDemoAppKeyText.ifBlank { null },
                                appSecretDemo = autoDemoAppSecretText.ifBlank { null },
                                appKeyProd = autoProdAppKeyText.ifBlank { null },
                                appSecretProd = autoProdAppSecretText.ifBlank { null },
                                accountNoDemo = autoAccountNoDemoText.ifBlank { null },
                                accountProductCodeDemo = autoAccountProductCodeDemoText.ifBlank { "01" },
                                accountNoProd = autoAccountNoProdText.ifBlank { null },
                                accountProductCodeProd = autoAccountProductCodeProdText.ifBlank { "01" },
                                clearDemo = false,
                                clearProd = false,
                                clearAccount = false,
                            )
                        )
                        scope.launch { snackbarHostState.showSnackbar("자동매매 계정정보 저장 요청 완료") }
                    },
                    enabled = autoBrokerDraftChanged && !autoBrokerState.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (autoBrokerState.loading) "저장 중..." else "자동매매 계정정보 저장") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onOpenAutoTrade, modifier = Modifier.fillMaxWidth()) { Text("자동매매 화면 열기") }
            }

            if (!strategy.error.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "전략 설정 조회가 지연되고 있습니다.",
                        color = Color(0xFFB45309),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.loadStrategySettings() }) { Text("다시 시도") }
                }
            }

            if (showDisplayAndNewsSettings) SettingsSection(title = "빠른 설정", subtitle = "처음 사용하는 분은 버튼 하나로 시작") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsChip(label = "방어", selected = riskPreset.uppercase() == "DEFENSIVE", modifier = Modifier.weight(1f)) {
                        riskPreset = "DEFENSIVE"
                        daytradeCount = "10"
                        longtermCount = "20"
                        quoteRefreshSec = "15"
                        themeCap = "2"
                        maxGapPct = "3.0"
                        gateThreshold = "0.0"
                        useCustom = false
                    }
                    SettingsChip(label = "균형", selected = riskPreset.uppercase() == "ADAPTIVE", modifier = Modifier.weight(1f)) {
                        riskPreset = "ADAPTIVE"
                        daytradeCount = "12"
                        longtermCount = "30"
                        quoteRefreshSec = "10"
                        themeCap = "2"
                        maxGapPct = "5.0"
                        gateThreshold = "-0.001"
                        useCustom = false
                    }
                    SettingsChip(label = "공격", selected = riskPreset.uppercase() == "AGGRESSIVE", modifier = Modifier.weight(1f)) {
                        riskPreset = "AGGRESSIVE"
                        daytradeCount = "20"
                        longtermCount = "30"
                        quoteRefreshSec = "5"
                        themeCap = "3"
                        maxGapPct = "8.0"
                        gateThreshold = "-0.005"
                        useCustom = false
                    }
                }
            }

            if (showDisplayAndNewsSettings) SettingsSection(title = "표시 옵션", subtitle = "추천 리스트와 실시간 갱신 속도") {
                SettingsDropdown(
                    label = "단타 표시 개수",
                    value = daytradeCount,
                    options = listOf("8", "10", "12", "15", "20", "30", "40", "50", "60", "80", "100"),
                    onSelect = { daytradeCount = it }
                )
                OutlinedTextField(
                    value = daytradeCount,
                    onValueChange = { daytradeCount = it },
                    label = { Text("단타 개수(직접 입력)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Spacer(Modifier.height(12.dp))
                SettingsDropdown(
                    label = "장투 표시 개수",
                    value = longtermCount,
                    options = listOf("10", "20", "30", "40", "50", "60", "80", "100"),
                    onSelect = { longtermCount = it }
                )
                OutlinedTextField(
                    value = longtermCount,
                    onValueChange = { longtermCount = it },
                    label = { Text("장투 개수(직접 입력)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Spacer(Modifier.height(12.dp))
                SettingsDropdown(
                    label = "실시간 갱신 주기",
                    value = quoteRefreshSec,
                    options = listOf("5", "8", "10", "15", "20", "30", "60"),
                    onSelect = { quoteRefreshSec = it }
                )
                OutlinedTextField(
                    value = quoteRefreshSec,
                    onValueChange = { quoteRefreshSec = it },
                    label = { Text("갱신(초) 직접 입력") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("종목 카드 화면 버전", color = Color(0xFF475569), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsChip(label = "V2", selected = cardUiVersion.uppercase() == "V2", modifier = Modifier.weight(1f)) {
                        cardUiVersion = "V2"
                    }
                    SettingsChip(label = "V1", selected = cardUiVersion.uppercase() == "V1", modifier = Modifier.weight(1f)) {
                        cardUiVersion = "V1"
                    }
                }
            }
            if (showDisplayAndNewsSettings) SettingsSection(title = "뉴스 사용성", subtitle = "뉴스 첫 화면/필터/가독성 기본값") {
                SettingsDropdown(
                    label = "기본 기간",
                    value = newsDefaultWindow,
                    options = listOf("10m", "1h", "24h", "7d"),
                    onSelect = { newsDefaultWindow = it }
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsChip(
                        label = "핫지수",
                        selected = newsDefaultMode == "HOT",
                        modifier = Modifier.weight(1f)
                    ) { newsDefaultMode = "HOT" }
                    SettingsChip(
                        label = "클러스터",
                        selected = newsDefaultMode == "CLUSTERS",
                        modifier = Modifier.weight(1f)
                    ) { newsDefaultMode = "CLUSTERS" }
                    SettingsChip(
                        label = "기사",
                        selected = newsDefaultMode == "ARTICLES",
                        modifier = Modifier.weight(1f)
                    ) { newsDefaultMode = "ARTICLES" }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsChip(
                        label = "전체",
                        selected = newsDefaultSource == "ALL",
                        modifier = Modifier.weight(1f)
                    ) { newsDefaultSource = "ALL" }
                    SettingsChip(
                        label = "공시",
                        selected = newsDefaultSource == "dart",
                        modifier = Modifier.weight(1f)
                    ) { newsDefaultSource = "dart" }
                    SettingsChip(
                        label = "언론",
                        selected = newsDefaultSource == "rss",
                        modifier = Modifier.weight(1f)
                    ) { newsDefaultSource = "rss" }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("악재숨김 기본 켜기")
                        Switch(
                            checked = newsDefaultHideRisk,
                            onCheckedChange = { newsDefaultHideRisk = it }
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("마지막 필터 복원")
                        Switch(
                            checked = newsRestoreLastFilters,
                            onCheckedChange = { newsRestoreLastFilters = it }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingsDropdown(
                    label = "기사 본문 글자 크기(sp 단위)",
                    value = newsArticleTextSizeSp,
                    options = listOf("13", "14", "15", "16", "17", "18"),
                    onSelect = { newsArticleTextSizeSp = it }
                )
            }
            if (showSecurityAndTabsSettings) SettingsSection(title = "보안/접근", subtitle = "생체 인증 설정") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("생체 인증", fontWeight = FontWeight.SemiBold)
                        Text("지문/얼굴로 토큰 잠금 해제", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = {
                            biometricEnabled = it
                            authRepo.setBiometricEnabled(it)
                        }
                    )
                }
            }
            if (showSecurityAndTabsSettings) SettingsSection(title = "하단 메뉴 순서", subtitle = "원하는 순서로 위/아래 이동 후 저장") {
                bottomTabOrder.forEachIndexed { idx, tab ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${idx + 1}. ${tab.label}",
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = { bottomTabOrder = moveTab(bottomTabOrder, idx, -1) },
                                enabled = idx > 0
                            ) { Text("위") }
                            TextButton(
                                onClick = { bottomTabOrder = moveTab(bottomTabOrder, idx, +1) },
                                enabled = idx < bottomTabOrder.lastIndex
                            ) { Text("아래") }
                        }
                    }
                    if (idx < bottomTabOrder.lastIndex) Spacer(Modifier.height(6.dp))
                }
                TextButton(
                    onClick = { bottomTabOrder = AppTab.entries.toList() },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text("기본 순서 복원") }
            }

            if (isMaster) {
                SettingsSection(title = "관리자", subtitle = "초대/계정 관리") {
                    // Share helpers (KakaoTalk direct + generic share sheet)
                    fun shareText(text: String, kakaoOnly: Boolean) {
                        try {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                if (kakaoOnly) setPackage("com.kakao.talk")
                            }
                            if (kakaoOnly) {
                                context.startActivity(send)
                            } else {
                                context.startActivity(Intent.createChooser(send, "공유"))
                            }
                        } catch (e: ActivityNotFoundException) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    if (kakaoOnly) "카카오톡이 설치되어 있지 않습니다." else "공유 앱을 열 수 없습니다."
                                )
                            }
                        }
                    }

                    val base = BuildConfig.DEFAULT_BASE_URL.trimEnd('/')
                    // Use the redirect endpoint so downloads use a versioned filename.
                    val apkUrl = "$base/apk/download"
                    val appName = context.getString(com.example.stock.R.string.app_name)

                    Text("테스터 설치 링크", fontWeight = FontWeight.SemiBold, color = Color(0xFF334155))
                    Text(apkUrl, color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                shareText(
                                    "$appName 설치 링크: $apkUrl",
                                    kakaoOnly = true
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("카카오톡") }
                        TextButton(
                            onClick = {
                                shareText(
                                    "$appName 설치 링크: $apkUrl",
                                    kakaoOnly = false
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("공유") }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("푸시 발송", fontWeight = FontWeight.SemiBold, color = Color(0xFF334155))
                    Text("공지/업데이트를 즉시 발송합니다.", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                    if (!BuildConfig.FCM_ENABLED) {
                        Text(
                            "현재 앱 빌드는 FCM 비활성입니다(google-services.json 필요).",
                            color = Color(0xFFB91C1C),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val pushStatus = adminPushStatus
                    when {
                        adminPushStatusLoading -> Text("푸시 상태 확인 중...", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                        !adminPushStatusError.isNullOrBlank() -> Text(adminPushStatusError.orEmpty(), color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                        pushStatus != null -> {
                            val ready = pushStatus.pushReady == true
                            val allDevices = pushStatus.allDeviceCount ?: 0
                            val allTokens = pushStatus.allTokenCount ?: 0
                            val activeDevices = pushStatus.active7dDeviceCount ?: 0
                            val activeTokens = pushStatus.active7dTokenCount ?: 0
                            Text(
                                if (ready) "푸시 인프라 상태: 준비됨" else "푸시 인프라 상태: 미준비(Firebase 설정 필요)",
                                color = if (ready) Color(0xFF334155) else Color(0xFFB91C1C),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "등록 디바이스 ${allDevices}대 · 토큰 ${allTokens}개 · 최근7일 ${activeDevices}대/${activeTokens}토큰",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    TextButton(
                        onClick = { loadAdminPushStatus() },
                        enabled = !adminPushStatusLoading && !adminPushLoading,
                    ) { Text("상태 새로고침") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = adminPushTitle,
                        onValueChange = { adminPushTitle = it },
                        label = { Text("제목 (1~60)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = adminPushBody,
                        onValueChange = { adminPushBody = it },
                        label = { Text("본문 (1~300)") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        minLines = 3,
                        maxLines = 5,
                    )
                    Text(
                        "이동 화면",
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF334155),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ADMIN_PUSH_ROUTE_OPTIONS.forEach { option ->
                            SettingsChip(
                                label = option.label,
                                selected = adminPushRouteKey == option.key,
                                compact = true,
                            ) { adminPushRouteKey = option.key }
                        }
                    }
                    Text(
                        "선택: ${adminPushRouteOption.label}${adminPushRouteOption.route?.let { " ($it)" } ?: ""}",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsChip(
                            label = "테스트 1대",
                            selected = adminPushTarget == "test",
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) { adminPushTarget = "test" }
                        SettingsChip(
                            label = "최근 7일",
                            selected = adminPushTarget == "active_7d",
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) { adminPushTarget = "active_7d" }
                        SettingsChip(
                            label = "전체",
                            selected = adminPushTarget == "all",
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) { adminPushTarget = "all" }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsChip(
                            label = "공지",
                            selected = adminPushAlertType == "ADMIN",
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) { adminPushAlertType = "ADMIN" }
                        SettingsChip(
                            label = "업데이트",
                            selected = adminPushAlertType == "UPDATE",
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) { adminPushAlertType = "UPDATE" }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("모의 점검", fontWeight = FontWeight.SemiBold)
                            Text("켜짐: 발송 없이 대상 집계만 점검", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = adminPushDryRun,
                            onCheckedChange = { adminPushDryRun = it },
                            enabled = !adminPushLoading,
                        )
                    }
                    if (!adminPushValidationError.isNullOrBlank()) {
                        Text(adminPushValidationError.orEmpty(), color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!adminPushError.isNullOrBlank()) {
                        Text(adminPushError.orEmpty(), color = Color(0xFFB91C1C), style = MaterialTheme.typography.bodySmall)
                    }
                    if (!adminPushSendBlockedMessage.isNullOrBlank()) {
                        Text(adminPushSendBlockedMessage.orEmpty(), color = Color(0xFFB45309), style = MaterialTheme.typography.bodySmall)
                    }
                    adminPushResult?.let { res ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "결과: 대상 ${res.targetCount ?: 0} · 토큰 ${res.tokenCount ?: 0} · 발송 ${res.sentCount ?: 0} · 실패 ${res.failedCount ?: 0} · 스킵 ${res.skippedCount ?: 0}",
                            color = if (res.ok == false) Color(0xFFB91C1C) else Color(0xFF334155),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if ((res.skippedNoTokenCount ?: 0) > 0 || (res.skippedPrefCount ?: 0) > 0) {
                            Text(
                                "스킵 상세: 토큰없음 ${res.skippedNoTokenCount ?: 0} · 환경설정 ${res.skippedPrefCount ?: 0}",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!res.message.isNullOrBlank()) {
                            Text("메시지: ${toFriendlyAdminPushServerMessage(res.message)}", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                        }
                        val samples = res.sampleTokensMasked.orEmpty().take(3)
                        if (samples.isNotEmpty()) {
                            Text("샘플 토큰: ${samples.joinToString(", ")}", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { sendAdminPush(true) },
                            enabled = adminPushCanDryRun,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (adminPushLoading && adminPushDryRun) "점검 중..." else "미리 점검") }
                        Button(
                            onClick = { sendAdminPush(false) },
                            enabled = adminPushCanSend,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (adminPushLoading && !adminPushDryRun) "발송 중..." else "즉시 발송") }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("초대 생성", fontWeight = FontWeight.SemiBold, color = Color(0xFF334155))
                    Spacer(Modifier.height(8.dp))

                    if (meLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                            Text("관리자 정보 확인 중...")
                        }
                    }
                    if (!meError.isNullOrBlank()) {
                        Text(
                            text = "관리자 정보 확인이 지연되고 있습니다. 초대 기능은 계속 사용 가능합니다.",
                            color = Color(0xFFB45309),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsChip(label = "자동", selected = invitePasswordMode == "AUTO", modifier = Modifier.weight(1f)) { invitePasswordMode = "AUTO" }
                            SettingsChip(label = "수동", selected = invitePasswordMode == "MANUAL", modifier = Modifier.weight(1f)) { invitePasswordMode = "MANUAL" }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SettingsChip(label = "일반", selected = inviteRole == "USER", modifier = Modifier.weight(1f)) { inviteRole = "USER" }
                            SettingsChip(label = "관리자", selected = inviteRole == "MASTER", modifier = Modifier.weight(1f)) { inviteRole = "MASTER" }
                        }
                        Spacer(Modifier.height(10.dp))
                        CompactTextField(
                            label = "실명(한글 권장)",
                            value = inviteName,
                            onValueChange = { inviteName = it },
                            placeholder = "예: 홍길동",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CompactTextField(
                            label = "로그인 아이디(비워두면 자동 생성)",
                            value = inviteUserCode,
                            onValueChange = { inviteUserCode = it.trim() },
                            placeholder = "예: hong123",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        if (invitePasswordMode == "MANUAL") {
                            CompactTextField(
                                label = "임시 비밀번호(수동)",
                                value = inviteManualPassword,
                                onValueChange = { inviteManualPassword = it },
                                placeholder = "임시 비밀번호 입력",
                                keyboardType = KeyboardType.Password,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                        CompactNumericField(
                            label = "만료(일)",
                            value = inviteExpiresDays,
                            onValueChange = { inviteExpiresDays = it.filter { c -> c.isDigit() } },
                            unit = "일",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        CompactTextField(
                            label = "메모(선택)",
                            value = inviteMemo,
                            onValueChange = { inviteMemo = it },
                            placeholder = "관리 메모",
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("디바이스 바인딩", fontWeight = FontWeight.SemiBold)
                                Text("최초 로그인 기기만 허용", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                            }
                            CompactSettingsSwitch(checked = inviteDeviceBinding, onCheckedChange = { inviteDeviceBinding = it })
                        }
                        if (!inviteError.isNullOrBlank()) {
                            Text(inviteError.orEmpty(), color = Color(0xFFB45309), modifier = Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                inviteError = null
                                inviteLoading = true
                                scope.launch {
                                    val expires = inviteExpiresDays.toIntOrNull() ?: 7
                                    val payload = com.example.stock.data.api.InviteCreateRequestDto(
                                        userCode = inviteUserCode.ifBlank { null },
                                        name = inviteName.ifBlank { null },
                                        passwordMode = invitePasswordMode,
                                        initialPassword = if (invitePasswordMode == "MANUAL") inviteManualPassword.ifBlank { null } else null,
                                        role = inviteRole,
                                        expiresInDays = expires,
                                        memo = inviteMemo.ifBlank { null },
                                        deviceBindingEnabled = inviteDeviceBinding,
                                    )
                                    authRepo.createInvite(payload)
                                        .onSuccess { res ->
                                            lastInvite = res
                                            loadUsers()
                                            loadInvitedUsers()
                                            scope.launch { snackbarHostState.showSnackbar("초대 생성 완료") }
                                        }
                                        .onFailure { e -> inviteError = toFriendlyNetworkMessage(e, "초대 생성에 실패했습니다.") }
                                    inviteLoading = false
                                }
                            },
                            enabled = !inviteLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (inviteLoading) "생성 중..." else "초대 생성") }

                        lastInvite?.let { inv ->
                            val code = inv.userCode.orEmpty()
                            val pass = inv.initialPassword.orEmpty()
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(10.dp))
                            Text("초대 결과(1회 노출)", fontWeight = FontWeight.SemiBold)
                            Text("코드: $code", color = Color(0xFF334155))
                            Text("임시비번: $pass", color = Color(0xFF334155))
                            Text("만료: ${inv.expiresAt}", color = Color(0xFF64748B))
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString("$code / $pass"))
                                        scope.launch { snackbarHostState.showSnackbar("복사됨") }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("복사") }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            authRepo.markInviteSent(code)
                                                .onSuccess { scope.launch { snackbarHostState.showSnackbar("전송 완료 처리됨") } }
                                                .onFailure { e -> scope.launch { snackbarHostState.showSnackbar(e.message ?: "전송 표시 실패") } }
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("전송 완료 표시") }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = {
                                        val msg = "$appName 설치 링크: $apkUrl\n초대 코드: $code\n(임시 비밀번호는 관리자에게 별도로 전달)"
                                        shareText(msg, kakaoOnly = true)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("카카오톡") }
                                TextButton(
                                    onClick = {
                                        val msg = "$appName 설치 링크: $apkUrl\n초대 코드: $code\n(임시 비밀번호는 관리자에게 별도로 전달)"
                                        shareText(msg, kakaoOnly = false)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("공유") }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("내가 초대한 사용자", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { loadInvitedUsers() }) { Text(if (invitedUsersLoading) "로딩..." else "새로고침") }
                                TextButton(
                                    onClick = { invitedUsersExpanded = !invitedUsersExpanded },
                                ) { Text(if (invitedUsersExpanded) "접기" else "펼치기") }
                            }
                        }
                        Text(
                            "총 ${invitedUsersTotal}명${if (invitedUsersExpanded) " · 표시 ${minOf(invitedUsers.size, 15)}명" else ""}",
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (invitedUsersExpanded) {
                            if (!invitedUsersError.isNullOrBlank()) {
                                Text(invitedUsersError.orEmpty(), color = Color(0xFFB45309))
                            } else if (invitedUsersLoading) {
                                Text("목록 불러오는 중...", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                            } else if (invitedUsers.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                invitedUsers.take(15).forEach { u ->
                                    val code = u.userCode.orEmpty()
                                    val name = u.name?.takeIf { it.isNotBlank() } ?: "(실명 미등록)"
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                                            .padding(10.dp)
                                    ) {
                                        Column {
                                            Text("$name · $code", fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                            Text(
                                                "초대: ${compactTs(u.invitedAt)} · 최근접속: ${compactTs(u.lastLoginAt)}",
                                                color = Color(0xFF64748B),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                "상태: ${u.status.orEmpty()} · ${u.inviteStatus.orEmpty()}",
                                                color = Color(0xFF64748B),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            } else {
                                Text("표시할 초대 사용자가 없습니다.", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("유저 목록", fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { loadUsers() }) { Text(if (usersLoading) "로딩..." else "새로고침") }
                                TextButton(onClick = { usersListExpanded = !usersListExpanded }) {
                                    Text(if (usersListExpanded) "접기" else "펼치기")
                                }
                            }
                        }
                        OutlinedTextField(
                            value = adminUserQuery,
                            onValueChange = { adminUserQuery = it },
                            label = { Text("사용자 검색(실명/아이디)") },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                        Text(
                            "로그 확인: 회원카드 펼치기 -> 접속로그 탭",
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        val filteredUsers = users.filter { u ->
                            val q = adminUserQuery.trim()
                            if (q.isBlank()) true else {
                                val code = u.userCode.orEmpty()
                                val name = u.name.orEmpty()
                                code.contains(q, ignoreCase = true) || name.contains(q, ignoreCase = true)
                            }
                        }
                        val collapsedPreviewCount = 6
                        val visibleUsers = if (usersListExpanded) {
                            filteredUsers.take(20)
                        } else {
                            val base = filteredUsers.take(collapsedPreviewCount).toMutableList()
                            val selectedCode = selectedUserCode
                            if (!selectedCode.isNullOrBlank() && base.none { it.userCode == selectedCode }) {
                                filteredUsers.firstOrNull { it.userCode == selectedCode }?.let { selectedUser ->
                                    base.add(0, selectedUser)
                                }
                            }
                            base.take(collapsedPreviewCount + 1)
                        }
                        if (!usersError.isNullOrBlank()) {
                            Text(usersError.orEmpty(), color = Color(0xFFB45309))
                        } else if (filteredUsers.isNotEmpty()) {
                            val labelShownCount = visibleUsers.size
                            Text(
                                "총 ${usersTotal}명 · 검색결과 ${filteredUsers.size}명 · 표시 ${labelShownCount}명",
                                color = Color(0xFF64748B),
                            )
                            if (!usersListExpanded && filteredUsers.size > collapsedPreviewCount) {
                                Text(
                                    "목록이 길어 기본 ${collapsedPreviewCount}명만 표시합니다. 필요 시 펼치세요.",
                                    color = Color(0xFF64748B),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            visibleUsers.forEach { u ->
                                val code = u.userCode.orEmpty()
                                val role = u.role.orEmpty()
                                val status = u.status.orEmpty()
                                val inviteStatus = u.inviteStatus.orEmpty()
                                val blocked = status.lowercase() == "blocked"
                                val selected = selectedUserCode == code
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (selected) Color(0xFFE2E8F0) else Color(0xFFF1F5F9),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .border(
                                            width = if (selected) 1.dp else 0.dp,
                                            color = if (selected) Color(0xFF1E3A8A) else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                val displayName = u.name?.takeIf { it.isNotBlank() } ?: "(실명 미등록)"
                                                Text("$displayName · $code", fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text("$role · $status · $inviteStatus", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                                            }
                                            TextButton(
                                                onClick = {
                                                    if (selectedUserCode == code) {
                                                        selectedUserCode = null
                                                    } else {
                                                        loadSelectedUserDetail(code)
                                                    }
                                                }
                                            ) { Text(if (selectedUserCode == code) "접기" else "펼치기") }
                                        }
                                        if (selected) {
                                            FlowRow(
                                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                AdminActionChip(
                                                    label = "메뉴권한",
                                                    tone = AdminActionTone.Primary,
                                                    onClick = {
                                                        val displayName = u.name?.takeIf { it.isNotBlank() } ?: code
                                                        openMenuEditor(code, displayName)
                                                    },
                                                )
                                                AdminActionChip(
                                                    label = "로그",
                                                    onClick = { selectedDetailTab = "접속로그" },
                                                )
                                                AdminActionChip(
                                                    label = "수익/종목",
                                                    onClick = { selectedDetailTab = "수익/종목" },
                                                )
                                                AdminActionChip(
                                                    label = if (blocked) "차단해제" else "접속차단",
                                                    tone = if (blocked) AdminActionTone.Primary else AdminActionTone.Danger,
                                                    onClick = {
                                                        scope.launch {
                                                            val r = if (blocked) authRepo.adminUnblockUser(code) else authRepo.adminBlockUser(code)
                                                            r.onSuccess {
                                                                scope.launch { snackbarHostState.showSnackbar(if (blocked) "UNBLOCK" else "BLOCK") }
                                                                loadUsers()
                                                                loadInvitedUsers()
                                                                if (selectedUserCode == code) loadSelectedUserDetail(code)
                                                            }.onFailure { e ->
                                                                scope.launch { snackbarHostState.showSnackbar(e.message ?: "실패") }
                                                            }
                                                        }
                                                    },
                                                )
                                                AdminActionChip(
                                                    label = "세션종료",
                                                    onClick = {
                                                        scope.launch {
                                                            authRepo.adminRevokeUserSessions(code)
                                                                .onSuccess {
                                                                    scope.launch { snackbarHostState.showSnackbar("세션 종료 완료") }
                                                                    if (selectedUserCode == code) loadSelectedUserDetail(code)
                                                                }
                                                                .onFailure { e -> scope.launch { snackbarHostState.showSnackbar(e.message ?: "세션 종료 실패") } }
                                                        }
                                                    },
                                                )
                                                AdminActionChip(
                                                    label = "비번리셋",
                                                    onClick = {
                                                        scope.launch {
                                                            authRepo.adminResetPassword(
                                                                code,
                                                                com.example.stock.data.api.PasswordResetRequestDto(passwordMode = "AUTO", expiresInDays = 7),
                                                            ).onSuccess { res ->
                                                                lastReset = res
                                                                clipboard.setText(AnnotatedString("${res.userCode} / ${res.initialPassword}"))
                                                                scope.launch { snackbarHostState.showSnackbar("리셋됨(복사됨)") }
                                                                if (selectedUserCode == code) loadSelectedUserDetail(code)
                                                            }.onFailure { e ->
                                                                scope.launch { snackbarHostState.showSnackbar(e.message ?: "리셋 실패") }
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                            val loginSummary = selectedUserLoginLogs?.summary
                                            val ov = selectedUserAutoOverview
                                            val perf = ov?.performanceSummary
                                            val blockedMenusCount = blockedMenuCount(selectedUserMenuPermissions)
                                            val positions = ov?.account?.positions.orEmpty()
                                            val recentOrders = ov?.recentOrders.orEmpty()
                                            Spacer(Modifier.height(8.dp))
                                            HorizontalDivider()
                                            Spacer(Modifier.height(8.dp))
                                            Text("회원 컨트롤 허브", fontWeight = FontWeight.ExtraBold, color = Color(0xFF0F172A))
                                            if (selectedUserDetailLoading) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                                                    Text("상세 데이터 로딩 중...")
                                                }
                                            }
                                            if (!selectedUserDetailError.isNullOrBlank()) {
                                                Text(selectedUserDetailError.orEmpty(), color = Color(0xFFB45309), modifier = Modifier.padding(top = 6.dp))
                                            }
                                            if (ov != null || selectedUserLoginLogs != null) {
                                                Spacer(Modifier.height(6.dp))
                                                FlowRow(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                                ) {
                                                    AdminMetricTile(
                                                        title = "누적 수익률",
                                                        value = fmtSignedPct(perf?.roiPct ?: 0.0),
                                                        valueColor = pnlColor(perf?.roiPct ?: 0.0),
                                                    )
                                                    AdminMetricTile(
                                                        title = "실현 손익",
                                                        value = fmtSignedKrw(perf?.realizedPnlKrw ?: 0.0),
                                                        valueColor = pnlColor(perf?.realizedPnlKrw ?: 0.0),
                                                    )
                                                    AdminMetricTile(
                                                        title = "미실현 손익",
                                                        value = fmtSignedKrw(perf?.unrealizedPnlKrw ?: 0.0),
                                                        valueColor = pnlColor(perf?.unrealizedPnlKrw ?: 0.0),
                                                    )
                                                    AdminMetricTile(
                                                        title = "평가 자산",
                                                        value = fmt(ov?.account?.totalAssetKrw ?: 0.0),
                                                    )
                                                }
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    "메뉴 권한 ${if (selectedUserMenuInheritedDefault) "기본(전체 허용)" else "개별 설정"} · 제한 ${blockedMenusCount}개",
                                                    color = Color(0xFF334155),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                Text(
                                                    "보유 ${positions.size}종목 · 최근주문 ${recentOrders.size}건 · 접속성공 ${loginSummary?.successCount ?: 0} · 실패 ${loginSummary?.failCount ?: 0}",
                                                    color = Color(0xFF64748B),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                            if (!selectedMenuError.isNullOrBlank()) {
                                                Text(
                                                    selectedMenuError.orEmpty(),
                                                    color = Color(0xFFB45309),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                SettingsChip(
                                                    label = "요약",
                                                    selected = selectedDetailTab == "요약",
                                                    compact = true,
                                                ) { selectedDetailTab = "요약" }
                                                SettingsChip(
                                                    label = "접속로그",
                                                    selected = selectedDetailTab == "접속로그",
                                                    compact = true,
                                                ) { selectedDetailTab = "접속로그" }
                                                SettingsChip(
                                                    label = "수익/종목",
                                                    selected = selectedDetailTab == "수익/종목",
                                                    compact = true,
                                                ) { selectedDetailTab = "수익/종목" }
                                            }

                                            if (selectedDetailTab == "요약") {
                                                Spacer(Modifier.height(10.dp))
                                                Text("계정 식별정보", fontWeight = FontWeight.SemiBold)
                                                OutlinedTextField(
                                                    value = identityName,
                                                    onValueChange = { identityName = it },
                                                    label = { Text("실명(한글 권장)") },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                )
                                                OutlinedTextField(
                                                    value = identityCode,
                                                    onValueChange = { identityCode = it.trim() },
                                                    label = { Text("로그인 아이디") },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                )
                                                OutlinedTextField(
                                                    value = identityPhone,
                                                    onValueChange = { identityPhone = it },
                                                    label = { Text("연락처(선택)") },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                )
                                                OutlinedTextField(
                                                    value = identityMemo,
                                                    onValueChange = { identityMemo = it },
                                                    label = { Text("메모(선택)") },
                                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                                )
                                                if (!identityError.isNullOrBlank()) {
                                                    Text(identityError.orEmpty(), color = Color(0xFFB45309), modifier = Modifier.padding(top = 6.dp))
                                                }
                                                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(
                                                        onClick = {
                                                            identityError = null
                                                            identitySaving = true
                                                            scope.launch {
                                                                authRepo.adminUpdateUserIdentity(
                                                                    userCode = code,
                                                                    payload = com.example.stock.data.api.UserIdentityUpdateRequestDto(
                                                                        userCode = identityCode.ifBlank { null },
                                                                        name = identityName.ifBlank { null },
                                                                        memo = identityMemo,
                                                                        phone = identityPhone,
                                                                    ),
                                                                ).onSuccess { updated ->
                                                                    scope.launch { snackbarHostState.showSnackbar("식별정보 저장 완료") }
                                                                    val newCode = updated.userCode.orEmpty().ifBlank { code }
                                                                    loadUsers()
                                                                    loadInvitedUsers()
                                                                    loadSelectedUserDetail(newCode)
                                                                }.onFailure { e ->
                                                                    identityError = toFriendlyNetworkMessage(e, "식별정보 저장 실패")
                                                                }
                                                                identitySaving = false
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        enabled = !identitySaving,
                                                    ) { Text(if (identitySaving) "저장 중..." else "식별정보 저장") }
                                                    TextButton(
                                                        onClick = { loadSelectedUserDetail(code) },
                                                        modifier = Modifier.weight(1f),
                                                    ) { Text("상세 새로고침") }
                                                }
                                            } else if (selectedDetailTab == "접속로그") {
                                                Spacer(Modifier.height(10.dp))
                                                Text("접속 로그", fontWeight = FontWeight.SemiBold)
                                                if (loginSummary != null) {
                                                    Text(
                                                        "최근 성공 ${compactTs(loginSummary.lastSuccessAt)} · 최근 실패 ${compactTs(loginSummary.lastFailAt)} · 활성세션 ${loginSummary.activeSessionCount ?: 0}",
                                                        color = Color(0xFF64748B),
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                                    val topReasons = loginSummary.reasonCounts.orEmpty()
                                                        .entries
                                                        .sortedByDescending { it.value }
                                                        .take(4)
                                                    if (topReasons.isNotEmpty()) {
                                                        FlowRow(
                                                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                                        ) {
                                                            topReasons.forEach { entry ->
                                                                AdminLogReasonChip(label = "${entry.key} ${entry.value}회")
                                                            }
                                                        }
                                                    }
                                                }
                                                val logItems = selectedUserLoginLogs?.items.orEmpty().take(20)
                                                if (logItems.isEmpty()) {
                                                    Text(
                                                        "최근 접속 로그가 없습니다.",
                                                        color = Color(0xFF64748B),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.padding(top = 8.dp),
                                                    )
                                                } else {
                                                    logItems.forEach { item ->
                                                        AdminLoginLogCard(item)
                                                    }
                                                }
                                            } else if (selectedDetailTab == "수익/종목") {
                                                Spacer(Modifier.height(10.dp))
                                                Text("보유 종목", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    "계좌 ${ov?.account?.accountNoMasked ?: "-"} · 실행환경 ${ov?.settings?.environment.orEmpty()}",
                                                    color = Color(0xFF64748B),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                if (positions.isEmpty()) {
                                                    Text("보유 종목이 없습니다.", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                                                } else {
                                                    positions.forEach { position ->
                                                        AdminPositionCard(position = position)
                                                    }
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                Text("최근 주문", fontWeight = FontWeight.SemiBold)
                                                if (recentOrders.isEmpty()) {
                                                    Text("최근 주문이 없습니다.", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                                                } else {
                                                    recentOrders.take(12).forEach { order ->
                                                        AdminRecentOrderCard(order = order)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        } else if (users.isNotEmpty()) {
                            Text("검색 결과가 없습니다.", color = Color(0xFF64748B))
                        }

                        lastReset?.let { rr ->
                            val code = rr.userCode.orEmpty()
                            val pass = rr.initialPassword.orEmpty()
                            Spacer(Modifier.height(6.dp))
                            Text("최근 리셋: $code / $pass", color = Color(0xFF334155), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                    }
                }
            }

            if (showStrategyTuning) SettingsSection(title = "전략 튜닝", subtitle = "리스크 프리셋과 민감도 설정") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsChip(label = "방어", selected = riskPreset.uppercase() == "DEFENSIVE", modifier = Modifier.weight(1f)) { riskPreset = "DEFENSIVE" }
                    SettingsChip(label = "균형", selected = riskPreset.uppercase() == "ADAPTIVE", modifier = Modifier.weight(1f)) { riskPreset = "ADAPTIVE" }
                    SettingsChip(label = "공격", selected = riskPreset.uppercase() == "AGGRESSIVE", modifier = Modifier.weight(1f)) { riskPreset = "AGGRESSIVE" }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("커스텀 가중치", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(checked = useCustom, onCheckedChange = { useCustom = it })
                }
                if (useCustom) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = wTa, onValueChange = { wTa = it }, label = { Text("기술") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = wRe, onValueChange = { wRe = it }, label = { Text("실적") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = wRs, onValueChange = { wRs = it }, label = { Text("수급") }, modifier = Modifier.weight(1f))
                    }
                    Button(
                        onClick = {
                            val a = wTa.toDoubleOrNull() ?: 0.0
                            val b = wRe.toDoubleOrNull() ?: 0.0
                            val c = wRs.toDoubleOrNull() ?: 0.0
                            val sum = a + b + c
                            if (sum > 0) {
                                wTa = String.format("%.4f", a / sum)
                                wRe = String.format("%.4f", b / sum)
                                wRs = String.format("%.4f", c / sum)
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("가중치 정렬") }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = themeCap, onValueChange = { themeCap = it }, label = { Text("테마 최대") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = maxGapPct, onValueChange = { maxGapPct = it }, label = { Text("갭 제한(%)") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = gateThreshold, onValueChange = { gateThreshold = it }, label = { Text("게이트 임계") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = gateQuantile, onValueChange = { gateQuantile = it }, label = { Text("분위수") }, modifier = Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val savedTabOrderCsv = toBottomTabOrderCsv(bottomTabOrder)
                    val s = vm.state.value
                    vm.save(
                        baseUrl = s.baseUrl,
                        lookback = s.lookbackDays.toString(),
                        riskPreset = s.riskPreset,
                        themeCap = s.themeCap.toString(),
                        daytradeDisplayCount = daytradeCount,
                        longtermDisplayCount = longtermCount,
                        quoteRefreshSec = quoteRefreshSec,
                        daytradeVariant = s.daytradeVariant.toString(),
                        bottomTabOrderCsv = savedTabOrderCsv,
                        cardUiVersion = cardUiVersion,
                    )
                    vm.saveNewsDefaults(
                        defaultWindow = newsDefaultWindow,
                        defaultMode = newsDefaultMode,
                        defaultSource = newsDefaultSource,
                        defaultHideRisk = newsDefaultHideRisk,
                        restoreLastFilters = newsRestoreLastFilters,
                        articleTextSizeSp = newsArticleTextSizeSp.toIntOrNull() ?: 15,
                    )
                    onAppSettingsSaved(savedTabOrderCsv)
                    val payload = com.example.stock.data.api.StrategySettingsDto(
                        riskPreset = riskPreset,
                        useCustomWeights = useCustom,
                        wTa = wTa.toDoubleOrNull(),
                        wRe = wRe.toDoubleOrNull(),
                        wRs = wRs.toDoubleOrNull(),
                        themeCap = themeCap.toIntOrNull(),
                        maxGapPct = maxGapPct.toDoubleOrNull(),
                        gateThreshold = gateThreshold.toDoubleOrNull(),
                        gateQuantile = gateQuantile.toDoubleOrNull(),
                    )
                    vm.saveStrategySettings(payload)
                    scope.launch { snackbarHostState.showSnackbar("앱/서버 설정 저장 요청 완료") }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("앱/서버 설정 저장") }

            Spacer(Modifier.height(8.dp))
        }
        if (menuEditorVisible) {
            AlertDialog(
                onDismissRequest = {
                    if (!menuEditorSaving) {
                        menuEditorVisible = false
                    }
                },
                title = {
                    Text(
                        "메뉴 권한 편집",
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "${menuEditorUserName.ifBlank { menuEditorUserCode }} · ${menuEditorUserCode}",
                            color = Color(0xFF334155),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (menuEditorInheritedDefault) "현재: 기본 정책(전체 허용)" else "현재: 사용자별 개별 권한 적용",
                            color = Color(0xFF64748B),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (menuEditorLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(end = 8.dp))
                                Text("권한 정보 불러오는 중...")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = {
                                        menuEditorPermissions = withAllMenuPermissions(menuEditorPermissions, allowed = true)
                                    },
                                    enabled = !menuEditorSaving,
                                    modifier = Modifier.weight(1f),
                                ) { Text("전체 허용") }
                                TextButton(
                                    onClick = {
                                        menuEditorPermissions = withAllMenuPermissions(menuEditorPermissions, allowed = false)
                                    },
                                    enabled = !menuEditorSaving,
                                    modifier = Modifier.weight(1f),
                                ) { Text("전체 제한") }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "제한 ${blockedMenuCount(menuEditorPermissions)}개",
                                color = Color(0xFF64748B),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            menuPermissionSpecs().forEach { spec ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(spec.label, fontWeight = FontWeight.SemiBold)
                                    CompactSettingsSwitch(
                                        checked = readMenuPermission(menuEditorPermissions, spec.key),
                                        onCheckedChange = { checked ->
                                            menuEditorPermissions = writeMenuPermission(menuEditorPermissions, spec.key, checked)
                                        },
                                        enabled = !menuEditorSaving,
                                    )
                                }
                            }
                        }
                        if (!menuEditorError.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                menuEditorError.orEmpty(),
                                color = Color(0xFFB45309),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { saveMenuEditor() },
                        enabled = !menuEditorLoading && !menuEditorSaving,
                    ) {
                        Text(if (menuEditorSaving) "저장 중..." else "저장")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { menuEditorVisible = false },
                        enabled = !menuEditorSaving,
                    ) {
                        Text("닫기")
                    }
                },
            )
        }
        if (settingsGlossaryOpen) {
            AlertDialog(
                onDismissRequest = { settingsGlossaryOpen = false },
                title = { Text("설정 용어집", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        settingsGlossary.forEach { item ->
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
                    TextButton(onClick = { settingsGlossaryOpen = false }) {
                        Text("닫기")
                    }
                },
            )
        }
    }

@Composable
private fun AdminMetricTile(
    title: String,
    value: String,
    valueColor: Color = Color(0xFF0F172A),
) {
    Column(
        modifier = Modifier
            .defaultMinSize(minWidth = 132.dp)
            .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(title, color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
        Text(value, color = valueColor, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun AdminPositionCard(position: com.example.stock.data.api.AutoTradeAccountPositionDto) {
    val pnlPct = position.pnlPct ?: 0.0
    val pnlAmount = position.pnlAmountKrw ?: 0.0
    val tone = pnlColor(pnlAmount)
    val source = when ((position.sourceTab ?: "").uppercase()) {
        "DAYTRADE" -> "단타"
        "SUPPLY" -> "수급"
        "MOVERS" -> "급등"
        "LONGTERM" -> "장투"
        "PAPERS" -> "논문"
        "FAVORITES" -> "관심"
        else -> position.sourceTab ?: "-"
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${position.name ?: position.ticker.orEmpty()} (${position.ticker.orEmpty()})",
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(source, color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
                }
                Text(fmtSignedPct(pnlPct), color = tone, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "수량 ${position.qty ?: 0}주 · 평단 ${fmt(position.avgPrice ?: 0.0)} · 현재가 ${fmt(position.currentPrice ?: 0.0)}",
                color = Color(0xFF334155),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "평가 ${fmt(position.evalAmountKrw ?: 0.0)} · 손익 ${fmtSignedKrw(pnlAmount)}",
                color = tone,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AdminRecentOrderCard(order: com.example.stock.data.api.AutoTradeOrderItemDto) {
    val pnlPct = order.pnlPct ?: 0.0
    val tone = pnlColor(pnlPct)
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${order.ticker.orEmpty()} ${order.side.orEmpty()} ${order.qty ?: 0}주",
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(order.status.orEmpty(), color = Color(0xFF334155), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${compactTs(order.requestedAt)} · 요청가 ${fmt(order.requestedPrice ?: 0.0)}",
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "손익률 ${fmtSignedPct(pnlPct)}",
                color = tone,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AdminLogReasonChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFE2E8F0))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, color = Color(0xFF334155), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AdminLoginLogCard(item: com.example.stock.data.api.LoginEventItemDto) {
    val success = isLoginSuccess(item.result)
    val badgeBg = if (success) Color(0xFFDCFCE7) else Color(0xFFFEE2E2)
    val badgeFg = if (success) Color(0xFF166534) else Color(0xFFB91C1C)
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    compactTs(item.timestamp),
                    color = Color(0xFF334155),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        loginResultLabel(item.result),
                        color = badgeFg,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                "사유: ${item.reasonCode.orEmpty().ifBlank { "-" }}",
                color = Color(0xFF475569),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                "IP: ${item.ip.orEmpty().ifBlank { "-" }}",
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 1.dp),
            )
            Text(
                "디바이스: ${item.deviceId.orEmpty().ifBlank { "-" }}",
                color = Color(0xFF64748B),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

private fun loginResultLabel(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "success", "ok" -> "성공"
    "fail", "failed" -> "실패"
    else -> raw?.ifBlank { "알수없음" } ?: "알수없음"
}

private fun isLoginSuccess(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
    "success", "ok" -> true
    else -> false
}

private fun fmtSignedPct(v: Double): String =
    "${if (v >= 0.0) "+" else ""}${"%.2f".format(v)}%"

private fun fmtSignedKrw(v: Double): String =
    "${if (v >= 0.0) "+" else ""}${fmt(kotlin.math.abs(v))}"

private fun pnlColor(v: Double): Color = when {
    v > 0.0 -> Color(0xFF166534)
    v < 0.0 -> Color(0xFFB91C1C)
    else -> Color(0xFF334155)
}

private data class MenuPermissionSpec(val key: String, val label: String)

private fun menuPermissionSpecs(): List<MenuPermissionSpec> = listOf(
    MenuPermissionSpec(key = "menu_daytrade", label = "단타"),
    MenuPermissionSpec(key = "menu_autotrade", label = "자동"),
    MenuPermissionSpec(key = "menu_holdings", label = "보유"),
    MenuPermissionSpec(key = "menu_supply", label = "수급"),
    MenuPermissionSpec(key = "menu_movers", label = "급등"),
    MenuPermissionSpec(key = "menu_us", label = "미장"),
    MenuPermissionSpec(key = "menu_news", label = "뉴스"),
    MenuPermissionSpec(key = "menu_longterm", label = "장투"),
    MenuPermissionSpec(key = "menu_papers", label = "논문"),
    MenuPermissionSpec(key = "menu_eod", label = "관심"),
    MenuPermissionSpec(key = "menu_alerts", label = "알림"),
)

private fun readMenuPermission(perms: MenuPermissionsDto, key: String): Boolean = when (key) {
    "menu_daytrade" -> perms.menuDaytrade != false
    "menu_autotrade" -> perms.menuAutotrade != false
    "menu_holdings" -> perms.menuHoldings != false
    "menu_supply" -> perms.menuSupply != false
    "menu_movers" -> perms.menuMovers != false
    "menu_us" -> perms.menuUs != false
    "menu_news" -> perms.menuNews != false
    "menu_longterm" -> perms.menuLongterm != false
    "menu_papers" -> perms.menuPapers != false
    "menu_eod" -> perms.menuEod != false
    "menu_alerts" -> perms.menuAlerts != false
    else -> true
}

private fun writeMenuPermission(perms: MenuPermissionsDto, key: String, allowed: Boolean): MenuPermissionsDto = when (key) {
    "menu_daytrade" -> perms.copy(menuDaytrade = allowed)
    "menu_autotrade" -> perms.copy(menuAutotrade = allowed)
    "menu_holdings" -> perms.copy(menuHoldings = allowed)
    "menu_supply" -> perms.copy(menuSupply = allowed)
    "menu_movers" -> perms.copy(menuMovers = allowed)
    "menu_us" -> perms.copy(menuUs = allowed)
    "menu_news" -> perms.copy(menuNews = allowed)
    "menu_longterm" -> perms.copy(menuLongterm = allowed)
    "menu_papers" -> perms.copy(menuPapers = allowed)
    "menu_eod" -> perms.copy(menuEod = allowed)
    "menu_alerts" -> perms.copy(menuAlerts = allowed)
    else -> perms
}

private fun withAllMenuPermissions(perms: MenuPermissionsDto, allowed: Boolean): MenuPermissionsDto =
    perms.copy(
        menuDaytrade = allowed,
        menuAutotrade = allowed,
        menuHoldings = allowed,
        menuSupply = allowed,
        menuMovers = allowed,
        menuUs = allowed,
        menuNews = allowed,
        menuLongterm = allowed,
        menuPapers = allowed,
        menuEod = allowed,
        menuAlerts = allowed,
    )

private fun blockedMenuCount(perms: MenuPermissionsDto): Int =
    menuPermissionSpecs().count { spec -> !readMenuPermission(perms, spec.key) }

private fun parseBottomTabOrderCsv(csv: String): List<AppTab> {
    val byRoute = AppTab.entries.associateBy { it.route }
    val seen = LinkedHashSet<String>()
    val ordered = mutableListOf<AppTab>()
    csv.split(",")
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { route ->
            val normalized = normalizeLegacyTabRoute(route)
            val tab = byRoute[normalized] ?: return@forEach
            if (seen.add(normalized)) ordered += tab
        }
    if (!seen.contains(AppTab.SUPPLY.route)) {
        val insertAt = ordered.indexOfFirst { it.route == AppTab.PREMARKET.route }
        val targetIndex = if (insertAt >= 0) insertAt + 1 else 0
        ordered.add(targetIndex, AppTab.SUPPLY)
        seen.add(AppTab.SUPPLY.route)
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

private fun moveTab(order: List<AppTab>, index: Int, delta: Int): List<AppTab> {
    val target = index + delta
    if (index !in order.indices || target !in order.indices) return order
    val mutable = order.toMutableList()
    val picked = mutable.removeAt(index)
    mutable.add(target, picked)
    return mutable.toList()
}

private fun sanitizeDecimalInput(raw: String, maxLength: Int = 5): String {
    val cleaned = buildString {
        var dotUsed = false
        raw.forEach { ch ->
            when {
                ch.isDigit() -> append(ch)
                ch == '.' && !dotUsed -> {
                    dotUsed = true
                    append(ch)
                }
            }
        }
    }
    return cleaned.take(maxLength)
}

@Composable
private fun CompactNumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    unit: String? = null,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    Column(modifier = modifier) {
        Text(label, color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = SolidColor(Color(0xFF1E3A8A)),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    if (value.isBlank() && placeholder.isNotBlank()) {
                        Text(placeholder, color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                    }
                    innerTextField()
                },
            )
            if (!unit.isNullOrBlank()) {
                Text(unit, color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CompactTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = modifier) {
        Text(label, color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White)
                .border(1.dp, Color(0xFFD0D7E2), RoundedCornerShape(9.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.SemiBold,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = SolidColor(Color(0xFF1E3A8A)),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    if (value.isBlank() && placeholder.isNotBlank()) {
                        Text(placeholder, color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
                    }
                    innerTextField()
                },
            )
        }
    }
}

private enum class AdminActionTone { Neutral, Primary, Danger }

@Composable
private fun AdminActionChip(
    label: String,
    tone: AdminActionTone = AdminActionTone.Neutral,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when (tone) {
        AdminActionTone.Primary -> Color(0xFFDBEAFE)
        AdminActionTone.Danger -> Color(0xFFFEE2E2)
        AdminActionTone.Neutral -> Color(0xFFE2E8F0)
    }
    val fg = when (tone) {
        AdminActionTone.Primary -> Color(0xFF1E3A8A)
        AdminActionTone.Danger -> Color(0xFFB91C1C)
        AdminActionTone.Neutral -> Color(0xFF334155)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) bg else Color(0xFFE5E7EB))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = if (enabled) fg else Color(0xFF9CA3AF),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.ExtraBold)
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = Color(0xFF94A3B8),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
                Text(
                    text = if (expanded) "접기" else "펼치기",
                    color = Color(0xFF64748B),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun SettingsChip(
    label: String,
    selected: Boolean,
    compact: Boolean = false,
    minWidth: Dp = 0.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) Color(0xFF1E3A8A) else Color(0xFFE2E8F0)
    val fg = if (selected) Color.White else Color(0xFF1E293B)
    val verticalPadding = if (compact) 3.dp else 7.dp
    val horizontalPadding = if (compact) 10.dp else 14.dp
    Box(
        modifier = modifier
            .defaultMinSize(
                minWidth = minWidth,
                minHeight = if (compact) 26.dp else 34.dp,
            )
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        Text(
            label,
            color = fg,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun CompactSettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = Modifier.scale(0.82f),
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Color(0xFF1E3A8A),
            checkedBorderColor = Color(0xFF1E3A8A),
            uncheckedThumbColor = Color(0xFF94A3B8),
            uncheckedTrackColor = Color(0xFFE2E8F0),
            uncheckedBorderColor = Color(0xFFE2E8F0),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = value.ifBlank { options.firstOrNull().orEmpty() },
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun Loading(message: String = "데이터 불러오는 중...") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(10.dp))
            Text(message, color = Color(0xFF64748B))
        }
    }
}

@Composable
private fun ErrorBox(msg: String, onRetry: () -> Unit) { 
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { 
        Text(msg, color = Color.Red); Button(onClick = onRetry) { Text("재시도") } 
    } 
}

private fun compactTs(raw: String?): String {
    val normalized = raw?.trim().orEmpty()
    if (normalized.isBlank()) return "-"
    val replaced = normalized.replace("T", " ").replace("Z", "")
    return if (replaced.length > 19) replaced.substring(0, 19) else replaced
}

private fun fmt(v: Double): String = "${"%,d".format(v.roundToLong())}원"
private fun holdingDays(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    val start = runCatching { OffsetDateTime.parse(raw) }.getOrNull() ?: return 0L
    val now = OffsetDateTime.now(start.offset)
    return max(0L, ChronoUnit.DAYS.between(start.toLocalDate(), now.toLocalDate()))
}
private fun favoriteDurationLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "관심 0일"
    val start = runCatching { OffsetDateTime.parse(raw) }.getOrNull() ?: return "관심 0일"
    val now = OffsetDateTime.now(start.offset)
    val totalHours = max(0L, ChronoUnit.HOURS.between(start, now))
    val days = totalHours / 24
    val hours = totalHours % 24
    return if (days <= 0L) "관심 0일 ${hours}시간" else "관심 ${days}일 ${hours}시간"
}
private fun favoriteTimeLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "-"
    val t = runCatching { OffsetDateTime.parse(raw) }.getOrNull() ?: return raw
    return t.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime().toString().replace('T', ' ')
}
private fun alertTypeKo(type: String): String = ""
private fun riskPresetKo(risk: String): String = ""
private fun buildPremarketShare(data: PremarketReportDto, eval: com.example.stock.data.api.EvalMonthlyDto?): String = ""
private fun shareText(context: android.content.Context, text: String, onError: (String) -> Unit) {}

// ══════════════════════════════════════════════════════════════════════
// ██  PreMarket2Screen  ─  단타2 풀 리디자인
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreMarket2Screen() {
    val context = LocalContext.current
    val vm: PremarketViewModel = viewModel(factory = AppViewModelFactory(ServiceLocator.repository(context)))
    val state = vm.reportState.value
    val quotes = vm.quoteState
    val miniCharts = vm.miniChartState
    val repo = ServiceLocator.repository(context)
    val appSettings = repo.getSettings()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = rememberFavoritesController(repo, snackbarHostState)
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

    LaunchedEffect(Unit) {
        vm.load()
        favorites.refresh()
    }
    val doRefresh = {
        refreshToken += 1
        vm.load(force = true)
        favorites.refresh()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { AppTopBar(title = "단타2 분석기", showRefresh = true, onRefresh = { doRefresh() }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize()) {
            if (state.data != null) {
                PreMarket2Body(
                    data = state.data,
                    quotes = quotes,
                    miniCharts = miniCharts,
                    updatedAt = state.refreshedAt ?: state.data?.generatedAt,
                    daytradeDisplayCount = appSettings.daytradeDisplayCount,
                    source = state.source,
                    refreshToken = refreshToken,
                    refreshLoading = state.loading,
                    onRefresh = doRefresh,
                    snackbarHostState = snackbarHostState,
                    favoriteTickers = favorites.favoriteTickers,
                    onToggleFavorite = { item, desired ->
                        favorites.setFavorite(item = item, sourceTab = "단타2", desiredFavorite = desired)
                    },
                    onOpenChart = { ticker, name ->
                        chartTitle = name
                        chartTicker = ticker
                        chartQuote = quotes[ticker]
                        chartOpen = true
                        chartRange = ChartRange.D1
                        chartCache = emptyMap()
                        chartLoading = true
                        chartError = null
                        chartData = null
                        scope.launch {
                            repo.getChartDaily(ticker, 2)
                                .onSuccess { data -> chartData = data; chartCache = chartCache + (ChartRange.D1 to data) }
                                .onFailure { e -> chartError = e.message ?: "차트 로드 실패" }
                            chartLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text("단타2 데이터를 불러오는 중...", color = Color(0xFF64748B))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(state.error ?: "데이터를 불러오지 못했습니다.", color = Color.Red)
                    Button(onClick = vm::load, modifier = Modifier.padding(top = 16.dp)) { Text("새로고침") }
                }
            }
        }
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
                        chartData = cached; chartLoading = false; chartError = null
                    } ?: run {
                        chartLoading = true; chartError = null; chartData = null
                        scope.launch {
                            val reqDays = if (next == ChartRange.D1) 2 else next.days
                            repo.getChartDaily(chartTicker, reqDays)
                                .onSuccess { d -> chartData = d; chartCache = chartCache + (next to d) }
                                .onFailure { e -> chartError = e.message ?: "차트 로드 실패" }
                            chartLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(max = sheetMaxHeight).padding(16.dp),
            )
        }
    }
}

@Composable
private fun PreMarket2Body(
    data: PremarketReportDto,
    quotes: Map<String, RealtimeQuoteItemDto>,
    miniCharts: Map<String, List<ChartPointDto>>,
    updatedAt: String?,
    daytradeDisplayCount: Int,
    source: UiSource,
    refreshToken: Int,
    refreshLoading: Boolean,
    onRefresh: () -> Unit,
    snackbarHostState: SnackbarHostState,
    favoriteTickers: Set<String>,
    onToggleFavorite: (CommonReportItemUi, Boolean) -> Unit,
    onOpenChart: (String, String) -> Unit,
    modifier: Modifier,
) {
    var sortId by remember { mutableStateOf(SortOptions.DEFAULT) }
    var query by remember { mutableStateOf("") }
    var selectedThemeTag by remember { mutableStateOf("") }

    val topItems = data.daytradeTop.orEmpty()
    val watchItems = data.daytradeWatch.orEmpty()
    val dayItems = topItems + watchItems
    val gateOn = data.daytradeGate?.on == true
    val topTickerSet = remember(topItems) {
        topItems.mapNotNull { it.ticker?.trim() }.filter { it.isNotBlank() }.toSet()
    }

    val themeCounts = remember(dayItems) {
        val counts = mutableMapOf<String, Int>()
        for (it in dayItems) {
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
            add(com.example.stock.ui.common.SelectOptionUi("", "전체"))
            themeCounts.forEach { e ->
                add(com.example.stock.ui.common.SelectOptionUi(e.key, "${e.key} (${e.value})"))
            }
        }
    }
    val filteredDayItems = remember(dayItems, selectedThemeTag) {
        if (selectedThemeTag.isBlank()) dayItems else dayItems.filter { it.tags.orEmpty().any { t -> t == selectedThemeTag } }
    }

    val rankedDayItems = filteredDayItems
        .map { item ->
            val tickerKey = item.ticker?.trim().orEmpty()
            val quote = quotes[tickerKey]
            val tags = item.tags.orEmpty().filter { it.isNotBlank() }
            val aiSignalScore = resolveAiSignalSortScore(
                thesis = item.thesis,
                quote = quote,
                miniPoints = miniCharts[tickerKey],
            )
            val action = resolveDaytradeRealtimeAction(item = item, quote = quote, gateOn = gateOn)
            RankedDaytradeItem(item = item, quote = quote, tags = tags, aiSignalScore = aiSignalScore, action = action)
        }
        .sortedWith(
            compareBy<RankedDaytradeItem> { it.action.priority }
                .thenBy { if (topTickerSet.contains(it.item.ticker?.trim().orEmpty())) 0 else 1 }
                .thenByDescending { it.aiSignalScore ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.quote?.chgPct ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.item.name ?: "" }
        )

    val readyCount = rankedDayItems.count { it.action.label.startsWith("진입 가능") || it.action.label.startsWith("조건부 진입") }
    val waitingCount = rankedDayItems.count { it.action.label.startsWith("진입 대기") || it.action.label.startsWith("조건부 대기") }
    val invalidCount = rankedDayItems.count { it.action.label.startsWith("무효") }
    val targetHitCount = rankedDayItems.count { it.action.label.startsWith("목표 도달") }

    // 단타2: 대시보드 요약을 statusMessage에 포함
    val regimeLabel = when (data.regime?.mode) {
        "LOW_VOL_UP" -> "저변동 상승"
        "HIGH_VOL_UP" -> "고변동 상승"
        "LOW_VOL_FLAT" -> "저변동 횡보"
        "HIGH_VOL_DOWN" -> "고변동 하락"
        else -> "분석중"
    }
    val tempLabel = data.marketTemperature?.let { temp ->
        val label = when (temp.label) {
            null -> ""
            else -> temp.label
        }
        val score = temp.score ?: 5
        "$label($score/10)"
    } ?: ""

    val dashboardSummary = buildString {
        append(if (gateOn) "게이트 ON" else "게이트 OFF")
        append(" · $regimeLabel")
        if (tempLabel.isNotBlank()) append(" · $tempLabel")
    }
    val statusMessage = listOfNotNull(
        dashboardSummary,
        "진입 $readyCount · 대기 $waitingCount" +
            (if (targetHitCount > 0) " · 목표 $targetHitCount" else "") +
            (if (invalidCount > 0) " · 무효 $invalidCount" else ""),
    ).joinToString(" | ")

    // 단타2: 카드 UI에 확장 정보 포함
    val uiItems = rankedDayItems.map { ranked ->
        val item = ranked.item
        val tags = ranked.tags
        val fallbackTheme = item.themeId?.let { "테마 ${it + 1}" }
        val tagLine = when {
            tags.isNotEmpty() -> "테마: " + tags.take(3).joinToString(" · ")
            !fallbackTheme.isNullOrBlank() -> "테마: $fallbackTheme"
            else -> null
        }

        // 단타2 확장: 상태 태그 + 진입 거리 + 예상R
        val actionLabel = ranked.action.label
        val borderColor = when {
            actionLabel.startsWith("진입 가능") || actionLabel.startsWith("조건부 진입") -> "#0EBE93"
            actionLabel.startsWith("진입 대기") || actionLabel.startsWith("조건부 대기") -> "#F59E0B"
            actionLabel.startsWith("목표 도달") -> "#3B82F6"
            else -> "#94A3B8"
        }

        val distPct = item.distanceToEntryPct
        val expR = item.expectedR
        // 실시간 거리 계산 (시세가 있으면 실시간, 없으면 서버 제공 값 사용)
        val realtimeDistPct = if (ranked.quote != null && (ranked.quote.price ?: 0.0) > 0.0 && (item.triggerBuy ?: 0.0) > 0.0) {
            ((item.triggerBuy!! - ranked.quote.price!!) / ranked.quote.price!! * 100.0)
        } else {
            distPct
        }
        val distLabel = realtimeDistPct?.let { "진입까지 ${"%+.1f".format(it)}%" }
        val rLabel = expR?.let { "R ${"%,.1f".format(it)}" }
        val extraInfo = listOfNotNull(distLabel, rLabel, actionLabel).joinToString(" · ")

        CommonReportItemUi(
            ticker = item.ticker,
            name = item.name,
            market = item.market,
            title = "${item.name} (${item.ticker})",
            quote = ranked.quote,
            miniPoints = miniCharts[item.ticker],
            metrics = listOf(
                MetricUi("진입", item.triggerBuy ?: 0.0),
                MetricUi("목표", item.target1 ?: 0.0),
                MetricUi("손절", item.stopLoss ?: 0.0),
            ),
            extraLines = listOfNotNull(tagLine, extraInfo),
            thesis = item.thesis,
            sortPrice = ranked.quote?.price,
            sortChangePct = ranked.quote?.chgPct,
            sortName = item.name,
            sortAiSignal = ranked.aiSignalScore,
            badgeLabel = "단타2",
            displayReturnPct = ranked.quote?.chgPct,
            eventTags = tags.take(5),
            statusTag = actionLabel,
        )
    }

    CommonReportList(
        source = source,
        statusMessage = statusMessage,
        updatedAt = updatedAt ?: data.generatedAt,
        header = when {
            !gateOn -> "단타2 · 관찰 모드"
            topItems.isNotEmpty() -> "단타2 · 추천 모드"
            else -> "단타2 · 관찰 모드"
        },
        glossaryDialogTitle = "단타2 용어 설명집",
        glossaryItems = listOf(
            GlossaryItem("진입", "트리거 매수가 — 이 가격 이상 도달 시 매수 신호"),
            GlossaryItem("목표", "목표 매도가 — 수익 실현 기준"),
            GlossaryItem("손절", "손절가 — 하락 시 손실 제한 기준"),
            GlossaryItem("R값", "리스크 대비 수익 배수. R 2.0 = 손절 위험의 2배 수익"),
            GlossaryItem("진입 거리", "현재가에서 진입가까지 남은 거리(%). +면 아직 미도달"),
            GlossaryItem("게이트", "시장 전체 단타 수익성 지표. ON=적극 매매, OFF=보수 관망"),
            GlossaryItem("시장 체제", "변동성+방향 기반 시장 분류 (상승/하락/횡보)"),
            GlossaryItem("시장 온도", "1~10 스케일. 높을수록 과열, 낮을수록 공포"),
        ),
        items = uiItems,
        emptyText = if (gateOn) "추천 종목이 없습니다." else "관찰 종목이 없습니다.",
        initialDisplayCount = daytradeDisplayCount,
        refreshToken = refreshToken,
        refreshLoading = refreshLoading,
        onRefresh = onRefresh,
        snackbarHostState = snackbarHostState,
        receivedCount = dayItems.size,
        query = query,
        onQueryChange = { query = it },
        onItemClick = { item -> onOpenChart(item.ticker.orEmpty(), item.name.orEmpty()) },
        showRiskRules = true,
        riskRules = data.hardRules.orEmpty(),
        selectedSortId = sortId,
        onSortChange = { sortId = it },
        favoriteTickers = favoriteTickers,
        onToggleFavorite = onToggleFavorite,
        filtersContent = {
            val sortOptions = listOf(
                com.example.stock.ui.common.SelectOptionUi(SortOptions.DEFAULT, "기본"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.PRICE_ASC, "가격↑"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.PRICE_DESC, "가격↓"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.CHANGE_ASC, "등락↑"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.CHANGE_DESC, "등락↓"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.NAME_ASC, "이름↑"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.NAME_DESC, "이름↓"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.AI_STRONG, "강함"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.AI_BUY, "매수"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.AI_WATCH, "관망"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.AI_CAUTION, "주의"),
                com.example.stock.ui.common.SelectOptionUi(SortOptions.AI_AVOID, "회피"),
            )
            com.example.stock.ui.common.CommonSortThemeBar(
                sortOptions = sortOptions,
                selectedSortId = sortId,
                onSortChange = { sortId = it },
                themeOptions = if (themeCounts.isNotEmpty()) themeOptions else null,
                selectedThemeId = selectedThemeTag,
                onThemeChange = { selectedThemeTag = it },
            )
        },
        modifier = modifier,
    )
}
