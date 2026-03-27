package com.example.stock.viewmodel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stock.data.api.AlertHistoryItemDto
import com.example.stock.data.api.EodReportDto
import com.example.stock.data.api.EvalMonthlyDto
import com.example.stock.data.api.PapersSummaryDto
import com.example.stock.data.api.PremarketReportDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.api.ResponseStatusDto
import com.example.stock.data.api.StrategySettingsDto
import com.example.stock.data.api.ChartPointDto
import com.example.stock.data.api.MoversResponseDto
import com.example.stock.data.api.Movers2ResponseDto
import com.example.stock.data.api.SupplyResponseDto
import com.example.stock.data.api.UsInsiderResponseDto
import com.example.stock.data.api.NewsThemesResponseDto
import com.example.stock.data.api.NewsClustersResponseDto
import com.example.stock.data.api.NewsStocksResponseDto
import com.example.stock.data.api.NewsArticlesResponseDto
import com.example.stock.data.api.NewsClusterResponseDto
import com.example.stock.data.api.AutoTradeSettingsDto
import com.example.stock.data.api.AutoTradeSettingsResponseDto
import com.example.stock.data.api.AutoTradeSymbolRuleUpsertDto
import com.example.stock.data.api.AutoTradeSymbolRulesResponseDto
import com.example.stock.data.api.AutoTradeBrokerCredentialDto
import com.example.stock.data.api.AutoTradeBrokerCredentialUpdateDto
import com.example.stock.data.api.AutoTradeCandidatesResponseDto
import com.example.stock.data.api.AutoTradeOrdersResponseDto
import com.example.stock.data.api.AutoTradePerformanceResponseDto
import com.example.stock.data.api.AutoTradeRunResponseDto
import com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto
import com.example.stock.data.api.AutoTradeBootstrapResponseDto
import com.example.stock.data.api.AutoTradeReservationsResponseDto
import com.example.stock.data.api.AutoTradeReservationActionResponseDto
import com.example.stock.data.api.AutoTradeReservationPendingCancelResponseDto
import com.example.stock.data.api.AutoTradeOrderCancelResponseDto
import com.example.stock.data.api.AutoTradePendingCancelResponseDto
import com.example.stock.data.api.AutoTradeReentryBlocksResponseDto
import com.example.stock.data.api.AutoTradeReentryReleaseResponseDto
import com.example.stock.data.api.StockSearchResponseDto
import com.example.stock.data.repository.AppSettings
import com.example.stock.data.repository.StockRepository
import com.example.stock.data.repository.UiSource
import com.example.stock.data.repository.humanizeApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import java.io.IOException
import java.net.SocketTimeoutException

data class UiState<T>(
    val loading: Boolean = false,
    val data: T? = null,
    val error: String? = null,
    val fromCache: Boolean = false,
    val source: UiSource = UiSource.LIVE,
    val refreshedAt: String? = null,
)

private fun Throwable?.isRetriableNetworkError(): Boolean = this is SocketTimeoutException || this is IOException

private fun Throwable?.toFriendlyNetworkMessage(defaultMessage: String): String = when (this) {
    null -> defaultMessage
    is SocketTimeoutException -> "서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해주세요."
    is IOException -> "네트워크 연결이 불안정합니다. 잠시 후 다시 시도해주세요."
    else -> humanizeApiError(this).takeIf { it.isNotBlank() } ?: defaultMessage
}

private suspend fun <T> retryNetworkResult(
    attempts: Int = 3,
    initialDelayMs: Long = 500L,
    request: suspend () -> Result<T>,
): Result<T> {
    var result = request()
    var waitMs = initialDelayMs
    var attempt = 1
    while (attempt < attempts && result.isFailure && result.exceptionOrNull().isRetriableNetworkError()) {
        delay(waitMs)
        waitMs *= 2
        attempt += 1
        result = request()
    }
    return result
}

class PremarketViewModel(private val repository: StockRepository) : ViewModel() {
    val reportState = mutableStateOf(UiState<PremarketReportDto>(loading = true))
    val evalState = mutableStateOf(UiState<EvalMonthlyDto>())
    val quoteState = mutableStateMapOf<String, RealtimeQuoteItemDto>()
    val miniChartState = mutableStateMapOf<String, List<ChartPointDto>>()
    private val miniChartFetchedAt = mutableMapOf<String, Long>()
    private val MINI_CHART_TTL_MS = 5 * 60 * 1000L
    private var quoteJob: Job? = null
    private var pollJob: Job? = null
    private var fallbackRecoveryJob: Job? = null
    private var forceRegenOnce: Boolean = false
    private var fallbackRecoveryDoneForDate: String? = null
    private val quoteMissCount = mutableMapOf<String, Int>()
    private var loadJob: Job? = null

    fun load(force: Boolean = false) {
        loadJob?.cancel()
        quoteJob?.cancel()
        pollJob?.cancel()
        fallbackRecoveryJob?.cancel()
        val seoul = TimeZone.of("Asia/Seoul")
        val today = Clock.System.todayIn(seoul)
        val todayStr = today.toString()
        val yesterdayStr = today.minus(1, DateTimeUnit.DAY).toString()

        reportState.value = reportState.value.copy(loading = true, error = null)

        loadJob = viewModelScope.launch {
            // 1. 즉시 캐시 데이터 표시 (오늘 혹은 가장 최근 것)
            val fast = repository.getPremarketFast(todayStr)
            reportState.value = UiState(
                data = fast.data,
                fromCache = fast.source == UiSource.CACHE,
                source = fast.source,
                loading = false,
                refreshedAt = Clock.System.now().toString()
            )

            // 2. 네트워크 요청 (오늘 데이터 시도)
            repository.getPremarket(todayStr, force = force)
                .onSuccess { wrapped ->
                    updateStateWithData(wrapped, date = todayStr)
                }
                .onFailure { error ->
                    // 오늘 데이터 실패 시 어제 데이터라도 시도
                    repository.getPremarket(yesterdayStr, force = force)
                        .onSuccess { wrapped ->
                            updateStateWithData(wrapped, date = yesterdayStr)
                        }
                        .onFailure {
                            reportState.value = UiState(
                                data = reportState.value.data, // 기존에 보여주던 캐시라도 유지
                                error = "데이터를 불러올 수 없습니다: ${error.message}",
                                loading = false
                            )
                        }
                }

            // 3. 성과 지표 로드
            repository.getEvalMonthly(todayStr)
                .onSuccess { evalState.value = UiState(data = it) }
        }
    }

    private fun updateStateWithData(
        wrapped: com.example.stock.data.repository.DataWithSource<PremarketReportDto>,
        date: String,
    ) {
        val isFallbackSource = wrapped.source == UiSource.FALLBACK ||
            wrapped.data.status?.source?.equals("FALLBACK", ignoreCase = true) == true
        if (!isFallbackSource) {
            fallbackRecoveryDoneForDate = null
        }
        reportState.value = UiState(
            data = wrapped.data,
            fromCache = wrapped.source == UiSource.CACHE,
            source = wrapped.source,
            loading = false,
            refreshedAt = Clock.System.now().toString()
        )
        maybeUsePreviousDaySnapshot(date = date, wrapped = wrapped)
        loadMiniCharts(wrapped.data)
        // Quote polling is independent of report cache; users expect live quotes even when report payload is cached.
        if (isFallbackSource) fetchQuotesOnce(wrapped.data) else startQuotePolling(wrapped.data)
        maybeStartPollingIfQueued(date, wrapped)
        maybeForceRegenerateIfTooSmall(date, wrapped)
    }

    private fun maybeForceRegenerateIfTooSmall(
        date: String,
        wrapped: com.example.stock.data.repository.DataWithSource<PremarketReportDto>,
    ) {
        // If user asked for a large list (e.g. 60) but we got a small cached payload (e.g. 16),
        // trigger a one-time force regeneration to refresh stale caches after server-side logic changes.
        if (forceRegenOnce) return
        if (wrapped.source != UiSource.CACHE) return

        val desired = repository.getSettings().daytradeDisplayCount
        if (desired <= 0) return
        val data = wrapped.data
        val received = (data.daytradeTop.orEmpty() + data.daytradeWatch.orEmpty()).size
        if (received in 1 until desired) {
            forceRegenOnce = true
            viewModelScope.launch {
                repository.getPremarket(date, force = true)
                    .onSuccess { next -> updateStateWithData(next, date = date) }
            }
        }
    }

    private fun maybeUsePreviousDaySnapshot(
        date: String,
        wrapped: com.example.stock.data.repository.DataWithSource<PremarketReportDto>,
    ) {
        val isFallbackSource = wrapped.source == UiSource.FALLBACK ||
            wrapped.data.status?.source?.equals("FALLBACK", ignoreCase = true) == true
        if (!isFallbackSource) return
        if (!isReportItemsEmpty(wrapped.data)) return
        val today = Clock.System.todayIn(TimeZone.of("Asia/Seoul")).toString()
        if (date != today) return
        if (fallbackRecoveryDoneForDate == date) return
        fallbackRecoveryDoneForDate = date
        fallbackRecoveryJob?.cancel()
        fallbackRecoveryJob = viewModelScope.launch {
            val yesterday = Clock.System.todayIn(TimeZone.of("Asia/Seoul"))
                .minus(1, DateTimeUnit.DAY)
                .toString()
            repository.getPremarket(yesterday, force = false)
                .onSuccess { prev ->
                    if (!isReportItemsEmpty(prev.data) && prev.source != UiSource.FALLBACK) {
                        updateStateWithData(prev, date = yesterday)
                    }
                }
        }
    }

    private fun isReportItemsEmpty(report: PremarketReportDto): Boolean {
        val dayCount = report.daytradeTop.orEmpty().size + report.daytradeWatch.orEmpty().size + report.daytradePrimary.orEmpty().size
        val longCount = report.longterm.orEmpty().size
        return (dayCount + longCount) <= 0
    }

