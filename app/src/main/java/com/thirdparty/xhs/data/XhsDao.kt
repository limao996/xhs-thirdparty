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
 * 最近浏览：本地记录查看过的内容，去重后按时间倒序；条数由设置里的「最近浏览上限」决定（默认 2000，见 `CredentialStore.historyLimit`）。
 */
@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE noteId = :noteId LIMIT 1")
    suspend fun byId(noteId: Long): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HistoryEntity)

        // The cap is a parameter, not a literal: 最近浏览 keeps far more than the
    // original 100 (default 2000, user-configurable).
    @Query("SELECT * FROM history ORDER BY viewedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HistoryEntity>

        @Query("DELETE FROM history WHERE noteId != 0 AND noteId NOT IN (SELECT noteId FROM history ORDER BY viewedAt DESC LIMIT :limit)")
    suspend fun trim(limit: Int)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

/**
 * 稍后观看队列：本地有序表。
 *
 * 顺序只靠 [position]：移动一格就交换两个 position，增删之后由仓库层重排压紧。
 * 不做链表/浮点 position —— 队列只有几十条，重写一遍比维护链表简单且不会积累误差。
 */
@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY position ASC, addedAt ASC")
    suspend fun all(): List<WatchLaterEntity>

    @Query("SELECT * FROM watch_later WHERE noteId = :noteId LIMIT 1")
    suspend fun byId(noteId: Long): WatchLaterEntity?

    @Query("SELECT COUNT(*) FROM watch_later")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM watch_later")
    suspend fun maxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchLaterEntity)

    @Query("UPDATE watch_later SET position = :position WHERE noteId = :noteId")
    suspend fun setPosition(noteId: Long, position: Int)

    @Query("DELETE FROM watch_later WHERE noteId = :noteId")
    suspend fun remove(noteId: Long)

    @Query("DELETE FROM watch_later")
    suspend fun clearAll()
}