package dev.phonepi.wear

import android.content.Context

/**
 * Last presence label (and optional next-alarm echo) from the phone DataItem.
 * INTEGRATION: cursor/phonepi-presence fills [label] via the phone publisher.
 * This cache never holds a token.
 */
object PresenceCache {
    private const val PREFS = "phonepi_wear_state"
    private const val KEY_LABEL = "presence_label"
    private const val KEY_ALARM = "next_alarm_epoch"

    @Volatile
    var lastAck: WearAck? = null

    fun label(context: Context): String? {
        val mem = memLabel
        if (!mem.isNullOrBlank()) return mem
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LABEL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun nextAlarmEpochMs(context: Context): Long? {
        memAlarm?.let { return it.takeIf { value -> value > 0L } }
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_ALARM, -1L)
        return stored.takeIf { it > 0L }
    }

    fun update(context: Context, presenceLabel: String?, nextAlarmEpochMs: Long?) {
        memLabel = presenceLabel?.trim()?.takeIf { it.isNotEmpty() }
        memAlarm = nextAlarmEpochMs
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LABEL, memLabel.orEmpty())
            .putLong(KEY_ALARM, nextAlarmEpochMs ?: -1L)
            .apply()
    }

    @Volatile
    private var memLabel: String? = null

    @Volatile
    private var memAlarm: Long? = null
}
