package dev.phonepi.companion.usage

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Master switch (on by default) plus pause/clear. Collects a daily rollup
 * and pushes it to the box. Usage access still has to be granted in Settings.
 */
object UsageEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile
    private var started = false

    fun start(context: Context) {
        val app = context.applicationContext
        if (started) {
            scope.launch { collectAndSync(app) }
            return
        }
        started = true
        scope.launch {
            collectAndSync(app)
            while (isActive) {
                delay(USAGE_COLLECT_INTERVAL_MS)
                collectAndSync(app)
            }
        }
    }

    fun refresh(context: Context) {
        scope.launch { collectAndSync(context.applicationContext) }
    }

    suspend fun collectAndSync(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val store = UsageCollector.store(app)
                if (UsagePrefs.canCapture(app)) {
                    val days = UsageCollector.collect(app)
                    if (days.isNotEmpty()) store.write(days)
                }
                UsageSync.nudge()
            }
        }
    }

    suspend fun clear(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            withContext(Dispatchers.IO) {
                UsageCollector.store(app).clear()
            }
        }
        UsagePrefs.setPendingRemoteClear(app, true)
        UsageSync.requestClear(app)
        ActivityLog.logUsage("Deleted all screen time history", "success")
    }

    fun currentMeta(context: Context, nowMs: Long = System.currentTimeMillis()): UsageMeta {
        val days = UsageCollector.store(context).readAll()
        return UsageMeta(
            enabled = UsagePrefs.enabled(context),
            paused = UsagePrefs.paused(context),
            usageAccessGranted = UsageAccess.isGranted(context),
            timeZone = ZoneId.systemDefault().id,
            lastUsedAt = days.mapNotNull { it.lastUsedAt }.maxOrNull(),
            updatedAtMs = nowMs,
        )
    }
}
