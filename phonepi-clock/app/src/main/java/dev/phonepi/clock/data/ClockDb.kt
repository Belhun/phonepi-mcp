package dev.phonepi.clock.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey val id: String,
    val hour: Int,
    val minute: Int,
    val daysMask: Int,
    val label: String,
    val enabled: Boolean,
    val deleteAfterRing: Boolean,
    val soundType: String,
    val soundUri: String,
    val soundName: String,
    val vibrate: Boolean,
    val vibratePattern: String,
    val rampSeconds: Int,
    val snoozeMinutes: Int,
    val maxSnoozes: Int,
    val silenceAfterMinutes: Int,
    val skipNext: Boolean,
    val preAlarmMinutes: Int,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
    val version: Int,
)

@Entity(
    tableName = "instances",
    indices = [Index("alarmId"), Index("state")],
)
data class InstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alarmId: String,
    val scheduledAt: Long,
    val state: String,
    val snoozeCount: Int,
    val firedAt: Long?,
    val endedAt: Long?,
    val endedBy: String?,
)

@Entity(tableName = "requests")
data class RequestEntity(
    @PrimaryKey val requestId: String,
    val atMs: Long,
    val body: String,
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val kind: String,
    val alarmId: String,
    val detail: String,
)

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute, label")
    fun all(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun get(id: String): AlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface InstanceDao {
    @Insert
    fun insert(instance: InstanceEntity): Long

    @Update
    fun update(instance: InstanceEntity)

    @Query("SELECT * FROM instances WHERE id = :id")
    fun get(id: Long): InstanceEntity?

    @Query(
        "SELECT * FROM instances WHERE alarmId = :alarmId AND state = 'scheduled' ORDER BY id DESC LIMIT 1"
    )
    fun scheduled(alarmId: String): InstanceEntity?

    @Query(
        "SELECT * FROM instances WHERE alarmId = :alarmId AND state = 'ringing' ORDER BY id DESC LIMIT 1"
    )
    fun ringing(alarmId: String): InstanceEntity?

    @Query(
        "SELECT * FROM instances WHERE alarmId = :alarmId AND state = 'snoozed' ORDER BY id DESC LIMIT 1"
    )
    fun snoozed(alarmId: String): InstanceEntity?

    @Query(
        "SELECT * FROM instances WHERE state = 'ringing' ORDER BY firedAt DESC LIMIT 1"
    )
    fun anyRinging(): InstanceEntity?

    @Query("UPDATE instances SET state = 'replaced' WHERE alarmId = :alarmId AND state = 'scheduled'")
    fun replaceScheduled(alarmId: String)

    @Query("DELETE FROM instances WHERE alarmId = :alarmId")
    fun deleteForAlarm(alarmId: String)

    @Query(
        "SELECT * FROM instances WHERE alarmId = :alarmId ORDER BY id DESC LIMIT :limit"
    )
    fun recent(alarmId: String, limit: Int): List<InstanceEntity>
}

@Dao
interface RequestDao {
    @Query("SELECT * FROM requests ORDER BY atMs ASC")
    fun all(): List<RequestEntity>

    @Query("DELETE FROM requests")
    fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(rows: List<RequestEntity>)
}

@Dao
interface EventDao {
    @Insert
    fun insert(event: EventEntity)

    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit")
    fun recent(limit: Int): List<EventEntity>

    @Query("DELETE FROM events WHERE alarmId = :alarmId")
    fun deleteForAlarm(alarmId: String)
}

@Database(
    entities = [AlarmEntity::class, InstanceEntity::class, RequestEntity::class, EventEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class ClockDatabase : RoomDatabase() {
    abstract fun alarms(): AlarmDao
    abstract fun instances(): InstanceDao
    abstract fun requests(): RequestDao
    abstract fun events(): EventDao

    companion object {
        @Volatile private var instance: ClockDatabase? = null

        fun get(context: Context): ClockDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext.createDeviceProtectedStorageContext(),
                    ClockDatabase::class.java,
                    "phonepi-clock.db",
                ).addMigrations(ClockMigrations.MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}

object ClockMigrations {
    const val ADD_PRE_ALARM_SQL =
        "ALTER TABLE alarms ADD COLUMN preAlarmMinutes INTEGER NOT NULL DEFAULT 30"

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(ADD_PRE_ALARM_SQL)
        }
    }

    const val V1_ALARMS = """
        CREATE TABLE IF NOT EXISTS alarms (
            id TEXT NOT NULL PRIMARY KEY,
            hour INTEGER NOT NULL,
            minute INTEGER NOT NULL,
            daysMask INTEGER NOT NULL,
            label TEXT NOT NULL,
            enabled INTEGER NOT NULL,
            deleteAfterRing INTEGER NOT NULL,
            soundType TEXT NOT NULL,
            soundUri TEXT NOT NULL,
            soundName TEXT NOT NULL,
            vibrate INTEGER NOT NULL,
            vibratePattern TEXT NOT NULL,
            rampSeconds INTEGER NOT NULL,
            snoozeMinutes INTEGER NOT NULL,
            maxSnoozes INTEGER NOT NULL,
            silenceAfterMinutes INTEGER NOT NULL,
            skipNext INTEGER NOT NULL,
            createdBy TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            version INTEGER NOT NULL
        )
    """
}
