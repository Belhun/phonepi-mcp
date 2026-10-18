package dev.phonepi.companion.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "location_events",
    indices = [Index("startMs"), Index("syncState")],
)
data class LocationEventEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val startMs: Long,
    val endMs: Long?,
    val latitude: Double,
    val longitude: Double,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val placeName: String?,
    val address: String?,
    val distanceMeters: Double?,
    val source: String,
    val updatedAtMs: Long,
    val syncState: String,
)

@Dao
interface LocationEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocationEventEntity)

    @Query(
        "SELECT * FROM location_events WHERE syncState = 'pending' " +
            "ORDER BY updatedAtMs ASC LIMIT :limit",
    )
    suspend fun pending(limit: Int): List<LocationEventEntity>

    @Query("SELECT * FROM location_events WHERE endMs IS NULL ORDER BY startMs DESC")
    suspend fun openEvents(): List<LocationEventEntity>

    @Query("UPDATE location_events SET syncState = 'synced' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE location_events SET syncState = 'rejected' WHERE id IN (:ids)")
    suspend fun markRejected(ids: List<String>)

    @Query("DELETE FROM location_events")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM location_events")
    suspend fun count(): Int

    @Query("DELETE FROM location_events WHERE startMs < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query(
        "DELETE FROM location_events WHERE id NOT IN (" +
            "SELECT id FROM location_events ORDER BY startMs DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int)
}

fun LocationEventEntity.toRecord(): LocationEventRecord = LocationEventRecord(
    id = id,
    kind = kind,
    startMs = startMs,
    endMs = endMs,
    latitude = latitude,
    longitude = longitude,
    endLatitude = endLatitude,
    endLongitude = endLongitude,
    placeName = placeName,
    address = address,
    distanceMeters = distanceMeters,
    source = source,
    updatedAtMs = updatedAtMs,
)

fun LocationEventRecord.toEntity(syncState: String): LocationEventEntity = LocationEventEntity(
    id = id,
    kind = kind,
    startMs = startMs,
    endMs = endMs,
    latitude = latitude,
    longitude = longitude,
    endLatitude = endLatitude,
    endLongitude = endLongitude,
    placeName = placeName,
    address = address,
    distanceMeters = distanceMeters,
    source = source,
    updatedAtMs = updatedAtMs,
    syncState = syncState,
)