    private fun loadMiniCharts(report: PremarketReportDto?) {
        if (report == null) return
        val primary = report.daytradePrimary.orEmpty()
        val top = report.daytradeTop.orEmpty()
        val watch = report.daytradeWatch.orEmpty()
        val longterm = report.longterm.orEmpty()
        val daytrade = (primary.ifEmpty { top } + watch)
        val daytradeTickers = daytrade.map { it.ticker.orEmpty() }.distinct().filter { it.isNotBlank() }
        val longtermTickers = longterm.map { it.ticker.orEmpty() }.distinct().filter { it.isNotBlank() }
        val settings = repository.getSettings()
        val daytradePrefetch = maxOf(12, minOf(settings.daytradeDisplayCount, 60))
        val longtermPrefetch = maxOf(8, minOf(settings.longtermDisplayCount, 20))
        val target = (daytradeTickers.take(daytradePrefetch) + longtermTickers.take(longtermPrefetch)).distinct()
        val now = System.currentTimeMillis()
        target.forEach { ticker ->
            val cached = miniChartState[ticker]
            val fetchedAt = miniChartFetchedAt[ticker] ?: 0L
            if ((cached?.size ?: 0) >= 2 && (now - fetchedAt) < MINI_CHART_TTL_MS) return@forEach
            viewModelScope.launch {
                repository.getChartDaily(ticker, 7)
                    .onSuccess { dto ->
                        val points = dto.points.orEmpty().takeLast(7)
                        if (points.size >= 2) { miniChartState[ticker] = points; miniChartFetchedAt[ticker] = System.currentTimeMillis() }
                        else miniChartState.remove(ticker)
                    }
            }
        }
    }

    fun ensureMiniCharts(tickers: List<String>) {
        val now = System.currentTimeMillis()
        val target = tickers.distinct().filter { it.isNotBlank() }.take(40)
        target.forEach { ticker ->
            val cached = miniChartState[ticker]
            val fetchedAt = miniChartFetchedAt[ticker] ?: 0L
            if ((cached?.size ?: 0) >= 2 && (now - fetchedAt) < MINI_CHART_TTL_MS) return@forEach
            viewModelScope.launch {
                repository.getChartDaily(ticker, 7)
                    .onSuccess { dto ->
                        val points = dto.points.orEmpty().takeLast(7)
                        if (points.size >= 2) { miniChartState[ticker] = points; miniChartFetchedAt[ticker] = System.currentTimeMillis() }
                        else miniChartState.remove(ticker)
                    }
            }
        }
    }

    private fun startQuotePolling(report: PremarketReportDto?) {
        if (report == null) return
        val primary = report.daytradePrimary.orEmpty()
        val top = report.daytradeTop.orEmpty()
        val watch = report.daytradeWatch.orEmpty()
        val longterm = report.longterm.orEmpty()

        val daytrade = (primary.ifEmpty { top } + watch)
        val dayTickers = daytrade.map { it.ticker.orEmpty() }.distinct().filter { it.isNotBlank() }
        val longTickers = longterm.map { it.ticker.orEmpty() }.distinct().filter { it.isNotBlank() }
        val settings = repository.getSettings()
        val dayLimit = maxOf(20, minOf(settings.daytradeDisplayCount, 120))
        val longLimit = maxOf(10, minOf(settings.longtermDisplayCount, 40))
        val tickers = (dayTickers.take(dayLimit) + longTickers.take(longLimit)).distinct()
        val refreshMs = settings.quoteRefreshSec * 1000L
        
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            while (isActive) {
                if (tickers.isNotEmpty()) {
                    val merged = fetchQuotesChunked(tickers)
                    if (merged.isNotEmpty()) {
                        merged.keys.forEach { quoteMissCount.remove(it) }
                        tickers.filter { it in quoteState && it !in merged }.forEach { key ->
                            val count = (quoteMissCount[key] ?: 0) + 1
                            if (count >= 3) { quoteState.remove(key); quoteMissCount.remove(key) }
                            else quoteMissCount[key] = count
                        }
                        quoteState.putAll(merged)
                    }
                }
                delay(refreshMs)
            }
        }
    }

    private fun fetchQuotesOnce(report: PremarketReportDto?) {
        if (report == null) return
        val primary = report.daytradePrimary.orEmpty()
        val top = report.daytradeTop.orEmpty()
        val watch = report.daytradeWatch.orEmpty()
        val longterm = report.longterm.orEmpty()
        val daytrade = (primary.ifEmpty { top } + watch)
        val dayTickers = daytrade.map { it.ticker.orEmpty() }.distinct().filter { it.isNotBlank() }
        val longTickers = longterm.map { it.ticker.orEmpty() }.distinct().filter { it.isNotBlank() }
        val settings = repository.getSettings()
        val dayLimit = maxOf(20, minOf(settings.daytradeDisplayCount, 120))
        val longLimit = maxOf(10, minOf(settings.longtermDisplayCount, 40))
        val tickers = (dayTickers.take(dayLimit) + longTickers.take(longLimit)).distinct()
        if (tickers.isEmpty()) return
        viewModelScope.launch {
            val merged = fetchQuotesChunked(tickers)
            if (merged.isNotEmpty()) {
                quoteState.keys.filter { key -> key in tickers && key !in merged }.forEach { quoteState.remove(it) }
                quoteState.putAll(merged)
            }
        }
    }

    private suspend fun fetchQuotesChunked(tickers: List<String>): Map<String, RealtimeQuoteItemDto> {
        if (tickers.isEmpty()) return emptyMap()
        val chunks = tickers.distinct().chunked(30)
        return coroutineScope {
            val merged = linkedMapOf<String, RealtimeQuoteItemDto>()
            chunks
                .map { chunk ->
                    async(Dispatchers.IO) {
                        repository.getRealtimeQuotes(chunk, mode = "light").getOrNull().orEmpty()
                    }
                }
                .awaitAll()
                .forEach { part -> merged.putAll(part) }
            merged
        }
    }

    private fun maybeStartPollingIfQueued(
        date: String,
        wrapped: com.example.stock.data.repository.DataWithSource<PremarketReportDto>,
    ) {
        val status = wrapped.data.status
        val isFallback = wrapped.source == UiSource.FALLBACK ||
            status?.source?.equals("FALLBACK", ignoreCase = true) == true
        val inFlight = isPremarketInFlight(status)
        if (!isFallback || !inFlight) return

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // Short, bounded polling loop to pick up the freshly generated cached report.
            // Server-side generation can take a few minutes on cold starts.
            val delaysMs = listOf(
                800L, 1200L, 1800L, 2500L, 3500L, 5000L, 7000L, 10000L, 15000L, 20000L,
                30000L, 30000L, 30000L, 30000L, 30000L, 30000L, 30000L, 30000L, 30000L
            )
            for (d in delaysMs) {
                delay(d)
                val r = repository.getPremarket(date, force = false)
                if (r.isSuccess) {
                    val next = r.getOrNull()!!
                    val nextStatus = next.data.status
                    val nextInFlight = isPremarketInFlight(nextStatus)
                    if (next.source != UiSource.FALLBACK || !nextInFlight) {
                        updateStateWithData(next, date = date)
                        return@launch
                    }
                }
            }
        }
    }

    private fun isPremarketInFlight(status: ResponseStatusDto?): Boolean {
        if (status?.queued == true) return true
        val msg = (status?.message ?: "").replace(" ", "")
        return msg.contains("생성대기") || msg.contains("생성중")
    }

    override fun onCleared() {
        loadJob?.cancel()
        quoteJob?.cancel()
        pollJob?.cancel()
        fallbackRecoveryJob?.cancel()
        super.onCleared()
    }
}

class EodViewModel(private val repository: StockRepository) : ViewModel() {
    val state = mutableStateOf(UiState<EodReportDto>())

    fun load() {
        val today = Clock.System.todayIn(TimeZone.of("Asia/Seoul")).toString()
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.getEod(today)
                .onSuccess { state.value = UiState(data = it, refreshedAt = Clock.System.now().toString()) }
                .onFailure { state.value = UiState(error = it.message ?: "장후 성적표를 불러오지 못했습니다") }
        }
    }
}

class AlertsViewModel(private val repository: StockRepository) : ViewModel() {
    val state = mutableStateOf(UiState<List<AlertHistoryItemDto>>())

    fun load() {
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.fetchAlerts()
                .onSuccess { state.value = UiState(data = it, refreshedAt = Clock.System.now().toString()) }
                .onFailure { state.value = UiState(error = it.message ?: "알림 목록을 불러오지 못했습니다") }
        }
    }
}

class MoversViewModel(private val repository: StockRepository) : ViewModel() {
    val state = mutableStateOf(UiState<MoversResponseDto>())
    val miniChartState = mutableStateMapOf<String, List<ChartPointDto>>()
    private var pollJob: Job? = null
    private val defaultCount = 100
    private val miniChartPending = mutableSetOf<String>()
    private val miniChartLock = Any()
    @Volatile private var appForeground = true
    @Volatile private var screenActive = true
    @Volatile private var lastInteractionAtMs = System.currentTimeMillis()

    fun noteUserInteraction() {
        lastInteractionAtMs = System.currentTimeMillis()
    }

