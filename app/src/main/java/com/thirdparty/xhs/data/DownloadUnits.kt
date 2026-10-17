package com.thirdparty.xhs.data

/**
 * 下载的**执行单元**：调度器按单元占用并发名额。
 *
 * 为什么要有它：用户 2026-10-17 要求"图集下载的并发数应该按视频分片的标准来，一张图片不能当成一个任务"。
 * 之前一张图片就是一条任务、一个名额 —— 下 20 张图的作品时，若"同时下载任务数"设成 2，
 * 就只剩 2 张图在跑，而且会把其它作品的视频挤在后面；这和"一个视频内部能并行拉 8 个切片"的体验完全不一致。
 *
 * 现在的口径：
 * - **一个视频**（[DownloadKind.VIDEO]）= 一个单元；
 * - **一个作品的全部图片**（[DownloadKind.IMAGE]，按 `noteId` 归并）= 一个单元，
 *   单元内部再按"并发数"设置并行拉图（与视频的分片并发同一档）。
 *
 * 任务行仍然是"一张图一行"（[DownloadTaskEntity]）：每张图的进度、失败原因、断点、去重都需要它。
 * 单元只是**调度**层面的分组，不改变状态真相（硬约束 30）。
 */
data class DownloadUnit(
    /** 单元键：`v<taskId>`（视频）或 `i<noteId>`（图集）。用于 `jobs` 与"是否已在跑"的判断。 */
    val key: String,
    val noteId: Long,
    val title: String,
    /** 视频单元里唯一的那条任务；图集单元为 null */
    val video: DownloadTaskEntity?,
    /** 图集单元里的图片任务（按作品内序号排好）；视频单元为空 */
    val images: List<DownloadTaskEntity>
) {
    val tasks: List<DownloadTaskEntity> get() = listOfNotNull(video) + images

    val isImageSet: Boolean get() = video == null && images.isNotEmpty()

    companion object {
        fun keyOf(task: DownloadTaskEntity): String =
            if (task.kind == DownloadKind.VIDEO) "v${task.id}" else "i${task.noteId}"

        /**
         * 把一批（通常是 `QUEUED` 的）任务归成执行单元。
         *
         * 顺序：按传入顺序**首次出现**的位置排 —— 调用方给的是 `createdAt` 序，所以先入队的作品先跑。
         * 同一作品的图片会合并进同一个单元（视频若也在里面，则各自成单元：视频是独立一步）。
         */
        fun of(tasks: List<DownloadTaskEntity>): List<DownloadUnit> {
            val order = LinkedHashMap<String, MutableList<DownloadTaskEntity>>()
            tasks.forEach { task ->
                order.getOrPut(keyOf(task)) { mutableListOf() }.add(task)
            }
            return order.map { (key, items) ->
                val video = items.firstOrNull { it.kind == DownloadKind.VIDEO }
                val images = items.filter { it.kind == DownloadKind.IMAGE }.sortedBy { it.imageIndex }
                DownloadUnit(
                    key = key,
                    noteId = items.first().noteId,
                    title = items.first().title,
                    video = video,
                    images = images
                )
            }
        }
    }
}
