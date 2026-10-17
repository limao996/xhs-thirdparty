package com.thirdparty.xhs.data

/**
 * 下载去重：**同一个作品的同一条内容，库里同时只保留一条有效任务**。
 *
 * 为什么要有它：用户在详情页反复点「下载」是常见操作（以为没点上、或者想重下），
 * 不去重就会给同一条内容建出多个任务 —— 重复下载、重复占用带宽，而且落到公共目录时会因为
 * 同名被系统自动改名，出现 `xxx(2).mp4`、`xxx(3).mp4` 这种堆叠（2026-10-17 用户提醒）。
 *
 * 判定键用「作品 + 类型 + 序号」：视频一部作品只有一个（序号恒为 0），图片按作品内序号区分。
 * **`FAILED` 的任务不算重复**：失败重试是正当需求，重新入队时会先清掉旧的失败行。
 */
object DownloadDedupe {

    /** 一条内容的唯一键（同一作品内）。 */
    fun itemKey(noteId: Long, kind: String, imageIndex: Int): String =
        "$noteId|$kind|$imageIndex"

    fun itemKey(task: DownloadTaskEntity): String =
        itemKey(task.noteId, task.kind, task.imageIndex)

    /** 正在跑或已经完成的任务：这些内容不允许重复入队。 */
    fun isActiveOrDone(task: DownloadTaskEntity): Boolean =
        task.status != DownloadStatus.FAILED

    /** 库里现有的"已占用键"（用于对话框标记「已下载 / 下载中」，以及跳过重复项）。 */
    fun occupiedKeys(existing: List<DownloadTaskEntity>): Set<String> =
        existing.filter { isActiveOrDone(it) }.map { itemKey(it) }.toSet()

    /** 入队计划：真正要新建的、被跳过的、以及要顺手清掉的旧失败行。 */
    data class EnqueuePlan(
        val added: List<DownloadTaskEntity>,
        val skipped: List<DownloadTaskEntity>,
        /** 同键的旧 `FAILED` 行（先删再建，避免同一内容堆两条） */
        val superseded: List<DownloadTaskEntity>
    ) {
        val skippedCount: Int get() = skipped.size
        val hasAnything: Boolean get() = added.isNotEmpty()
    }

    /**
     * 规划一次入队：与库里现有任务比对。
     *
     * @param existing 该作品（或全部）现有任务；调用方给 `noteId` 相同的即可
     */
    fun planEnqueue(
        incoming: List<DownloadTaskEntity>,
        existing: List<DownloadTaskEntity>
    ): EnqueuePlan {
        val occupied = occupiedKeys(existing)
        val added = mutableListOf<DownloadTaskEntity>()
        val skipped = mutableListOf<DownloadTaskEntity>()
        val superseded = mutableListOf<DownloadTaskEntity>()
        val seenInBatch = mutableSetOf<String>()

        incoming.forEach { task ->
            val key = itemKey(task)
            when {
                // 同一批里重复选择（理论上 UI 不会给，但兜一层）
                !seenInBatch.add(key) -> skipped += task

                key in occupied -> skipped += task

                else -> {
                    added += task
                    // 同键的失败行先删掉，避免"重试"之后同一内容两条记录
                    superseded += existing.filter { itemKey(it) == key && !isActiveOrDone(it) }
                }
            }
        }
        return EnqueuePlan(added, skipped, superseded)
    }

    /** 对话框里给每条内容显示的标记（已下载 / 下载中… / 失败可重试 → null 表示可下载）。 */
    fun badgeFor(task: DownloadTaskEntity?): String? = when (task?.status) {
        null -> null
        DownloadStatus.DONE -> "已下载"
        DownloadStatus.FAILED -> null
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.RUNNING, DownloadStatus.QUEUED -> "下载中"
        else -> null
    }
}
