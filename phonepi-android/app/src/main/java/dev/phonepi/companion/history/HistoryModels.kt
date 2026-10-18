package dev.phonepi.companion.history

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val DWELL_MS = 8 * 60 * 1000L
const val VISIT_RADIUS_METERS = 120.0
const val MAX_ACCURACY_METERS = 200f
const val MIN_DRIVE_METERS = 250.0
const val DRIVE_SPEED_MPS = 4f
const val STILL_SPEED_MPS = 1.2f
const val DRIVE_STEP_METERS = 30.0
const val MAX_STORED_EVENTS = 8000
const val PHONE_RETENTION_MS = 400L * 24L * 60L * 60L * 1000L

fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val earth = 6_371_000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(p1) * cos(p2) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * earth * atan2(sqrt(a), sqrt(1 - a))
}

data class LocationFix(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float?,
)

enum class MotionHint { VEHICLE_START, VEHICLE_STOP, STILL }

data class LocationEventRecord(
    val id: String,
    val kind: String,
    val startMs: Long,
    val endMs: Long?,
    val latitude: Double,
    val longitude: Double,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val placeName: String?,
    val address: String?,
    val distanceMeters: Double?,
    val source: String,
    val updatedAtMs: Long,
)

class IdSource {
    private var n = 0
    fun next(prefix: String): String {
        n += 1
        return "$prefix-${n.toString().padStart(8, '0')}"
    }
}
