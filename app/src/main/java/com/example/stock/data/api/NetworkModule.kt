package com.example.stock.data.api

import com.example.stock.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object NetworkModule {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var tokenProvider: (() -> String?) = { null }
    @Volatile private var refreshTokenProvider: (() -> String?) = { null }
    @Volatile private var tokenUpdateHandler: ((String, String?) -> Unit) = { _, _ -> }
    @Volatile private var clearAuthHandler: (() -> Unit) = {}
    @Volatile private var sessionExpiredListener: ((String) -> Unit)? = null
    private val refreshLock = Any()
    private val sessionExpiredNotified = AtomicBoolean(false)

    private data class RefreshOutcome(
        val accessToken: String,
        val refreshToken: String?,
        val reason: String,
    )

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count += 1
            prior = prior.priorResponse
        }
        return count
    }

    private fun buildRefreshUrl(url: HttpUrl): HttpUrl {
        return url.newBuilder()
            .encodedPath("/auth/refresh")
            .query(null)
            .fragment(null)
            .build()
    }

    private val refreshClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    private fun requestRefreshToken(requestUrl: HttpUrl, refreshToken: String): RefreshOutcome {
        val refreshUrl = buildRefreshUrl(requestUrl)
        val body = JSONObject()
            .put("refresh_token", refreshToken)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(refreshUrl)
            .post(body)
            .addHeader("Accept", "application/json")
            .build()

        return runCatching {
            refreshClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = runCatching { JSONObject(raw).optString("detail", "") }
                        .getOrDefault("")
                        .trim()
                    return@use RefreshOutcome(
                        accessToken = "",
                        refreshToken = null,
                        reason = if (reason.isBlank()) "TOKEN_EXPIRED" else reason,
                    )
                }
                val obj = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                val access = obj.optString("token", "").trim()
                val refresh = obj.optString("refresh_token", "").trim().ifBlank { null }
                if (access.isBlank()) {
                    return@use RefreshOutcome(
                        accessToken = "",
                        refreshToken = null,
                        reason = "TOKEN_INVALID",
                    )
                }
                RefreshOutcome(
                    accessToken = access,
                    refreshToken = refresh,
                    reason = "OK",
                )
            }
        }.getOrElse {
            RefreshOutcome(
                accessToken = "",
                refreshToken = null,
                reason = "NETWORK_ERROR",
            )
        }
    }

    private fun notifySessionExpiredOnce(reason: String) {
        if (!sessionExpiredNotified.compareAndSet(false, true)) return
        runCatching { clearAuthHandler.invoke() }
        runCatching { sessionExpiredListener?.invoke(reason) }
    }

    private fun buildClient(readTimeoutSec: Long): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val token = tokenProvider.invoke()
                val req = if (!token.isNullOrBlank()) {
                    chain.request().newBuilder().addHeader("Authorization", "Bearer $token").build()
                } else {
                    chain.request()
                }
                chain.proceed(req)
            }
            .authenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (response.code != 401) return null
                    if (response.request.url.encodedPath.endsWith("/auth/refresh")) return null
                    if (responseCount(response) >= 2) return null
                    val priorAuthHeader = response.request.header("Authorization")?.trim().orEmpty()
                    if (!priorAuthHeader.startsWith("Bearer ")) return null
                    val latestAccess = tokenProvider.invoke()?.trim().orEmpty()
                    if (latestAccess.isNotEmpty() && priorAuthHeader != "Bearer $latestAccess") {
                        return response.request.newBuilder()
                            .header("Authorization", "Bearer $latestAccess")
                            .build()
                    }

                    val refreshToken = refreshTokenProvider.invoke()?.trim().orEmpty()
                    if (refreshToken.isEmpty()) {
                        notifySessionExpiredOnce("TOKEN_EXPIRED")
                        return null
                    }

                    synchronized(refreshLock) {
                        val afterLockAccess = tokenProvider.invoke()?.trim().orEmpty()
                        if (afterLockAccess.isNotEmpty() && priorAuthHeader != "Bearer $afterLockAccess") {
                            return response.request.newBuilder()
                                .header("Authorization", "Bearer $afterLockAccess")
                                .build()
                        }

                        val refreshed = requestRefreshToken(response.request.url, refreshToken)
                        if (refreshed.accessToken.isNotBlank()) {
                            runCatching {
                                tokenUpdateHandler.invoke(refreshed.accessToken, refreshed.refreshToken)
                            }
                            sessionExpiredNotified.set(false)
                            return response.request.newBuilder()
                                .header("Authorization", "Bearer ${refreshed.accessToken}")
                                .build()
                        }
                        notifySessionExpiredOnce(refreshed.reason.ifBlank { "TOKEN_EXPIRED" })
                        return null
                    }
                }
            })
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }
    private val client: OkHttpClient by lazy { buildClient(readTimeoutSec = 20) }
    private val slowClient: OkHttpClient by lazy { buildClient(readTimeoutSec = 90) }
    private val apiCache = ConcurrentHashMap<String, StockApiService>()
    private val slowApiCache = ConcurrentHashMap<String, StockApiService>()

    private fun normalizeBaseUrl(baseUrl: String): String {
        return baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
    }

    private fun buildService(baseUrl: String, client: OkHttpClient): StockApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(StockApiService::class.java)
    }

    fun setTokenProvider(provider: () -> String?) {
        tokenProvider = provider
    }

    fun setRefreshTokenProvider(provider: () -> String?) {
        refreshTokenProvider = provider
    }

    fun setTokenUpdateHandler(handler: (String, String?) -> Unit) {
        tokenUpdateHandler = handler
    }

    fun setClearAuthHandler(handler: () -> Unit) {
        clearAuthHandler = handler
    }

    fun setSessionExpiredListener(listener: ((String) -> Unit)?) {
        sessionExpiredListener = listener
    }

    fun markSessionAuthenticated() {
        sessionExpiredNotified.set(false)
    }

    fun api(baseUrl: String): StockApiService {
        val normalized = normalizeBaseUrl(baseUrl)
        return apiCache[normalized] ?: synchronized(apiCache) {
            apiCache[normalized] ?: buildService(normalized, client).also { svc ->
                apiCache[normalized] = svc
            }
        }
    }

    fun slowApi(baseUrl: String): StockApiService {
        val normalized = normalizeBaseUrl(baseUrl)
        return slowApiCache[normalized] ?: synchronized(slowApiCache) {
            slowApiCache[normalized] ?: buildService(normalized, slowClient).also { svc ->
                slowApiCache[normalized] = svc
            }
        }
    }

    fun defaultBaseUrl(): String = BuildConfig.DEFAULT_BASE_URL
}
