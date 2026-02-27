package com.example.stock.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefsName = "koreastockdash_auth_secure"
    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = createEncryptedPrefsWithRecovery()

    private fun createEncryptedPrefsWithRecovery() =
        runCatching { createEncryptedPrefs() }
            .getOrElse { first ->
                Log.w("AuthStore", "Encrypted prefs init failed. Clearing secure auth prefs and retrying.", first)
                clearCorruptedPrefs()
                runCatching { createEncryptedPrefs() }
                    .getOrElse { second ->
                        Log.e("AuthStore", "Encrypted prefs recovery failed. Falling back to empty secure prefs.", second)
                        appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
                        appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                    }
            }

    private fun createEncryptedPrefs() = EncryptedSharedPreferences.create(
        appContext,
        prefsName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun clearCorruptedPrefs() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                appContext.deleteSharedPreferences(prefsName)
            } else {
                appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().apply()
            }
        }.onFailure {
            Log.w("AuthStore", "Failed to clear corrupted auth prefs.", it)
        }
    }

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
    }

    fun getToken(): String? = prefs.getString("token", null)

    fun saveRefreshToken(token: String) {
        prefs.edit().putString("refresh_token", token).apply()
    }

    fun getRefreshToken(): String? = prefs.getString("refresh_token", null)

    fun clearToken() {
        prefs.edit()
            .remove("token")
            .remove("refresh_token")
            .remove("role")
            .apply()
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("bio_enabled", enabled).apply()
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean("bio_enabled", false)

    fun saveUserCode(code: String) {
        prefs.edit().putString("user_code", code).apply()
    }

    fun getUserCode(): String? = prefs.getString("user_code", null)

    fun saveRole(role: String) {
        prefs.edit().putString("role", role).apply()
    }

    fun getRole(): String? = prefs.getString("role", null)
}
