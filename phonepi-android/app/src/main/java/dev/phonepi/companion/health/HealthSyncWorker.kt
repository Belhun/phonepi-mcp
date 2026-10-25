package dev.phonepi.companion.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class HealthSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!HealthConnectPrefs.canSync(applicationContext)) return Result.success()
        return try {
            HealthSync.run(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object HealthSyncScheduler {
    private const val UNIQUE = "phonepi-health-sync"

    fun ensure(context: Context) {
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(
            HEALTH_SYNC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE)
    }

    fun apply(context: Context) {
        if (HealthConnectPrefs.enabled(context)) ensure(context) else cancel(context)
    }
}
