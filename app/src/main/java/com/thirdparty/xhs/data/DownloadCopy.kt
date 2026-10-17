package com.thirdparty.xhs.data

/**
 * 通知文案的组装（纯函数，带单测）。
 *
 * 为什么单独抽出来：用户 2026-10-17 反馈"下载状态通知的文案太乱了" —— 原来的通知把
 * **图片张数**和**作品数**混在一起数（「已完成 3 / 5 项（同时下载 1 个）」），
 * 接入图集并发之后更难读。现在的口径统一成：
 *
 * - 通知**标题**说"这是什么状态、在做哪件作品"；
 * - 通知**正文**说"这件作品进行到哪一步"（与下载管理页同一套作品口径）；
 * - 通知**副标题**说"一共几件作品、完成了几件"；
 * - 完成通知按**类型**数：「完成 1 个视频、20 张图片」。
 */
object DownloadCopy {

    /** 进度通知的标题。 */
    fun runningTitle(currentTitle: String, runningWorks: Int): String = when {
        currentTitle.isNotBlank() -> "下载中 · $currentTitle"
        runningWorks > 1 -> "下载中 · $runningWorks 件作品"
        else -> "下载中"
    }

    /** 进度通知的正文：优先显示当前作品的进度行（没有就跑不出进度，用"正在准备"兜底）。 */
    fun runningText(currentDetail: String, stageLabel: String?, slowHint: String?): String {
        val detail = currentDetail.ifBlank { "正在准备…" }
        val extras = buildList {
            stageLabel?.let { add(it) }
            slowHint?.let { add(it) }
        }
        return if (extras.isEmpty()) detail else "$detail · ${extras.joinToString(" · ")}"
    }

    /** 进度通知的副标题：作品维度的总数。 */
    fun worksSubText(done: Int, total: Int): String? =
        if (total <= 0) null else "已完成 $done / $total 件作品"

    /**
     * 完成通知的正文：按**类型**数，而不是含糊的"N 项"。
     *
     * @param okVideo / okImage：这一轮完成的视频数、图片张数
     * @param failedVideo / failedImage：失败的数量
     */
    fun finishedText(
        okVideo: Int,
        okImage: Int,
        failedVideo: Int,
        failedImage: Int
    ): String {
        val ok = buildList {
            if (okVideo > 0) add("$okVideo 个视频")
            if (okImage > 0) add("$okImage 张图片")
        }
        val failed = buildList {
            if (failedVideo > 0) add("$failedVideo 个视频")
            if (failedImage > 0) add("$failedImage 张图片")
        }.joinToString("、")
        return when {
            ok.isEmpty() && failed.isEmpty() -> "没有需要下载的内容"
            ok.isEmpty() -> "$failed 下载失败，点开可重试"
            failed.isEmpty() -> "完成 ${ok.joinToString("、")}，点开查看"
            else -> "完成 ${ok.joinToString("、")}；$failed 失败"
        }
    }
}
