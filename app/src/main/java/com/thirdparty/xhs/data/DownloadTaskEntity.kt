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
    /**
     * 进行中的**阶段**（[DownloadStage] 之一），只在 `RUNNING` 时有意义。
     *
     * 为什么要有它：切片下完之后还有"合并 TS → 转封装 mp4 → 保存到相册"三段，
     * 其中转封装**拿不到进度**（media3 Transformer 只在结束时回调），如果界面上仍显示
     * "下载中 22/22 片"，用户会以为卡住了（2026-10-17 用户反馈）。
     *
     * `DEFAULT ''` 是按 Room 的 schema 校验写的：实体这里用了 `defaultValue`，
     * 迁移里的建列语句必须带同样的默认值，否则打开库时会校验失败。
     */
    @ColumnInfo(name = "stage", defaultValue = "")
    val stage: String = "",
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

/**
 * 进行中的阶段（配合 [DownloadStatus.RUNNING] 用）。
 *
 * 顺序：拉取切片 → 合并 → 转封装 → 保存。后三段都没有"可数的进度"，
 * 所以界面与通知要显示**阶段名**，并给一个"还在动"的信号（已用秒数 / 不确定进度条）。
 * 纯常量与映射，可单测。
 */
object DownloadStage {
    /** 拉取（并解密）切片；此时 `doneSegments/totalSegments` 有效。 */
    const val FETCHING = "FETCHING"

    /** 把解密后的切片按序合并成单个 TS（有字节进度，但用户不需要看那么细）。 */
    const val MERGING = "MERGING"

    /** TS → MP4 转封装。**没有进度**，只能显示"进行中 + 已用时间"。 */
    const val REMUXING = "REMUXING"

    /** 写进公共目录（MediaStore）。 */
    const val SAVING = "SAVING"

    /** 阶段的中文说明；`null` / 未知阶段返回 null（调用方退回默认文案）。 */
    fun label(stage: String?): String? = when (stage) {
        FETCHING -> "下载中"
        MERGING -> "合并中"
        REMUXING -> "转换为 mp4"
        SAVING -> "保存中"
        else -> null
    }

    /** 这一段是不是"没有可数进度"的处理阶段（界面要换成不确定进度条 + 秒数）。 */
    /**
     * 处理阶段超过一定时间后的补充说明（纯函数，可单测）。
     *
     * 为什么要有：大视频 media3 会落到**重编码**，耗时以分钟计。界面只显示「转换为 mp4… · 已用 68 秒」
     * 用户会以为卡死；超过 30 秒后补一句"视频较大"，把预期说清楚。
     */
    fun slowHint(stage: String?, elapsedSeconds: Int): String? =
        if (stage == REMUXING && elapsedSeconds >= 30) "视频较大，可能需要几分钟" else null

    fun isProcessing(stage: String?): Boolean = stage == MERGING || stage == REMUXING || stage == SAVING
}
