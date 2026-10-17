package com.thirdparty.xhs.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务的读写。
 *
 * 说明：状态流转集中在这里，避免 UI 与服务各写一套 SQL 而生成不一致的列值
 * （`updatedAt` 一律由调用方传入毫秒时间戳，DAO 不依赖系统时钟）。
 */
@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_task ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task ORDER BY createdAt ASC, id ASC")
    suspend fun all(): List<DownloadTaskEntity>

    /** 未完成的任务（应用重开后要继续的）。 */
    @Query(
        "SELECT * FROM download_task WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED') " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun unfinished(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE status = 'DONE' ORDER BY updatedAt DESC")
    suspend fun finished(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun byId(id: Long): DownloadTaskEntity?

    @Query("SELECT COUNT(*) FROM download_task WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED')")
    fun observeUnfinishedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM download_task")
    suspend fun count(): Int

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("UPDATE download_task SET status = :status, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?, now: Long)

    @Query(
        "UPDATE download_task SET doneSegments = :done, totalSegments = :total, " +
            "bytes = :bytes, updatedAt = :now WHERE id = :id"
    )
    suspend fun setProgress(id: Long, done: Int, total: Int, bytes: Long, now: Long)

    @Query(
        "UPDATE download_task SET status = 'DONE', savedPath = :path, savedUri = :uri, " +
            "bytes = :bytes, error = NULL, updatedAt = :now WHERE id = :id"
    )
    suspend fun setDone(id: Long, path: String, uri: String?, bytes: Long, now: Long)

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM download_task WHERE status = 'DONE'")
    suspend fun clearFinished(): Int

    @Query("DELETE FROM download_task")
    suspend fun clearAll()
}
