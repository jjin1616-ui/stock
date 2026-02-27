package com.example.stock.data.repository

import android.content.Context
import com.example.stock.BuildConfig

data class AppSettings(
    val baseUrl: String,
    val lookbackDays: Int,
    val riskPreset: String,
    val themeCap: Int,
    val daytradeDisplayCount: Int,
    val longtermDisplayCount: Int,
    val quoteRefreshSec: Int,
    val daytradeVariant: Int,
    val bottomTabOrderCsv: String,
    val newsDefaultWindow: String,
    val newsDefaultMode: String,
    val newsDefaultSource: String,
    val newsDefaultHideRisk: Boolean,
    val newsRestoreLastFilters: Boolean,
    val newsArticleTextSizeSp: Int,
    val newsLastWindow: String,
    val newsLastMode: String,
    val newsLastSource: String,
    val newsLastEvent: String,
    val newsLastHideRisk: Boolean,
    val cardUiVersion: String,
    val lastAdvancedSection: String,
)

class AppSettingsStore(context: Context) {
    private val pref = context.getSharedPreferences("koreastockdash_settings", Context.MODE_PRIVATE)
    private val keyBottomTabDragGuideSeenToken = "bottom_tab_drag_guide_seen_token"

    fun get(): AppSettings {
        val savedUrl = pref.getString("base_url", null)
        val normalizedUrl = if (savedUrl.isNullOrBlank()) BuildConfig.DEFAULT_BASE_URL else savedUrl
        return AppSettings(
            baseUrl = normalizedUrl,
            lookbackDays = pref.getInt("lookback_days", BuildConfig.DEFAULT_GATE_LOOKBACK),
            riskPreset = pref.getString("risk_preset", "ADAPTIVE") ?: "ADAPTIVE",
            themeCap = pref.getInt("theme_cap", 2).coerceIn(1, 3),
            daytradeDisplayCount = pref.getInt("daytrade_display_count", 10).coerceIn(3, 100),
            longtermDisplayCount = pref.getInt("longterm_display_count", 8).coerceIn(3, 100),
            quoteRefreshSec = pref.getInt("quote_refresh_sec", 10).coerceIn(5, 120),
            daytradeVariant = pref.getInt("daytrade_variant", 0).coerceIn(0, 9),
            bottomTabOrderCsv = pref.getString("bottom_tab_order_csv", "") ?: "",
            newsDefaultWindow = pref.getString("news_default_window", "24h") ?: "24h",
            newsDefaultMode = pref.getString("news_default_mode", "HOT") ?: "HOT",
            newsDefaultSource = pref.getString("news_default_source", "ALL") ?: "ALL",
            newsDefaultHideRisk = pref.getBoolean("news_default_hide_risk", false),
            newsRestoreLastFilters = pref.getBoolean("news_restore_last_filters", true),
            newsArticleTextSizeSp = pref.getInt("news_article_text_size_sp", 15).coerceIn(13, 18),
            newsLastWindow = pref.getString("news_last_window", "24h") ?: "24h",
            newsLastMode = pref.getString("news_last_mode", "HOT") ?: "HOT",
            newsLastSource = pref.getString("news_last_source", "ALL") ?: "ALL",
            newsLastEvent = pref.getString("news_last_event", "") ?: "",
            newsLastHideRisk = pref.getBoolean("news_last_hide_risk", false),
            cardUiVersion = pref.getString("card_ui_version", "V2") ?: "V2",
            lastAdvancedSection = pref.getString("settings_last_advanced_section", "AUTO_BROKER") ?: "AUTO_BROKER",
        )
    }

    fun save(
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
    ) {
        val edit = pref.edit()
        edit
            .putString("base_url", if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .putInt("lookback_days", lookbackDays)
            .putString("risk_preset", riskPreset.uppercase())
            .putInt("theme_cap", themeCap.coerceIn(1, 3))
            .putInt("daytrade_display_count", daytradeDisplayCount.coerceIn(3, 100))
            .putInt("longterm_display_count", longtermDisplayCount.coerceIn(3, 100))
            .putInt("quote_refresh_sec", quoteRefreshSec.coerceIn(5, 120))
            .putInt("daytrade_variant", daytradeVariant.coerceIn(0, 9))
        if (cardUiVersion != null) {
            val normalized = cardUiVersion.uppercase().let { if (it == "V1") "V1" else "V2" }
            edit.putString("card_ui_version", normalized)
        }
        if (bottomTabOrderCsv != null) {
            edit.putString("bottom_tab_order_csv", bottomTabOrderCsv)
        }
        edit.apply()
    }

    fun saveNewsDefaults(
        defaultWindow: String,
        defaultMode: String,
        defaultSource: String,
        defaultHideRisk: Boolean,
        restoreLastFilters: Boolean,
        articleTextSizeSp: Int,
    ) {
        pref.edit()
            .putString("news_default_window", defaultWindow)
            .putString("news_default_mode", defaultMode)
            .putString("news_default_source", defaultSource)
            .putBoolean("news_default_hide_risk", defaultHideRisk)
            .putBoolean("news_restore_last_filters", restoreLastFilters)
            .putInt("news_article_text_size_sp", articleTextSizeSp.coerceIn(13, 18))
            .apply()
    }

    fun saveNewsLastFilters(
        window: String,
        mode: String,
        source: String,
        event: String,
        hideRisk: Boolean,
    ) {
        pref.edit()
            .putString("news_last_window", window)
            .putString("news_last_mode", mode)
            .putString("news_last_source", source)
            .putString("news_last_event", event)
            .putBoolean("news_last_hide_risk", hideRisk)
            .apply()
    }

    fun saveLastAdvancedSection(section: String) {
        pref.edit()
            .putString("settings_last_advanced_section", section)
            .apply()
    }

    fun shouldShowBottomTabDragGuide(token: Int): Boolean {
        val seenToken = pref.getInt(keyBottomTabDragGuideSeenToken, 0)
        return seenToken < token
    }

    fun markBottomTabDragGuideSeen(token: Int) {
        pref.edit()
            .putInt(keyBottomTabDragGuideSeenToken, token)
            .apply()
    }
}
