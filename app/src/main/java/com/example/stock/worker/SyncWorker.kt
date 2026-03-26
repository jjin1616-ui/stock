package com.example.stock.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.stock.ServiceLocator
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

class SyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val today = Clock.System.todayIn(TimeZone.of("Asia/Seoul")).toString()
        val repo = ServiceLocator.repository(applicationContext)
        return runCatching {
            repo.getPremarket(today)
            repo.getEod(today)
            repo.fetchAlerts(20)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}
