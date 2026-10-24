package dev.phonepi.clock.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClockMigrationTest {
    @Test
    fun migrationAddsPreAlarmMinutesAndKeepsTheRow() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("clock-v1.db")
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        raw.execSQL(ClockMigrations.V1_ALARMS)
        raw.execSQL(
            "INSERT INTO alarms (id, hour, minute, daysMask, label, enabled, deleteAfterRing, soundType, soundUri, soundName, vibrate, vibratePattern, rampSeconds, snoozeMinutes, maxSnoozes, silenceAfterMinutes, skipNext, createdBy, createdAt, updatedAt, version) " +
                "VALUES ('sat', 7, 45, 32, 'Saturday', 1, 0, 'default', '', '', 1, '0,500', 0, 10, 0, 15, 0, 'test', 1, 1, 1)"
        )
        raw.execSQL(ClockMigrations.ADD_PRE_ALARM_SQL)
        raw.rawQuery("SELECT preAlarmMinutes, label FROM alarms WHERE id = 'sat'", null).use { cursor ->
            cursor.moveToFirst()
            assertEquals(30, cursor.getInt(0))
            assertEquals("Saturday", cursor.getString(1))
        }
        raw.close()
    }

    @Test
    fun roomRoundTripKeepsAnAlarm() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ClockDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.alarms().upsert(
            AlarmEntity(
                id = "sat",
                hour = 7,
                minute = 45,
                daysMask = 32,
                label = "Saturday",
                enabled = true,
                deleteAfterRing = false,
                soundType = "default",
                soundUri = "",
                soundName = "",
                vibrate = true,
                vibratePattern = "0,500",
                rampSeconds = 0,
                snoozeMinutes = 10,
                maxSnoozes = 0,
                silenceAfterMinutes = 15,
                skipNext = false,
                preAlarmMinutes = 30,
                createdBy = "test",
                createdAt = 1,
                updatedAt = 1,
                version = 1,
            )
        )
        val ledger = dev.phonepi.clock.schedule.RequestLedger()
        ledger.remember("11111111-1111-4111-8111-111111111111", 10, """{"status":"success"}""")
        ledger.remember("11111111-1111-4111-8111-111111111111", 11, """{"status":"other"}""")
        assertEquals("""{"status":"success"}""", ledger.replay("11111111-1111-4111-8111-111111111111", 12))
        assertEquals("Saturday", db.alarms().get("sat")!!.label)
        assertEquals(30, db.alarms().get("sat")!!.preAlarmMinutes)
        db.close()
    }
}
