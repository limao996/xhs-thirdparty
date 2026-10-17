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

    /** 已结束的任务：完成或失败（与下载页「清空已完成」的文案一致）。 */
    @Query("SELECT * FROM download_task WHERE status IN ('DONE', 'FAILED') ORDER BY updatedAt DESC")
    suspend fun finished(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun byId(id: Long): DownloadTaskEntity?

    /** 某件作品的全部任务（去重与"已下载"标记要用）。 */
    @Query("SELECT * FROM download_task WHERE noteId = :noteId ORDER BY kind DESC, imageIndex ASC")
    suspend fun byNote(noteId: Long): List<DownloadTaskEntity>

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
            "bytes = :bytes, error = NULL, stage = '', updatedAt = :now WHERE id = :id"
    )
    suspend fun setDone(id: Long, path: String, uri: String?, bytes: Long, now: Long)

    /**
     * 只更新"进行中的阶段"（见 `DownloadStage`），不改状态。
     *
     * 用途：切片下完之后的 合并 / 转封装 / 保存 三段没有可数进度，
     * 界面与通知靠这个字段告诉用户"现在在干什么"，避免看起来像卡住。
     */
    @Query("UPDATE download_task SET stage = :stage, updatedAt = :now WHERE id = :id")
    suspend fun setStage(id: Long, stage: String, now: Long)

    /**
     * 把"进程被杀时卡在 RUNNING"的任务重新排队。
     *
     * 为什么必须有：任务状态在库里，进程一死 RUNNING 就永远停在那里，
     * 下次启动时 `unfinished()` 虽然会读到它，但调度只挑 QUEUED（PAUSED 是用户意图），
     * 于是这些任务**再也不会被跑**。启动恢复时先调它。
     */
    @Query("UPDATE download_task SET status = 'QUEUED', error = NULL, updatedAt = :now WHERE status = 'RUNNING'")
    suspend fun requeueRunning(now: Long): Int

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun delete(id: Long)

    /** 清掉已结束的记录（完成 **与** 失败，与界面文案一致）。 */
    @Query("DELETE FROM download_task WHERE status IN ('DONE', 'FAILED')")
    suspend fun clearFinished(): Int
    @Query("DELETE FROM download_task")
    suspend fun clearAll()
}