    fun setScreenActive(active: Boolean) {
        screenActive = active
        if (active) noteUserInteraction()
    }

    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
    }

    fun load(mode: String = "chg", period: String = "1d") {
        noteUserInteraction()
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.getMarketMovers(mode = mode, period = period, count = defaultCount)
                .onSuccess { dto ->
                    state.value = UiState(data = dto, loading = false, refreshedAt = Clock.System.now().toString())
                }
                .onFailure { e ->
                    state.value = UiState(error = e.message ?: "급등주를 불러오지 못했습니다", loading = false)
                }
        }
    }

    fun startPolling(mode: String, period: String, intervalSec: Int) {
        val baseSec = intervalSec.coerceIn(8, 30)
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(nextPollDelayMs(baseSec))
                repository.getMarketMovers(mode = mode, period = period, count = defaultCount)
                    .onSuccess { dto ->
                        state.value = UiState(data = dto, loading = false, refreshedAt = Clock.System.now().toString())
                    }
            }
        }
    }

    private fun nextPollDelayMs(baseSec: Int): Long {
        if (!appForeground) return 30_000L
        if (!screenActive) return 15_000L
        val idleMs = System.currentTimeMillis() - lastInteractionAtMs
        return if (idleMs >= 20_000L) 15_000L else (baseSec.coerceIn(8, 10) * 1000L)
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun ensureMiniCharts(tickers: List<String>, days: Int = 7) {
        val targetDays = days.coerceIn(5, 260)
        val target = tickers.distinct().filter { it.isNotBlank() }.take(24)
        val request = mutableListOf<String>()
        synchronized(miniChartLock) {
            target.forEach { ticker ->
                val cached = miniChartState[ticker]
                if ((cached?.size ?: 0) >= 2) return@forEach
                if (miniChartPending.add(ticker)) {
                    request += ticker
                }
            }
        }
        if (request.isEmpty()) return
        viewModelScope.launch {
            try {
                val batch = repository.getChartDailyBatch(request, targetDays).getOrNull().orEmpty()
                request.forEach { ticker ->
                    val dto = batch[ticker] ?: return@forEach
                    val points = dto.points.orEmpty().takeLast(7)
                    if (points.size >= 2) miniChartState[ticker] = points else miniChartState.remove(ticker)
                }
            } finally {
                synchronized(miniChartLock) {
                    request.forEach { miniChartPending.remove(it) }
                }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

class Movers2ViewModel(private val repository: StockRepository) : ViewModel() {
    val state = mutableStateOf(UiState<Movers2ResponseDto>())
    val miniChartState = mutableStateMapOf<String, List<ChartPointDto>>()
    private var pollJob: Job? = null
    private val defaultCount = 100
    private val miniChartPending = mutableSetOf<String>()
    private val miniChartLock = Any()
    @Volatile private var appForeground = true
    @Volatile private var screenActive = true
    @Volatile private var lastInteractionAtMs = System.currentTimeMillis()

    fun noteUserInteraction() {
        lastInteractionAtMs = System.currentTimeMillis()
    }

    fun setScreenActive(active: Boolean) {
        screenActive = active
        if (active) noteUserInteraction()
    }

    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
    }

    fun load(session: String = "regular", direction: String = "up") {
        noteUserInteraction()
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.getMarketMovers2(
                session = session,
                direction = direction,
                count = defaultCount,
                fields = "basic",
            )
                .onSuccess { dto ->
                    state.value = UiState(data = dto, loading = false, refreshedAt = Clock.System.now().toString())
                }
                .onFailure { e ->
                    state.value = UiState(error = e.message ?: "급등을 불러오지 못했습니다", loading = false)
                }
        }
    }

    fun startPolling(session: String, direction: String, intervalSec: Int) {
        val baseSec = intervalSec.coerceIn(8, 30)
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(nextPollDelayMs(baseSec))
                repository.getMarketMovers2(
                    session = session,
                    direction = direction,
                    count = defaultCount,
                    fields = "basic",
                )
                    .onSuccess { dto ->
                        state.value = UiState(data = dto, loading = false, refreshedAt = Clock.System.now().toString())
                    }
            }
        }
    }

    private fun nextPollDelayMs(baseSec: Int): Long {
        if (!appForeground) return 30_000L
        if (!screenActive) return 15_000L
        val idleMs = System.currentTimeMillis() - lastInteractionAtMs
        return if (idleMs >= 20_000L) 15_000L else (baseSec.coerceIn(8, 10) * 1000L)
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun ensureMiniCharts(tickers: List<String>, days: Int = 7) {
        val targetDays = days.coerceIn(5, 260)
        val target = tickers.distinct().filter { it.isNotBlank() }.take(24)
        val request = mutableListOf<String>()
        synchronized(miniChartLock) {
            target.forEach { ticker ->
                val cached = miniChartState[ticker]
                if ((cached?.size ?: 0) >= 2) return@forEach
                if (miniChartPending.add(ticker)) {
                    request += ticker
                }
            }
        }
        if (request.isEmpty()) return
        viewModelScope.launch {
            try {
                val batch = repository.getChartDailyBatch(request, targetDays).getOrNull().orEmpty()
                request.forEach { ticker ->
                    val dto = batch[ticker] ?: return@forEach
                    val points = dto.points.orEmpty().takeLast(7)
                    if (points.size >= 2) miniChartState[ticker] = points else miniChartState.remove(ticker)
                }
            } finally {
                synchronized(miniChartLock) {
                    request.forEach { miniChartPending.remove(it) }
                }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

class SupplyViewModel(private val repository: StockRepository) : ViewModel() {
    val state = mutableStateOf(UiState<SupplyResponseDto>())
    val miniChartState = mutableStateMapOf<String, List<ChartPointDto>>()
    private var pollJob: Job? = null
    private val defaultCount = 60
    private val miniChartPending = mutableSetOf<String>()
    private val miniChartLock = Any()
    @Volatile private var appForeground = true
    @Volatile private var screenActive = true
    @Volatile private var lastInteractionAtMs = System.currentTimeMillis()

    private fun mapSource(raw: String?): UiSource = when ((raw ?: "").uppercase()) {
        "CACHE" -> UiSource.CACHE
        "FALLBACK" -> UiSource.FALLBACK
        else -> UiSource.LIVE
    }

    fun noteUserInteraction() {
        lastInteractionAtMs = System.currentTimeMillis()
    }

    fun setScreenActive(active: Boolean) {
        screenActive = active
        if (active) noteUserInteraction()
    }

    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
    }

    fun load() {
        noteUserInteraction()
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.getMarketSupply(count = defaultCount)
                .onSuccess { dto ->
                    state.value = UiState(
                        data = dto,
                        loading = false,
                        source = mapSource(dto.source),
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    state.value = UiState(error = e.message ?: "수급 데이터를 불러오지 못했습니다", loading = false)
                }
        }
    }

    fun startPolling(intervalSec: Int) {
        // 수급 탭은 서버 계산 비용이 큰 편이라 과도한 폴링으로 타임아웃이 누적되지 않도록 최소 간격을 높인다.
        val baseSec = intervalSec.coerceIn(12, 45)
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(nextPollDelayMs(baseSec))
                repository.getMarketSupply(count = defaultCount)
                    .onSuccess { dto ->
                        state.value = UiState(
                            data = dto,
                            loading = false,
                            source = mapSource(dto.source),
                            refreshedAt = Clock.System.now().toString(),
                        )
                    }
            }
        }
    }

    private fun nextPollDelayMs(baseSec: Int): Long {
        if (!appForeground) return 40_000L
        if (!screenActive) return 25_000L
        val idleMs = System.currentTimeMillis() - lastInteractionAtMs
        return if (idleMs >= 25_000L) 20_000L else (baseSec.coerceIn(12, 18) * 1000L)
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun ensureMiniCharts(tickers: List<String>, days: Int = 7) {
        val targetDays = days.coerceIn(5, 260)
        val target = tickers.distinct().filter { it.isNotBlank() }.take(24)
        val request = mutableListOf<String>()
        synchronized(miniChartLock) {
            target.forEach { ticker ->
                val cached = miniChartState[ticker]
                if ((cached?.size ?: 0) >= 2) return@forEach
                if (miniChartPending.add(ticker)) {
                    request += ticker
                }
            }
        }
        if (request.isEmpty()) return
        viewModelScope.launch {
            try {
                val batch = repository.getChartDailyBatch(request, targetDays).getOrNull().orEmpty()
                request.forEach { ticker ->
                    val dto = batch[ticker] ?: return@forEach
                    val points = dto.points.orEmpty().takeLast(7)
                    if (points.size >= 2) miniChartState[ticker] = points else miniChartState.remove(ticker)
                }
            } finally {
                synchronized(miniChartLock) {
                    request.forEach { miniChartPending.remove(it) }
                }
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

class UsInsiderViewModel(private val repository: StockRepository) : ViewModel() {
    val state = mutableStateOf(UiState<UsInsiderResponseDto>())

    fun load(
        targetCount: Int = 10,
        tradingDays: Int = 10,
        expandDays: Int = 20,
        maxCandidates: Int = 120,
        transactionCodes: String = "ALL",
        force: Boolean = false,
    ) {
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.getUsInsiders(
                targetCount = targetCount,
                tradingDays = tradingDays,
                expandDays = expandDays,
                maxCandidates = maxCandidates,
                transactionCodes = transactionCodes,
                force = force,
            )
                .onSuccess { dto ->
                    if ((dto.returnedCount ?: 0) > 0 || tradingDays >= 30) {
                        state.value = UiState(data = dto, loading = false, refreshedAt = Clock.System.now().toString())
                    } else {
                        val retryTradingDays = when {
                            tradingDays < 20 -> 20
                            tradingDays < 30 -> 30
                            else -> tradingDays
                        }
                        repository.getUsInsiders(
                            targetCount = targetCount,
                            tradingDays = retryTradingDays,
                            expandDays = retryTradingDays,
                            maxCandidates = maxOf(120, maxCandidates),
                            transactionCodes = transactionCodes,
                            force = force,
                        )
                            .onSuccess { retry ->
                                state.value = UiState(data = retry, loading = false, refreshedAt = Clock.System.now().toString())
                            }
                            .onFailure { e ->
                                state.value = UiState(error = e.message ?: "미장 내부자 매수를 불러오지 못했습니다", loading = false)
                            }
                    }
                }
                .onFailure { e ->
                    state.value = UiState(error = e.message ?: "미장 내부자 매수를 불러오지 못했습니다", loading = false)
                }
        }
    }
}

class PapersViewModel(private val repository: StockRepository) : ViewModel() {
    val summaryState = mutableStateOf(UiState<PapersSummaryDto>())
    val reportState = mutableStateOf(UiState<PremarketReportDto>())
    val evalState = mutableStateOf(UiState<EvalMonthlyDto>())
    val quoteState = mutableStateMapOf<String, RealtimeQuoteItemDto>()
    val miniChartState = mutableStateMapOf<String, List<ChartPointDto>>()
    private var quoteJob: Job? = null

    fun load(force: Boolean = false) {
        val today = Clock.System.todayIn(TimeZone.of("Asia/Seoul")).toString()
        summaryState.value = summaryState.value.copy(loading = true, error = null)
        reportState.value = reportState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val fast = repository.getPremarketFast(today)
            reportState.value = UiState(
                data = fast.data,
                fromCache = fast.source == UiSource.CACHE,
                source = fast.source,
                loading = false,
                refreshedAt = Clock.System.now().toString()
            )
            
            repository.getPapersSummary()
                .onSuccess { summaryState.value = UiState(data = it) }
            
            repository.getPremarket(today, force = force)
                .onSuccess { wrapped ->
                    reportState.value = UiState(
                        data = wrapped.data,
                        fromCache = wrapped.source == UiSource.CACHE,
                        source = wrapped.source,
                        loading = false,
                        refreshedAt = Clock.System.now().toString()
                    )
                    loadMiniCharts(wrapped.data)
                    if (wrapped.source == UiSource.FALLBACK) fetchQuotesOnce(wrapped.data) else startQuotePolling(wrapped.data)
                }
                .onFailure { 
                    reportState.value = reportState.value.copy(loading = false, error = "추천 데이터를 가져오지 못했습니다")
                }
        }
    }

    private fun startQuotePolling(report: PremarketReportDto?) {
        if (report == null) return
        val primary = report.daytradePrimary.orEmpty()
        val top = report.daytradeTop.orEmpty()
        val watch = report.daytradeWatch.orEmpty()
        val longterm = report.longterm.orEmpty()

        val daytrade = (primary.ifEmpty { top } + watch)
        val ordered = (daytrade.map { it.ticker.orEmpty() } + longterm.map { it.ticker.orEmpty() }).distinct()
        val settings = repository.getSettings()
        val tickers = ordered.take(12)
        val refreshMs = settings.quoteRefreshSec * 1000L
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            while (isActive) {
                repository.getRealtimeQuotes(tickers)
                    .onSuccess { newMap ->
                        quoteState.keys.minus(newMap.keys).forEach { quoteState.remove(it) }
                        quoteState.putAll(newMap)
                    }
                delay(refreshMs)
            }
        }
    }

    private fun fetchQuotesOnce(report: PremarketReportDto?) {
        if (report == null) return
        val primary = report.daytradePrimary.orEmpty()
        val top = report.daytradeTop.orEmpty()
        val watch = report.daytradeWatch.orEmpty()
        val longterm = report.longterm.orEmpty()
        val daytrade = (primary.ifEmpty { top } + watch)
        val ordered = (daytrade.map { it.ticker.orEmpty() } + longterm.map { it.ticker.orEmpty() }).distinct()
        val tickers = ordered.take(12)
        if (tickers.isEmpty()) return
        viewModelScope.launch {
            repository.getRealtimeQuotes(tickers)
                .onSuccess { newMap ->
                    quoteState.keys.minus(newMap.keys).forEach { quoteState.remove(it) }
                    quoteState.putAll(newMap)
                }
        }
    }

    private fun loadMiniCharts(report: PremarketReportDto?) {
        if (report == null) return
        val primary = report.daytradePrimary.orEmpty()
        val top = report.daytradeTop.orEmpty()
        val watch = report.daytradeWatch.orEmpty()
        val longterm = report.longterm.orEmpty()
        val daytrade = (primary.ifEmpty { top } + watch)
        val ordered = (daytrade.map { it.ticker.orEmpty() } + longterm.map { it.ticker.orEmpty() })
            .distinct()
            .filter { it.isNotBlank() }
        val target = ordered.take(12)
        target.forEach { ticker ->
            val cached = miniChartState[ticker]
            if ((cached?.size ?: 0) >= 2) return@forEach
            viewModelScope.launch {
                repository.getChartDaily(ticker, 7)
                    .onSuccess { dto ->
                        val points = dto.points.orEmpty().takeLast(7)
                        if (points.size >= 2) miniChartState[ticker] = points else miniChartState.remove(ticker)
                    }
            }
        }
    }

    override fun onCleared() {
        quoteJob?.cancel()
        super.onCleared()
    }
}

class NewsViewModel(private val repository: StockRepository) : ViewModel() {
    val themesState = mutableStateOf(UiState<NewsThemesResponseDto>())
    val clustersState = mutableStateOf(UiState<NewsClustersResponseDto>())
    val stocksState = mutableStateOf(UiState<NewsStocksResponseDto>())
    val articlesState = mutableStateOf(UiState<NewsArticlesResponseDto>())
    val clusterState = mutableStateOf(UiState<NewsClusterResponseDto>())
    private var themesJob: Job? = null
    private var clustersJob: Job? = null
    private var stocksJob: Job? = null
    private var articlesJob: Job? = null
    private var clusterJob: Job? = null
    private var themesReqId: Int = 0
    private var clustersReqId: Int = 0
    private var stocksReqId: Int = 0
    private var articlesReqId: Int = 0
    private var clusterReqId: Int = 0

    private fun toSource(meta: com.example.stock.data.api.NewsMetaDto?): UiSource {
        return when (meta?.source?.uppercase()) {
            "CACHE" -> UiSource.CACHE
            "FALLBACK" -> UiSource.FALLBACK
            else -> UiSource.LIVE
        }
    }

    fun loadThemes(
        window: String = "24h",
        ymd: Int? = null,
        source: String = "all",
        eventType: String? = null,
        hideRisk: Boolean = false,
    ) {
        themesState.value = themesState.value.copy(loading = true, error = null)
        val requestId = ++themesReqId
        themesJob?.cancel()
        themesJob = viewModelScope.launch {
            repository.getNewsThemes(
                window = window,
                ymd = ymd,
                source = source,
                eventType = eventType,
                hideRisk = hideRisk,
            )
                .onSuccess { dto ->
                    if (requestId != themesReqId) return@onSuccess
                    themesState.value = UiState(
                        data = dto,
                        source = toSource(dto.meta),
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    if (requestId != themesReqId) return@onFailure
                    themesState.value = themesState.value.copy(
                        loading = false,
                        error = e.message ?: "뉴스(테마)를 불러오지 못했습니다",
                    )
                }
        }
    }

    fun loadClusters(
        window: String = "24h",
        ymd: Int? = null,
        themeKey: String? = null,
        source: String = "all",
        eventType: String? = null,
        hideRisk: Boolean = false,
        sort: String = "hot",
        limit: Int = 200,
    ) {
        clustersState.value = clustersState.value.copy(loading = true, error = null)
        val requestId = ++clustersReqId
        clustersJob?.cancel()
        clustersJob = viewModelScope.launch {
            repository.getNewsClusters(
                window = window,
                ymd = ymd,
                themeKey = themeKey,
                source = source,
                eventType = eventType,
                hideRisk = hideRisk,
                sort = sort,
                limit = limit,
            )
                .onSuccess { dto ->
                    if (requestId != clustersReqId) return@onSuccess
                    clustersState.value = UiState(
                        data = dto,
                        source = toSource(dto.meta),
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    if (requestId != clustersReqId) return@onFailure
                    clustersState.value = clustersState.value.copy(
                        loading = false,
                        error = e.message ?: "뉴스(클러스터 목록)를 불러오지 못했습니다",
                    )
                }
        }
    }

    fun loadStocks(
        window: String = "24h",
        ymd: Int? = null,
        themeKey: String? = null,
        source: String = "all",
        eventType: String? = null,
        hideRisk: Boolean = false,
        sort: String = "hot",
    ) {
        stocksState.value = stocksState.value.copy(loading = true, error = null)
        val requestId = ++stocksReqId
        stocksJob?.cancel()
        stocksJob = viewModelScope.launch {
            repository.getNewsStocks(
                window = window,
                ymd = ymd,
                themeKey = themeKey,
                source = source,
                eventType = eventType,
                hideRisk = hideRisk,
                sort = sort,
            )
                .onSuccess { dto ->
                    if (requestId != stocksReqId) return@onSuccess
                    stocksState.value = UiState(
                        data = dto,
                        source = toSource(dto.meta),
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    if (requestId != stocksReqId) return@onFailure
                    stocksState.value = stocksState.value.copy(
                        loading = false,
                        error = e.message ?: "뉴스(종목)를 불러오지 못했습니다",
                    )
                }
        }
    }

    fun loadArticles(
        window: String = "24h",
        ymd: Int? = null,
        themeKey: String? = null,
        source: String = "all",
        eventType: String? = null,
        hideRisk: Boolean = false,
        sort: String = "latest",
        limit: Int = 200,
    ) {
        articlesState.value = articlesState.value.copy(loading = true, error = null)
        val requestId = ++articlesReqId
        articlesJob?.cancel()
        articlesJob = viewModelScope.launch {
            repository.getNewsArticles(
                window = window,
                ymd = ymd,
                themeKey = themeKey,
                source = source,
                eventType = eventType,
                hideRisk = hideRisk,
                sort = sort,
                limit = limit,
            )
                .onSuccess { dto ->
                    if (requestId != articlesReqId) return@onSuccess
                    articlesState.value = UiState(
                        data = dto,
                        source = toSource(dto.meta),
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    if (requestId != articlesReqId) return@onFailure
                    articlesState.value = articlesState.value.copy(
                        loading = false,
                        error = e.message ?: "뉴스(기사 목록)를 불러오지 못했습니다",
                    )
                }
        }
    }

    fun loadCluster(clusterId: Int) {
        clusterState.value = clusterState.value.copy(loading = true, error = null)
        val requestId = ++clusterReqId
        clusterJob?.cancel()
        clusterJob = viewModelScope.launch {
            repository.getNewsCluster(clusterId)
                .onSuccess { dto ->
                    if (requestId != clusterReqId) return@onSuccess
                    clusterState.value = UiState(
                        data = dto,
                        source = toSource(dto.meta),
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    if (requestId != clusterReqId) return@onFailure
                    clusterState.value = clusterState.value.copy(
                        loading = false,
                        error = e.message ?: "뉴스(클러스터)를 불러오지 못했습니다",
                    )
                }
        }
    }

    fun saveNewsLastFilters(
        window: String,
        mode: String,
        source: String,
        event: String,
        hideRisk: Boolean,
    ) {
        repository.saveNewsLastFilters(
            window = window,
            mode = mode,
            source = source,
            event = event,
            hideRisk = hideRisk,
        )
    }

    override fun onCleared() {
        themesJob?.cancel()
        clustersJob?.cancel()
        stocksJob?.cancel()
        articlesJob?.cancel()
        clusterJob?.cancel()
        super.onCleared()
    }
}

class SettingsViewModel(private val repository: StockRepository) : ViewModel() {
    val state = mutableStateOf(repository.getSettings())
    val strategyState = mutableStateOf(StrategySettingsUiState())

    fun save(
        baseUrl: String,
        lookback: String,
        riskPreset: String,
        themeCap: String,
        daytradeDisplayCount: String,
        longtermDisplayCount: String,
        quoteRefreshSec: String,
        daytradeVariant: String,
        bottomTabOrderCsv: String? = null,
        cardUiVersion: String? = null,
    ) {
        val parsedLookback = lookback.toIntOrNull()?.coerceIn(5, 120) ?: state.value.lookbackDays
        val parsedThemeCap = themeCap.toIntOrNull()?.coerceIn(1, 3) ?: state.value.themeCap
        val parsedDaytradeCount = daytradeDisplayCount.toIntOrNull()?.coerceIn(3, 100) ?: state.value.daytradeDisplayCount
        val parsedLongtermCount = longtermDisplayCount.toIntOrNull()?.coerceIn(3, 100) ?: state.value.longtermDisplayCount
        val parsedRefreshSec = quoteRefreshSec.toIntOrNull()?.coerceIn(3, 120) ?: state.value.quoteRefreshSec
        val parsedVariant = daytradeVariant.toIntOrNull()?.coerceIn(0, 9) ?: state.value.daytradeVariant
        repository.saveSettings(
            baseUrl.trim(),
            parsedLookback,
            riskPreset,
            parsedThemeCap,
            parsedDaytradeCount,
            parsedLongtermCount,
            parsedRefreshSec,
            parsedVariant,
            bottomTabOrderCsv,
            cardUiVersion,
        )
        state.value = repository.getSettings()
    }

    fun current(): AppSettings = repository.getSettings()

    fun saveNewsDefaults(
        defaultWindow: String,
        defaultMode: String,
        defaultSource: String,
        defaultHideRisk: Boolean,
        restoreLastFilters: Boolean,
        articleTextSizeSp: Int,
    ) {
        repository.saveNewsDefaults(
            defaultWindow = defaultWindow,
            defaultMode = defaultMode,
            defaultSource = defaultSource,
            defaultHideRisk = defaultHideRisk,
            restoreLastFilters = restoreLastFilters,
            articleTextSizeSp = articleTextSizeSp,
        )
        state.value = repository.getSettings()
    }

    fun saveNewsLastFilters(
        window: String,
        mode: String,
        source: String,
        event: String,
        hideRisk: Boolean,
    ) {
        repository.saveNewsLastFilters(
            window = window,
            mode = mode,
            source = source,
            event = event,
            hideRisk = hideRisk,
        )
    }

    fun saveLastAdvancedSection(section: String) {
        repository.saveLastAdvancedSection(section)
        state.value = repository.getSettings()
    }

    fun loadStrategySettings() {
        strategyState.value = strategyState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getStrategySettings() }
                .onSuccess { resp ->
                    strategyState.value = StrategySettingsUiState(
                        loading = false,
                        error = null,
                        settings = resp.settings ?: StrategySettingsDto(),
                        settingsHash = resp.settingsHash ?: ""
                    )
                }
                .onFailure { e ->
                    strategyState.value = strategyState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("전략 설정을 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun saveStrategySettings(payload: StrategySettingsDto) {
        strategyState.value = strategyState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.updateStrategySettings(payload)
                .onSuccess { resp ->
                    strategyState.value = StrategySettingsUiState(
                        loading = false,
                        error = null,
                        settings = resp.settings ?: payload,
                        settingsHash = resp.settingsHash ?: ""
                    )
                }
                .onFailure { e ->
                    strategyState.value = strategyState.value.copy(loading = false, error = e.message ?: "설정 저장 실패")
                }
        }
    }
}

class AutoTradeViewModel(private val repository: StockRepository) : ViewModel() {
    val settingsState = mutableStateOf(UiState<AutoTradeSettingsResponseDto>())
    val symbolRulesState = mutableStateOf(UiState<AutoTradeSymbolRulesResponseDto>())
    val brokerState = mutableStateOf(UiState<AutoTradeBrokerCredentialDto>())
    val candidatesState = mutableStateOf(UiState<AutoTradeCandidatesResponseDto>())
    val ordersState = mutableStateOf(UiState<AutoTradeOrdersResponseDto>())
    val performanceState = mutableStateOf(UiState<AutoTradePerformanceResponseDto>())
    val accountState = mutableStateOf(UiState<AutoTradeAccountSnapshotResponseDto>())
    val runState = mutableStateOf(UiState<AutoTradeRunResponseDto>())
    val reservationsState = mutableStateOf(UiState<AutoTradeReservationsResponseDto>())
    val reservationActionState = mutableStateOf(UiState<AutoTradeReservationActionResponseDto>())
    val reservationPendingCancelState = mutableStateOf(UiState<AutoTradeReservationPendingCancelResponseDto>())
    val orderCancelState = mutableStateOf(UiState<AutoTradeOrderCancelResponseDto>())
    val pendingCancelState = mutableStateOf(UiState<AutoTradePendingCancelResponseDto>())
    val pendingCountState = mutableStateOf(UiState<Int>())
    val reentryBlocksState = mutableStateOf(UiState<AutoTradeReentryBlocksResponseDto>())
    val reentryReleaseState = mutableStateOf(UiState<AutoTradeReentryReleaseResponseDto>())
    val stockSearchState = mutableStateOf(UiState<StockSearchResponseDto>())
    val holdingQuoteState = mutableStateOf<Map<String, RealtimeQuoteItemDto>>(emptyMap())
    val reservationQuoteState = mutableStateOf<Map<String, RealtimeQuoteItemDto>>(emptyMap())

    fun loadAll() {
        loadBootstrap()
    }

    private fun loadBootstrap() {
        settingsState.value = settingsState.value.copy(loading = true, error = null)
        symbolRulesState.value = symbolRulesState.value.copy(loading = true, error = null)
        brokerState.value = brokerState.value.copy(loading = true, error = null)
        ordersState.value = ordersState.value.copy(loading = true, error = null)
        accountState.value = accountState.value.copy(loading = true, error = null)
        reservationsState.value = reservationsState.value.copy(loading = true, error = null)
        candidatesState.value = candidatesState.value.copy(loading = true, error = null)

        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeBootstrap(fast = true) }
                .onSuccess { payload ->
                    applyBootstrap(payload)
                }
                .onFailure { e ->
                    val fallbackMessage = e.toFriendlyNetworkMessage("초기 데이터를 불러오지 못했습니다")
                    settingsState.value = settingsState.value.copy(loading = false, error = fallbackMessage)
                    loadSettings()
                    loadSymbolRules()
                    loadBroker()
                    loadOrders()
                    loadPendingCount()
                    loadAccount()
                    loadReservations()
                    loadCandidates(limit = 80, profile = "initial")
                }
        }
    }

    private fun applyBootstrap(payload: AutoTradeBootstrapResponseDto) {
        val refreshedAt = payload.generatedAt?.takeIf { it.isNotBlank() } ?: Clock.System.now().toString()

        settingsState.value = if (payload.settings != null) {
            UiState(
                data = payload.settings,
                loading = false,
                refreshedAt = refreshedAt,
            )
        } else {
            settingsState.value.copy(loading = false, refreshedAt = refreshedAt)
        }

        symbolRulesState.value = if (payload.symbolRules != null) {
            UiState(
                data = payload.symbolRules,
                loading = false,
                refreshedAt = refreshedAt,
            )
        } else {
            symbolRulesState.value.copy(loading = false, refreshedAt = refreshedAt)
        }

        brokerState.value = if (payload.broker != null) {
            UiState(
                data = payload.broker,
                loading = false,
                refreshedAt = refreshedAt,
            )
        } else {
            brokerState.value.copy(loading = false, refreshedAt = refreshedAt)
        }

        ordersState.value = if (payload.orders != null) {
            UiState(
                data = payload.orders,
                loading = false,
                refreshedAt = refreshedAt,
            )
        } else {
            ordersState.value.copy(loading = false, refreshedAt = refreshedAt)
        }

        accountState.value = if (payload.account != null) {
            UiState(
                data = payload.account,
                loading = false,
                refreshedAt = refreshedAt,
            )
        } else {
            accountState.value.copy(loading = false, refreshedAt = refreshedAt)
        }

        reservationsState.value = UiState(
            data = reservationsState.value.data ?: AutoTradeReservationsResponseDto(
                total = 0,
                items = emptyList(),
            ),
            loading = false,
            refreshedAt = refreshedAt,
        )

        val prefetchLimit = maxOf(40, minOf(100, payload.candidatesPrefetchLimit ?: 80))
        loadCandidates(limit = prefetchLimit, profile = "initial")
        loadReservations(limit = 200)
        loadPendingCount(environment = payload.settings?.settings?.environment)
        val accountSource = payload.account?.source?.trim()?.uppercase()
        if (accountSource != "BROKER_LIVE") {
            loadAccount()
        }
    }

    fun loadSettings() {
        settingsState.value = settingsState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeSettings() }
                .onSuccess {
                    settingsState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    settingsState.value = settingsState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("자동매매 설정을 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun saveSettings(payload: AutoTradeSettingsDto) {
        settingsState.value = settingsState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.updateAutoTradeSettings(payload)
                .onSuccess {
                    settingsState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                    loadAll()
                }
                .onFailure { e ->
                    settingsState.value = settingsState.value.copy(
                        loading = false,
                        error = e.message ?: "자동매매 설정 저장에 실패했습니다",
                    )
                }
        }
    }

    fun loadSymbolRules() {
        symbolRulesState.value = symbolRulesState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeSymbolRules() }
                .onSuccess {
                    symbolRulesState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    symbolRulesState.value = symbolRulesState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("종목별 익절/손절 설정을 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun saveSymbolRule(payload: AutoTradeSymbolRuleUpsertDto) {
        symbolRulesState.value = symbolRulesState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.upsertAutoTradeSymbolRule(payload)
                .onSuccess { saved ->
                    val savedTicker = saved.ticker?.trim().orEmpty()
                    val merged = (
                        symbolRulesState.value.data?.items.orEmpty()
                            .filterNot { it.ticker?.trim().orEmpty().equals(savedTicker, ignoreCase = true) } + saved
                        )
                        .sortedByDescending { it.updatedAt ?: "" }
                    symbolRulesState.value = UiState(
                        data = AutoTradeSymbolRulesResponseDto(
                            count = merged.size,
                            items = merged,
                        ),
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    symbolRulesState.value = symbolRulesState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("종목별 익절/손절 설정 저장에 실패했습니다"),
                    )
                }
        }
    }

    fun deleteSymbolRule(ticker: String) {
        val tk = ticker.trim()
        if (tk.isBlank()) return
        symbolRulesState.value = symbolRulesState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.deleteAutoTradeSymbolRule(tk)
                .onSuccess {
                    val filtered = symbolRulesState.value.data?.items.orEmpty()
                        .filterNot { it.ticker?.trim().orEmpty().equals(tk, ignoreCase = true) }
                    symbolRulesState.value = UiState(
                        data = AutoTradeSymbolRulesResponseDto(
                            count = filtered.size,
                            items = filtered,
                        ),
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    symbolRulesState.value = symbolRulesState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("종목별 익절/손절 설정 삭제에 실패했습니다"),
                    )
                }
        }
    }

    fun loadBroker() {
        brokerState.value = brokerState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeBrokerCredential() }
                .onSuccess {
                    brokerState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    brokerState.value = brokerState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("증권사 계정정보를 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun saveBroker(payload: AutoTradeBrokerCredentialUpdateDto) {
        brokerState.value = brokerState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.updateAutoTradeBrokerCredential(payload)
                .onSuccess {
                    brokerState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    brokerState.value = brokerState.value.copy(
                        loading = false,
                        error = e.message ?: "증권사 계정정보 저장에 실패했습니다",
                    )
                }
        }
    }

    fun loadCandidates(limit: Int = 300, profile: String = "full") {
        candidatesState.value = candidatesState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeCandidates(limit = limit, profile = profile) }
                .onSuccess {
                    candidatesState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    candidatesState.value = candidatesState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("자동매매 후보를 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun loadOrders(page: Int = 1, size: Int = 100) {
        ordersState.value = ordersState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeOrders(page = page, size = size) }
                .onSuccess {
                    ordersState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    ordersState.value = ordersState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("자동매매 히스토리를 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun loadPerformance(days: Int = 30) {
        performanceState.value = performanceState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradePerformance(days = days) }
                .onSuccess {
                    performanceState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    performanceState.value = performanceState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("자동매매 성과를 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun loadAccount() {
        accountState.value = accountState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeAccountSnapshot() }
                .onSuccess {
                    accountState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    accountState.value = accountState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("자동매매 계좌 스냅샷을 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun loadReservations(status: String? = null, limit: Int = 200) {
        reservationsState.value = reservationsState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeReservations(status = status, limit = limit) }
                .onSuccess {
                    reservationsState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    reservationsState.value = reservationsState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("예약 주문 내역을 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun run(dryRun: Boolean, limit: Int? = null, reserveIfClosed: Boolean = false) {
        runState.value = runState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.runAutoTrade(
                dryRun = dryRun,
                limit = limit,
                reserveIfClosed = reserveIfClosed,
            )
                .onSuccess { dto ->
                    runState.value = UiState(
                        data = dto,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                    loadOrders()
                    loadAccount()
                    loadReservations()
                    loadCandidates(limit = 80, profile = "initial")
                    loadPendingCount()
                }
                .onFailure { e ->
                    runState.value = runState.value.copy(
                        loading = false,
                        error = e.message ?: "자동매매 실행에 실패했습니다",
                    )
                }
        }
    }

    fun confirmReservation(reservationId: Int) {
        reservationActionState.value = reservationActionState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.confirmAutoTradeReservation(reservationId)
                .onSuccess {
                    reservationActionState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                    loadOrders()
                    loadAccount()
                    loadReservations()
                }
                .onFailure { e ->
                    reservationActionState.value = reservationActionState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("예약 주문 실행에 실패했습니다"),
                    )
                }
        }
    }

    fun cancelReservation(reservationId: Int) {
        reservationActionState.value = reservationActionState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.cancelAutoTradeReservation(reservationId)
                .onSuccess {
                    reservationActionState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                    loadReservations()
                }
                .onFailure { e ->
                    reservationActionState.value = reservationActionState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("예약 주문 취소에 실패했습니다"),
                    )
                }
        }
    }

    fun cancelReservationItem(reservationId: Int, ticker: String) {
        val targetTicker = ticker.trim()
        if (targetTicker.isBlank()) return
        reservationActionState.value = reservationActionState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.cancelAutoTradeReservationItem(
                reservationId = reservationId,
                ticker = targetTicker,
            ).onSuccess {
                reservationActionState.value = UiState(
                    data = it,
                    loading = false,
                    refreshedAt = Clock.System.now().toString(),
                )
                loadReservations(limit = 200)
            }.onFailure { e ->
                reservationActionState.value = reservationActionState.value.copy(
                    loading = false,
                    error = e.toFriendlyNetworkMessage("예약 종목 취소에 실패했습니다"),
                )
            }
        }
    }

    fun cancelAllPendingReservations(environment: String? = null, maxCount: Int = 30) {
        reservationPendingCancelState.value = reservationPendingCancelState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.cancelAutoTradePendingReservations(
                environment = environment,
                maxCount = maxCount.coerceIn(1, 300),
            ).onSuccess {
                reservationPendingCancelState.value = UiState(
                    data = it,
                    loading = false,
                    refreshedAt = Clock.System.now().toString(),
                )
                loadReservations(limit = 200)
            }.onFailure { e ->
                reservationPendingCancelState.value = reservationPendingCancelState.value.copy(
                    loading = false,
                    error = e.toFriendlyNetworkMessage("예약 일괄취소에 실패했습니다"),
                )
            }
        }
    }

    fun cancelPendingOrder(orderId: Int, environment: String? = null) {
        if (orderId <= 0) return
        orderCancelState.value = orderCancelState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.cancelAutoTradeOrder(orderId = orderId, environment = environment)
                .onSuccess {
                    orderCancelState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                    loadOrders()
                    loadAccount()
                    loadPendingCount()
                }
                .onFailure { e ->
                    orderCancelState.value = orderCancelState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("접수대기 주문 취소에 실패했습니다"),
                    )
                }
        }
    }

    fun cancelAllPendingOrders(environment: String? = null, maxCount: Int = 20) {
        pendingCancelState.value = pendingCancelState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.cancelAutoTradePendingOrders(
                environment = environment,
                maxCount = maxCount.coerceIn(1, 50),
            ).onSuccess {
                pendingCancelState.value = UiState(
                    data = it,
                    loading = false,
                    refreshedAt = Clock.System.now().toString(),
                )
                loadOrders()
                loadAccount()
                loadPendingCount()
            }.onFailure { e ->
                pendingCancelState.value = pendingCancelState.value.copy(
                    loading = false,
                    error = e.toFriendlyNetworkMessage("접수대기 주문 일괄취소에 실패했습니다"),
                )
            }
        }
    }

    fun loadReentryBlocks(environment: String? = null, triggerReason: String? = null, limit: Int = 200) {
        reentryBlocksState.value = reentryBlocksState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.getAutoTradeReentryBlocks(
                environment = environment,
                triggerReason = triggerReason,
                limit = limit,
            ).onSuccess {
                reentryBlocksState.value = UiState(
                    data = it,
                    loading = false,
                    refreshedAt = Clock.System.now().toString(),
                )
            }.onFailure { e ->
                reentryBlocksState.value = reentryBlocksState.value.copy(
                    loading = false,
                    error = e.toFriendlyNetworkMessage("재진입 차단 목록을 불러오지 못했습니다"),
                )
            }
        }
    }

    fun releaseReentryBlocks(
        environment: String? = null,
        ticker: String? = null,
        triggerReason: String? = null,
        releaseAll: Boolean = false,
    ) {
        reentryReleaseState.value = reentryReleaseState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.releaseAutoTradeReentryBlocks(
                environment = environment,
                ticker = ticker,
                triggerReason = triggerReason,
                releaseAll = releaseAll,
            ).onSuccess {
                reentryReleaseState.value = UiState(
                    data = it,
                    loading = false,
                    refreshedAt = Clock.System.now().toString(),
                )
                loadReentryBlocks(environment = environment)
                loadCandidates(limit = 80, profile = "initial")
            }.onFailure { e ->
                reentryReleaseState.value = reentryReleaseState.value.copy(
                    loading = false,
                    error = e.toFriendlyNetworkMessage("재진입 차단 해제에 실패했습니다"),
                )
            }
        }
    }

    fun loadPendingCount(environment: String? = null) {
        pendingCountState.value = pendingCountState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.getAutoTradeOrders(
                page = 1,
                size = 1,
                environment = environment,
                status = "BROKER_SUBMITTED",
            ).onSuccess { dto ->
                pendingCountState.value = UiState(
                    data = dto.total ?: 0,
                    loading = false,
                    refreshedAt = Clock.System.now().toString(),
                )
            }.onFailure { e ->
                pendingCountState.value = pendingCountState.value.copy(
                    loading = false,
                    error = e.toFriendlyNetworkMessage("진행중 주문 개수를 불러오지 못했습니다"),
                )
            }
        }
    }

    fun searchStocks(query: String, limit: Int = 50) {
        val q = query.trim()
        if (q.isBlank()) {
            stockSearchState.value = UiState(
                data = StockSearchResponseDto(count = 0, items = emptyList()),
                loading = false,
                refreshedAt = Clock.System.now().toString(),
            )
            return
        }
        stockSearchState.value = stockSearchState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.searchStocks(query = q, limit = limit) }
                .onSuccess {
                    stockSearchState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    stockSearchState.value = stockSearchState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("종목 검색에 실패했습니다"),
                    )
                }
        }
    }

    fun loadHoldingQuotes(tickers: List<String>) {
        val target = tickers.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(60)
        if (target.isEmpty()) {
            holdingQuoteState.value = emptyMap()
            return
        }
        viewModelScope.launch {
            repository.getRealtimeQuotes(target, mode = "light")
                .onSuccess { quotes ->
                    holdingQuoteState.value = quotes
                }
        }
    }

    fun loadReservationQuotes(tickers: List<String>) {
        val target = tickers.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(120)
        if (target.isEmpty()) {
            reservationQuoteState.value = emptyMap()
            return
        }
        viewModelScope.launch {
            repository.getRealtimeQuotes(target, mode = "light")
                .onSuccess { quotes ->
                    reservationQuoteState.value = quotes
                }
        }
    }
}

