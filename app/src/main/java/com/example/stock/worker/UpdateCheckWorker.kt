package com.example.stock.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stock.BuildConfig
import com.example.stock.ServiceLocator
import com.example.stock.data.repository.UpdateStore
import com.example.stock.push.NotificationHelper
import com.example.stock.util.isRemoteBuildNewer
import com.example.stock.util.parseBuildOrdinal
import com.example.stock.util.resolveLatestApkUrl
import com.example.stock.util.toShortBuildLabel

class UpdateCheckWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val updateStore = UpdateStore(applicationContext)
        updateStore.setLastCheckedAtMs(System.currentTimeMillis())

        val repo = ServiceLocator.repository(applicationContext)
        return repo.getLatestApkInfo().fold(
            onSuccess = { info ->
                val remoteCode = info.versionCode ?: 0
                val localCode = BuildConfig.VERSION_CODE
                val localLabel = BuildConfig.APP_BUILD_LABEL
                val remoteLabel = info.buildLabel.orEmpty()
                val localShort = toShortBuildLabel(localLabel, localCode)
                val remoteShort = toShortBuildLabel(remoteLabel, remoteCode)
                val remoteOrdinal = parseBuildOrdinal(remoteLabel)
                val alreadyNotifiedByOrdinal = remoteOrdinal != null && updateStore.getLastNotifiedBuildOrdinal() == remoteOrdinal

                // Notify when remote build is newer.
                val newer = isRemoteBuildNewer(
                    localVersionCode = localCode,
                    localBuildLabel = localLabel,
                    remoteVersionCode = remoteCode,
                    remoteBuildLabel = remoteLabel,
                )
                if (
                    newer &&
                    !alreadyNotifiedByOrdinal &&
                    updateStore.getLastNotifiedVersionCode() != remoteCode
                ) {
                    val apkUrl = resolveLatestApkUrl(info, BuildConfig.DEFAULT_BASE_URL)
                    NotificationHelper.showUpdate(
                        context = applicationContext,
                        title = "업데이트가 있습니다",
                        body = "현재 $localShort · 최신 $remoteShort. 탭하면 최신 설치 페이지로 이동합니다.",
                        apkUrl = apkUrl,
                    )
                    updateStore.setLastNotifiedVersionCode(remoteCode)
                    if (remoteOrdinal != null) {
                        updateStore.setLastNotifiedBuildOrdinal(remoteOrdinal)
                    }
                }
                Result.success()
            },
            onFailure = {
                // transient network errors: retry later via WorkManager backoff
                Result.retry()
            }
        )
    }
}
