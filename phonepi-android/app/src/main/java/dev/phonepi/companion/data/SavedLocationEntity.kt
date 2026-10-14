package dev.phonepi.companion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "saved_locations")
data class SavedLocationEntity(
    @PrimaryKey val nameKey: String,
    val name: String,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val mapsUrl: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface SavedLocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedLocationEntity)

    @Query("SELECT * FROM saved_locations ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<SavedLocationEntity>

    @Query("SELECT * FROM saved_locations WHERE nameKey = :nameKey LIMIT 1")
    suspend fun getByKey(nameKey: String): SavedLocationEntity?

    @Query("DELETE FROM saved_locations WHERE nameKey = :nameKey")
    suspend fun deleteByKey(nameKey: String): Int
}
