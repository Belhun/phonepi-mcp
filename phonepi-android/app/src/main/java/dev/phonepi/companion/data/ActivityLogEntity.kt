package dev.phonepi.companion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "activity_log")
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val category: String,
    val title: String,
    val detail: String,
    val status: String,
)

@Dao
interface ActivityLogDao {
    @Insert
    suspend fun insert(entry: ActivityLogEntity)

    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ActivityLogEntity>

    @Query("DELETE FROM activity_log")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM activity_log")
    suspend fun count(): Int
}