class HoldingsViewModel(private val repository: StockRepository) : ViewModel() {
    val accountPaperState = mutableStateOf(UiState<AutoTradeAccountSnapshotResponseDto>())
    val accountDemoState = mutableStateOf(UiState<AutoTradeAccountSnapshotResponseDto>())
    val accountProdState = mutableStateOf(UiState<AutoTradeAccountSnapshotResponseDto>())
    val symbolRulesState = mutableStateOf(UiState<AutoTradeSymbolRulesResponseDto>())
    val ordersState = mutableStateOf(UiState<AutoTradeOrdersResponseDto>())
    val reservationsState = mutableStateOf(UiState<AutoTradeReservationsResponseDto>())
    val performanceState = mutableStateOf(UiState<AutoTradePerformanceResponseDto>())
    val actionState = mutableStateOf(UiState<AutoTradeRunResponseDto>())
    val holdingQuoteState = mutableStateOf<Map<String, RealtimeQuoteItemDto>>(emptyMap())

    fun loadAll() {
        loadAccounts()
        loadSymbolRules()
        loadOrders()
        loadReservations(limit = 200)
        loadPerformance(days = 365)
    }

    fun loadAccounts() {
        loadAccount("paper", accountPaperState)
        loadAccount("demo", accountDemoState)
        loadAccount("prod", accountProdState)
    }

