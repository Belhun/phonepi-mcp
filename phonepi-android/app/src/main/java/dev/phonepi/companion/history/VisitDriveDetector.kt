package dev.phonepi.companion.history

/**
 * Turns sparse fixes and activity hints into visits and drives.
 * A visit is a dwell inside [radiusMeters] for at least [dwellMs].
 * A drive is movement of at least [minDriveMeters], usually from a vehicle hint.
 * GPS is not polled here. The caller decides when a fix arrives.
 */
class VisitDriveDetector(
    private val dwellMs: Long = DWELL_MS,
    private val radiusMeters: Double = VISIT_RADIUS_METERS,
    private val minDriveMeters: Double = MIN_DRIVE_METERS,
    private val ids: IdSource = IdSource(),
) {
    private var candidateStart: Long? = null
    private var candidateLat = 0.0
    private var candidateLng = 0.0
    private var candidateLast = 0L
    private var visit: LocationEventRecord? = null
    private var drive: LocationEventRecord? = null
    private var driveMeters = 0.0
    private var lastLat: Double? = null
    private var lastLng: Double? = null
    private var lastTime: Long? = null

    fun onFix(fix: LocationFix): List<LocationEventRecord> {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) return emptyList()
        if (fix.accuracyMeters > MAX_ACCURACY_METERS) return emptyList()
        val changes = mutableListOf<LocationEventRecord>()
        val fast = fix.speedMps != null && fix.speedMps >= DRIVE_SPEED_MPS
        if (drive != null) {
            addDriveStep(fix.latitude, fix.longitude)
            if (fix.speedMps != null && fix.speedMps < STILL_SPEED_MPS) {
                closeDrive(fix.timeMs, fix.latitude, fix.longitude)?.let(changes::add)
                observeStill(fix)?.let(changes::add)
            } else {
                driveSnapshot(fix.timeMs, fix.latitude, fix.longitude)?.let(changes::add)
            }
            remember(fix)
            return changes
        }
        if (fast) {
            depart(fix.timeMs)?.let(changes::add)
            beginDrive(fix.timeMs, fix.latitude, fix.longitude)
            remember(fix)
            return changes
        }
        observeStill(fix)?.let(changes::add)
        remember(fix)
        return changes
    }

    fun onMotion(hint: MotionHint, timeMs: Long): List<LocationEventRecord> {
        val changes = mutableListOf<LocationEventRecord>()
        when (hint) {
            MotionHint.VEHICLE_START -> {
                depart(timeMs)?.let(changes::add)
                val lat = lastLat
                val lng = lastLng
                if (lat != null && lng != null) beginDrive(timeMs, lat, lng)
            }
            MotionHint.VEHICLE_STOP, MotionHint.STILL -> {
                val lat = lastLat
                val lng = lastLng
                if (drive != null && lat != null && lng != null) {
                    closeDrive(timeMs, lat, lng)?.let(changes::add)
                }
            }
        }
        return changes
    }

    fun memory(): DetectorMemory = DetectorMemory(
        candidateStart = candidateStart,
        candidateLat = candidateLat,
        candidateLng = candidateLng,
        candidateLast = candidateLast,
        visit = visit,
        drive = drive,
        driveMeters = driveMeters,
        lastLat = lastLat,
        lastLng = lastLng,
        lastTime = lastTime,
    )

    fun restore(memory: DetectorMemory) {
        candidateStart = memory.candidateStart
        candidateLat = memory.candidateLat
        candidateLng = memory.candidateLng
        candidateLast = memory.candidateLast
        visit = memory.visit
        drive = memory.drive
        driveMeters = memory.driveMeters
        lastLat = memory.lastLat
        lastLng = memory.lastLng
        lastTime = memory.lastTime
    }

    private fun observeStill(fix: LocationFix): LocationEventRecord? {
        val start = candidateStart
        if (start == null) {
            resetCandidate(fix)
            return null
        }
        val centerLat = visit?.latitude ?: candidateLat
        val centerLng = visit?.longitude ?: candidateLng
        if (!near(centerLat, centerLng, fix)) {
            val closed = if (visit != null) {
                depart(fix.timeMs)
            } else if (candidateLast - start >= dwellMs) {
                closedVisit(
                    id = ids.next("v"),
                    startMs = start,
                    endMs = candidateLast,
                    lat = candidateLat,
                    lng = candidateLng,
                    updatedAtMs = fix.timeMs,
                ).also { candidateStart = null }
            } else {
                candidateStart = null
                null
            }
            resetCandidate(fix)
            return closed
        }
        candidateLast = fix.timeMs
        if (visit == null && fix.timeMs - start >= dwellMs) {
            visit = openVisit(start, candidateLat, candidateLng, fix.timeMs)
            return visit
        }
        return null
    }

    private fun depart(timeMs: Long): LocationEventRecord? {
        val open = visit
        if (open != null) {
            val end = candidateLast.takeIf { it >= open.startMs } ?: timeMs
            val closed = open.copy(endMs = end, updatedAtMs = timeMs)
            visit = null
            candidateStart = null
            return closed
        }
        val start = candidateStart ?: return null
        val stayed = candidateLast - start
        candidateStart = null
        if (stayed < dwellMs) return null
        return closedVisit(
            id = ids.next("v"),
            startMs = start,
            endMs = candidateLast,
            lat = candidateLat,
            lng = candidateLng,
            updatedAtMs = timeMs,
        )
    }

    private fun beginDrive(timeMs: Long, lat: Double, lng: Double) {
        drive = LocationEventRecord(
            id = ids.next("d"),
            kind = "drive",
            startMs = timeMs,
            endMs = null,
            latitude = lat,
            longitude = lng,
            endLatitude = lat,
            endLongitude = lng,
            placeName = null,
            address = null,
            distanceMeters = 0.0,
            source = "phonepi",
            updatedAtMs = timeMs,
        )
        driveMeters = 0.0
    }

    private fun addDriveStep(lat: Double, lng: Double) {
        val prevLat = lastLat ?: return
        val prevLng = lastLng ?: return
        val step = haversineMeters(prevLat, prevLng, lat, lng)
        if (step >= DRIVE_STEP_METERS) driveMeters += step
    }

    private fun driveSnapshot(timeMs: Long, lat: Double, lng: Double): LocationEventRecord? {
        val open = drive ?: return null
        if (driveMeters < minDriveMeters) return null
        val updated = open.copy(
            endLatitude = lat,
            endLongitude = lng,
            distanceMeters = driveMeters,
            updatedAtMs = timeMs,
        )
        drive = updated
        return updated
    }

    private fun closeDrive(timeMs: Long, lat: Double, lng: Double): LocationEventRecord? {
        val open = drive ?: return null
        drive = null
        val distance = driveMeters
        driveMeters = 0.0
        val longEnough = timeMs - open.startMs >= 3 * 60 * 1000L && distance >= 100.0
        if (distance < minDriveMeters && !longEnough) return null
        return open.copy(
            endMs = timeMs,
            endLatitude = lat,
            endLongitude = lng,
            distanceMeters = distance,
            updatedAtMs = timeMs,
        )
    }

    private fun openVisit(startMs: Long, lat: Double, lng: Double, updatedAtMs: Long) =
        LocationEventRecord(
            id = ids.next("v"),
            kind = "visit",
            startMs = startMs,
            endMs = null,
            latitude = lat,
            longitude = lng,
            endLatitude = null,
            endLongitude = null,
            placeName = null,
            address = null,
            distanceMeters = null,
            source = "phonepi",
            updatedAtMs = updatedAtMs,
        )

    private fun closedVisit(
        id: String,
        startMs: Long,
        endMs: Long,
        lat: Double,
        lng: Double,
        updatedAtMs: Long,
    ) = LocationEventRecord(
        id = id,
        kind = "visit",
        startMs = startMs,
        endMs = endMs,
        latitude = lat,
        longitude = lng,
        endLatitude = null,
        endLongitude = null,
        placeName = null,
        address = null,
        distanceMeters = null,
        source = "phonepi",
        updatedAtMs = updatedAtMs,
    )

    private fun near(lat: Double, lng: Double, fix: LocationFix): Boolean =
        haversineMeters(lat, lng, fix.latitude, fix.longitude) <= radiusMeters

    private fun resetCandidate(fix: LocationFix) {
        candidateStart = fix.timeMs
        candidateLat = fix.latitude
        candidateLng = fix.longitude
        candidateLast = fix.timeMs
    }

    private fun remember(fix: LocationFix) {
        lastLat = fix.latitude
        lastLng = fix.longitude
        lastTime = fix.timeMs
    }
}

data class DetectorMemory(
    val candidateStart: Long?,
    val candidateLat: Double,
    val candidateLng: Double,
    val candidateLast: Long,
    val visit: LocationEventRecord?,
    val drive: LocationEventRecord?,
    val driveMeters: Double,
    val lastLat: Double?,
    val lastLng: Double?,
    val lastTime: Long?,
)
