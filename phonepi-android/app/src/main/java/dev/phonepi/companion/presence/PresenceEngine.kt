package dev.phonepi.companion.presence

import android.content.Context
import dev.phonepi.companion.data.PhonePiDatabase
import dev.phonepi.companion.log.ActivityLog
import dev.phonepi.companion.volume.VolumeController
import dev.phonepi.companion.wear.WearStatePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PresenceEngine {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun nudge(context: Context) {
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }

    suspend fun markedPlaces(context: Context): List<PresencePlace> = withContext(Dispatchers.IO) {
        val marks = PresencePrefs.marks(context)
        if (marks.isEmpty()) return@withContext emptyList()
        val saved = PhonePiDatabase.get(context).savedLocationDao().getAll()
        PresenceLogic.geofencePlaces(
            saved.mapNotNull { row ->
                val lat = row.latitude
                val lng = row.longitude
                if (row.nameKey !in marks || lat == null || lng == null) null
                else PresencePlace(row.nameKey, row.name, lat, lng)
            },
        )
    }

    suspend fun onFix(
        context: Context,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        nowMs: Long = System.currentTimeMillis(),
        source: String = "periodic",
    ) {
        if (!PresenceAccess.canCapture(context)) return
        apply(context, source, nowMs) { memory, places ->
            PresenceLogic.onFix(memory, latitude, longitude, accuracyMeters, nowMs, places)
        }
    }

    suspend fun onGeofence(
        context: Context,
        geofenceIds: List<String>,
        enter: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!PresenceAccess.canCapture(context)) return
        apply(context, "geofence", nowMs) { memory, places ->
            PresenceLogic.onGeofence(memory, geofenceIds, enter, nowMs, places)
        }
    }

    suspend fun onActivity(
        context: Context,
        activity: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!PresenceAccess.canCapture(context)) return
        apply(context, "activity", nowMs) { memory, _ ->
            PresenceLogic.onActivity(memory, activity, nowMs)
        }
    }

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        if (!PresencePrefs.enabled(app)) {
            PresenceCapture.stop(app)
            PresenceSync.requestClear(app)
            WearStatePublisher.publish(app)
            return
        }
        if (PresencePrefs.paused(app)) {
            PresenceCapture.stop(app)
            push(app, "manual")
            return
        }
        PresenceCapture.refresh(app, markedPlaces(app))
        PresenceCapture.requestOneFix(app)
    }

    suspend fun clear(context: Context) {
        val app = context.applicationContext
        mutex.withLock {
            PresencePrefs.clearMemory(app)
        }
        PresencePrefs.setPendingRemoteClear(app, true)
        PresenceSync.requestClear(app)
        ActivityLog.logPresence("Deleted presence snapshot", "success")
        PresenceCapture.refresh(app, markedPlaces(app))
    }

    private suspend fun apply(
        context: Context,
        source: String,
        nowMs: Long,
        block: (PresenceMemory, List<PresencePlace>) -> PresenceMemory,
    ) {
        val app = context.applicationContext
        val previous: PresenceMemory
        val next: PresenceMemory
        mutex.withLock {
            previous = PresencePrefs.memory(app)
            val places = markedPlaces(app)
            next = block(previous, places)
            PresencePrefs.setMemory(app, next)
        }
        if (previous.placeName != next.placeName) {
            val detail = when (next.lastEvent) {
                "arrive" -> "Arrived at ${next.placeName}"
                "leave" -> "Left ${previous.placeName}"
                else -> "Presence ${next.placeName}"
            }
            ActivityLog.logPresence(detail, "info")
        }
        push(app, source, nowMs)
    }

    private fun push(context: Context, source: String, nowMs: Long = System.currentTimeMillis()) {
        val dnd = when {
            dev.phonepi.companion.privacy.PhonePiQuiet.isActive(context) -> "quiet"
            else -> runCatching { VolumeController.snapshot(context).dnd }.getOrDefault("unknown")
        }
        val snapshot = PresenceLogic.snapshot(
            memory = PresencePrefs.memory(context),
            nowMs = nowMs,
            enabled = PresencePrefs.enabled(context),
            paused = PresencePrefs.paused(context),
            dnd = dnd,
            source = source,
        )
        PresenceSync.push(context, snapshot)
        WearStatePublisher.publish(context)
    }
}