    private fun loadAccount(
        environment: String,
        targetState: MutableState<UiState<AutoTradeAccountSnapshotResponseDto>>,
    ) {
        targetState.value = targetState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeAccountSnapshot(environment = environment) }
                .onSuccess {
                    targetState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    targetState.value = targetState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("보유 정보를 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun loadSymbolRules() {
        symbolRulesState.value = symbolRulesState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeSymbolRules() }
                .onSuccess {
                    symbolRulesState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    symbolRulesState.value = symbolRulesState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("종목별 제어 설정을 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun loadOrders() {
        ordersState.value = ordersState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult {
                com.example.stock.data.repository.suspendRunCatching {
                    val pageSize = 300
                    val first = repository.getAutoTradeOrders(page = 1, size = pageSize).getOrThrow()
                    val merged = first.items.orEmpty().toMutableList()
                    val total = (first.total ?: merged.size).coerceAtLeast(merged.size)
                    var page = 2
                    while (merged.size < total && page <= 1000) {
                        val next = repository.getAutoTradeOrders(page = page, size = pageSize).getOrThrow()
                        val nextItems = next.items.orEmpty()
                        if (nextItems.isEmpty()) break
                        merged += nextItems
                        page += 1
                    }
                    AutoTradeOrdersResponseDto(
                        total = merged.size,
                        items = merged,
                    )
                }
            }
                .onSuccess {
                    ordersState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    ordersState.value = ordersState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("거래 이력을 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun loadReservations(limit: Int = 200) {
        val safeLimit = limit.coerceIn(1, 200)
        reservationsState.value = reservationsState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradeReservations(limit = safeLimit) }
                .onSuccess {
                    reservationsState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    reservationsState.value = reservationsState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("예약 이력을 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun loadPerformance(days: Int = 365, environment: String? = null) {
        val safeDays = days.coerceIn(1, 365)
        performanceState.value = performanceState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            retryNetworkResult { repository.getAutoTradePerformance(days = safeDays, environment = environment) }
                .onSuccess {
                    performanceState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    performanceState.value = performanceState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("기간 손익 데이터를 불러오지 못했습니다"),
                    )
                }
        }
    }

    fun saveSymbolRule(payload: AutoTradeSymbolRuleUpsertDto) {
        symbolRulesState.value = symbolRulesState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.upsertAutoTradeSymbolRule(payload)
                .onSuccess { saved ->
                    val savedTicker = saved.ticker?.trim().orEmpty()
                    val merged = (
                        symbolRulesState.value.data?.items.orEmpty()
                            .filterNot { it.ticker?.trim().orEmpty().equals(savedTicker, ignoreCase = true) } + saved
                        )
                        .sortedByDescending { it.updatedAt ?: "" }
                    symbolRulesState.value = UiState(
                        data = AutoTradeSymbolRulesResponseDto(
                            count = merged.size,
                            items = merged,
                        ),
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                }
                .onFailure { e ->
                    symbolRulesState.value = symbolRulesState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("종목별 제어 설정 저장에 실패했습니다"),
                    )
                }
        }
    }

    fun runManualSell(
        ticker: String,
        name: String?,
        mode: String,
        qty: Int,
        requestPrice: Double? = null,
        marketOrder: Boolean? = null,
        dryRun: Boolean = false,
    ) {
        actionState.value = actionState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            repository.runAutoTradeManualSell(
                ticker = ticker,
                name = name,
                mode = mode,
                qty = qty,
                requestPrice = requestPrice,
                marketOrder = marketOrder,
                dryRun = dryRun,
            )
                .onSuccess {
                    actionState.value = UiState(
                        data = it,
                        loading = false,
                        refreshedAt = Clock.System.now().toString(),
                    )
                    loadAccounts()
                    loadOrders()
                    loadReservations(limit = 200)
                }
                .onFailure { e ->
                    actionState.value = actionState.value.copy(
                        loading = false,
                        error = e.toFriendlyNetworkMessage("매도 요청에 실패했습니다"),
                    )
                }
        }
    }

    fun loadHoldingQuotes(tickers: List<String>) {
        val target = tickers.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(60)
        if (target.isEmpty()) {
            holdingQuoteState.value = emptyMap()
            return
        }
        viewModelScope.launch {
            repository.getRealtimeQuotes(target, mode = "light")
                .onSuccess { quotes ->
                    holdingQuoteState.value = quotes
                }
        }
    }
}

data class StrategySettingsUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val settings: StrategySettingsDto = StrategySettingsDto(),
    val settingsHash: String = "",
)

