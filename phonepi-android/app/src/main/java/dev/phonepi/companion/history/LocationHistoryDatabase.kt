package dev.phonepi.companion.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LocationEventEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LocationHistoryDatabase : RoomDatabase() {
    abstract fun dao(): LocationEventDao

    companion object {
        @Volatile
        private var instance: LocationHistoryDatabase? = null

        fun get(context: Context): LocationHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocationHistoryDatabase::class.java,
                    "phonepi-location.db",
                ).build().also { instance = it }
            }
    }
}
