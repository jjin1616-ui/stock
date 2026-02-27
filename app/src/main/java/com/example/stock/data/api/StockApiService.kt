package com.example.stock.data.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface StockApiService {
    @GET("reports/premarket")
    suspend fun getPremarketReport(
        @Query("date") date: String,
        @Query("lookback") lookback: Int,
        @Query("risk") risk: String,
        @Query("theme_cap") themeCap: Int,
        @Query("variant") variant: Int,
        @Query("daytrade_limit") daytradeLimit: Int? = null,
        @Query("longterm_limit") longtermLimit: Int? = null,
        @Query("force") force: Boolean = false,
    ): PremarketReportDto

    @GET("reports/eod")
    suspend fun getEodReport(@Query("date") date: String): EodReportDto

    @GET("eval/monthly")
    suspend fun getEvalMonthly(@Query("end") end: String): EvalMonthlyDto

    @GET("alerts/history")
    suspend fun getAlertHistory(@Query("limit") limit: Int = 50): List<AlertHistoryItemDto>

    @GET("quotes/realtime")
    suspend fun getRealtimeQuotes(
        @Query("tickers") tickersCsv: String,
        @Query("mode") mode: String = "full",
    ): RealtimeQuotesDto

    @GET("papers/summary")
    suspend fun getPapersSummary(): PapersSummaryDto

    @GET("papers/recommendations")
    suspend fun getPaperRecommendations(
        @Query("date") date: String,
        @Query("lookback") lookback: Int = 10,
        @Query("risk") risk: String = "ADAPTIVE",
        @Query("theme_cap") themeCap: Int = 2,
        @Query("variant") variant: Int = 7,
    ): PremarketReportDto

    @GET("chart/daily")
    suspend fun getChartDaily(
        @Query("code") code: String,
        @Query("days") days: Int = 180,
    ): ChartDailyDto

    @POST("chart/daily/batch")
    suspend fun getChartDailyBatch(
        @Body payload: ChartDailyBatchRequestDto,
    ): ChartDailyBatchResponseDto

    @GET("stock/investor/daily")
    suspend fun getStockInvestorDaily(
        @Query("ticker") ticker: String,
        @Query("days") days: Int = 60,
    ): StockInvestorDailyResponseDto

    @GET("stock/trend/intraday")
    suspend fun getStockIntradayTrend(
        @Query("ticker") ticker: String,
        @Query("limit") limit: Int = 80,
    ): StockTrendIntradayResponseDto

    @POST("auth/login/first")
    suspend fun firstLogin(@Body payload: FirstLoginRequest): LoginResponseDto

    @POST("auth/login")
    suspend fun login(@Body payload: LoginRequest): LoginResponseDto

    @POST("auth/refresh")
    suspend fun refresh(@Body payload: RefreshTokenRequestDto): LoginResponseDto

    @GET("auth/me")
    suspend fun getMe(): UserDetailDto

    @GET("auth/menu_permissions")
    suspend fun getMyMenuPermissions(): MenuPermissionsResponseDto

    @POST("auth/profile")
    suspend fun updateProfile(@Body payload: ProfileRequest): OkResponse

    @POST("auth/password/change")
    suspend fun changePassword(@Body payload: PasswordChangeRequest): LoginResponseDto

    @POST("auth/logout")
    suspend fun logout(): OkResponse

    // Admin / Access Control v1.1
    @POST("auth/invite")
    suspend fun createInvite(@Body payload: InviteCreateRequestDto): InviteCreateResponseDto

    @POST("auth/invite/{user_code}/mark_sent")
    suspend fun markInviteSent(@Path("user_code") userCode: String): InviteMarkSentResponseDto

    @GET("admin/users")
    suspend fun adminListUsers(@Query("page") page: Int = 1, @Query("size") size: Int = 50): UsersListResponseDto

    @GET("admin/users/{user_code}/menu_permissions")
    suspend fun adminGetUserMenuPermissions(@Path("user_code") userCode: String): MenuPermissionsResponseDto

    @POST("admin/users/{user_code}/menu_permissions")
    suspend fun adminUpdateUserMenuPermissions(
        @Path("user_code") userCode: String,
        @Body payload: MenuPermissionsDto,
    ): MenuPermissionsResponseDto

    @GET("admin/my/invited_users")
    suspend fun adminListMyInvitedUsers(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 50,
    ): MyInvitedUsersResponseDto

    @POST("admin/users/{user_code}/identity")
    suspend fun adminUpdateUserIdentity(
        @Path("user_code") userCode: String,
        @Body payload: UserIdentityUpdateRequestDto,
    ): UserDetailDto

    @POST("admin/users/{user_code}/revoke_sessions")
    suspend fun adminRevokeUserSessions(@Path("user_code") userCode: String): OkResponse

    @GET("admin/users/{user_code}/login_logs")
    suspend fun adminGetUserLoginLogs(
        @Path("user_code") userCode: String,
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 50,
    ): UserLoginLogsResponseDto

    @GET("admin/users/{user_code}/autotrade_overview")
    suspend fun adminGetUserAutotradeOverview(
        @Path("user_code") userCode: String,
        @Query("days") days: Int = 30,
        @Query("order_size") orderSize: Int = 50,
    ): AdminUserAutoTradeOverviewDto

    @POST("admin/users/{user_code}/block")
    suspend fun adminBlockUser(@Path("user_code") userCode: String): OkResponse

    @POST("admin/users/{user_code}/unblock")
    suspend fun adminUnblockUser(@Path("user_code") userCode: String): OkResponse

    @POST("admin/users/{user_code}/reset_password")
    suspend fun adminResetPassword(
        @Path("user_code") userCode: String,
        @Body payload: PasswordResetRequestDto,
    ): InviteCreateResponseDto

    @POST("admin/push/send")
    suspend fun adminSendPush(@Body payload: AdminPushSendRequestDto): AdminPushSendResponseDto

    @GET("admin/push/status")
    suspend fun adminGetPushStatus(): AdminPushStatusResponseDto

    @GET("api/settings")
    suspend fun getStrategySettings(): StrategySettingsResponseDto

    @POST("api/settings")
    suspend fun updateStrategySettings(@Body payload: StrategySettingsDto): StrategySettingsResponseDto

    @POST("device/register")
    suspend fun registerDevice(@Body request: DeviceRegisterRequest): OkResponse

    // Static update metadata served by nginx (no auth required).
    @GET("apk/latest.json")
    suspend fun getLatestApkInfo(): LatestApkInfoDto

    @GET("market/movers")
    suspend fun getMarketMovers(
        @Query("mode") mode: String = "chg",
        @Query("period") period: String = "1d",
        @Query("count") count: Int = 100,
        @Query("pool_size") poolSize: Int = 120,
        @Query("markets") markets: String = "KOSPI,KOSDAQ",
    ): MoversResponseDto

    @GET("market/movers2")
    suspend fun getMarketMovers2(
        @Query("session") session: String = "regular",
        @Query("direction") direction: String = "up",
        @Query("count") count: Int = 100,
        @Query("universe_top_value") universeTopValue: Int = 500,
        @Query("universe_top_chg") universeTopChg: Int = 200,
        @Query("markets") markets: String = "KOSPI,KOSDAQ",
        @Query("fields") fields: String? = null,
    ): Movers2ResponseDto

    @GET("market/supply")
    suspend fun getMarketSupply(
        @Query("count") count: Int = 60,
        @Query("days") days: Int = 20,
        @Query("universe_top_value") universeTopValue: Int = 450,
        @Query("universe_top_chg") universeTopChg: Int = 220,
        @Query("markets") markets: String = "KOSPI,KOSDAQ",
        @Query("include_contrarian") includeContrarian: Boolean = true,
    ): SupplyResponseDto

    @GET("market/us-insiders")
    suspend fun getUsInsiders(
        @Query("target_count") targetCount: Int = 10,
        @Query("trading_days") tradingDays: Int = 10,
        @Query("expand_days") expandDays: Int = 20,
        @Query("max_candidates") maxCandidates: Int = 120,
        @Query("transaction_codes") transactionCodes: String = "ALL",
        @Query("force") force: Boolean = false,
    ): UsInsiderResponseDto

    @GET("favorites")
    suspend fun getFavorites(): FavoritesResponseDto

    @GET("stocks/search")
    suspend fun searchStocks(
        @Query("q") query: String,
        @Query("limit") limit: Int = 50,
    ): StockSearchResponseDto

    @POST("favorites")
    suspend fun upsertFavorite(@Body payload: FavoriteUpsertRequestDto): FavoriteItemDto

    @DELETE("favorites/{ticker}")
    suspend fun deleteFavorite(@Path("ticker") ticker: String): OkResponse

    @GET("autotrade/settings")
    suspend fun getAutoTradeSettings(): AutoTradeSettingsResponseDto

    @POST("autotrade/settings")
    suspend fun updateAutoTradeSettings(@Body payload: AutoTradeSettingsDto): AutoTradeSettingsResponseDto

    @GET("autotrade/symbol-rules")
    suspend fun getAutoTradeSymbolRules(): AutoTradeSymbolRulesResponseDto

    @POST("autotrade/symbol-rules")
    suspend fun upsertAutoTradeSymbolRule(@Body payload: AutoTradeSymbolRuleUpsertDto): AutoTradeSymbolRuleItemDto

    @DELETE("autotrade/symbol-rules/{ticker}")
    suspend fun deleteAutoTradeSymbolRule(@Path("ticker") ticker: String): OkResponse

    @GET("autotrade/broker")
    suspend fun getAutoTradeBrokerCredential(): AutoTradeBrokerCredentialDto

    @POST("autotrade/broker")
    suspend fun updateAutoTradeBrokerCredential(@Body payload: AutoTradeBrokerCredentialUpdateDto): AutoTradeBrokerCredentialDto

    @GET("autotrade/bootstrap")
    suspend fun getAutoTradeBootstrap(): AutoTradeBootstrapResponseDto

    @GET("autotrade/candidates")
    suspend fun getAutoTradeCandidates(
        @Query("limit") limit: Int = 50,
        @Query("profile") profile: String = "full",
    ): AutoTradeCandidatesResponseDto

    @POST("autotrade/run")
    suspend fun runAutoTrade(@Body payload: AutoTradeRunRequestDto): AutoTradeRunResponseDto

    @GET("autotrade/reservations")
    suspend fun getAutoTradeReservations(
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 30,
    ): AutoTradeReservationsResponseDto

    @POST("autotrade/reservations/{reservation_id}/confirm")
    suspend fun confirmAutoTradeReservation(
        @Path("reservation_id") reservationId: Int,
    ): AutoTradeReservationActionResponseDto

    @POST("autotrade/reservations/{reservation_id}/cancel")
    suspend fun cancelAutoTradeReservation(
        @Path("reservation_id") reservationId: Int,
    ): AutoTradeReservationActionResponseDto

    @POST("autotrade/orders/{order_id}/cancel")
    suspend fun cancelAutoTradeOrder(
        @Path("order_id") orderId: Int,
        @Query("environment") environment: String? = null,
    ): AutoTradeOrderCancelResponseDto

    @POST("autotrade/orders/pending-cancel")
    suspend fun cancelAutoTradePendingOrders(
        @Body payload: AutoTradePendingCancelRequestDto,
    ): AutoTradePendingCancelResponseDto

    @GET("autotrade/reentry-blocks")
    suspend fun getAutoTradeReentryBlocks(
        @Query("environment") environment: String? = null,
        @Query("trigger_reason") triggerReason: String? = null,
        @Query("limit") limit: Int = 200,
    ): AutoTradeReentryBlocksResponseDto

    @POST("autotrade/reentry-blocks/release")
    suspend fun releaseAutoTradeReentryBlocks(
        @Body payload: AutoTradeReentryReleaseRequestDto,
    ): AutoTradeReentryReleaseResponseDto

    @POST("autotrade/manual-buy")
    suspend fun runAutoTradeManualBuy(@Body payload: AutoTradeManualBuyRequestDto): AutoTradeRunResponseDto

    @POST("autotrade/manual-sell")
    suspend fun runAutoTradeManualSell(@Body payload: AutoTradeManualSellRequestDto): AutoTradeRunResponseDto

    @GET("autotrade/orders")
    suspend fun getAutoTradeOrders(
        @Query("page") page: Int = 1,
        @Query("size") size: Int = 50,
    ): AutoTradeOrdersResponseDto

    @GET("autotrade/performance")
    suspend fun getAutoTradePerformance(@Query("days") days: Int = 30): AutoTradePerformanceResponseDto

    @GET("autotrade/account")
    suspend fun getAutoTradeAccountSnapshot(@Query("environment") environment: String? = null): AutoTradeAccountSnapshotResponseDto

    // --- News (Hybrid) ---

    @GET("api/news/themes")
    suspend fun getNewsThemes(
        @Query("window") window: String = "24h",
        @Query("ymd") ymd: Int? = null,
        @Query("source") source: String = "all",
        @Query("event_type") eventType: String? = null,
        @Query("hide_risk") hideRisk: Boolean = false,
    ): NewsThemesResponseDto

    @GET("api/news/clusters")
    suspend fun getNewsClusters(
        @Query("window") window: String = "24h",
        @Query("ymd") ymd: Int? = null,
        @Query("theme_key") themeKey: String? = null,
        @Query("source") source: String = "all",
        @Query("event_type") eventType: String? = null,
        @Query("hide_risk") hideRisk: Boolean = false,
        @Query("sort") sort: String = "hot",
        @Query("limit") limit: Int = 200,
    ): NewsClustersResponseDto

    @GET("api/news/articles")
    suspend fun getNewsArticles(
        @Query("window") window: String = "24h",
        @Query("ymd") ymd: Int? = null,
        @Query("theme_key") themeKey: String? = null,
        @Query("source") source: String = "all",
        @Query("event_type") eventType: String? = null,
        @Query("hide_risk") hideRisk: Boolean = false,
        @Query("ticker") ticker: String? = null,
        @Query("q") query: String? = null,
        @Query("sort") sort: String = "latest",
        @Query("limit") limit: Int = 200,
    ): NewsArticlesResponseDto

    @GET("api/news/stocks")
    suspend fun getNewsStocks(
        @Query("window") window: String = "24h",
        @Query("ymd") ymd: Int? = null,
        @Query("theme_key") themeKey: String? = null,
        @Query("source") source: String = "all",
        @Query("event_type") eventType: String? = null,
        @Query("hide_risk") hideRisk: Boolean = false,
        @Query("sort") sort: String = "hot",
    ): NewsStocksResponseDto

    @GET("api/news/cluster/{cluster_id}")
    suspend fun getNewsCluster(
        @Path("cluster_id") clusterId: Int,
    ): NewsClusterResponseDto
}
