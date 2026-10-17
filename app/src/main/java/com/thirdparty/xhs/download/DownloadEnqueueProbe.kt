package com.thirdparty.xhs.download

import android.content.Context
import android.util.Log
import com.thirdparty.xhs.data.DownloadKind
import com.thirdparty.xhs.data.DownloadStatus
import com.thirdparty.xhs.data.DownloadTaskEntity
import com.thirdparty.xhs.data.XhsDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * **入队探针（DEBUG 专用，T7 做出正式 UI 后删除）**：走完整的正式路径
 * 「建任务行 → 启动前台服务 → 引擎并发下载 → MediaStore 落盘」，用于在没有 UI 的情况下验证
 * T5（前台服务/通知/后台续传）与 T6（任务列表状态）。
 *
 * 触发：
 * ```
 * adb shell "run-as com.thirdparty.xhs.debug sh -c 'echo 1 > files/run-download-enqueue'"
 * adb shell am force-stop com.thirdparty.xhs.debug
 * adb shell am start -n com.thirdparty.xhs.debug/com.thirdparty.xhs.MainActivity
 * adb logcat -s XhsDownload
 * ```
 */
object DownloadEnqueueProbe {

    private const val TAG = "XhsProbe"
    private const val MARKER = "run-download-enqueue"
    private const val DUMP_MARKER = "run-download-dump"

    /** 一条真实视频（HLS + AES-128；22 片 ≈ 100 MB，够用来观察暂停/继续）。 */
    private const val SHORT_VIDEO =
        "https://xn--z6uom.ienglish.store/static/m3u8/20201209/afqbZZ/p.m3u8"


    fun runIfRequested(context: Context) {
        if (!com.thirdparty.xhs.BuildConfig.DEBUG) return

        // 只打印任务行（运行中直接拉 sqlite 文件会损坏，改成让应用自己读）
        val dumpMarker = File(context.filesDir, DUMP_MARKER)
        if (dumpMarker.exists()) {
            dumpMarker.delete()
            CoroutineScope(Dispatchers.IO).launch { dump(context, "手动 DUMP") }
            return
        }

        val marker = File(context.filesDir, MARKER)
        if (!marker.exists()) return
        marker.delete()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { enqueue(context) }
                .onFailure { Log.e(TAG, "入队探针失败：${it.message}", it) }
        }
    }

    private suspend fun enqueue(context: Context) {
        val dao = XhsDatabase.get(context).downloadTaskDao()
        dao.clearAll()
        val now = System.currentTimeMillis()
        val video = DownloadTaskEntity(
            noteId = 4242L,
            title = "服务验证视频",
            kind = DownloadKind.VIDEO,
            sourceUrl = SHORT_VIDEO,
            status = DownloadStatus.QUEUED,
            totalSegments = 0,
            createdAt = now,
            updatedAt = now
        )
        // 摘要层不带图片列表（实测发现页 39 条全是视频，images 全空），所以逐条走详情接口找带图的
        val realImages = runCatching {
            val repo = com.thirdparty.xhs.App.repo
            val categoryId = repo.categories().firstOrNull()?.id ?: return@runCatching emptyList()
            var found: List<String> = emptyList()
            var page = 1
            while (page <= 2 && found.isEmpty()) {
                val notes = repo.discoverPage(categoryId = categoryId, groupId = 0, page = page)
                Log.i(TAG, "第 $page 页 ${notes.size} 条，类型=${notes.map { it.noteType }.distinct()}")
                for (note in notes) {
                    val detail = repo.fetchDetail(note.noteId) ?: continue
                    if (detail.images.size >= 2) {
                        found = detail.images.map { it.url }.take(2)
                        Log.i(TAG, "详情命中带图作品 ${note.noteId}：${detail.images.size} 张")
                        break
                    }
                }
                page++
            }
            found
        }.getOrDefault(emptyList())
        Log.i(TAG, "从接口拿到的真实图片地址 ${realImages.size} 个")
        val images = realImages.mapIndexed { index, url ->
            DownloadTaskEntity(
                noteId = 4242L,
                title = "服务验证图片",
                kind = DownloadKind.IMAGE,
                sourceUrl = url,
                imageIndex = index,
                status = DownloadStatus.QUEUED,
                totalSegments = 1,
                createdAt = now + index + 1,
                updatedAt = now + index + 1
            )
        }
        DownloadEngine.init(context)
        DownloadEngine.enqueue(listOf(video) + images)
        Log.i(TAG, "任务入队完成：1 个视频 + ${images.size} 张图片")

        // ---- 脚本化验证：暂停 → 继续 → 等完成，每一步都把任务行打出来 ----
        kotlinx.coroutines.delay(8_000)
        dump(context, "暂停前")
        DownloadEngine.pauseAll()
        kotlinx.coroutines.delay(2_500)
        dump(context, "暂停后（应见 PAUSED）")
        DownloadEngine.resumeAll()
        kotlinx.coroutines.delay(2_500)
        dump(context, "继续后（应见 RUNNING/QUEUED）")

        // 等视频完成（22 片 ≈ 100MB，最长等 4 分钟）
        var waited = 0
        while (waited < 240_000) {
            kotlinx.coroutines.delay(5_000)
            waited += 5_000
            val rows = XhsDatabase.get(context).downloadTaskDao().all()
            if (rows.none { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }) break
        }
        dump(context, "结束后")
        Log.i(TAG, "已入队 ${1 + images.size} 个任务（视频 1 + 图片 ${images.size}）")
    }

    /** 把任务表逐行打进日志（验证用：不依赖 adb 拉 sqlite 文件）。 */
    private suspend fun dump(context: Context, stage: String) {
        Log.i(TAG, "---- $stage ----")
        val dao = XhsDatabase.get(context).downloadTaskDao()
        val rows = dao.all()
        Log.i(TAG, "任务表共 ${rows.size} 行")
        rows.forEach { t ->
            Log.i(
                TAG,
                "  行 id=${t.id} kind=${t.kind} status=${t.status} " +
                    "切片=${t.doneSegments}/${t.totalSegments} bytes=${t.bytes} " +
                    "path=${t.savedPath} err=${t.error}"
            )
        }
    }
}
