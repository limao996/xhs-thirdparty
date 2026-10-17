package com.thirdparty.xhs.data

/**
 * 把下载任务按**作品**聚合成一行（纯逻辑，可单测）。
 *
 * 为什么要有它：一个作品可能同时有 1 个视频 + 几十张图片，一行一个文件会让列表被同一个作品的
 * 图片刷屏，用户反而找不到作品（2026-10-17 用户反馈）。所以列表按作品成行，
 * 行内再给出"视频 / 图片 各进行到哪一步"的汇总；需要看单张时再展开。
 */
data class DownloadGroup(
    val noteId: Long,
    val title: String,
    val cover: String,
    /** 作品里的视频任务（没有视频时是 null） */
    val video: DownloadTaskEntity?,
    /** 作品里的图片任务，按作品内序号排列 */
    val images: List<DownloadTaskEntity>,
    /** 全部条目：视频在前、图片按序号 */
    val items: List<DownloadTaskEntity>
) {
    val total: Int get() = items.size
    val doneCount: Int get() = items.count { it.status == DownloadStatus.DONE }
    val failedCount: Int get() = items.count { it.status == DownloadStatus.FAILED }
    val unfinishedCount: Int get() = items.count { it.status in DownloadStatus.unfinished }
    val pausedCount: Int get() = items.count { it.status == DownloadStatus.PAUSED }
    val queuedOrRunning: Int get() = items.count {
        it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED
    }

    /** 整组都结束了（完成或失败）——决定它落在哪个分组。 */
    val settled: Boolean get() = unfinishedCount == 0

    /** 整组结束**且有失败**：这些要单独放「失败」组（可重试），不能混进「已完成」。 */
    val failed: Boolean get() = settled && failedCount > 0

    /**
     * 组内暂停的原因（取暂停条目上非空的那个），例如仅 Wi-Fi 时的「等待 Wi-Fi（已开启仅 Wi-Fi 下载）」。
     *
     * 为什么要显示：库里一直记着原因，但界面只显示「已暂停」，用户不知道是被设置挡住了还是自己按的
     * （2026-10-17 审查发现）。
     */
    val pauseReason: String?
        get() = items.firstOrNull { it.status == DownloadStatus.PAUSED && !it.error.isNullOrBlank() }?.error

    /** 暂停原因的简称（取括号前那截）：组级汇总里再套一层括号会变成双括号，很难看。 */
    val pauseReasonShort: String?
        get() = pauseReason?.substringBefore('（')?.trim()?.takeIf { it.isNotEmpty() }

    /** 组内是否正在做"没有可数进度"的处理（合并 / 转封装 / 保存）。 */
    val processing: Boolean get() = items.any {
        it.status == DownloadStatus.RUNNING && DownloadStage.isProcessing(it.stage)
    }

    /** 已完成的图片张数（列表里显示「图片 12/43 张」用）。 */
    val doneImages: Int get() = images.count { it.status == DownloadStatus.DONE }

    val bytes: Long get() = items.sumOf { it.bytes }

    /**
     * 组级进度（0f–1f）；**null 表示不确定**（有处理阶段，或只有视频且切片总数未知）。
     *
     * 有图片时按"条目数"算（视频算 1 项、每张图算 1 项），只看视频时按切片算 —— 两种粒度
     * 分开，用户看到的都是"这一组还剩多少"。
     */
    fun progressFraction(): Float? {
        if (processing) return null
        if (images.isNotEmpty()) {
            if (total == 0) return null
            return doneCount.toFloat() / total.toFloat()
        }
        val v = video ?: return null
        if (v.totalSegments <= 0) return null
        return (v.doneSegments.toFloat() / v.totalSegments.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 自报作品里有哪几类内容，如「1 个视频、12 张图片」。
     *
     * 用量词（个 / 张）+ 顿号：与完成通知、选择对话框里的说法一致 ——
     * 原来写「视频 + 12 张图片」，既没量词又用加号，读起来像算式。
     */
    fun itemPart(): String = buildList {
        if (video != null) add("1 个视频")
        if (images.isNotEmpty()) add("${images.size} 张图片")
    }.joinToString("、").ifEmpty { "0 项" }

    /**
     * 组级状态说明（应用文案，书面中性）：
     * - 整组完成 → 「已完成 · 1 个视频、43 张图片」
     * - 整组结束但有失败 → 「视频 下载失败」/「失败 2 项 · 1 个视频、43 张图片」
     *   （这类会单独落在「失败」组，见 [failed]）
     * - 只有图片在跑 → 「图片 12/43 张」；视频与图片同时在跑则两段都写。
     */
    fun statusSummary(): String {
        if (settled) {
            if (failedCount == 0) return "已完成 · ${itemPart()}"
            // 失败项要尽量说清"哪一类失败了"：只有视频就直说视频，否则给个数
            val onlyVideoFailed = video != null && failedCount == 1 && video.status == DownloadStatus.FAILED
            if (onlyVideoFailed) return "视频 下载失败"
            if (images.isNotEmpty() && images.all { it.status == DownloadStatus.FAILED } && video == null) {
                return "${images.size} 张图片下载失败"
            }
            return "$failedCount 项失败 · ${itemPart()}"
        }
        if (pausedCount > 0 && queuedOrRunning == 0) {
            val why = pauseReasonShort?.let { "（$it）" }.orEmpty()
            return "已暂停 $pausedCount 项$why · $doneCount/$total 已完成"
        }
        val parts = mutableListOf<String>()
        video?.let { v ->
            parts += when (v.status) {
                DownloadStatus.DONE -> "视频 已完成"
                DownloadStatus.FAILED -> "视频 失败"
                DownloadStatus.PAUSED -> "视频 已暂停"
                DownloadStatus.QUEUED -> "视频 排队中"
                else -> if (DownloadStage.isProcessing(v.stage)) {
                    (DownloadStage.label(v.stage) ?: "处理中") + "…"
                } else if (v.totalSegments > 0) {
                    "视频 ${v.doneSegments}/${v.totalSegments} 片"
                } else {
                    "视频 下载中"
                }
            }
        }
        if (images.isNotEmpty()) {
            // 统一用计数式：同一行里的各段读起来一致（不要这里"已全部完成"、那里"1/3 张"）
            parts += "图片 $doneImages/${images.size} 张"
        }
        return parts.joinToString(" · ").ifEmpty { "排队中" }
    }

    /** 组内第一条"可打开"的条目：优先视频，其次第一张已完成的图片。 */
    fun firstOpenable(): DownloadTaskEntity? =
        video?.takeIf { it.status == DownloadStatus.DONE && it.savedUri != null }
            ?: images.firstOrNull { it.status == DownloadStatus.DONE && it.savedUri != null }
}

/**
 * 按作品聚合：顺序 = 输入里首次出现的顺序（DAO 已按"最新在前"排好）；
 * 同一作品的视频与图片合成一组，视频在前、图片按 `imageIndex`。
 *
 * 收敛成一个入口（原来叫 `groupDownloads` 顶层函数）：UI、通知、引擎都从这里拿作品维度。
 */
object DownloadGroups {
    fun of(tasks: List<DownloadTaskEntity>): List<DownloadGroup> {
    val order = LinkedHashMap<Long, MutableList<DownloadTaskEntity>>()
    tasks.forEach { task ->
        order.getOrPut(task.noteId) { mutableListOf() }.add(task)
    }
    return order.map { (noteId, items) ->
        val video = items.firstOrNull { it.kind == DownloadKind.VIDEO }
        val images = items.filter { it.kind == DownloadKind.IMAGE }.sortedBy { it.imageIndex }
        val ordered = buildList {
            video?.let { add(it) }
            addAll(images)
        }
        DownloadGroup(
            noteId = noteId,
            title = items.first().title,
            cover = items.first().cover,
            video = video,
            images = images,
            items = ordered
        )
    }
}
}
