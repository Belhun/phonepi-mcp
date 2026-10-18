package dev.phonepi.companion.history

import android.content.Context
import dev.phonepi.companion.log.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Applies fixes to the visit/drive detector, stores rows, reverse-geocodes
 * new visits, and asks the socket to sync.
 */
object LocationHistoryEngine {
    private val detector = VisitDriveDetector()
    private val mutex = Mutex()
    private var restored = false

    suspend fun onFix(context: Context, fix: LocationFix) {
        if (!LocationAccess.canCapture(context)) return
        val app = context.applicationContext
        val changes = mutex.withLock {
            restoreLocked(app)
            detector.onFix(fix)
        }
        store(app, changes)
    }

    suspend fun onMotion(context: Context, hint: MotionHint, timeMs: Long) {
        if (!LocationAccess.canCapture(context)) return
        val app = context.applicationContext
        val changes = mutex.withLock {
            restoreLocked(app)
            detector.onMotion(hint, timeMs)
        }
        store(app, changes)
    }

    suspend fun importTimeline(context: Context, text: String): TimelineImportResult {
        val result = parseTimelineExport(text)
        if (result.error != null) {
            ActivityLog.logLocation(result.error, "error")
            return result
        }
        val app = context.applicationContext
        store(app, result.events, geocode = false, logRows = false, armCapture = false)
        ActivityLog.logLocation(
            "Imported ${result.visits} visits and ${result.drives} drives (${result.skipped} skipped)",
            "success",
        )
        return result
    }

    suspend fun clear(context: Context) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            val dao = LocationHistoryDatabase.get(app).dao()
            dao.clearAll()
        }
        mutex.withLock {
            detector.restore(
                DetectorMemory(
                    candidateStart = null,
                    candidateLat = 0.0,
                    candidateLng = 0.0,
                    candidateLast = 0L,
                    visit = null,
                    drive = null,
                    driveMeters = 0.0,
                    lastLat = null,
                    lastLng = null,
                    lastTime = null,
                ),
            )
            restored = true
        }
        LocationHistoryPrefs.setPendingRemoteClear(app, true)
        LocationHistorySync.requestClear(app)
        ActivityLog.logLocation("Deleted all location history", "success")
    }

    fun driveActive(): Boolean = detector.memory().drive != null

    private suspend fun restoreLocked(context: Context) {
        if (restored) return
        val open = withContext(Dispatchers.IO) {
            LocationHistoryDatabase.get(context).dao().openEvents()
        }
        val visit = open.firstOrNull { it.kind == "visit" }?.toRecord()
        val drive = open.firstOrNull { it.kind == "drive" }?.toRecord()
        detector.restore(
            DetectorMemory(
                candidateStart = visit?.startMs,
                candidateLat = visit?.latitude ?: 0.0,
                candidateLng = visit?.longitude ?: 0.0,
                candidateLast = visit?.updatedAtMs ?: 0L,
                visit = visit,
                drive = drive,
                driveMeters = drive?.distanceMeters ?: 0.0,
                lastLat = visit?.latitude ?: drive?.latitude,
                lastLng = visit?.longitude ?: drive?.longitude,
                lastTime = visit?.updatedAtMs ?: drive?.updatedAtMs,
            ),
        )
        restored = true
    }

    private suspend fun store(
        context: Context,
        changes: List<LocationEventRecord>,
        geocode: Boolean = true,
        logRows: Boolean = true,
        armCapture: Boolean = true,
    ) {
        if (changes.isEmpty()) return
        val saved = mutableListOf<LocationEventRecord>()
        withContext(Dispatchers.IO) {
            val dao = LocationHistoryDatabase.get(context).dao()
            for (record in changes) {
                var next = record
                if (geocode && next.kind == "visit" && next.address.isNullOrBlank()) {
                    val label = DeviceGeocoder.reverse(context, next.latitude, next.longitude)
                    if (label != null) {
                        next = next.copy(
                            placeName = next.placeName ?: label.placeName,
                            address = label.address,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }
                }
                dao.upsert(next.toEntity("pending"))
                saved += next
            }
            val cutoff = System.currentTimeMillis() - PHONE_RETENTION_MS
            dao.deleteOlderThan(cutoff)
            dao.trimTo(MAX_STORED_EVENTS)
        }
        val visits = saved.count { it.kind == "visit" }
        val closedDrives = saved.count { it.kind == "drive" && it.endMs != null }
        if (logRows && (visits > 0 || closedDrives > 0)) {
            ActivityLog.logLocation("Recorded $visits visit(s) and $closedDrives drive(s)", "info")
        }
        if (armCapture) {
            LocationCapture.onDriveActive(context, driveActive())
            val visit = saved.lastOrNull { it.kind == "visit" && it.endMs == null }
            if (visit != null) LocationCapture.armGeofence(context, visit.latitude, visit.longitude)
        }
        LocationHistorySync.nudge()
    }
}
