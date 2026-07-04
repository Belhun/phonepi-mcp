package dev.phonepi.companion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val title: String,
    val body: String,
    val state: String,
    val response: String?,
    val responseIndex: Int? = null,
    val respondedAt: Long? = null,
    val responseOptions: String? = null,
    val requireResponse: Boolean = false,
    val expiresAt: Long?,
    val createdAt: Long,
)

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(entity: MessageEntity)

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("SELECT * FROM messages WHERE messageId = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?
}