/**
 * 홈 화면 투자자 수급 요약 데이터.
 * [individual], [foreign], [institution]은 각각 개인/외국인/기관의
 * 3일 순매수 합계(백만원 단위).
 */
data class DailyFlow(
    val date: String,
    val foreign: Long,
    val institution: Long,
    val individual: Long,
)

data class InvestorFlowSummary(
    val individual: Long = 0L,
    val foreign: Long = 0L,
    val institution: Long = 0L,
    val unit: String = "value",
    val dailyFlow: List<DailyFlow> = emptyList(),
)

class HomeViewModel(private val repository: StockRepository) : ViewModel() {
    val premarketState = mutableStateOf(UiState<PremarketReportDto>(loading = true))
    val favoritesState = mutableStateOf<List<com.example.stock.data.api.FavoriteItemDto>>(emptyList())
    val quoteState = mutableStateMapOf<String, RealtimeQuoteItemDto>()
    val miniChartState = mutableStateMapOf<String, List<ChartPointDto>>()
    /** 시장 지수 (premarket report의 regime.market_snapshot에서 추출) */
    val marketSnapshotState = mutableStateOf<com.example.stock.data.api.MarketSnapshotDto?>(null)
    val regimeModeState = mutableStateOf<String?>(null)
    /** 시장 지표 스냅샷 날짜 (전일 기준 라벨용) */
    val snapshotDateState = mutableStateOf<String?>(null)
    /** 투자자 수급 현황 (개인/외국인/기관 3일 순매수 합계) */
    val investorFlowState = mutableStateOf<InvestorFlowSummary?>(null)
    /** 계좌 스냅샷 */
    val accountState = mutableStateOf<com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto?>(null)
    /** 자동매매 성과 요약 */
    val performanceState = mutableStateOf<com.example.stock.data.api.AutoTradePerformanceItemDto?>(null)
    /** 자동매매 설정 (활성화 여부, 환경 등) */
    val autoTradeEnabledState = mutableStateOf<Boolean?>(null)
    val autoTradeEnvState = mutableStateOf<String?>(null)
    /** 예약 대기 건수 */
    val reservationCountState = mutableStateOf(0)
    /** 뉴스 클러스터 (핫 뉴스) */
    val newsClustersState = mutableStateOf<List<com.example.stock.data.api.NewsClusterListItemDto>>(emptyList())
    /** 한줄 브리핑 */
    val briefingState = mutableStateOf<String?>(null)
    /** 시장 온도계 */
    val marketTemperatureState = mutableStateOf<com.example.stock.data.api.MarketTemperatureDto?>(null)
    /** 실시간 시장 지수 */
    val liveIndicesState = mutableStateOf<com.example.stock.data.api.MarketIndicesResponseDto?>(null)
    /** 매매 피드 */
    val tradeFeedState = mutableStateOf<List<com.example.stock.data.api.TradeFeedItemDto>>(emptyList())
    /** 수익 캘린더 */
    val pnlCalendarState = mutableStateOf<com.example.stock.data.api.PnlCalendarResponseDto?>(null)
    /** 섹션별 에러 메시지 (키: supply, account, performance, news, indices, feed, calendar) */
    val sectionErrorState = mutableStateMapOf<String, String>()
    private var pollJob: Job? = null
    private var loadJob: Job? = null
    private val accountMutex = kotlinx.coroutines.sync.Mutex()

