package com.thirdparty.xhs.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 下载任务（一个任务 = 一个文件：视频 1 个，或某张图片）。
 *
 * 为什么一行一个文件而不是"一个作品一行"：下载对话框允许**多选**图片，且并发是按文件算的，
 * 一行一个文件能让"并发任务数 / 单文件并行切片 / 逐条暂停重试"都落在同一种粒度上。
 *
 * `kind` / `status` 存字符串而不是枚举：这张表的迁移 SQL 要手写，字符串列的建表语句最不容易写错，
 * 也便于以后加状态；语义常量见 [DownloadKind] / [DownloadStatus]。
 */
@Entity(tableName = "download_task")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val noteId: Long,
    /** 作品标题，用来生成公共目录里的文件名 */
    val title: String,
    /** 作品封面地址（下载管理页显示缩略图用；空串表示没有封面，退回类型图标） */
    val cover: String = "",
    /** [DownloadKind.VIDEO] 或 [DownloadKind.IMAGE] */
    val kind: String,
    /** 视频：m3u8 地址；图片：图片地址 */
    val sourceUrl: String,
    /** 图片在作品内的序号（视频恒为 0），决定 `01.jpg` 这种文件名 */
    val imageIndex: Int = 0,
    /** [DownloadStatus] 之一 */
    val status: String,
    /** HLS 已完成的切片数 / 总数（图片任务恒为 1/1） */
    val doneSegments: Int = 0,
    val totalSegments: Int = 0,
    /** 已落盘字节数（进行中是"已下载"，完成后是最终文件大小） */
    val bytes: Long = 0L,
    /** 落盘后的展示路径，如 `Movies/小黄书/标题_1.mp4` */
    @ColumnInfo(name = "savedPath") val savedPath: String? = null,
    /** 落盘后的 MediaStore uri 字符串，供"打开/播放"用 */
    @ColumnInfo(name = "savedUri") val savedUri: String? = null,
    /** 失败原因（给用户看的具体原因，不是英文异常） */
    @ColumnInfo(name = "error") val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/** 任务类型。 */
object DownloadKind {
    const val VIDEO = "VIDEO"
    const val IMAGE = "IMAGE"
}

/** 任务状态。`QUEUED`/`RUNNING`/`PAUSED` 都算"未完成"，会在应用重开后继续。 */
object DownloadStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val DONE = "DONE"
    const val FAILED = "FAILED"

    val unfinished = listOf(QUEUED, RUNNING, PAUSED)
}
