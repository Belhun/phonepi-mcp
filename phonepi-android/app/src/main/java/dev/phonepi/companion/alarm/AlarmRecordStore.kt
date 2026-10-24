package dev.phonepi.companion.alarm

import android.content.Context

/** Small on-phone log of alarms PhonePi created. Shared prefs, capped, no token. */
object AlarmRecordStore {
    private const val PREFS = "phonepi_alarms"
    private const val KEY = "created"

    fun list(context: Context): List<PhonePiAlarmRecord> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        return AlarmRecordLog.decode(raw)
    }

    fun add(context: Context, record: PhonePiAlarmRecord) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val next = AlarmRecordLog.remember(AlarmRecordLog.decode(prefs.getString(KEY, null)), record)
            prefs.edit().putString(KEY, AlarmRecordLog.encode(next)).apply()
        }
    }
}