    fun load() {
        loadJob?.cancel()
        pollJob?.cancel()
        loadJob = viewModelScope.launch {
            val seoul = kotlinx.datetime.TimeZone.of("Asia/Seoul")
            val today = Clock.System.todayIn(seoul).toString()

            premarketState.value = premarketState.value.copy(loading = true, error = null)
            val fast = repository.getPremarketFast(today)
            premarketState.value = UiState(data = fast.data, loading = false, source = fast.source)

            coroutineScope {
                val preJob = async {
                    repository.getPremarket(today).onSuccess { wrapped ->
                        premarketState.value = UiState(data = wrapped.data, loading = false, source = wrapped.source)
                        loadMiniCharts(wrapped.data)
                        // regime에서 시장 지수 추출
                        wrapped.data.regime?.let { regime ->
                            regimeModeState.value = regime.mode
                            regime.marketSnapshot?.let { snap ->
                                marketSnapshotState.value = snap
                            }
                        }
                        // 스냅샷 날짜 (전일 기준 라벨용)
                        snapshotDateState.value = wrapped.data.status?.snapshotDate
                        // 한줄 브리핑 + 시장 온도계
                        briefingState.value = wrapped.data.briefing
                        marketTemperatureState.value = wrapped.data.marketTemperature
                    }.onFailure { err ->
                        if (premarketState.value.data == null) {
                            premarketState.value = UiState(error = err.message, loading = false)
                        }
                    }
                }
                val favJob = async {
                    repository.getFavorites().onSuccess { items ->
                        favoritesState.value = items
                    }
                }
                val acctJob = async { loadAccount() }
                val perfJob = async { loadPerformance() }
                val newsJob = async { loadNewsClusters() }
                val feedJob = async { loadTradeFeed() }
                val calendarJob = async { loadPnlCalendar() }
                val indicesJob = async { loadMarketIndices() }
                preJob.await()
                favJob.await()
                acctJob.await()
                perfJob.await()
                newsJob.await()
                feedJob.await()
                calendarJob.await()
                indicesJob.await()
                // 수급은 최대 12s 소요 — await 제거하여 다른 카드 로드를 막지 않음
                viewModelScope.launch { loadInvestorFlow() }
            }
            startPolling()
        }
    }

    private fun loadMiniCharts(report: PremarketReportDto) {
        val preTickers = report.daytradeTop?.mapNotNull { it.ticker }.orEmpty().take(5)
        val favTickers = favoritesState.value.mapNotNull { it.ticker }
        val tickers = (preTickers + favTickers).distinct()
        if (tickers.isEmpty()) return
        viewModelScope.launch {
            repository.getChartDailyBatch(tickers, days = 7).onSuccess { map ->
                map.forEach { (code, dto) ->
                    dto.points?.let { pts -> miniChartState[code] = pts }
                }
            }
        }
    }

