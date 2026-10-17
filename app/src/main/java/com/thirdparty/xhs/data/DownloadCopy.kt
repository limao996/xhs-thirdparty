package com.thirdparty.xhs.data

/**
 * 通知文案的组装（纯函数，带单测）。
 *
 * 用户 2026-10-17 两次要求优化通知文案，这一版的口径是**极简、不重复**：
 *
 * | 场景 | 标题 | 正文 | 副标题 |
 * | --- | --- | --- | --- |
 * | 下载中 | 当前作品的标题 | `图片 26/43 张` / `视频 5/14 片` / `转换为 mp4 · 已用 32 秒` | `下载中 · 已完成 2/5 件作品` |
 * | 已暂停 | `下载已暂停` | `已完成 2/5 件作品` | 暂停原因（如 `等待 Wi-Fi`） |
 * | 已结束 | `下载已结束` | `已完成 5/5 件作品` | — |
 * | 完成通知 | `下载完成` / `下载失败` | `完成 1 个视频、20 张图片` | — |
 *
 * 三条硬规矩：
 * 1. **同一句话里不出现两个相同的词** —— 原来正文既贴作品汇总（已经含「转换为 mp4…」）
 *    又追加阶段标签，结果出现「转换为 mp4 · 转换为 mp4」（用户报的 bug）；
 * 2. 进度数字只出现在正文（标题不带数字，副标题不说"进度"）；
 * 3. 处理阶段才补「已用 N 秒」与"视频较大"的预期。
 */
object DownloadCopy {

    /** 下载中：标题就是当前作品。 */
    fun runningTitle(currentTitle: String): String = currentTitle.ifBlank { "下载中" }

    /**
     * 下载中：正文 = 作品汇总行（已含阶段与片数/张数），处理阶段补"已用 N 秒"与慢速提示。
     *
     * **不要再传阶段标签进来**：汇总行里有（`转换为 mp4…`），重复贴就是两份。
     */
    fun runningText(detail: String, stageSeconds: Int, slowHint: String?): String {
        val base = detail.ifBlank { "正在准备…" }
        val extras = buildList {
            if (stageSeconds > 0) add("已用 ${stageSeconds} 秒")
            slowHint?.let { add(it) }
        }
        return if (extras.isEmpty()) base else "$base · ${extras.joinToString(" · ")}"
    }

    /** 副标题：状态 + 作品维度计数。 */
    fun worksSubText(state: String, done: Int, total: Int): String? =
        if (total <= 0) null else "$state · 已完成 $done / $total 件作品"

    /** 暂停时的正文（标题已经是"下载已暂停"，这里只说还剩多少）。 */
    fun pausedText(done: Int, total: Int): String =
        if (total <= 0) "点开可继续" else "已完成 $done / $total 件作品 · 点开可继续"

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
            ok.isEmpty() -> "${failed}下载失败，点开可重试"
            failed.isEmpty() -> "完成 ${ok.joinToString("、")}，点开查看"
            else -> "完成 ${ok.joinToString("、")}；${failed}失败"
        }
    }
}
