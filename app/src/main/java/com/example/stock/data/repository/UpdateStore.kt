package com.example.stock.data.repository

import android.content.Context

class UpdateStore(context: Context) {
    private val pref = context.getSharedPreferences("koreastockdash_updates", Context.MODE_PRIVATE)

    fun getLastNotifiedVersionCode(): Int = pref.getInt("last_notified_version_code", 0)
    fun setLastNotifiedVersionCode(code: Int) {
        pref.edit().putInt("last_notified_version_code", code).apply()
    }

    fun getLastNotifiedBuildOrdinal(): Int = pref.getInt("last_notified_build_ordinal", 0)
    fun setLastNotifiedBuildOrdinal(ordinal: Int) {
        pref.edit().putInt("last_notified_build_ordinal", ordinal).apply()
    }

    fun getLastCheckedAtMs(): Long = pref.getLong("last_checked_at_ms", 0L)
    fun setLastCheckedAtMs(ms: Long) {
        pref.edit().putLong("last_checked_at_ms", ms).apply()
    }

    fun shouldCheckNow(nowMs: Long, minIntervalMs: Long): Boolean {
        val last = getLastCheckedAtMs()
        return last <= 0L || nowMs - last >= minIntervalMs
    }
}
