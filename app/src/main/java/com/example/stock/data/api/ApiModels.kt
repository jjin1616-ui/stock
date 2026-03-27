package com.example.stock.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PremarketReportDto(
    val date: String? = "",
    @SerialName("generated_at") val generatedAt: String? = "",
    val status: ResponseStatusDto? = ResponseStatusDto(),
    @SerialName("daytrade_gate") val daytradeGate: DaytradeGateDto? = null,
    @SerialName("daytrade_top") val daytradeTop: List<DaytradeTopItemDto>? = emptyList(),
    @SerialName("daytrade_primary") val daytradePrimary: List<DaytradeTopItemDto>? = emptyList(),
    @SerialName("daytrade_watch") val daytradeWatch: List<DaytradeTopItemDto>? = emptyList(),
    @SerialName("daytrade_top10") val daytradeTop10: List<DaytradeTopItemDto>? = emptyList(),
    val longterm: List<LongtermItemDto>? = emptyList(),
    @SerialName("longterm_top10") val longtermTop10: List<LongtermItemDto>? = emptyList(),
    @SerialName("overlap_bucket") val overlapBucket: List<DaytradeTopItemDto>? = emptyList(),
    @SerialName("base_top10") val baseTop10: List<DaytradeTopItemDto>? = emptyList(),
    @SerialName("var7_top10") val var7Top10: List<DaytradeTopItemDto>? = emptyList(),
    @SerialName("delta_kpi") val deltaKpi: DeltaKpiDto? = DeltaKpiDto(),
    @SerialName("delta_explain") val deltaExplain: List<String>? = emptyList(),
    val themes: List<ThemeItemDto>? = emptyList(),
    @SerialName("hard_rules") val hardRules: List<String>? = emptyList(),
    val regime: RegimeDto? = null,
    val briefing: String? = null,
    @SerialName("market_temperature") val marketTemperature: MarketTemperatureDto? = null,
)

@Serializable
data class RegimeDto(
    val mode: String? = null,
    val bullets: List<String>? = emptyList(),
    @SerialName("market_snapshot") val marketSnapshot: MarketSnapshotDto? = null,
)

@Serializable
data class MarketSnapshotDto(
    @SerialName("kospi_close") val kospiClose: Double? = null,
    @SerialName("kosdaq_close") val kosdaqClose: Double? = null,
    @SerialName("usdkrw_close") val usdkrwClose: Double? = null,
)

@Serializable
data class MarketTemperatureDto(
    val score: Int? = null,
    val label: String? = null,
    @SerialName("gate_on") val gateOn: Boolean? = null,
)

// ── 매매 피드 ──
@Serializable
data class TradeFeedItemDto(
    val time: String? = null,
    val ticker: String? = null,
    val name: String? = null,
    val side: String? = null,
    val qty: Int? = null,
    val price: Double? = null,
    val pnl: Double? = null,
)

@Serializable
data class TradeFeedResponseDto(
    val items: List<TradeFeedItemDto>? = emptyList(),
    val total: Int? = 0,
)

// ── 실시간 시장 지수 ──
@Serializable
data class MarketIndexValueDto(
    val value: Double? = null,
    val change: Double? = null,
    @SerialName("change_pct") val changePct: Double? = null,
)

@Serializable
data class MarketIndicesResponseDto(
    val kospi: MarketIndexValueDto? = null,
    val kosdaq: MarketIndexValueDto? = null,
    val usdkrw: MarketIndexValueDto? = null,
    @SerialName("as_of") val asOf: String? = null,
    val source: String? = null,
)

// ── 수익 캘린더 ──
@Serializable
data class PnlCalendarDayDto(
    val date: String? = null,
    val pnl: Double? = 0.0,
    @SerialName("trade_count") val tradeCount: Int? = 0,
)

@Serializable
data class PnlCalendarResponseDto(
    val days: List<PnlCalendarDayDto>? = emptyList(),
    @SerialName("month_total_pnl") val monthTotalPnl: Double? = 0.0,
    @SerialName("month_trade_count") val monthTradeCount: Int? = 0,
)

@Serializable
data class ResponseStatusDto(
    val source: String? = "LIVE",
    val queued: Boolean? = false,
    val message: String? = null,
    @SerialName("cache_key") val cacheKey: String? = null,
    @SerialName("settings_hash") val settingsHash: String? = null,
    @SerialName("snapshot_date") val snapshotDate: String? = null,
)

@Serializable
data class DeltaKpiDto(
    @SerialName("delta_expectancy_exec") val deltaExpectancyExec: Double? = 0.0,
    @SerialName("delta_mdd_exec") val deltaMddExec: Double? = 0.0,
    @SerialName("delta_theme_hhi") val deltaThemeHhi: Double? = 0.0,
    @SerialName("delta_cost_impact") val deltaCostImpact: Double? = 0.0,
)

@Serializable
data class DaytradeGateDto(
    val on: Boolean? = false,
    @SerialName("lookback_days") val lookbackDays: Int? = 0,
    @SerialName("gate_metric") val gateMetric: Double? = 0.0,
    @SerialName("gate_on_days") val gateOnDays: Int? = 0,
    @SerialName("gate_total_days") val gateTotalDays: Int? = 0,
    val reason: List<String>? = emptyList(),
)

@Serializable
data class DaytradeTopItemDto(
    val ticker: String? = "",
    val name: String? = "",
    val market: String? = "",
    @SerialName("theme_id") val themeId: Int? = null,
    val tags: List<String>? = emptyList(),
    @SerialName("trigger_buy") val triggerBuy: Double? = 0.0,
    @SerialName("target_1") val target1: Double? = 0.0,
    @SerialName("stop_loss") val stopLoss: Double? = 0.0,
    val thesis: String? = "",
)

@Serializable
data class LongtermItemDto(
    val ticker: String? = "",
    val name: String? = "",
    val market: String? = "",
    @SerialName("theme_id") val themeId: Int? = null,
    val tags: List<String>? = emptyList(),
    @SerialName("d1_close") val d1Close: Double? = 0.0,
    @SerialName("buy_zone") val buyZone: BuyZoneDto? = BuyZoneDto(0.0, 0.0),
    @SerialName("target_12m") val target12m: Double? = 0.0,
    @SerialName("stop_loss") val stopLoss: Double? = 0.0,
    val thesis: String? = "",
)

@Serializable
data class BuyZoneDto(val low: Double? = 0.0, val high: Double? = 0.0)

@Serializable
data class ThemeItemDto(val rank: Int? = 0, val name: String? = "", val why: String? = "")

@Serializable
data class EodReportDto(
    val date: String? = "",
    @SerialName("generated_at") val generatedAt: String? = "",
    val summary: List<String>? = emptyList(),
)

@Serializable
data class EvalMonthlyDto(
    val end: String? = "",
    @SerialName("trades_total") val tradesTotal: Int? = 0,
    @SerialName("win_rate") val winRate: Double? = 0.0,
    @SerialName("avg_r") val avgR: Double? = 0.0,
    @SerialName("expectancy_r") val expectancyR: Double? = 0.0,
    @SerialName("mdd_r") val mddR: Double? = 0.0,
)

@Serializable
data class DevicePrefDto(
    @SerialName("push_premarket") val pushPremarket: Boolean? = true,
    @SerialName("push_eod") val pushEod: Boolean? = true,
    @SerialName("push_triggers") val pushTriggers: Boolean? = true,
)

@Serializable
data class DeviceRegisterRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("fcm_token") val fcmToken: String? = null,
    val pref: DevicePrefDto? = DevicePrefDto(),
)

@Serializable
data class AlertHistoryItemDto(
    val ts: String? = "",
    val type: String? = "",
    val title: String? = "",
    val body: String? = "",
)

@Serializable
data class OkResponse(val ok: Boolean? = true)

@Serializable
data class RealtimeQuotesDto(
    @SerialName("as_of") val asOf: String? = "",
    val items: List<RealtimeQuoteItemDto>? = emptyList(),
)

