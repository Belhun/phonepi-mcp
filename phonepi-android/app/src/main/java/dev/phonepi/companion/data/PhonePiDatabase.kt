package dev.phonepi.companion.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SnippetEntity::class,
        MessageEntity::class,
        ActivityLogEntity::class,
        SavedLocationEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class PhonePiDatabase : RoomDatabase() {
    abstract fun snippetDao(): SnippetDao
    abstract fun messageDao(): MessageDao
    abstract fun activityLogDao(): ActivityLogDao
    abstract fun savedLocationDao(): SavedLocationDao

    companion object {
        @Volatile
        private var instance: PhonePiDatabase? = null

        fun get(context: Context): PhonePiDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PhonePiDatabase::class.java,
                    "phonepi.db",
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
