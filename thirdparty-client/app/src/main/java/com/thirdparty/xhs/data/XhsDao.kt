package com.thirdparty.xhs.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 收藏：完全本地存储，不调用云端 v2/note/do-collect。
 * 键为 note_id，重复收藏直接覆盖（幂等）。
 */
@Dao
interface SavedNoteDao {
    @Query("SELECT * FROM saved_notes ORDER BY savedAt DESC")
    suspend fun all(): List<SavedNoteEntity>

    @Query("SELECT * FROM saved_notes WHERE noteId = :noteId LIMIT 1")
    suspend fun byId(noteId: Long): SavedNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedNoteEntity)

    @Query("DELETE FROM saved_notes WHERE noteId = :noteId")
    suspend fun remove(noteId: Long)

    @Query("DELETE FROM saved_notes")
    suspend fun clearAll()
}

/**
 * 最近浏览：本地记录查看过的内容，去重后按时间倒序，最多保留 100 条。
 */
@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE noteId = :noteId LIMIT 1")
    suspend fun byId(noteId: Long): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY viewedAt DESC LIMIT 100")
    suspend fun recent(): List<HistoryEntity>

    @Query("DELETE FROM history WHERE noteId != 0 AND noteId NOT IN (SELECT noteId FROM history ORDER BY viewedAt DESC LIMIT 100)")
    suspend fun trim()

    @Query("DELETE FROM history")
    suspend fun clearAll()
}