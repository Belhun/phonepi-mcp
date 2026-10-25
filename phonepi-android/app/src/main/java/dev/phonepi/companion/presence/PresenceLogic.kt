package dev.phonepi.companion.presence

import dev.phonepi.companion.history.haversineMeters
import kotlin.math.floor

const val PRESENCE_MAX_GEOFENCES = 100
const val PRESENCE_RADIUS_METERS = 150f
const val PRESENCE_CHECK_INTERVAL_MS = 3 * 60 * 1000L
const val PRESENCE_AWAY = "away"

data class PresencePlace(
    val nameKey: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = PRESENCE_RADIUS_METERS,
) {
    fun geofenceId(): String = "p:${nameKey.take(90)}"
}

data class PresenceMemory(
    val placeKey: String?,
    val placeName: String,
    val arrivedAtMs: Long?,
    val driving: Boolean,
    val moving: Boolean,
    val activity: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
    val lastEvent: String?,
    val lastEventPlace: String?,
    val lastEventMs: Long?,
) {
    companion object {
        fun empty(): PresenceMemory = PresenceMemory(
            placeKey = null,
            placeName = PRESENCE_AWAY,
            arrivedAtMs = null,
            driving = false,
            moving = false,
            activity = "unknown",
            latitude = null,
            longitude = null,
            accuracyMeters = null,
            lastEvent = null,
            lastEventPlace = null,
            lastEventMs = null,
        )
    }
}

data class PresenceSnapshot(
    val capturedAtMs: Long,
    val enabled: Boolean,
    val paused: Boolean,
    val place: String,
    val minutesAtPlace: Int,
    val arrivedAtMs: Long?,
    val driving: Boolean,
    val moving: Boolean,
    val activity: String,
    val dnd: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Double?,
    val lastEvent: String?,
    val lastEventPlace: String?,
    val lastEventMs: Long?,
    val source: String,
)

object PresenceLogic {
    fun geofencePlaces(places: List<PresencePlace>): List<PresencePlace> =
        places
            .filter { it.latitude.isFinite() && it.longitude.isFinite() }
            .distinctBy { it.nameKey }
            .take(PRESENCE_MAX_GEOFENCES)

    fun matchPlace(latitude: Double, longitude: Double, places: List<PresencePlace>): PresencePlace? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        return geofencePlaces(places)
            .map { place -> place to haversineMeters(latitude, longitude, place.latitude, place.longitude) }
            .filter { (place, meters) -> meters <= place.radiusMeters }
            .minByOrNull { it.second }
            ?.first
    }

    fun minutesAt(arrivedAtMs: Long?, nowMs: Long): Int {
        if (arrivedAtMs == null || nowMs < arrivedAtMs) return 0
        return floor((nowMs - arrivedAtMs) / 60_000.0).toInt().coerceAtLeast(0)
    }

    fun activityFlags(activity: String): Pair<Boolean, Boolean> {
        return when (activity) {
            "driving" -> true to true
            "walking", "running", "cycling" -> false to true
            else -> false to false
        }
    }

    fun activityName(detectedType: Int): String = when (detectedType) {
        0 -> "driving" // DetectedActivity.IN_VEHICLE
        1 -> "cycling" // ON_BICYCLE
        2 -> "walking" // ON_FOOT
        7 -> "walking" // WALKING
        8 -> "running" // RUNNING
        3 -> "still" // STILL
        else -> "unknown"
    }

    fun onFix(
        memory: PresenceMemory,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        nowMs: Long,
        places: List<PresencePlace>,
    ): PresenceMemory {
        val match = matchPlace(latitude, longitude, places)
        return arriveOrLeave(
            memory.copy(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracyMeters,
            ),
            match,
            nowMs,
        )
    }

    fun onGeofence(
        memory: PresenceMemory,
        geofenceIds: List<String>,
        enter: Boolean,
        nowMs: Long,
        places: List<PresencePlace>,
    ): PresenceMemory {
        val byId = geofencePlaces(places).associateBy { it.geofenceId() }
        val hits = geofenceIds.mapNotNull { byId[it] }
        if (hits.isEmpty()) return memory
        val next = if (enter) {
            hits.minByOrNull { place ->
                val lat = memory.latitude
                val lng = memory.longitude
                if (lat == null || lng == null) 0.0
                else haversineMeters(lat, lng, place.latitude, place.longitude)
            }
        } else {
            if (memory.placeKey != null && hits.any { it.nameKey == memory.placeKey }) null
            else geofencePlaces(places).firstOrNull { it.nameKey == memory.placeKey }
        }
        return arriveOrLeave(memory, next, nowMs)
    }

    fun onActivity(memory: PresenceMemory, activity: String, nowMs: Long): PresenceMemory {
        val flags = activityFlags(activity)
        return memory.copy(
            activity = activity,
            driving = flags.first,
            moving = flags.second,
            lastEventMs = memory.lastEventMs ?: nowMs,
        )
    }

    fun snapshot(
        memory: PresenceMemory,
        nowMs: Long,
        enabled: Boolean,
        paused: Boolean,
        dnd: String,
        source: String,
    ): PresenceSnapshot = PresenceSnapshot(
        capturedAtMs = nowMs,
        enabled = enabled,
        paused = paused,
        place = memory.placeName,
        minutesAtPlace = minutesAt(memory.arrivedAtMs, nowMs),
        arrivedAtMs = memory.arrivedAtMs,
        driving = memory.driving,
        moving = memory.moving,
        activity = memory.activity,
        dnd = dnd,
        latitude = if (enabled && !paused) memory.latitude else null,
        longitude = if (enabled && !paused) memory.longitude else null,
        accuracyMeters = if (enabled && !paused) memory.accuracyMeters else null,
        lastEvent = memory.lastEvent,
        lastEventPlace = memory.lastEventPlace,
        lastEventMs = memory.lastEventMs,
        source = source,
    )

    private fun arriveOrLeave(
        memory: PresenceMemory,
        match: PresencePlace?,
        nowMs: Long,
    ): PresenceMemory {
        val nextKey = match?.nameKey
        val nextName = match?.name ?: PRESENCE_AWAY
        if (nextKey == memory.placeKey && nextName == memory.placeName) {
            return memory.copy(
                arrivedAtMs = memory.arrivedAtMs ?: if (match != null) nowMs else null,
            )
        }
        val event = if (match != null) "arrive" else "leave"
        val eventPlace = if (match != null) nextName else memory.placeName
        return memory.copy(
            placeKey = nextKey,
            placeName = nextName,
            arrivedAtMs = if (match != null) nowMs else null,
            lastEvent = event,
            lastEventPlace = eventPlace,
            lastEventMs = nowMs,
        )
    }
}