    /**
     * /market/supply API로 종목별 투자자 수급 데이터를 가져와
     * 전체 개인/외국인/기관 3일 순매수 합계를 계산한다.
     */
    private suspend fun loadInvestorFlow() {
        repository.getMarketSupply(count = 15).onSuccess { resp ->
            sectionErrorState.remove("supply")
            val items = resp.items.orEmpty()
            var individual = 0L
            var foreign = 0L
            var institution = 0L
            for (item in items) {
                individual += (item.individual3d ?: 0).toLong()
                foreign += (item.foreign3d ?: 0).toLong()
                institution += (item.institution3d ?: 0).toLong()
            }
            val dailyFlow = resp.dailyFlow.orEmpty().mapNotNull { d ->
                val dt = d.date ?: return@mapNotNull null
                DailyFlow(date = dt, foreign = d.foreign, institution = d.institution, individual = d.individual)
            }
            investorFlowState.value = InvestorFlowSummary(
                individual = individual,
                foreign = foreign,
                institution = institution,
                unit = resp.unit ?: "value",
                dailyFlow = dailyFlow,
            )
        }.onFailure {
            if (investorFlowState.value == null) sectionErrorState["supply"] = "수급 데이터를 불러올 수 없습니다"
        }
    }

    private suspend fun loadAccount() {
        if (!accountMutex.tryLock()) return // 이미 실행 중이면 무시
        try {
            repository.getAutoTradeBootstrap(fast = true).onSuccess { boot ->
                sectionErrorState.remove("account")
                boot.account?.let { accountState.value = it }
                boot.settings?.settings?.let { s ->
                    autoTradeEnabledState.value = s.enabled
                    autoTradeEnvState.value = s.environment
                }
            }.onFailure {
                if (accountState.value == null) sectionErrorState["account"] = "계좌 정보를 불러올 수 없습니다"
            }
            repository.getAutoTradeReservations(status = "PENDING").onSuccess { res ->
                reservationCountState.value = res.total ?: 0
            }
        } finally {
            accountMutex.unlock()
        }
    }

    private suspend fun loadPerformance() {
        repository.getAutoTradePerformance(days = 30).onSuccess { perf ->
            sectionErrorState.remove("performance")
            performanceState.value = perf.summary
        }.onFailure {
            if (performanceState.value == null) sectionErrorState["performance"] = "성과 데이터를 불러올 수 없습니다"
        }
    }

    private suspend fun loadNewsClusters() {
        repository.getNewsClusters(limit = 3).onSuccess { resp ->
            sectionErrorState.remove("news")
            newsClustersState.value = resp.clusters.orEmpty().take(3)
        }.onFailure {
            if (newsClustersState.value.isEmpty()) sectionErrorState["news"] = "뉴스를 불러올 수 없습니다"
        }
    }

    private suspend fun loadMarketIndices() {
        repository.getMarketIndices().onSuccess { resp ->
            sectionErrorState.remove("indices")
            liveIndicesState.value = resp
            // 실시간 지수로 marketSnapshotState 덮어쓰기
            val kospi = resp.kospi?.value
            val kosdaq = resp.kosdaq?.value
            val usdkrw = resp.usdkrw?.value
            if (kospi != null || kosdaq != null || usdkrw != null) {
                marketSnapshotState.value = com.example.stock.data.api.MarketSnapshotDto(
                    kospiClose = kospi,
                    kosdaqClose = kosdaq,
                    usdkrwClose = usdkrw,
                )
            }
        }.onFailure {
            if (marketSnapshotState.value == null) sectionErrorState["indices"] = "시장 지수를 불러올 수 없습니다"
        }
    }

    private suspend fun loadTradeFeed() {
        repository.getAutoTradeFeed(limit = 20).onSuccess { resp: com.example.stock.data.api.TradeFeedResponseDto ->
            sectionErrorState.remove("feed")
            tradeFeedState.value = resp.items.orEmpty()
        }.onFailure {
            if (tradeFeedState.value.isEmpty()) sectionErrorState["feed"] = "매매 피드를 불러올 수 없습니다"
        }
    }

    private suspend fun loadPnlCalendar() {
        val seoul = kotlinx.datetime.TimeZone.of("Asia/Seoul")
        val today = Clock.System.todayIn(seoul)
        repository.getAutoTradePnlCalendar(year = today.year, month = today.monthNumber).onSuccess { resp: com.example.stock.data.api.PnlCalendarResponseDto ->
            sectionErrorState.remove("calendar")
            pnlCalendarState.value = resp
        }.onFailure {
            if (pnlCalendarState.value == null) sectionErrorState["calendar"] = "캘린더를 불러올 수 없습니다"
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                fetchQuotes()
                // 장중(09:00~15:30)이면 수급 + 지수도 갱신
                val now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Seoul"))
                val marketOpen = now.hour in 9..14 || (now.hour == 15 && now.minute <= 30)
                if (marketOpen) {
                    loadMarketIndices()
                    loadInvestorFlow()
                }
                delay(30_000L)
            }
        }
    }

    private suspend fun fetchQuotes() {
        val premarketTickers = premarketState.value.data?.daytradeTop?.mapNotNull { it.ticker }.orEmpty()
        val favTickers = favoritesState.value.mapNotNull { it.ticker }
        val all = (premarketTickers + favTickers).distinct().take(40)
        if (all.isEmpty()) return
        repository.getRealtimeQuotes(all).onSuccess { map -> quoteState.putAll(map) }
    }

    fun stopPolling() { pollJob?.cancel() }

    override fun onCleared() {
        loadJob?.cancel()
        pollJob?.cancel()
        super.onCleared()
    }
}

class Home2ViewModel(private val repository: StockRepository) : ViewModel() {
    // 기존 홈 데이터 state
    val briefingState = mutableStateOf<String?>(null)
    val accountState = mutableStateOf<com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto?>(null)
    val performanceState = mutableStateOf<com.example.stock.data.api.AutoTradePerformanceItemDto?>(null)
    val autoTradeEnabledState = mutableStateOf<Boolean?>(null)
    val autoTradeEnvState = mutableStateOf<String?>(null)
    val tradeFeedState = mutableStateOf<List<com.example.stock.data.api.TradeFeedItemDto>>(emptyList())
    // TODO: uncomment when TradeFeedSummaryDto server integration is complete
    // val tradeFeedSummaryState = mutableStateOf<com.example.stock.data.api.TradeFeedSummaryDto?>(null)
    val liveIndicesState = mutableStateOf<com.example.stock.data.api.MarketIndicesResponseDto?>(null)
    val regimeModeState = mutableStateOf<String?>(null)
    val marketTemperatureState = mutableStateOf<com.example.stock.data.api.MarketTemperatureDto?>(null)
    val premarketState = mutableStateOf(UiState<PremarketReportDto>(loading = true))
    val investorFlowState = mutableStateOf<InvestorFlowSummary?>(null)
    val favoritesState = mutableStateOf<List<com.example.stock.data.api.FavoriteItemDto>>(emptyList())
    val quoteState = mutableStateMapOf<String, RealtimeQuoteItemDto>()
    val miniChartState = mutableStateMapOf<String, List<ChartPointDto>>()
    val pnlCalendarState = mutableStateOf<com.example.stock.data.api.PnlCalendarResponseDto?>(null)
    val newsClustersState = mutableStateOf<List<com.example.stock.data.api.NewsClusterListItemDto>>(emptyList())
    val reservationCountState = mutableStateOf(0)
    val snapshotDateState = mutableStateOf<String?>(null)

    // 신규 데이터 (Home2 전용)
    // TODO: uncomment when SectorItemDto server integration is complete
    // val sectorHeatmapState = mutableStateOf<List<com.example.stock.data.api.SectorItemDto>>(emptyList())
    // TODO: uncomment when VolumeSurgeItemDto server integration is complete
    // val volumeSurgeState = mutableStateOf<List<com.example.stock.data.api.VolumeSurgeItemDto>>(emptyList())
    // TODO: uncomment when WeekExtremeResponseDto server integration is complete
    // val weekExtremeState = mutableStateOf<com.example.stock.data.api.WeekExtremeResponseDto?>(null)
    // TODO: uncomment when DividendItemDto server integration is complete
    // val dividendState = mutableStateOf<List<com.example.stock.data.api.DividendItemDto>>(emptyList())

    val sectionErrorState = mutableStateMapOf<String, String>()

    private var pollingJob: Job? = null

    fun load() {
        viewModelScope.launch {
            coroutineScope {
                async { loadAccount() }
            }
            startPolling()
        }
    }

    private suspend fun loadAccount() {
        try {
            val bootstrap = repository.getAutoTradeBootstrap(fast = true)
            bootstrap.onSuccess { boot ->
                sectionErrorState.remove("account")
                boot.account?.let { accountState.value = it }
                boot.settings?.settings?.let { s ->
                    autoTradeEnabledState.value = s.enabled
                    autoTradeEnvState.value = s.environment
                }
            }.onFailure {
                if (accountState.value == null) sectionErrorState["account"] = "계좌 정보를 불러올 수 없습니다"
            }
            // 예약 대기 건수
            repository.getAutoTradeReservations(status = "PENDING").onSuccess { res ->
                reservationCountState.value = res.total ?: 0
            }
        } catch (e: Exception) {
            if (accountState.value == null) sectionErrorState["account"] = (e.message ?: "계좌 로드 실패")
        }
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            delay(30_000)
            while (isActive) {
                fetchQuotes()
                delay(30_000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun fetchQuotes() {
        // Phase 1에서 구현
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

class AppViewModelFactory(private val repository: StockRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(HomeViewModel::class.java) -> HomeViewModel(repository) as T
            modelClass.isAssignableFrom(Home2ViewModel::class.java) -> Home2ViewModel(repository) as T
            modelClass.isAssignableFrom(PremarketViewModel::class.java) -> PremarketViewModel(repository) as T
            modelClass.isAssignableFrom(EodViewModel::class.java) -> EodViewModel(repository) as T
            modelClass.isAssignableFrom(AlertsViewModel::class.java) -> AlertsViewModel(repository) as T
            modelClass.isAssignableFrom(MoversViewModel::class.java) -> MoversViewModel(repository) as T
            modelClass.isAssignableFrom(Movers2ViewModel::class.java) -> Movers2ViewModel(repository) as T
            modelClass.isAssignableFrom(SupplyViewModel::class.java) -> SupplyViewModel(repository) as T
            modelClass.isAssignableFrom(UsInsiderViewModel::class.java) -> UsInsiderViewModel(repository) as T
            modelClass.isAssignableFrom(PapersViewModel::class.java) -> PapersViewModel(repository) as T
            modelClass.isAssignableFrom(NewsViewModel::class.java) -> NewsViewModel(repository) as T
            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(repository) as T
            modelClass.isAssignableFrom(AutoTradeViewModel::class.java) -> AutoTradeViewModel(repository) as T
            modelClass.isAssignableFrom(HoldingsViewModel::class.java) -> HoldingsViewModel(repository) as T
            else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
        }
    }
}
