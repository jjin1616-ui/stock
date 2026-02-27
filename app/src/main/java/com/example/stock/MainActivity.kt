package com.example.stock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.stock.data.api.LatestApkInfoDto
import com.example.stock.data.api.NetworkModule
import com.example.stock.navigation.AppNavigation
import com.example.stock.push.NotificationHelper
import com.example.stock.ui.theme.StockTheme
import com.example.stock.worker.SyncWorker
import com.example.stock.worker.UpdateCheckWorker
import com.example.stock.util.resolveLatestApkUrl
import com.example.stock.util.isRemoteBuildNewer
import com.example.stock.util.toShortBuildLabel
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import com.example.stock.ui.screens.AuthScreen
import com.example.stock.data.repository.UpdateStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private const val FOREGROUND_UPDATE_CHECK_MIN_INTERVAL_MS = 3 * 60 * 1000L

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        NotificationHelper.ensureChannel(this)
        requestNotificationPermissionIfNeeded()
        scheduleWorker()
        scheduleUpdateWorker()

        val repo = ServiceLocator.repository(applicationContext)
        val updateStore = UpdateStore(applicationContext)

        // fcm flavor only initializer (reflection-safe in nofcm)
        initFcmIfAvailable()

        setContent {
            var authed by remember { mutableStateOf(false) }
            var authNotice by remember { mutableStateOf<String?>(null) }
            var updateRequired by remember { mutableStateOf<LatestApkInfoDto?>(null) }
            var updateClickLocked by remember { mutableStateOf(false) }
            var updateCheckTick by remember { mutableStateOf(0L) }
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            StockTheme {
                DisposableEffect(Unit) {
                    NetworkModule.setSessionExpiredListener { reason ->
                        this@MainActivity.runOnUiThread {
                            authNotice = when (reason.trim().uppercase()) {
                                "TOKEN_REVOKED" -> "세션이 종료되었습니다. 다시 로그인해 주세요."
                                "TOKEN_INVALID" -> "인증정보가 유효하지 않습니다. 다시 로그인해 주세요."
                                else -> "세션이 만료되었습니다. 다시 로그인해 주세요."
                            }
                            authed = false
                        }
                    }
                    onDispose { NetworkModule.setSessionExpiredListener(null) }
                }
                if (authed) {
                    AppNavigation(startRoute = intent?.getStringExtra("route"))
                    LaunchedEffect(Unit) {
                        initFcmIfAvailable()
                        runCatching { repo.registerDevice(null) }
                    }
                    DisposableEffect(lifecycleOwner, authed) {
                        if (!authed) return@DisposableEffect onDispose { }
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_START) {
                                updateCheckTick = System.currentTimeMillis()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }
                    LaunchedEffect(updateCheckTick, authed) {
                        if (!authed || updateCheckTick == 0L) return@LaunchedEffect
                        val nowMs = System.currentTimeMillis()
                        if (!updateStore.shouldCheckNow(nowMs, FOREGROUND_UPDATE_CHECK_MIN_INTERVAL_MS)) {
                            return@LaunchedEffect
                        }
                        updateStore.setLastCheckedAtMs(nowMs)
                        repo.getLatestApkInfo().onSuccess { info ->
                            val remoteCode = info.versionCode ?: 0
                            val localCode = BuildConfig.VERSION_CODE
                            updateRequired = if (
                                isRemoteBuildNewer(
                                    localVersionCode = localCode,
                                    localBuildLabel = BuildConfig.APP_BUILD_LABEL,
                                    remoteVersionCode = remoteCode,
                                    remoteBuildLabel = info.buildLabel,
                                )
                            ) info else null
                        }.onFailure {
                            // Keep the existing gate state; user should not lose current update prompt
                            // due to transient network issues while app is active.
                        }
                    }

                    updateRequired?.let { info ->
                        val remoteCode = info.versionCode ?: 0
                        val localCode = BuildConfig.VERSION_CODE
                        val localShort = toShortBuildLabel(BuildConfig.APP_BUILD_LABEL, localCode)
                        val remoteShort = toShortBuildLabel(info.buildLabel, remoteCode)
                        val installUrl = resolveLatestApkUrl(info, BuildConfig.DEFAULT_BASE_URL)
                        val notes = info.notes.orEmpty().trim()

                        AlertDialog(
                            onDismissRequest = { /* mandatory */ },
                            properties = DialogProperties(
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false,
                            ),
                            title = { Text("업데이트 필요") },
                            text = {
                                val msg = buildString {
                                    appendLine("현재 버전: $localShort")
                                    appendLine("최신 버전: $remoteShort")
                                    if (notes.isNotBlank()) {
                                        appendLine()
                                        appendLine(notes)
                                    }
                                    appendLine()
                                    append("탭하여 최신 버전 다운로드")
                                }
                                Text(msg)
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (updateClickLocked) return@TextButton
                                        updateClickLocked = true
                                        NotificationHelper.clearUpdateNotifications(context)
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(installUrl)).apply {
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            })
                                        }
                                    }
                                ) { Text("업데이트") }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { this@MainActivity.finish() }
                                ) { Text("종료") }
                            },
                        )
                    }
                } else {
                    AuthScreen(
                        initialErrorText = authNotice,
                        onAuthed = {
                            authed = true
                            authNotice = null
                            NetworkModule.markSessionAuthenticated()
                            updateCheckTick = System.currentTimeMillis()
                        }
                    )
                }
            }
        }
    }

    private fun scheduleWorker() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "koreastockdash_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun initFcmIfAvailable() {
        runCatching {
            val clazz = Class.forName("com.example.stock.push.FcmInitializer")
            val method = clazz.getMethod("init", android.content.Context::class.java)
            method.invoke(null, applicationContext)
        }
    }

    private fun scheduleUpdateWorker() {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "koreastockdash_update_check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
