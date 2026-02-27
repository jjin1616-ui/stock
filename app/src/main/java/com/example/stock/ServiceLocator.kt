package com.example.stock

import android.content.Context
import com.example.stock.data.api.NetworkModule
import com.example.stock.data.repository.AppSettingsStore
import com.example.stock.data.repository.AuthRepository
import com.example.stock.data.repository.AuthStore
import com.example.stock.data.repository.StockRepository
import com.example.stock.db.AppDatabase

object ServiceLocator {
    @Volatile private var repo: StockRepository? = null
    @Volatile private var authRepo: AuthRepository? = null

    fun repository(context: Context): StockRepository {
        return repo ?: synchronized(this) {
            repo ?: StockRepository(
                context = context.applicationContext,
                db = AppDatabase.getInstance(context),
                settingsStore = AppSettingsStore(context.applicationContext),
            ).also { repo = it }
        }
    }

    fun authRepository(context: Context): AuthRepository {
        return authRepo ?: synchronized(this) {
            authRepo ?: run {
                val store = AuthStore(context.applicationContext)
                NetworkModule.setTokenProvider { store.getToken() }
                NetworkModule.setRefreshTokenProvider { store.getRefreshToken() }
                NetworkModule.setTokenUpdateHandler { accessToken, refreshToken ->
                    if (accessToken.isNotBlank()) {
                        store.saveToken(accessToken)
                    }
                    refreshToken
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { store.saveRefreshToken(it) }
                }
                NetworkModule.setClearAuthHandler { store.clearToken() }
                AuthRepository(store, AppSettingsStore(context.applicationContext))
            }.also { authRepo = it }
        }
    }
}
