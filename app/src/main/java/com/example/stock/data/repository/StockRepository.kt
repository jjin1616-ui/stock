package com.example.stock.data.repository

import android.content.Context
import android.provider.Settings
import com.example.stock.data.api.AlertHistoryItemDto
import com.example.stock.data.api.EodReportDto
import com.example.stock.data.api.EvalMonthlyDto
import com.example.stock.data.api.NetworkModule
import com.example.stock.data.api.PapersSummaryDto
import com.example.stock.data.api.PremarketReportDto
import com.example.stock.data.api.DaytradeTopItemDto
import com.example.stock.data.api.RealtimeQuoteItemDto
import com.example.stock.data.api.ChartDailyDto
import com.example.stock.data.api.ChartDailyBatchRequestDto
import com.example.stock.data.api.StockInvestorDailyResponseDto
import com.example.stock.data.api.StockTrendIntradayResponseDto
import com.example.stock.data.api.ResponseStatusDto
import com.example.stock.data.api.StrategySettingsDto
import com.example.stock.data.api.StrategySettingsResponseDto
import com.example.stock.data.api.LatestApkInfoDto
import com.example.stock.data.api.MoversResponseDto
import com.example.stock.data.api.Movers2ResponseDto
import com.example.stock.data.api.SupplyResponseDto
import com.example.stock.data.api.UsInsiderResponseDto
import com.example.stock.data.api.NewsThemesResponseDto
import com.example.stock.data.api.NewsClustersResponseDto
import com.example.stock.data.api.NewsStocksResponseDto
import com.example.stock.data.api.NewsArticlesResponseDto
import com.example.stock.data.api.NewsClusterResponseDto
import com.example.stock.data.api.FavoriteItemDto
import com.example.stock.data.api.FavoriteUpsertRequestDto
import com.example.stock.data.api.DevicePrefDto
import com.example.stock.data.api.DeviceRegisterRequest
import com.example.stock.data.api.AutoTradeSettingsDto
import com.example.stock.data.api.AutoTradeSettingsResponseDto
import com.example.stock.data.api.AutoTradeSymbolRuleItemDto
import com.example.stock.data.api.AutoTradeSymbolRulesResponseDto
import com.example.stock.data.api.AutoTradeSymbolRuleUpsertDto
import com.example.stock.data.api.AutoTradeBrokerCredentialDto
import com.example.stock.data.api.AutoTradeBrokerCredentialUpdateDto
import com.example.stock.data.api.AutoTradeCandidatesResponseDto
import com.example.stock.data.api.AutoTradeManualBuyRequestDto
import com.example.stock.data.api.AutoTradeManualSellRequestDto
import com.example.stock.data.api.AutoTradeRunRequestDto
import com.example.stock.data.api.AutoTradeRunResponseDto
import com.example.stock.data.api.AutoTradeReservationsResponseDto
import com.example.stock.data.api.AutoTradeReservationActionResponseDto
import com.example.stock.data.api.AutoTradeReservationPendingCancelRequestDto
import com.example.stock.data.api.AutoTradeReservationPendingCancelResponseDto
import com.example.stock.data.api.AutoTradeOrderCancelResponseDto
import com.example.stock.data.api.AutoTradePendingCancelRequestDto
import com.example.stock.data.api.AutoTradePendingCancelResponseDto
import com.example.stock.data.api.AutoTradeReentryBlocksResponseDto
import com.example.stock.data.api.AutoTradeReentryReleaseRequestDto
import com.example.stock.data.api.AutoTradeReentryReleaseResponseDto
import com.example.stock.data.api.AutoTradeOrdersResponseDto
import com.example.stock.data.api.AutoTradePerformanceResponseDto
import com.example.stock.data.api.AutoTradeAccountSnapshotResponseDto
import com.example.stock.data.api.AutoTradeBootstrapResponseDto
import com.example.stock.data.api.StockSearchResponseDto
import com.example.stock.db.AppDatabase
import com.example.stock.db.AlertCacheEntity
import com.example.stock.db.PremarketCacheEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class UiSource { LIVE, CACHE, FALLBACK }