@Serializable
data class RealtimeQuoteItemDto(
    val ticker: String? = "",
    val price: Double? = 0.0,
    @SerialName("prev_close") val prevClose: Double? = 0.0,
    @SerialName("chg_pct") val chgPct: Double? = 0.0,
    @SerialName("as_of") val asOf: String? = "",
    val source: String? = "",
    @SerialName("is_live") val isLive: Boolean? = false,
)

@Serializable
data class PaperSectionDto(
    val title: String? = "",
    val bullets: List<String>? = emptyList(),
)

@Serializable
data class PapersSummaryDto(
    val title: String? = "",
    @SerialName("updated_at") val updatedAt: String? = "",
    val sections: List<PaperSectionDto>? = emptyList(),
)

@Serializable
data class ChartDailyDto(val name: String? = "", val points: List<ChartPointDto>? = emptyList())

@Serializable
data class ChartPointDto(
    val date: String? = "",
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double? = 0.0,
    val volume: Double? = null
)

@Serializable
data class ChartDailyBatchRequestDto(
    val tickers: List<String> = emptyList(),
    val days: Int = 7,
    val interval: String = "1d",
)

@Serializable
data class ChartDailyBatchItemDto(
    val code: String? = "",
    val name: String? = "",
    val points: List<ChartPointDto>? = emptyList(),
    val error: String? = null,
)

@Serializable
data class ChartDailyBatchResponseDto(
    @SerialName("as_of") val asOf: String? = "",
    val items: List<ChartDailyBatchItemDto>? = emptyList(),
)

@Serializable
data class StockInvestorDailyItemDto(
    val date: String? = "",
    @SerialName("individual_qty") val individualQty: Long? = 0,
    @SerialName("foreign_qty") val foreignQty: Long? = 0,
    @SerialName("institution_qty") val institutionQty: Long? = 0,
    @SerialName("private_fund_qty") val privateFundQty: Long? = 0,
    @SerialName("corporate_qty") val corporateQty: Long? = 0,
    @SerialName("financial_investment_qty") val financialInvestmentQty: Long? = 0,
    @SerialName("insurance_qty") val insuranceQty: Long? = 0,
    @SerialName("trust_qty") val trustQty: Long? = 0,
    @SerialName("pension_qty") val pensionQty: Long? = 0,
    @SerialName("bank_qty") val bankQty: Long? = 0,
    @SerialName("etc_finance_qty") val etcFinanceQty: Long? = 0,
    @SerialName("other_foreign_qty") val otherForeignQty: Long? = 0,
    @SerialName("total_qty") val totalQty: Long? = 0,
    @SerialName("individual_value") val individualValue: Double? = 0.0,
    @SerialName("foreign_value") val foreignValue: Double? = 0.0,
    @SerialName("institution_value") val institutionValue: Double? = 0.0,
    @SerialName("private_fund_value") val privateFundValue: Double? = 0.0,
    @SerialName("corporate_value") val corporateValue: Double? = 0.0,
    @SerialName("total_value") val totalValue: Double? = 0.0,
)

@Serializable
data class StockInvestorDailyResponseDto(
    val ticker: String? = "",
    val name: String? = null,
    @SerialName("as_of") val asOf: String? = "",
    val source: String? = "LIVE",
    val message: String? = null,
    val days: Int? = 60,
    val items: List<StockInvestorDailyItemDto>? = emptyList(),
)

@Serializable
data class StockTrendIntradayItemDto(
    val time: String? = "",
    @SerialName("current_price") val currentPrice: Double? = 0.0,
    @SerialName("change_abs") val changeAbs: Double? = 0.0,
    @SerialName("change_pct") val changePct: Double? = 0.0,
    @SerialName("volume_delta") val volumeDelta: Long? = 0,
    @SerialName("cumulative_volume") val cumulativeVolume: Long? = 0,
    @SerialName("net_buy_qty_estimate") val netBuyQtyEstimate: Long? = 0,
    val direction: String? = "FLAT",
)

@Serializable
data class StockTrendIntradayResponseDto(
    val ticker: String? = "",
    val name: String? = null,
    @SerialName("as_of") val asOf: String? = "",
    @SerialName("prev_close") val prevClose: Double? = 0.0,
    val source: String? = "LIVE",
    val message: String? = null,
    @SerialName("window_minutes") val windowMinutes: Int? = 0,
    val items: List<StockTrendIntradayItemDto>? = emptyList(),
)

@Serializable
data class StrategySettingsDto(
    @SerialName("risk_preset") val riskPreset: String? = "ADAPTIVE",
    @SerialName("use_custom_weights") val useCustomWeights: Boolean? = false,
    @SerialName("w_ta") val wTa: Double? = null,
    @SerialName("w_re") val wRe: Double? = null,
    @SerialName("w_rs") val wRs: Double? = null,
    @SerialName("theme_cap") val themeCap: Int? = 2,
    @SerialName("max_gap_pct") val maxGapPct: Double? = 0.0,
    @SerialName("gate_threshold") val gateThreshold: Double? = 0.0,
    @SerialName("gate_quantile") val gateQuantile: Double? = null,
)

@Serializable
data class StrategySettingsResponseDto(
    val settings: StrategySettingsDto? = StrategySettingsDto(),
    @SerialName("settings_hash") val settingsHash: String? = "",
)

