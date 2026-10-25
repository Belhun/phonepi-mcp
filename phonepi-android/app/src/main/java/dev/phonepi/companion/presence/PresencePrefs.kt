package dev.phonepi.companion.presence

import android.content.Context
import dev.phonepi.companion.history.LocationAccess

object PresencePrefs {
    private const val FILE = "presence"
    private const val ENABLED = "enabled"
    private const val PAUSED = "paused"
    private const val MARKS = "marks"
    private const val PENDING_CLEAR = "pending_remote_clear"
    private const val PLACE_KEY = "place_key"
    private const val PLACE_NAME = "place_name"
    private const val ARRIVED_AT = "arrived_at"
    private const val DRIVING = "driving"
    private const val MOVING = "moving"
    private const val ACTIVITY = "activity"
    private const val LAT = "lat"
    private const val LNG = "lng"
    private const val ACCURACY = "accuracy"
    private const val LAST_EVENT = "last_event"
    private const val LAST_EVENT_PLACE = "last_event_place"
    private const val LAST_EVENT_MS = "last_event_ms"

    fun enabled(context: Context): Boolean =
        prefs(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        edit(context) { putBoolean(ENABLED, value) }
    }

    fun paused(context: Context): Boolean =
        prefs(context).getBoolean(PAUSED, false)

    fun setPaused(context: Context, value: Boolean) {
        edit(context) { putBoolean(PAUSED, value) }
    }

    fun marks(context: Context): Set<String> =
        prefs(context).getStringSet(MARKS, emptySet())?.toSet() ?: emptySet()

    fun isMarked(context: Context, nameKey: String): Boolean =
        marks(context).contains(nameKey)

    fun setMarked(context: Context, nameKey: String, marked: Boolean) {
        val next = marks(context).toMutableSet()
        if (marked) next.add(nameKey) else next.remove(nameKey)
        edit(context) { putStringSet(MARKS, next) }
    }

    fun pendingRemoteClear(context: Context): Boolean =
        prefs(context).getBoolean(PENDING_CLEAR, false)

    fun setPendingRemoteClear(context: Context, value: Boolean) {
        edit(context) { putBoolean(PENDING_CLEAR, value) }
    }

    fun memory(context: Context): PresenceMemory {
        val p = prefs(context)
        return PresenceMemory(
            placeKey = p.getString(PLACE_KEY, null),
            placeName = p.getString(PLACE_NAME, null)?.ifBlank { null } ?: PRESENCE_AWAY,
            arrivedAtMs = p.getLong(ARRIVED_AT, -1L).takeIf { it >= 0 },
            driving = p.getBoolean(DRIVING, false),
            moving = p.getBoolean(MOVING, false),
            activity = p.getString(ACTIVITY, null) ?: "unknown",
            latitude = p.contains(LAT).takeIf { it }?.let { java.lang.Double.longBitsToDouble(p.getLong(LAT, 0)) },
            longitude = p.contains(LNG).takeIf { it }?.let { java.lang.Double.longBitsToDouble(p.getLong(LNG, 0)) },
            accuracyMeters = p.contains(ACCURACY).takeIf { it }?.let {
                java.lang.Double.longBitsToDouble(p.getLong(ACCURACY, 0))
            },
            lastEvent = p.getString(LAST_EVENT, null),
            lastEventPlace = p.getString(LAST_EVENT_PLACE, null),
            lastEventMs = p.getLong(LAST_EVENT_MS, -1L).takeIf { it >= 0 },
        )
    }

    fun setMemory(context: Context, memory: PresenceMemory) {
        edit(context) {
            if (memory.placeKey != null) putString(PLACE_KEY, memory.placeKey) else remove(PLACE_KEY)
            putString(PLACE_NAME, memory.placeName)
            if (memory.arrivedAtMs != null) putLong(ARRIVED_AT, memory.arrivedAtMs) else remove(ARRIVED_AT)
            putBoolean(DRIVING, memory.driving)
            putBoolean(MOVING, memory.moving)
            putString(ACTIVITY, memory.activity)
            if (memory.latitude != null) putLong(LAT, java.lang.Double.doubleToRawLongBits(memory.latitude))
            else remove(LAT)
            if (memory.longitude != null) putLong(LNG, java.lang.Double.doubleToRawLongBits(memory.longitude))
            else remove(LNG)
            if (memory.accuracyMeters != null) {
                putLong(ACCURACY, java.lang.Double.doubleToRawLongBits(memory.accuracyMeters))
            } else {
                remove(ACCURACY)
            }
            if (memory.lastEvent != null) putString(LAST_EVENT, memory.lastEvent) else remove(LAST_EVENT)
            if (memory.lastEventPlace != null) putString(LAST_EVENT_PLACE, memory.lastEventPlace)
            else remove(LAST_EVENT_PLACE)
            if (memory.lastEventMs != null) putLong(LAST_EVENT_MS, memory.lastEventMs) else remove(LAST_EVENT_MS)
        }
    }

    fun clearMemory(context: Context) {
        setMemory(context, PresenceMemory.empty())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun edit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(context).edit().apply(block).apply()
    }
}

object PresenceAccess {
    fun canCapture(context: Context): Boolean =
        PresencePrefs.enabled(context) &&
            !PresencePrefs.paused(context) &&
            (LocationAccess.fineGranted(context) || LocationAccess.coarseGranted(context))

    fun fineGranted(context: Context): Boolean = LocationAccess.fineGranted(context)

    fun backgroundGranted(context: Context): Boolean = LocationAccess.backgroundGranted(context)

    fun activityGranted(context: Context): Boolean = LocationAccess.activityGranted(context)
}