data class DataWithSource<T>(val data: T, val source: UiSource)

class StockRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsStore: AppSettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val chartCache = ConcurrentHashMap<String, ChartCacheItem>()
    private val chartLocks = ConcurrentHashMap<String, Mutex>()
    private val chartSemaphore = Semaphore(permits = 4)
    private val chartTtlMs = 5 * 60 * 1000L

    private data class ChartCacheItem(val savedAtMs: Long, val payload: ChartDailyDto)
    private fun chartKey(code: String, days: Int): String = "$code:$days"
    private fun findBestChartCache(code: String): ChartCacheItem? {
        val prefix = "$code:"
        return chartCache.entries
            .asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.value }
            .maxByOrNull { it.payload.points?.size ?: 0 }
    }

    private fun getRescueData(date: String, message: String): PremarketReportDto {
        val items = emptyList<DaytradeTopItemDto>()
        return PremarketReportDto(
            date = date,
            generatedAt = "RESQUE_MODE",
            status = ResponseStatusDto(source = "FALLBACK", message = message),
            daytradeTop = items,
            daytradePrimary = items,
            daytradeTop10 = items,
            hardRules = listOf("서버와 통신할 수 없어 실시간 추천을 표시할 수 없습니다.", "설정에서 서버 주소를 확인해주세요.")
        )
    }

    suspend fun getPremarket(date: String, force: Boolean = false): Result<DataWithSource<PremarketReportDto>> {
        return runCatching {
            val s = settingsStore.get()
            val data = withTimeout(10000L) {
                NetworkModule.api(s.baseUrl).getPremarketReport(
                    date = date, 
                    lookback = s.lookbackDays, 
                    risk = s.riskPreset, 
                    themeCap = s.themeCap, 
                    variant = s.daytradeVariant,
                    daytradeLimit = s.daytradeDisplayCount,
                    longtermLimit = s.longtermDisplayCount,
                    force = force
                )
            }
            val source = when (data.status?.source) {
                "CACHE" -> UiSource.CACHE
                "FALLBACK" -> UiSource.FALLBACK
                else -> UiSource.LIVE
            }
            db.premarketDao().upsert(PremarketCacheEntity(
                date = date,
                generatedAt = data.generatedAt ?: "",
                payloadJson = json.encodeToString(data),
                updatedAtMs = System.currentTimeMillis()
            ))
            DataWithSource(data, source)
        }.recoverCatching { error ->
            val cached = db.premarketDao().latest()
            if (cached != null) {
                val cachedData = json.decodeFromString<PremarketReportDto>(cached.payloadJson)
                val cachedSource = when (cachedData.status?.source?.uppercase()) {
                    "FALLBACK" -> UiSource.FALLBACK
                    else -> UiSource.CACHE
                }
                DataWithSource(cachedData, cachedSource)
            } else {
                // 어떤 에러인지 메시지에 포함
                val errorMsg = when(error) {
                    is java.net.UnknownHostException -> "서버 주소를 찾을 수 없습니다."
                    is java.net.ConnectException -> "서버에 연결할 수 없습니다."
                    is kotlinx.coroutines.TimeoutCancellationException -> "연결 시간이 초과되었습니다."
                    else -> error.message ?: "알 수 없는 오류"
                }
                DataWithSource(getRescueData(date, "연결 실패: $errorMsg"), UiSource.FALLBACK)
            }
        }
    }

    suspend fun getPremarketFast(date: String): DataWithSource<PremarketReportDto> {
        val cached = db.premarketDao().latest()
        return if (cached != null) {
            DataWithSource(json.decodeFromString<PremarketReportDto>(cached.payloadJson), UiSource.CACHE)
        } else {
            DataWithSource(getRescueData(date, "최초 로딩 중..."), UiSource.FALLBACK)
        }
    }

    suspend fun getEod(date: String): Result<EodReportDto> = runCatching { NetworkModule.api(settingsStore.get().baseUrl).getEodReport(date) }
    suspend fun getEvalMonthly(end: String): Result<EvalMonthlyDto> = runCatching { NetworkModule.api(settingsStore.get().baseUrl).getEvalMonthly(end) }
    suspend fun fetchAlerts(limit: Int = 50): Result<List<AlertHistoryItemDto>> = runCatching { NetworkModule.api(settingsStore.get().baseUrl).getAlertHistory(limit) }
    suspend fun getRealtimeQuotes(
        tickers: List<String>,
        mode: String = "full",
    ): Result<Map<String, RealtimeQuoteItemDto>> {
        if (tickers.isEmpty()) return Result.success(emptyMap())
        return runCatching {
            val normalizedMode = if (mode.equals("light", ignoreCase = true)) "light" else "full"
            val dto = NetworkModule.api(settingsStore.get().baseUrl).getRealtimeQuotes(
                tickersCsv = tickers.joinToString(","),
                mode = normalizedMode,
            )
            dto.items?.associateBy { it.ticker.orEmpty() }.orEmpty()
        }
    }
    suspend fun getPapersSummary(): Result<PapersSummaryDto> = runCatching { NetworkModule.api(settingsStore.get().baseUrl).getPapersSummary() }
    suspend fun getChartDaily(code: String, days: Int = 180): Result<ChartDailyDto> {
        val key = chartKey(code, days)
        val now = System.currentTimeMillis()
        chartCache[key]?.let { cached ->
            if (now - cached.savedAtMs <= chartTtlMs) {
                return Result.success(cached.payload)
            }
        }
        findBestChartCache(code)?.let { cached ->
            if (now - cached.savedAtMs <= chartTtlMs) {
                val points = cached.payload.points.orEmpty()
                if (points.size >= days) {
                    val sliced = points.takeLast(days)
                    return Result.success(ChartDailyDto(name = cached.payload.name, points = sliced))
                }
            }
        }
        val lock = chartLocks.getOrPut(key) { Mutex() }
        return lock.withLock {
            val cached = chartCache[key]
            if (cached != null && now - cached.savedAtMs <= chartTtlMs) {
                return@withLock Result.success(cached.payload)
            }
            findBestChartCache(code)?.let { fallback ->
                if (now - fallback.savedAtMs <= chartTtlMs) {
                    val points = fallback.payload.points.orEmpty()
                    if (points.size >= days) {
                        val sliced = points.takeLast(days)
                        return@withLock Result.success(ChartDailyDto(name = fallback.payload.name, points = sliced))
                    }
                }
            }
            runCatching {
                chartSemaphore.acquire()
                try {
                    val dto = withContext(Dispatchers.IO) {
                        NetworkModule.api(settingsStore.get().baseUrl).getChartDaily(code, days)
                    }
                    chartCache[key] = ChartCacheItem(System.currentTimeMillis(), dto)
                    dto
                } finally {
                    chartSemaphore.release()
                }
            }
        }
    }
    suspend fun getChartDailyBatch(codes: List<String>, days: Int = 7): Result<Map<String, ChartDailyDto>> {
        val targetDays = days.coerceIn(1, 2000)
        val normalizedCodes = codes.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(60)
        if (normalizedCodes.isEmpty()) return Result.success(emptyMap())
        val now = System.currentTimeMillis()
        val out = linkedMapOf<String, ChartDailyDto>()
        val missing = mutableListOf<String>()

        normalizedCodes.forEach { code ->
            val key = chartKey(code, targetDays)
            val cached = chartCache[key]
            if (cached != null && now - cached.savedAtMs <= chartTtlMs) {
                out[code] = cached.payload
            } else {
                missing += code
            }
        }
        if (missing.isEmpty()) return Result.success(out)

        return runCatching {
            val s = settingsStore.get()
            val dto = NetworkModule.api(s.baseUrl).getChartDailyBatch(
                ChartDailyBatchRequestDto(
                    tickers = missing,
                    days = targetDays,
                    interval = "1d",
                )
            )
            val fetchedAt = System.currentTimeMillis()
            dto.items.orEmpty().forEach { item ->
                val code = item.code.orEmpty().trim()
                if (code.isBlank() || !item.error.isNullOrBlank()) return@forEach
                val payload = ChartDailyDto(
                    name = item.name,
                    points = item.points.orEmpty(),
                )
                chartCache[chartKey(code, targetDays)] = ChartCacheItem(fetchedAt, payload)
                out[code] = payload
            }
            out
        }.recoverCatching {
            missing.forEach { code ->
                getChartDaily(code, targetDays).getOrNull()?.let { dto ->
                    out[code] = dto
                }
            }
            out
        }
    }
    suspend fun getStockInvestorDaily(
        ticker: String,
        days: Int = 60,
    ): Result<StockInvestorDailyResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getStockInvestorDaily(
            ticker = ticker,
            days = days.coerceIn(5, 180),
        )
    }

    suspend fun getStockIntradayTrend(
        ticker: String,
        limit: Int = 80,
    ): Result<StockTrendIntradayResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getStockIntradayTrend(
            ticker = ticker,
            limit = limit.coerceIn(10, 240),
        )
    }

    suspend fun getPaperRecommendations(date: String): Result<DataWithSource<PremarketReportDto>> = getPremarket(date)
    suspend fun getStrategySettings(): Result<StrategySettingsResponseDto> = runCatching {
        NetworkModule.slowApi(settingsStore.get().baseUrl).getStrategySettings()
    }
    suspend fun updateStrategySettings(payload: StrategySettingsDto): Result<StrategySettingsResponseDto> = runCatching { NetworkModule.api(settingsStore.get().baseUrl).updateStrategySettings(payload) }
    suspend fun getLatestApkInfo(): Result<LatestApkInfoDto> = runCatching {
        // Update metadata should be fetched from the baked-in distribution host first.
        // Users may set baseUrl to USB/LAN/localhost which would otherwise break update checks.
        val defaultBase = NetworkModule.defaultBaseUrl()
        try {
            NetworkModule.api(defaultBase).getLatestApkInfo()
        } catch (_: Exception) {
            NetworkModule.api(settingsStore.get().baseUrl).getLatestApkInfo()
        }
    }
    suspend fun getMarketMovers(mode: String, period: String = "1d", count: Int = 100): Result<MoversResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getMarketMovers(mode = mode, period = period, count = count)
    }
    suspend fun getMarketMovers2(
        session: String,
        direction: String = "up",
        count: Int = 100,
        universeTopValue: Int = 500,
        universeTopChg: Int = 200,
        fields: String? = null,
    ): Result<Movers2ResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getMarketMovers2(
            session = session,
            direction = direction,
            count = count,
            universeTopValue = universeTopValue,
            universeTopChg = universeTopChg,
            fields = fields,
        )
    }
    suspend fun getMarketSupply(
        count: Int = 60,
        days: Int = 20,
        universeTopValue: Int = 450,
        universeTopChg: Int = 220,
        includeContrarian: Boolean = true,
    ): Result<SupplyResponseDto> = runCatching {
        val s = settingsStore.get()
        // 수급 계산은 상류 수급/시세 조합으로 지연이 발생할 수 있어 확장 타임아웃 클라이언트를 사용한다.
        NetworkModule.slowApi(s.baseUrl).getMarketSupply(
            count = count,
            days = days,
            universeTopValue = universeTopValue,
            universeTopChg = universeTopChg,
            includeContrarian = includeContrarian,
        )
    }
    suspend fun getUsInsiders(
        targetCount: Int = 10,
        tradingDays: Int = 10,
        expandDays: Int = 20,
        maxCandidates: Int = 120,
        transactionCodes: String = "ALL",
        force: Boolean = false,
    ): Result<UsInsiderResponseDto> = runCatching {
        val s = settingsStore.get()
        // SEC Form4 scan can exceed the default 20s read timeout depending on candidate count.
        NetworkModule.slowApi(s.baseUrl).getUsInsiders(
            targetCount = targetCount,
            tradingDays = tradingDays,
            expandDays = expandDays,
            maxCandidates = maxCandidates,
            transactionCodes = transactionCodes,
            force = force,
        )
    }
    suspend fun getFavorites(): Result<List<FavoriteItemDto>> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getFavorites().items.orEmpty()
    }
    suspend fun upsertFavorite(
        ticker: String,
        name: String?,
        baselinePrice: Double,
        sourceTab: String? = null,
        favoritedAt: String? = null,
    ): Result<FavoriteItemDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).upsertFavorite(
            FavoriteUpsertRequestDto(
                ticker = ticker,
                name = name,
                baselinePrice = baselinePrice,
                sourceTab = sourceTab,
                favoritedAt = favoritedAt,
            )
        )
    }
    suspend fun deleteFavorite(ticker: String): Result<Unit> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).deleteFavorite(ticker)
        Unit
    }

    suspend fun getAutoTradeSettings(): Result<AutoTradeSettingsResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).getAutoTradeSettings()
    }

    suspend fun updateAutoTradeSettings(payload: AutoTradeSettingsDto): Result<AutoTradeSettingsResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).updateAutoTradeSettings(payload)
    }

    suspend fun getAutoTradeSymbolRules(): Result<AutoTradeSymbolRulesResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getAutoTradeSymbolRules()
    }

    suspend fun upsertAutoTradeSymbolRule(payload: AutoTradeSymbolRuleUpsertDto): Result<AutoTradeSymbolRuleItemDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).upsertAutoTradeSymbolRule(payload)
    }

    suspend fun deleteAutoTradeSymbolRule(ticker: String): Result<Unit> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).deleteAutoTradeSymbolRule(ticker)
        Unit
    }

    suspend fun getAutoTradeBrokerCredential(): Result<AutoTradeBrokerCredentialDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).getAutoTradeBrokerCredential()
    }

    suspend fun updateAutoTradeBrokerCredential(payload: AutoTradeBrokerCredentialUpdateDto): Result<AutoTradeBrokerCredentialDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).updateAutoTradeBrokerCredential(payload)
    }

    suspend fun getAutoTradeBootstrap(fast: Boolean = true): Result<AutoTradeBootstrapResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).getAutoTradeBootstrap(fast = fast)
    }

    suspend fun getAutoTradeCandidates(limit: Int = 300, profile: String = "full"): Result<AutoTradeCandidatesResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).getAutoTradeCandidates(limit = limit, profile = profile)
    }

    suspend fun runAutoTrade(
        dryRun: Boolean = false,
        limit: Int? = null,
        reserveIfClosed: Boolean = false,
    ): Result<AutoTradeRunResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).runAutoTrade(
            AutoTradeRunRequestDto(
                dryRun = dryRun,
                limit = limit,
                reserveIfClosed = reserveIfClosed,
            )
        )
    }

    suspend fun getAutoTradeReservations(
        status: String? = null,
        limit: Int = 30,
    ): Result<AutoTradeReservationsResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getAutoTradeReservations(
            status = status,
            limit = limit,
        )
    }

    suspend fun confirmAutoTradeReservation(
        reservationId: Int,
    ): Result<AutoTradeReservationActionResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).confirmAutoTradeReservation(reservationId = reservationId)
    }

    suspend fun cancelAutoTradeReservation(
        reservationId: Int,
    ): Result<AutoTradeReservationActionResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).cancelAutoTradeReservation(reservationId = reservationId)
    }

    suspend fun cancelAutoTradeReservationItem(
        reservationId: Int,
        ticker: String,
    ): Result<AutoTradeReservationActionResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).cancelAutoTradeReservationItem(
            reservationId = reservationId,
            ticker = ticker,
        )
    }

    suspend fun cancelAutoTradePendingReservations(
        environment: String? = null,
        maxCount: Int = 30,
    ): Result<AutoTradeReservationPendingCancelResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).cancelAutoTradePendingReservations(
            payload = AutoTradeReservationPendingCancelRequestDto(
                environment = environment,
                maxCount = maxCount,
            )
        )
    }

    suspend fun cancelAutoTradeOrder(
        orderId: Int,
        environment: String? = null,
    ): Result<AutoTradeOrderCancelResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).cancelAutoTradeOrder(
            orderId = orderId,
            environment = environment,
        )
    }

    suspend fun cancelAutoTradePendingOrders(
        environment: String? = null,
        maxCount: Int = 20,
    ): Result<AutoTradePendingCancelResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).cancelAutoTradePendingOrders(
            payload = AutoTradePendingCancelRequestDto(
                environment = environment,
                maxCount = maxCount,
            )
        )
    }

    suspend fun getAutoTradeReentryBlocks(
        environment: String? = null,
        triggerReason: String? = null,
        limit: Int = 200,
    ): Result<AutoTradeReentryBlocksResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getAutoTradeReentryBlocks(
            environment = environment,
            triggerReason = triggerReason,
            limit = limit,
        )
    }

    suspend fun releaseAutoTradeReentryBlocks(
        environment: String? = null,
        ticker: String? = null,
        triggerReason: String? = null,
        releaseAll: Boolean = false,
    ): Result<AutoTradeReentryReleaseResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).releaseAutoTradeReentryBlocks(
            payload = AutoTradeReentryReleaseRequestDto(
                environment = environment,
                ticker = ticker,
                triggerReason = triggerReason,
                releaseAll = releaseAll,
            )
        )
    }

    suspend fun runAutoTradeManualBuy(
        ticker: String,
        name: String?,
        mode: String,
        qty: Int? = null,
        budgetKrw: Double? = null,
        requestPrice: Double? = null,
        marketOrder: Boolean? = null,
        dryRun: Boolean = false,
    ): Result<AutoTradeRunResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).runAutoTradeManualBuy(
            AutoTradeManualBuyRequestDto(
                ticker = ticker,
                name = name,
                mode = mode,
                qty = qty,
                budgetKrw = budgetKrw,
                requestPrice = requestPrice,
                marketOrder = marketOrder,
                dryRun = dryRun,
            )
        )
    }

    suspend fun runAutoTradeManualSell(
        ticker: String,
        name: String?,
        mode: String,
        qty: Int? = null,
        requestPrice: Double? = null,
        marketOrder: Boolean? = null,
        dryRun: Boolean = false,
    ): Result<AutoTradeRunResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).runAutoTradeManualSell(
            AutoTradeManualSellRequestDto(
                ticker = ticker,
                name = name,
                mode = mode,
                qty = qty,
                requestPrice = requestPrice,
                marketOrder = marketOrder,
                dryRun = dryRun,
            )
        )
    }

    suspend fun getAutoTradeOrders(
        page: Int = 1,
        size: Int = 50,
        environment: String? = null,
        status: String? = null,
        ticker: String? = null,
    ): Result<AutoTradeOrdersResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getAutoTradeOrders(
            page = page,
            size = size,
            environment = environment,
            status = status,
            ticker = ticker,
        )
    }

    suspend fun getAutoTradePerformance(
        days: Int = 30,
        environment: String? = null,
    ): Result<AutoTradePerformanceResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getAutoTradePerformance(days = days, environment = environment)
    }

    suspend fun getAutoTradeAccountSnapshot(environment: String? = null): Result<AutoTradeAccountSnapshotResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.slowApi(s.baseUrl).getAutoTradeAccountSnapshot(environment = environment)
    }

    suspend fun searchStocks(query: String, limit: Int = 50): Result<StockSearchResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).searchStocks(query = query, limit = limit)
    }

    suspend fun getNewsThemes(
        window: String = "24h",
        ymd: Int? = null,
        source: String = "all",
        eventType: String? = null,
        hideRisk: Boolean = false,
    ): Result<NewsThemesResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getNewsThemes(
            window = window,
            ymd = ymd,
            source = source,
            eventType = eventType,
            hideRisk = hideRisk,
        )
    }

    suspend fun getNewsClusters(
        window: String = "24h",
        ymd: Int? = null,
        themeKey: String? = null,
        source: String = "all",
        eventType: String? = null,
        hideRisk: Boolean = false,
        sort: String = "hot",
        limit: Int = 200,
    ): Result<NewsClustersResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getNewsClusters(
            window = window,
            ymd = ymd,
            themeKey = themeKey,
            source = source,
            eventType = eventType,
            hideRisk = hideRisk,
            sort = sort,
            limit = limit,
        )
    }

    suspend fun getNewsArticles(
        window: String = "24h",
        ymd: Int? = null,
        themeKey: String? = null,
        source: String = "all",
        eventType: String? = null,
        hideRisk: Boolean = false,
        ticker: String? = null,
        query: String? = null,
        sort: String = "latest",
        limit: Int = 200,
    ): Result<NewsArticlesResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getNewsArticles(
            window = window,
            ymd = ymd,
            themeKey = themeKey,
            source = source,
            eventType = eventType,
            hideRisk = hideRisk,
            ticker = ticker,
            query = query,
            sort = sort,
            limit = limit,
        )
    }

    suspend fun getNewsStocks(
        window: String = "24h",
        ymd: Int? = null,
        themeKey: String? = null,
        source: String = "all",
        eventType: String? = null,
        hideRisk: Boolean = false,
        sort: String = "hot",
    ): Result<NewsStocksResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getNewsStocks(
            window = window,
            ymd = ymd,
            themeKey = themeKey,
            source = source,
            eventType = eventType,
            hideRisk = hideRisk,
            sort = sort,
        )
    }

    suspend fun getNewsCluster(clusterId: Int): Result<NewsClusterResponseDto> = runCatching {
        val s = settingsStore.get()
        NetworkModule.api(s.baseUrl).getNewsCluster(clusterId)
    }

    suspend fun cacheIncomingAlert(type: String, title: String, body: String, payload: Map<String, String>) {
        runCatching {
            val entity = AlertCacheEntity(
                ts = System.currentTimeMillis().toString(),
                type = type,
                title = title,
                body = body,
                payloadJson = json.encodeToString(payload),
            )
            db.alertsDao().insert(entity)
        }
    }
    suspend fun registerDevice(fcmToken: String?) {
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
        if (deviceId.isBlank()) return
        val token = fcmToken?.trim()?.takeIf { it.isNotBlank() }
        runCatching {
            NetworkModule.api(settingsStore.get().baseUrl).registerDevice(
                DeviceRegisterRequest(
                    deviceId = deviceId,
                    fcmToken = token,
                    pref = DevicePrefDto(),
                )
            )
        }
    }
    fun getSettings(): AppSettings = settingsStore.get()
    fun saveSettings(
        baseUrl: String,
        lookbackDays: Int,
        riskPreset: String,
        themeCap: Int,
        daytradeDisplayCount: Int,
        longtermDisplayCount: Int,
        quoteRefreshSec: Int,
        daytradeVariant: Int,
        bottomTabOrderCsv: String? = null,
        cardUiVersion: String? = null,
    ) = settingsStore.save(
        baseUrl,
        lookbackDays,
        riskPreset,
        themeCap,
        daytradeDisplayCount,
        longtermDisplayCount,
        quoteRefreshSec,
        daytradeVariant,
        bottomTabOrderCsv,
        cardUiVersion,
    )

    fun saveNewsDefaults(
        defaultWindow: String,
        defaultMode: String,
        defaultSource: String,
        defaultHideRisk: Boolean,
        restoreLastFilters: Boolean,
        articleTextSizeSp: Int,
    ) = settingsStore.saveNewsDefaults(
        defaultWindow = defaultWindow,
        defaultMode = defaultMode,
        defaultSource = defaultSource,
        defaultHideRisk = defaultHideRisk,
        restoreLastFilters = restoreLastFilters,
        articleTextSizeSp = articleTextSizeSp,
    )

    fun saveNewsLastFilters(
        window: String,
        mode: String,
        source: String,
        event: String,
        hideRisk: Boolean,
    ) = settingsStore.saveNewsLastFilters(
        window = window,
        mode = mode,
        source = source,
        event = event,
        hideRisk = hideRisk,
    )

    fun saveLastAdvancedSection(section: String) = settingsStore.saveLastAdvancedSection(section)
    fun shouldShowBottomTabDragGuide(token: Int): Boolean = settingsStore.shouldShowBottomTabDragGuide(token)
    fun markBottomTabDragGuideSeen(token: Int) = settingsStore.markBottomTabDragGuideSeen(token)
}