@Serializable
data class FirstLoginRequest(
    @SerialName("user_code") val userCode: String,
    @SerialName("initial_password") val initialPassword: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class LoginRequest(
    @SerialName("user_code") val userCode: String,
    val password: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class LoginResponseDto(
    val token: String? = "",
    @SerialName("expires_at") val expiresAt: String? = "",
    @SerialName("refresh_token") val refreshToken: String? = "",
    @SerialName("refresh_expires_at") val refreshExpiresAt: String? = "",
    @SerialName("force_password_change") val forcePasswordChange: Boolean? = false,
    @SerialName("invite_status") val inviteStatus: String? = "",
    val role: String? = "",
)

@Serializable
data class RefreshTokenRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class ProfileRequest(
    val name: String,
    val phone: String,
    val consent: Boolean = true,
)

@Serializable
data class PasswordChangeRequest(
    @SerialName("current_password") val currentPassword: String? = null,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class UserDetailDto(
    @SerialName("user_code") val userCode: String? = "",
    val name: String? = "",
    val phone: String? = "",
    val role: String? = "",
    val status: String? = "",
    @SerialName("last_login_at") val lastLoginAt: String? = "",
    @SerialName("failed_attempts") val failedAttempts: Int? = 0,
    @SerialName("locked_until") val lockedUntil: String? = "",
    @SerialName("invite_status") val inviteStatus: String? = "",
    @SerialName("created_at") val createdAt: String? = "",
    val memo: String? = "",
    @SerialName("force_password_change") val forcePasswordChange: Boolean? = false,
    @SerialName("expires_at") val expiresAt: String? = "",
    @SerialName("device_binding_enabled") val deviceBindingEnabled: Boolean? = false,
    @SerialName("bound_device_id") val boundDeviceId: String? = "",
)

@Serializable
data class MenuPermissionsDto(
    @SerialName("menu_daytrade") val menuDaytrade: Boolean? = true,
    @SerialName("menu_autotrade") val menuAutotrade: Boolean? = true,
    @SerialName("menu_holdings") val menuHoldings: Boolean? = true,
    @SerialName("menu_supply") val menuSupply: Boolean? = true,
    @SerialName("menu_movers") val menuMovers: Boolean? = true,
    @SerialName("menu_us") val menuUs: Boolean? = true,
    @SerialName("menu_news") val menuNews: Boolean? = true,
    @SerialName("menu_longterm") val menuLongterm: Boolean? = true,
    @SerialName("menu_papers") val menuPapers: Boolean? = true,
    @SerialName("menu_eod") val menuEod: Boolean? = true,
    @SerialName("menu_alerts") val menuAlerts: Boolean? = true,
)

@Serializable
data class MenuPermissionsResponseDto(
    @SerialName("user_code") val userCode: String? = "",
    val permissions: MenuPermissionsDto? = MenuPermissionsDto(),
    @SerialName("updated_at") val updatedAt: String? = "",
    @SerialName("inherited_default") val inheritedDefault: Boolean? = false,
)

// --- Admin/Auth (Access Control v1.1) ---

@Serializable
data class InviteCreateRequestDto(
    @SerialName("user_code") val userCode: String? = null,
    val name: String? = null,
    @SerialName("password_mode") val passwordMode: String? = "AUTO", // AUTO|MANUAL
    @SerialName("initial_password") val initialPassword: String? = null,
    val role: String? = "USER", // USER|MASTER
    @SerialName("expires_in_days") val expiresInDays: Int? = 7,
    val memo: String? = null,
    @SerialName("device_binding_enabled") val deviceBindingEnabled: Boolean? = false,
)

@Serializable
data class InviteCreateResponseDto(
    @SerialName("user_code") val userCode: String? = "",
    @SerialName("initial_password") val initialPassword: String? = "",
    @SerialName("expires_at") val expiresAt: String? = "",
    @SerialName("invite_status") val inviteStatus: String? = "",
)

@Serializable
data class InviteMarkSentResponseDto(
    val ok: Boolean? = true,
    @SerialName("invite_status") val inviteStatus: String? = "",
)

@Serializable
data class PasswordResetRequestDto(
    @SerialName("password_mode") val passwordMode: String? = "AUTO",
    @SerialName("initial_password") val initialPassword: String? = null,
    @SerialName("expires_in_days") val expiresInDays: Int? = 7,
)

@Serializable
data class UserSummaryDto(
    @SerialName("user_code") val userCode: String? = "",
    val name: String? = "",
    val phone: String? = "",
    val role: String? = "",
    val status: String? = "",
    @SerialName("last_login_at") val lastLoginAt: String? = "",
    @SerialName("failed_attempts") val failedAttempts: Int? = 0,
    @SerialName("locked_until") val lockedUntil: String? = "",
    @SerialName("invite_status") val inviteStatus: String? = "",
    @SerialName("created_at") val createdAt: String? = "",
)

@Serializable
data class UsersListResponseDto(
    val items: List<UserSummaryDto>? = emptyList(),
    val total: Int? = 0,
)

@Serializable
data class InvitedUserSummaryDto(
    @SerialName("user_code") val userCode: String? = "",
    val name: String? = "",
    val phone: String? = "",
    val role: String? = "",
    val status: String? = "",
    @SerialName("last_login_at") val lastLoginAt: String? = "",
    @SerialName("failed_attempts") val failedAttempts: Int? = 0,
    @SerialName("locked_until") val lockedUntil: String? = "",
    @SerialName("invite_status") val inviteStatus: String? = "",
    @SerialName("created_at") val createdAt: String? = "",
    @SerialName("invited_at") val invitedAt: String? = "",
)

@Serializable
data class MyInvitedUsersResponseDto(
    val items: List<InvitedUserSummaryDto>? = emptyList(),
    val total: Int? = 0,
)

@Serializable
data class UserIdentityUpdateRequestDto(
    @SerialName("user_code") val userCode: String? = null,
    val name: String? = null,
    val memo: String? = null,
    val phone: String? = null,
)

@Serializable
data class LoginEventItemDto(
    val timestamp: String? = "",
    @SerialName("user_code") val userCode: String? = "",
    val result: String? = "",
    @SerialName("reason_code") val reasonCode: String? = "",
    val ip: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
data class UserLoginLogSummaryDto(
    @SerialName("success_count") val successCount: Int? = 0,
    @SerialName("fail_count") val failCount: Int? = 0,
    @SerialName("reason_counts") val reasonCounts: Map<String, Int>? = emptyMap(),
    @SerialName("last_success_at") val lastSuccessAt: String? = "",
    @SerialName("last_fail_at") val lastFailAt: String? = "",
    @SerialName("active_session_count") val activeSessionCount: Int? = 0,
)

@Serializable
data class UserLoginLogsResponseDto(
    val user: UserSummaryDto? = UserSummaryDto(),
    val total: Int? = 0,
    val items: List<LoginEventItemDto>? = emptyList(),
    val summary: UserLoginLogSummaryDto? = UserLoginLogSummaryDto(),
)

@Serializable
data class AdminPushSendRequestDto(
    val title: String,
    val body: String,
    val target: String = "all", // all | active_7d | test
    @SerialName("alert_type") val alertType: String = "ADMIN", // ADMIN | UPDATE
    val route: String? = null,
    @SerialName("dry_run") val dryRun: Boolean = false,
)

@Serializable
data class AdminPushSendResponseDto(
    val ok: Boolean? = false,
    @SerialName("target_count") val targetCount: Int? = 0,
    @SerialName("token_count") val tokenCount: Int? = 0,
    @SerialName("sent_count") val sentCount: Int? = 0,
    @SerialName("failed_count") val failedCount: Int? = 0,
    @SerialName("skipped_count") val skippedCount: Int? = 0,
    @SerialName("skipped_no_token_count") val skippedNoTokenCount: Int? = 0,
    @SerialName("skipped_pref_count") val skippedPrefCount: Int? = 0,
    @SerialName("push_ready") val pushReady: Boolean? = false,
    val message: String? = "",
    @SerialName("sample_tokens_masked") val sampleTokensMasked: List<String>? = emptyList(),
)

@Serializable
data class AdminPushStatusResponseDto(
    @SerialName("push_ready") val pushReady: Boolean? = false,
    @SerialName("all_device_count") val allDeviceCount: Int? = 0,
    @SerialName("all_token_count") val allTokenCount: Int? = 0,
    @SerialName("active_7d_device_count") val active7dDeviceCount: Int? = 0,
    @SerialName("active_7d_token_count") val active7dTokenCount: Int? = 0,
)

// --- App Update (static served via nginx: /apk/latest.json) ---

@Serializable
data class LatestApkInfoDto(
    @SerialName("version_code") val versionCode: Int? = 0,
    @SerialName("version_name") val versionName: String? = "",
    @SerialName("build_label") val buildLabel: String? = "",
    @SerialName("build_type") val buildType: String? = "",
    @SerialName("apk_url") val apkUrl: String? = "",
    @SerialName("apk_versioned_url") val apkVersionedUrl: String? = "",
    @SerialName("apk_filename") val apkFilename: String? = "",
    @SerialName("sha256_url") val sha256Url: String? = "",
    val sha256: String? = null,
    val notes: String? = null,
    @SerialName("published_at") val publishedAt: String? = "",
    @SerialName("min_supported_version_code") val minSupportedVersionCode: Int? = null,
)

// --- Movers (급등주) ---

@Serializable
data class MoverItemDto(
    val ticker: String? = "",
    val name: String? = "",
    val market: String? = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("logo_png_url") val logoPngUrl: String? = null,
    val tags: List<String>? = emptyList(),
    val rank: Int? = null,
    @SerialName("search_ratio") val searchRatio: Double? = null,
    val price: Double? = 0.0,
    @SerialName("prev_close") val prevClose: Double? = 0.0,
    @SerialName("chg_pct") val chgPct: Double? = 0.0,
    @SerialName("as_of") val asOf: String? = "",
    val source: String? = "",
    @SerialName("is_live") val isLive: Boolean? = false,
    val volume: Double? = 0.0,
    val value: Double? = 0.0,
    @SerialName("baseline_value") val baselineValue: Double? = null,
    @SerialName("value_ratio") val valueRatio: Double? = null,
)

@Serializable
data class MoversResponseDto(
    @SerialName("as_of") val asOf: String? = "",
    @SerialName("bas_dd") val basDd: String? = "",
    @SerialName("ref_bas_dd") val refBasDd: String? = "",
    val period: String? = "1d",
    val mode: String? = "",
    val items: List<MoverItemDto>? = emptyList(),
)

@Serializable
data class Mover2ItemDto(
    val ticker: String? = "",
    val name: String? = "",
    val market: String? = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("logo_png_url") val logoPngUrl: String? = null,
    val tags: List<String>? = emptyList(),
    val price: Double? = 0.0,
    @SerialName("prev_close") val prevClose: Double? = 0.0,
    @SerialName("chg_pct") val chgPct: Double? = 0.0,
    @SerialName("as_of") val asOf: String? = "",
    val source: String? = "",
    @SerialName("is_live") val isLive: Boolean? = false,
    val volume: Double? = 0.0,
    val value: Double? = 0.0,
    @SerialName("baseline_value") val baselineValue: Double? = null,
    @SerialName("value_ratio") val valueRatio: Double? = null,
    val session: String? = "regular",
    @SerialName("flow_source") val flowSource: String? = "MISSING",
    @SerialName("metric_name") val metricName: String? = "",
    @SerialName("metric_value") val metricValue: Double? = 0.0,
    val quality: String? = "APPROX",
    @SerialName("quality_reason") val qualityReason: String? = "",
    @SerialName("session_price") val sessionPrice: Double? = 0.0,
    @SerialName("basis_price") val basisPrice: Double? = 0.0,
    @SerialName("basis_label") val basisLabel: String? = "",
    @SerialName("over_status") val overStatus: String? = "",
)

@Serializable
data class Movers2ResponseDto(
    @SerialName("as_of") val asOf: String? = "",
    @SerialName("bas_dd") val basDd: String? = "",
    val session: String? = "regular",
    @SerialName("session_label") val sessionLabel: String? = "",
    @SerialName("active_session") val activeSession: String? = "",
    val direction: String? = "up",
    @SerialName("data_state") val dataState: String? = "LIVE",
    @SerialName("snapshot_as_of") val snapshotAsOf: String? = null,
    @SerialName("universe_count") val universeCount: Int? = 0,
    @SerialName("candidate_quotes") val candidateQuotes: Int? = 0,
    @SerialName("session_progress") val sessionProgress: Double? = 0.0,
    val notes: List<String>? = emptyList(),
    val items: List<Mover2ItemDto>? = emptyList(),
)

@Serializable
data class SupplyItemDto(
    val ticker: String? = "",
    val name: String? = "",
    val market: String? = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("logo_png_url") val logoPngUrl: String? = null,
    val tags: List<String>? = emptyList(),
    val price: Double? = 0.0,
    @SerialName("prev_close") val prevClose: Double? = 0.0,
    @SerialName("chg_pct") val chgPct: Double? = 0.0,
    @SerialName("as_of") val asOf: String? = "",
    val source: String? = "",
    @SerialName("is_live") val isLive: Boolean? = false,
    val volume: Double? = 0.0,
    val value: Double? = 0.0,
    @SerialName("baseline_value") val baselineValue: Double? = null,
    @SerialName("value_ratio") val valueRatio: Double? = null,
    @SerialName("flow_label") val flowLabel: String? = "",
    val confidence: String? = "MID",
    @SerialName("investor_source") val investorSource: String? = "LIVE",
    @SerialName("investor_message") val investorMessage: String? = null,
    @SerialName("investor_days") val investorDays: Int? = 0,
    @SerialName("foreign_3d") val foreign3d: Long? = 0,
    @SerialName("institution_3d") val institution3d: Long? = 0,
    @SerialName("individual_3d") val individual3d: Long? = 0,
    @SerialName("net_3d") val net3d: Long? = 0,
    @SerialName("net_5d") val net5d: Long? = 0,
    @SerialName("buy_streak_days") val buyStreakDays: Int? = 0,
    @SerialName("flow_score") val flowScore: Double? = 0.0,
)

@Serializable
data class SupplyResponseDto(
    @SerialName("as_of") val asOf: String? = "",
    @SerialName("bas_dd") val basDd: String? = "",
    val source: String? = "LIVE",
    val message: String? = null,
    val unit: String? = "value",
    @SerialName("universe_count") val universeCount: Int? = 0,
    @SerialName("candidate_quotes") val candidateQuotes: Int? = 0,
    val notes: List<String>? = emptyList(),
    val items: List<SupplyItemDto>? = emptyList(),
    @SerialName("daily_flow") val dailyFlow: List<DailyFlowItemDto>? = emptyList(),
)

@Serializable
data class DailyFlowItemDto(
    val date: String? = "",
    val foreign: Long = 0L,
    val institution: Long = 0L,
    val individual: Long = 0L,
)

// --- US Insiders (미장: SEC Form 4 CEO/CFO P 거래) ---

@Serializable
data class UsInsiderItemDto(
    val ticker: String? = "",
    @SerialName("company_name") val companyName: String? = "",
    val cik: String? = "",
    @SerialName("executive_name") val executiveName: String? = "",
    @SerialName("executive_role") val executiveRole: String? = "",
    @SerialName("transaction_code") val transactionCode: String? = "",
    @SerialName("acquired_disposed_code") val acquiredDisposedCode: String? = "",
    @SerialName("transaction_date") val transactionDate: String? = "",
    @SerialName("filing_date") val filingDate: String? = "",
    @SerialName("buy_dates") val buyDates: List<String>? = emptyList(),
    @SerialName("buy_date_range") val buyDateRange: String? = "",
    @SerialName("transaction_count") val transactionCount: Int? = 0,
    @SerialName("total_shares") val totalShares: Double? = 0.0,
    @SerialName("avg_price_usd") val avgPriceUsd: Double? = 0.0,
    @SerialName("total_value_usd") val totalValueUsd: Double? = 0.0,
    @SerialName("pattern_summary") val patternSummary: String? = "",
    @SerialName("repeat_buy_90d") val repeatBuy90d: Boolean? = false,
    @SerialName("repeat_count_90d") val repeatCount90d: Int? = 0,
    @SerialName("has_10b5_1") val has10b51: Boolean? = false,
    @SerialName("accession_no") val accessionNo: String? = "",
    @SerialName("source_url") val sourceUrl: String? = "",
    val notes: List<String>? = emptyList(),
)

@Serializable
data class UsInsiderResponseDto(
    @SerialName("as_of") val asOf: String? = "",
    @SerialName("requested_trading_days") val requestedTradingDays: Int? = 10,
    @SerialName("effective_trading_days") val effectiveTradingDays: Int? = 10,
    @SerialName("expanded_window") val expandedWindow: Boolean? = false,
    @SerialName("selected_transaction_codes") val selectedTransactionCodes: List<String>? = emptyList(),
    @SerialName("target_count") val targetCount: Int? = 10,
    @SerialName("returned_count") val returnedCount: Int? = 0,
    @SerialName("candidate_daily_index") val candidateDailyIndex: Int? = 0,
    @SerialName("candidate_atom") val candidateAtom: Int? = 0,
    @SerialName("candidate_github") val candidateGithub: Int? = 0,
    @SerialName("candidate_merged") val candidateMerged: Int? = 0,
    @SerialName("forms_checked") val formsChecked: Int? = 0,
    @SerialName("forms_parsed") val formsParsed: Int? = 0,
    @SerialName("parse_errors") val parseErrors: Int? = 0,
    @SerialName("purchase_rows_total") val purchaseRowsTotal: Int? = 0,
    @SerialName("purchase_rows_in_requested") val purchaseRowsInRequested: Int? = 0,
    @SerialName("purchase_rows_in_expanded") val purchaseRowsInExpanded: Int? = 0,
    @SerialName("purchase_rows_in_effective") val purchaseRowsInEffective: Int? = 0,
    @SerialName("shortage_reason") val shortageReason: String? = null,
    val items: List<UsInsiderItemDto>? = emptyList(),
    val notes: List<String>? = emptyList(),
)

// --- Favorites (관심 종목) ---

@Serializable
data class FavoriteUpsertRequestDto(
    val ticker: String,
    val name: String? = null,
    @SerialName("baseline_price") val baselinePrice: Double,
    @SerialName("favorited_at") val favoritedAt: String? = null,
    @SerialName("source_tab") val sourceTab: String? = null,
)

@Serializable
data class FavoriteItemDto(
    val ticker: String? = "",
    val name: String? = "",
    @SerialName("baseline_price") val baselinePrice: Double? = 0.0,
    @SerialName("favorited_at") val favoritedAt: String? = "",
    @SerialName("source_tab") val sourceTab: String? = "",
    @SerialName("current_price") val currentPrice: Double? = null,
    @SerialName("change_since_favorite_pct") val changeSinceFavoritePct: Double? = null,
    @SerialName("as_of") val asOf: String? = "",
    val source: String? = "",
    @SerialName("is_live") val isLive: Boolean? = false,
)

@Serializable
data class FavoritesResponseDto(
    val items: List<FavoriteItemDto>? = emptyList(),
)

@Serializable
data class StockSearchItemDto(
    val ticker: String? = "",
    val name: String? = "",
    val market: String? = "",
    @SerialName("current_price") val currentPrice: Double? = null,
    @SerialName("chg_pct") val chgPct: Double? = null,
)

@Serializable
data class StockSearchResponseDto(
    val count: Int? = 0,
    val items: List<StockSearchItemDto>? = emptyList(),
)

// --- AutoTrade ---

@Serializable
data class AutoTradeSettingsDto(
    val enabled: Boolean? = false,
    val environment: String? = "demo", // demo|prod
    @SerialName("include_daytrade") val includeDaytrade: Boolean? = true,
    @SerialName("include_movers") val includeMovers: Boolean? = true,
    @SerialName("include_supply") val includeSupply: Boolean? = true,
    @SerialName("include_papers") val includePapers: Boolean? = true,
    @SerialName("include_longterm") val includeLongterm: Boolean? = true,
    @SerialName("include_favorites") val includeFavorites: Boolean? = true,
    @SerialName("order_budget_krw") val orderBudgetKrw: Double? = 200000.0,
    @SerialName("max_orders_per_run") val maxOrdersPerRun: Int? = 5,
    @SerialName("max_daily_loss_pct") val maxDailyLossPct: Double? = 3.0,
    @SerialName("seed_krw") val seedKrw: Double? = 10000000.0,
    @SerialName("take_profit_pct") val takeProfitPct: Double? = 7.0,
    @SerialName("stop_loss_pct") val stopLossPct: Double? = 5.0,
    @SerialName("stoploss_reentry_policy") val stoplossReentryPolicy: String? = "cooldown",
    @SerialName("stoploss_reentry_cooldown_min") val stoplossReentryCooldownMin: Int? = 30,
    @SerialName("takeprofit_reentry_policy") val takeprofitReentryPolicy: String? = "cooldown",
    @SerialName("takeprofit_reentry_cooldown_min") val takeprofitReentryCooldownMin: Int? = 30,
    @SerialName("allow_market_order") val allowMarketOrder: Boolean? = false,
    @SerialName("offhours_reservation_enabled") val offhoursReservationEnabled: Boolean? = true,
    @SerialName("offhours_reservation_mode") val offhoursReservationMode: String? = "auto",
    @SerialName("offhours_confirm_timeout_min") val offhoursConfirmTimeoutMin: Int? = 3,
    @SerialName("offhours_confirm_timeout_action") val offhoursConfirmTimeoutAction: String? = "cancel",
)

@Serializable
data class AutoTradeSettingsResponseDto(
    val settings: AutoTradeSettingsDto? = AutoTradeSettingsDto(),
    @SerialName("updated_at") val updatedAt: String? = "",
)

@Serializable
data class AutoTradeSymbolRuleUpsertDto(
    val ticker: String,
    val name: String? = null,
    @SerialName("take_profit_pct") val takeProfitPct: Double? = 7.0,
    @SerialName("stop_loss_pct") val stopLossPct: Double? = 5.0,
    val enabled: Boolean? = true,
)

@Serializable
data class AutoTradeSymbolRuleItemDto(
    val ticker: String? = "",
    val name: String? = null,
    @SerialName("take_profit_pct") val takeProfitPct: Double? = 7.0,
    @SerialName("stop_loss_pct") val stopLossPct: Double? = 5.0,
    val enabled: Boolean? = true,
    @SerialName("updated_at") val updatedAt: String? = "",
)

@Serializable
data class AutoTradeSymbolRulesResponseDto(
    val count: Int? = 0,
    val items: List<AutoTradeSymbolRuleItemDto>? = emptyList(),
)

@Serializable
data class AutoTradeBrokerCredentialDto(
    @SerialName("kis_trading_enabled") val kisTradingEnabled: Boolean? = false,
    @SerialName("use_user_credentials") val useUserCredentials: Boolean? = false,
    @SerialName("has_demo_app_key") val hasDemoAppKey: Boolean? = false,
    @SerialName("has_demo_app_secret") val hasDemoAppSecret: Boolean? = false,
    @SerialName("has_prod_app_key") val hasProdAppKey: Boolean? = false,
    @SerialName("has_prod_app_secret") val hasProdAppSecret: Boolean? = false,
    @SerialName("has_account_no") val hasAccountNo: Boolean? = false,
    @SerialName("has_demo_account_no") val hasDemoAccountNo: Boolean? = false,
    @SerialName("has_prod_account_no") val hasProdAccountNo: Boolean? = false,
    @SerialName("masked_account_no") val maskedAccountNo: String? = null,
    @SerialName("masked_demo_account_no") val maskedDemoAccountNo: String? = null,
    @SerialName("masked_prod_account_no") val maskedProdAccountNo: String? = null,
    @SerialName("masked_prod_app_key") val maskedProdAppKey: String? = null,
    @SerialName("masked_prod_app_secret") val maskedProdAppSecret: String? = null,
    @SerialName("account_product_code") val accountProductCode: String? = "01",
    @SerialName("account_product_code_demo") val accountProductCodeDemo: String? = "01",
    @SerialName("account_product_code_prod") val accountProductCodeProd: String? = "01",
    @SerialName("demo_ready_user") val demoReadyUser: Boolean? = false,
    @SerialName("prod_ready_user") val prodReadyUser: Boolean? = false,
    @SerialName("demo_ready_server") val demoReadyServer: Boolean? = false,
    @SerialName("prod_ready_server") val prodReadyServer: Boolean? = false,
    @SerialName("demo_ready_effective") val demoReadyEffective: Boolean? = false,
    @SerialName("prod_ready_effective") val prodReadyEffective: Boolean? = false,
    val source: String? = "SERVER_ENV",
    @SerialName("updated_at") val updatedAt: String? = "",
)

@Serializable
data class AutoTradeBrokerCredentialUpdateDto(
    @SerialName("use_user_credentials") val useUserCredentials: Boolean? = true,
    @SerialName("app_key_demo") val appKeyDemo: String? = null,
    @SerialName("app_secret_demo") val appSecretDemo: String? = null,
    @SerialName("app_key_prod") val appKeyProd: String? = null,
    @SerialName("app_secret_prod") val appSecretProd: String? = null,
    @SerialName("account_no") val accountNo: String? = null,
    @SerialName("account_product_code") val accountProductCode: String? = null,
    @SerialName("account_no_demo") val accountNoDemo: String? = null,
    @SerialName("account_product_code_demo") val accountProductCodeDemo: String? = null,
    @SerialName("account_no_prod") val accountNoProd: String? = null,
    @SerialName("account_product_code_prod") val accountProductCodeProd: String? = null,
    @SerialName("clear_demo") val clearDemo: Boolean? = false,
    @SerialName("clear_prod") val clearProd: Boolean? = false,
    @SerialName("clear_account") val clearAccount: Boolean? = false,
)

@Serializable
data class AutoTradeCandidateItemDto(
    val ticker: String? = "",
    val name: String? = null,
    @SerialName("source_tab") val sourceTab: String? = "",
    @SerialName("signal_price") val signalPrice: Double? = null,
    @SerialName("current_price") val currentPrice: Double? = null,
    @SerialName("chg_pct") val chgPct: Double? = null,
    val note: String? = null,
)

@Serializable
data class AutoTradeCandidatesResponseDto(
    @SerialName("generated_at") val generatedAt: String? = "",
    val count: Int? = 0,
    val items: List<AutoTradeCandidateItemDto>? = emptyList(),
    @SerialName("source_counts") val sourceCounts: Map<String, Int>? = emptyMap(),
    val warnings: List<String>? = emptyList(),
)

@Serializable
data class AutoTradeRunRequestDto(
    @SerialName("dry_run") val dryRun: Boolean = false,
    val limit: Int? = null,
    @SerialName("reserve_if_closed") val reserveIfClosed: Boolean = false,
)

@Serializable
data class AutoTradeManualBuyRequestDto(
    val ticker: String,
    val name: String? = null,
    val mode: String = "demo",
    val qty: Int? = null,
    @SerialName("budget_krw") val budgetKrw: Double? = null,
    @SerialName("request_price") val requestPrice: Double? = null,
    @SerialName("market_order") val marketOrder: Boolean? = null,
    @SerialName("dry_run") val dryRun: Boolean = false,
)

@Serializable
data class AutoTradeManualSellRequestDto(
    val ticker: String,
    val name: String? = null,
    val mode: String = "demo",
    val qty: Int? = null,
    @SerialName("request_price") val requestPrice: Double? = null,
    @SerialName("market_order") val marketOrder: Boolean? = null,
    @SerialName("dry_run") val dryRun: Boolean = false,
)

@Serializable
data class AutoTradeOrderItemDto(
    val id: Int? = 0,
    @SerialName("run_id") val runId: String? = "",
    @SerialName("source_tab") val sourceTab: String? = "",
    val environment: String? = null,
    val ticker: String? = "",
    val name: String? = null,
    val side: String? = "BUY",
    val qty: Int? = 0,
    @SerialName("requested_price") val requestedPrice: Double? = 0.0,
    @SerialName("filled_price") val filledPrice: Double? = null,
    @SerialName("current_price") val currentPrice: Double? = null,
    @SerialName("pnl_pct") val pnlPct: Double? = null,
    val status: String? = "",
    @SerialName("broker_order_no") val brokerOrderNo: String? = null,
    val reason: String? = null,
    @SerialName("reason_detail") val reasonDetail: AutoTradeReasonDetailDto? = null,
    @SerialName("requested_at") val requestedAt: String? = "",
    @SerialName("filled_at") val filledAt: String? = null,
)

@Serializable
data class AutoTradeReasonDetailDto(
    val conclusion: String? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
    val evidence: Map<String, String>? = emptyMap(),
    val action: String? = null,
)

@Serializable
data class AutoTradeOrdersResponseDto(
    val total: Int? = 0,
    val items: List<AutoTradeOrderItemDto>? = emptyList(),
)

@Serializable
data class AutoTradePerformanceItemDto(
    val ymd: String? = "",
    @SerialName("orders_total") val ordersTotal: Int? = 0,
    @SerialName("filled_total") val filledTotal: Int? = 0,
    @SerialName("buy_amount_krw") val buyAmountKrw: Double? = 0.0,
    @SerialName("eval_amount_krw") val evalAmountKrw: Double? = 0.0,
    @SerialName("realized_pnl_krw") val realizedPnlKrw: Double? = 0.0,
    @SerialName("unrealized_pnl_krw") val unrealizedPnlKrw: Double? = 0.0,
    @SerialName("roi_pct") val roiPct: Double? = 0.0,
    @SerialName("win_rate") val winRate: Double? = 0.0,
    @SerialName("mdd_pct") val mddPct: Double? = 0.0,
    @SerialName("total_asset_krw") val totalAssetKrw: Double? = null,
    @SerialName("daily_return_pct") val dailyReturnPct: Double? = null,
    @SerialName("twr_cum_pct") val twrCumPct: Double? = null,
    @SerialName("holding_pnl_krw") val holdingPnlKrw: Double? = null,
    @SerialName("holding_pnl_pct") val holdingPnlPct: Double? = null,
    @SerialName("today_pnl_krw") val todayPnlKrw: Double? = null,
    @SerialName("today_pnl_pct") val todayPnlPct: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = "",
)

@Serializable
data class AutoTradePerformanceResponseDto(
    val days: Int? = 30,
    val summary: AutoTradePerformanceItemDto? = null,
    val items: List<AutoTradePerformanceItemDto>? = emptyList(),
)

@Serializable
data class AutoTradeAccountPositionDto(
    val ticker: String? = "",
    val name: String? = null,
    @SerialName("source_tab") val sourceTab: String? = null,
    val qty: Int? = 0,
    @SerialName("avg_price") val avgPrice: Double? = 0.0,
    @SerialName("current_price") val currentPrice: Double? = 0.0,
    @SerialName("eval_amount_krw") val evalAmountKrw: Double? = 0.0,
    @SerialName("pnl_amount_krw") val pnlAmountKrw: Double? = 0.0,
    @SerialName("pnl_pct") val pnlPct: Double? = 0.0,
)

@Serializable
data class AutoTradeAccountSnapshotResponseDto(
    val environment: String? = "paper",
    val source: String? = "UNAVAILABLE",
    @SerialName("broker_connected") val brokerConnected: Boolean? = false,
    @SerialName("account_no_masked") val accountNoMasked: String? = null,
    @SerialName("cash_krw") val cashKrw: Double? = null,
    @SerialName("orderable_cash_krw") val orderableCashKrw: Double? = null,
    @SerialName("stock_eval_krw") val stockEvalKrw: Double? = null,
    @SerialName("total_asset_krw") val totalAssetKrw: Double? = null,
    @SerialName("realized_pnl_krw") val realizedPnlKrw: Double? = null,
    @SerialName("unrealized_pnl_krw") val unrealizedPnlKrw: Double? = null,
    @SerialName("real_eval_pnl_krw") val realEvalPnlKrw: Double? = null,
    @SerialName("real_eval_pnl_pct") val realEvalPnlPct: Double? = null,
    @SerialName("asset_change_krw") val assetChangeKrw: Double? = null,
    @SerialName("asset_change_pct") val assetChangePct: Double? = null,
    val positions: List<AutoTradeAccountPositionDto>? = emptyList(),
    val message: String? = null,
    @SerialName("updated_at") val updatedAt: String? = "",
)

@Serializable
data class AutoTradeBootstrapResponseDto(
    @SerialName("generated_at") val generatedAt: String? = "",
    val settings: AutoTradeSettingsResponseDto? = AutoTradeSettingsResponseDto(),
    @SerialName("symbol_rules") val symbolRules: AutoTradeSymbolRulesResponseDto? = AutoTradeSymbolRulesResponseDto(),
    val broker: AutoTradeBrokerCredentialDto? = AutoTradeBrokerCredentialDto(),
    val account: AutoTradeAccountSnapshotResponseDto? = AutoTradeAccountSnapshotResponseDto(),
    val orders: AutoTradeOrdersResponseDto? = AutoTradeOrdersResponseDto(),
    @SerialName("candidates_prefetch_limit") val candidatesPrefetchLimit: Int? = 80,
    @SerialName("deferred_sections") val deferredSections: List<String>? = emptyList(),
)

@Serializable
data class AutoTradeRunResponseDto(
    @SerialName("run_id") val runId: String? = "",
    val message: String? = "",
    val queued: Boolean? = false,
    @SerialName("reservation_id") val reservationId: Int? = null,
    @SerialName("reservation_status") val reservationStatus: String? = null,
    @SerialName("reservation_merged") val reservationMerged: Boolean? = false,
    @SerialName("reservation_merge_requests") val reservationMergeRequests: Int? = null,
    @SerialName("reservation_preview_count") val reservationPreviewCount: Int? = null,
    @SerialName("reservation_preview_items") val reservationPreviewItems: List<AutoTradeReservationPreviewItemDto>? = emptyList(),
    @SerialName("requested_count") val requestedCount: Int? = 0,
    @SerialName("submitted_count") val submittedCount: Int? = 0,
    @SerialName("filled_count") val filledCount: Int? = 0,
    @SerialName("skipped_count") val skippedCount: Int? = 0,
    val orders: List<AutoTradeOrderItemDto>? = emptyList(),
    val metric: AutoTradePerformanceItemDto? = null,
)

@Serializable
data class AutoTradeReservationPreviewItemDto(
    val ticker: String? = "",
    val name: String? = null,
    @SerialName("source_tab") val sourceTab: String? = "UNKNOWN",
    @SerialName("signal_price") val signalPrice: Double? = null,
    @SerialName("current_price") val currentPrice: Double? = null,
    @SerialName("chg_pct") val chgPct: Double? = null,
    @SerialName("planned_qty") val plannedQty: Int? = null,
    @SerialName("planned_price") val plannedPrice: Double? = null,
    @SerialName("planned_amount_krw") val plannedAmountKrw: Double? = null,
    @SerialName("order_type") val orderType: String? = null,
    @SerialName("merged_count") val mergedCount: Int? = null,
)

@Serializable
data class AutoTradeReservationResultSummaryDto(
    val message: String? = null,
    @SerialName("requested_count") val requestedCount: Int? = 0,
    @SerialName("submitted_count") val submittedCount: Int? = 0,
    @SerialName("filled_count") val filledCount: Int? = 0,
    @SerialName("skipped_count") val skippedCount: Int? = 0,
    @SerialName("rejected_count") val rejectedCount: Int? = 0,
)

@Serializable
data class AutoTradeReservationItemDto(
    val id: Int? = 0,
    val environment: String? = "demo",
    val kind: String? = "AUTOTRADE_ENTRY",
    val mode: String? = "auto",
    val status: String? = "QUEUED",
    @SerialName("requested_at") val requestedAt: String? = "",
    @SerialName("execute_at") val executeAt: String? = "",
    @SerialName("confirm_deadline_at") val confirmDeadlineAt: String? = null,
    @SerialName("timeout_action") val timeoutAction: String? = "cancel",
    @SerialName("reason_code") val reasonCode: String? = null,
    @SerialName("reason_message") val reasonMessage: String? = null,
    @SerialName("result_run_id") val resultRunId: String? = null,
    @SerialName("preview_count") val previewCount: Int? = 0,
    @SerialName("preview_items") val previewItems: List<AutoTradeReservationPreviewItemDto>? = emptyList(),
    @SerialName("result_summary") val resultSummary: AutoTradeReservationResultSummaryDto? = null,
    @SerialName("updated_at") val updatedAt: String? = "",
)

@Serializable
data class AutoTradeReservationsResponseDto(
    val total: Int? = 0,
    val items: List<AutoTradeReservationItemDto>? = emptyList(),
)

@Serializable
data class AutoTradeReservationActionResponseDto(
    val ok: Boolean? = true,
    val reservation: AutoTradeReservationItemDto? = null,
    @SerialName("run_result") val runResult: AutoTradeRunResponseDto? = null,
    val message: String? = null,
)

@Serializable
data class AutoTradeOrderCancelResponseDto(
    val ok: Boolean? = true,
    val order: AutoTradeOrderItemDto? = null,
    val scope: String? = "symbol",
    @SerialName("requested_count") val requestedCount: Int? = 0,
    @SerialName("canceled_count") val canceledCount: Int? = 0,
    @SerialName("closed_count") val closedCount: Int? = 0,
    @SerialName("reserved_count") val reservedCount: Int? = 0,
    @SerialName("failed_count") val failedCount: Int? = 0,
    @SerialName("skipped_count") val skippedCount: Int? = 0,
    @SerialName("canceled_order_ids") val canceledOrderIds: List<Int>? = emptyList(),
    @SerialName("closed_order_ids") val closedOrderIds: List<Int>? = emptyList(),
    @SerialName("reservation_id") val reservationId: Int? = null,
    @SerialName("reservation_status") val reservationStatus: String? = null,
    val message: String? = "",
)

@Serializable
data class AutoTradePendingCancelRequestDto(
    val environment: String? = null,
    @SerialName("max_count") val maxCount: Int = 20,
)

@Serializable
data class AutoTradePendingCancelResponseDto(
    val ok: Boolean? = true,
    @SerialName("requested_count") val requestedCount: Int? = 0,
    @SerialName("canceled_count") val canceledCount: Int? = 0,
    @SerialName("closed_count") val closedCount: Int? = 0,
    @SerialName("reserved_count") val reservedCount: Int? = 0,
    @SerialName("reservation_id") val reservationId: Int? = null,
    @SerialName("failed_count") val failedCount: Int? = 0,
    @SerialName("skipped_count") val skippedCount: Int? = 0,
    @SerialName("canceled_orders") val canceledOrders: List<AutoTradeOrderItemDto>? = emptyList(),
    @SerialName("failed_orders") val failedOrders: List<AutoTradeOrderItemDto>? = emptyList(),
    val message: String? = "",
)

@Serializable
data class AutoTradeReservationPendingCancelRequestDto(
    val environment: String? = null,
    @SerialName("max_count") val maxCount: Int = 30,
)

@Serializable
data class AutoTradeReservationPendingCancelResponseDto(
    val ok: Boolean? = true,
    @SerialName("requested_count") val requestedCount: Int? = 0,
    @SerialName("canceled_count") val canceledCount: Int? = 0,
    @SerialName("failed_count") val failedCount: Int? = 0,
    @SerialName("skipped_count") val skippedCount: Int? = 0,
    @SerialName("canceled_reservation_ids") val canceledReservationIds: List<Int>? = emptyList(),
    @SerialName("failed_reservation_ids") val failedReservationIds: List<Int>? = emptyList(),
    val message: String? = "",
)

@Serializable
data class AutoTradeReentryBlockItemDto(
    val id: Int? = 0,
    val environment: String? = "demo",
    val ticker: String? = "",
    @SerialName("trigger_reason") val triggerReason: String? = "STOP_LOSS",
    @SerialName("blocked_at") val blockedAt: String? = "",
    @SerialName("released_at") val releasedAt: String? = null,
    val note: String? = null,
)

@Serializable
data class AutoTradeReentryBlocksResponseDto(
    val total: Int? = 0,
    val items: List<AutoTradeReentryBlockItemDto>? = emptyList(),
)

@Serializable
data class AutoTradeReentryReleaseRequestDto(
    val environment: String? = null,
    val ticker: String? = null,
    @SerialName("trigger_reason") val triggerReason: String? = null,
    @SerialName("release_all") val releaseAll: Boolean = false,
)

@Serializable
data class AutoTradeReentryReleaseResponseDto(
    val ok: Boolean? = true,
    @SerialName("released_count") val releasedCount: Int? = 0,
    val message: String? = "",
)

@Serializable
data class AdminUserAutoTradeOverviewDto(
    val user: UserDetailDto? = UserDetailDto(),
    val settings: AutoTradeSettingsDto? = AutoTradeSettingsDto(),
    val broker: AutoTradeBrokerCredentialDto? = AutoTradeBrokerCredentialDto(),
    val account: AutoTradeAccountSnapshotResponseDto? = AutoTradeAccountSnapshotResponseDto(),
    @SerialName("performance_days") val performanceDays: Int? = 30,
    @SerialName("performance_summary") val performanceSummary: AutoTradePerformanceItemDto? = null,
    @SerialName("performance_items") val performanceItems: List<AutoTradePerformanceItemDto>? = emptyList(),
    @SerialName("symbol_rules") val symbolRules: List<AutoTradeSymbolRuleItemDto>? = emptyList(),
    @SerialName("recent_orders_total") val recentOrdersTotal: Int? = 0,
    @SerialName("recent_orders") val recentOrders: List<AutoTradeOrderItemDto>? = emptyList(),
    @SerialName("source_counts") val sourceCounts: Map<String, Int>? = emptyMap(),
    @SerialName("status_counts") val statusCounts: Map<String, Int>? = emptyMap(),
)

// --- News (Hybrid) ---

@Serializable
data class NewsMetaDto(
    val source: String? = "LIVE",
    val status: String? = "OK",
    val message: String? = null,
    @SerialName("generated_at") val generatedAt: String? = "",
    val params: JsonObject? = null,
)

@Serializable
data class NewsClusterLiteDto(
    val id: Int? = 0,
    @SerialName("theme_key") val themeKey: String? = "",
    @SerialName("event_type") val eventType: String? = "",
    val title: String? = "",
    val summary: String? = null,
    @SerialName("top_tickers") val topTickers: List<String>? = emptyList(),
    @SerialName("published_start") val publishedStart: String? = "",
    @SerialName("published_end") val publishedEnd: String? = "",
    @SerialName("published_ymd") val publishedYmd: Int? = 0,
    @SerialName("article_count") val articleCount: Int? = 0,
)

@Serializable
data class NewsThemeCardDto(
    @SerialName("theme_key") val themeKey: String? = "",
    @SerialName("hot_score") val hotScore: Double? = 0.0,
    @SerialName("cluster_count") val clusterCount: Int? = 0,
    @SerialName("article_count") val articleCount: Int? = 0,
    @SerialName("impact_sum") val impactSum: Int? = 0,
    @SerialName("latest_published_at") val latestPublishedAt: String? = null,
    @SerialName("top_clusters") val topClusters: List<NewsClusterLiteDto>? = emptyList(),
)

@Serializable
data class NewsThemesResponseDto(
    val meta: NewsMetaDto? = NewsMetaDto(),
    val themes: List<NewsThemeCardDto>? = emptyList(),
)

@Serializable
data class NewsClusterListItemDto(
    val id: Int? = 0,
    @SerialName("cluster_key") val clusterKey: String? = "",
    @SerialName("theme_key") val themeKey: String? = "",
    @SerialName("event_type") val eventType: String? = "",
    val title: String? = "",
    val summary: String? = null,
    @SerialName("top_tickers") val topTickers: List<String>? = emptyList(),
    @SerialName("published_start") val publishedStart: String? = "",
    @SerialName("published_end") val publishedEnd: String? = "",
    @SerialName("published_ymd") val publishedYmd: Int? = 0,
    @SerialName("article_count") val articleCount: Int? = 0,
    @SerialName("impact_sum") val impactSum: Int? = 0,
    @SerialName("hot_score") val hotScore: Double? = 0.0,
)

@Serializable
data class NewsClustersResponseDto(
    val meta: NewsMetaDto? = NewsMetaDto(),
    val clusters: List<NewsClusterListItemDto>? = emptyList(),
)

@Serializable
data class NewsStockHotItemDto(
    val ticker: String? = "",
    @SerialName("hot_score") val hotScore: Double? = 0.0,
    @SerialName("mention_count") val mentionCount: Int? = 0,
    @SerialName("impact_sum") val impactSum: Int? = 0,
    @SerialName("latest_published_at") val latestPublishedAt: String? = null,
    val name: String? = null,
)

@Serializable
data class NewsStocksResponseDto(
    val meta: NewsMetaDto? = NewsMetaDto(),
    val stocks: List<NewsStockHotItemDto>? = emptyList(),
)

@Serializable
data class NewsArticleItemDto(
    val source: String? = "",
    @SerialName("source_uid") val sourceUid: String? = "",
    val url: String? = "",
    val title: String? = "",
    val summary: String? = null,
    @SerialName("published_at") val publishedAt: String? = "",
    @SerialName("published_ymd") val publishedYmd: Int? = 0,
    @SerialName("event_type") val eventType: String? = "",
    val polarity: String? = "",
    val impact: Int? = 0,
    @SerialName("theme_key") val themeKey: String? = "",
    val tickers: List<String>? = emptyList(),
)

@Serializable
data class NewsClusterItemDto(
    val id: Int? = 0,
    @SerialName("cluster_key") val clusterKey: String? = "",
    @SerialName("theme_key") val themeKey: String? = "",
    @SerialName("event_type") val eventType: String? = "",
    val title: String? = "",
    val summary: String? = null,
    @SerialName("top_tickers") val topTickers: List<String>? = emptyList(),
    @SerialName("published_start") val publishedStart: String? = "",
    @SerialName("published_end") val publishedEnd: String? = "",
    @SerialName("published_ymd") val publishedYmd: Int? = 0,
    @SerialName("article_count") val articleCount: Int? = 0,
)

@Serializable
data class NewsClusterResponseDto(
    val meta: NewsMetaDto? = NewsMetaDto(),
    val cluster: NewsClusterItemDto? = NewsClusterItemDto(),
    val articles: List<NewsArticleItemDto>? = emptyList(),
)

@Serializable
data class NewsArticlesResponseDto(
    val meta: NewsMetaDto? = NewsMetaDto(),
    val articles: List<NewsArticleItemDto>? = emptyList(),
)

// ── Home2 전용 스텁 DTO (Phase 1에서 서버 연동 시 필드 확장 예정) ──

@Serializable
data class TradeFeedSummaryDto(
    @SerialName("total_count") val totalCount: Int? = 0,
    @SerialName("realized_pnl") val realizedPnl: Double? = 0.0,
    @SerialName("buy_count") val buyCount: Int? = 0,
    @SerialName("sell_count") val sellCount: Int? = 0,
)

@Serializable
data class SectorItemDto(
    val name: String? = null,
    @SerialName("change_pct") val changePct: Double? = 0.0,
    val volume: Int? = 0,
)

@Serializable
data class SectorResponseDto(
    val items: List<SectorItemDto>? = emptyList(),
    @SerialName("as_of") val asOf: String? = null,
    val source: String? = null,
)

@Serializable
data class VolumeSurgeItemDto(
    val ticker: String? = null,
    val name: String? = null,
    @SerialName("volume_ratio") val volumeRatio: Double? = 0.0,
    val price: Double? = 0.0,
    @SerialName("change_pct") val changePct: Double? = 0.0,
)

@Serializable
data class VolumeSurgeResponseDto(
    val items: List<VolumeSurgeItemDto>? = emptyList(),
    @SerialName("as_of") val asOf: String? = null,
)

@Serializable
data class WeekExtremeItemDto(
    val ticker: String? = null,
    val name: String? = null,
    val price: Double? = 0.0,
    @SerialName("prev_extreme") val prevExtreme: Double? = 0.0,
)

@Serializable
data class WeekExtremeResponseDto(
    val highs: List<WeekExtremeItemDto>? = emptyList(),
    val lows: List<WeekExtremeItemDto>? = emptyList(),
    @SerialName("as_of") val asOf: String? = null,
)

@Serializable
data class DividendItemDto(
    val ticker: String? = null,
    val name: String? = null,
    @SerialName("ex_date") val exDate: String? = null,
    @SerialName("dividend_per_share") val dividendPerShare: Double? = 0.0,
    @SerialName("dividend_yield") val dividendYield: Double? = 0.0,
)

@Serializable
data class DividendResponseDto(
    val items: List<DividendItemDto>? = emptyList(),
    @SerialName("as_of") val asOf: String? = null,
)
