package dev.phonepi.companion.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val type: String,
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface SnippetDao {
    @Insert
    suspend fun insert(entity: SnippetEntity): Long

    @Update
    suspend fun update(entity: SnippetEntity)

    @Query("SELECT * FROM snippets ORDER BY updatedAt DESC")
    suspend fun getAll(): List<SnippetEntity>

    @Query("SELECT * FROM snippets WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SnippetEntity?

    @Query("SELECT * FROM snippets WHERE type = :type ORDER BY updatedAt DESC")
    suspend fun getByType(type: String): List<SnippetEntity>

    @Query(
        """
        SELECT * FROM snippets
        WHERE lower(title) LIKE '%' || lower(:query) || '%'
           OR lower(content) LIKE '%' || lower(:query) || '%'
           OR lower(tags) LIKE '%' || lower(:query) || '%'
        ORDER BY updatedAt DESC
        """,
    )
    suspend fun search(query: String): List<SnippetEntity>

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
