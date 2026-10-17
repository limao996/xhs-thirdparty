package com.thirdparty.xhs.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TS → MP4：优先**转封装**（不重编码、画质无损），media3 判定必须重编码时才允许它转。
 *
 * 为什么需要：接口给的视频全是 HLS（`p.m3u8` + `localhost.tN.ts`，AES-128），没有 mp4 直链，
 * 而用户要的是能直接播放的单文件 mp4。
 *
 * **为什么用 `Transformer` 而不是自己驱动 `TsExtractor` + `Mp4Muxer`**：2026-10-17 先试了手写驱动，
 * extractor 会在"跳到尾部读时长"与"回到开头"之间来回请求 `RESULT_SEEK`，mp4 产出 0 字节（GOTCHAS H29）。
 *
 * `Transformer` 必须在有 Looper 的线程创建与启动，所以内部切到 Main。
 * `Transformer` 属于 media3 的 `@UnstableApi`：按项目约定用 **`androidx.annotation.OptIn`** 标注。
 *
 * ## 2026-10-17 后台报 muxer error 的修复（用户反馈"切后台到合并 mp4 阶段就 muxer error"）
 *
 * 实测（模拟器 API 34，163 片 / 115 MB 的视频）：这种体积/来源的视频 media3 **不会转封装**，
 * 而是落到 `c2.android.avc.encoder` **软件重编码**，耗时以分钟计；而当时应用**没有持有唤醒锁**
 * （`dumpsys power` 里 `mWakeLockSummary=0x0`），切后台/息屏后 CPU 被挂起 → 编码停滞或抛
 * muxer/codec 异常，用户看到的就是"muxer error"。三处修：
 *
 * 1. 下载期间由 [DownloadService] 持有 `PARTIAL_WAKE_LOCK`（不重编码的场景省电，重编码的场景保命）；
 * 2. **卡住检测**：每 5 秒读一次 `Transformer.getProgress()`，进度 90 秒没前进就判定卡住、主动失败
 *    （原来只能干等到 10 分钟超时，用户什么也看不到）；
 * 3. 失败原因**全部翻成中文**（media3 的英文原文只进日志），并按错误码给不同说法；
 *    上层还会自动重试一次（见 `DownloadEngine.executeVideo`）。
 */
@OptIn(UnstableApi::class)
object HlsRemuxer {

    private const val TAG = "XhsDownload"

    /** 整体超时：重编码大文件本来就慢，给足 10 分钟。 */
    private const val TIMEOUT_SECONDS = 10 * 60L

    /** 进度多久没前进算"卡住"（实测卡住时进度会完全不动）。 */
    private const val STALL_SECONDS = 90L

    /** 进度采样间隔。 */
    private const val POLL_SECONDS = 5L

    sealed interface Outcome {
        data class Ok(val file: File, val durationMs: Long, val bytes: Long) : Outcome

        /** [reason] 一定是给用户看的中文；英文细节只进日志。 */
        data class Failed(val reason: String) : Outcome
    }

    suspend fun remux(context: Context, ts: File, mp4: File): Outcome = withContext(Dispatchers.Main) {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val success = AtomicReference<Outcome.Ok?>(null)
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    success.set(Outcome.Ok(mp4, exportResult.durationMs, mp4.length()))
                    latch.countDown()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    // 英文原文只进日志：界面上永远是中文（文案约定 27）
                    Log.w(
                        TAG,
                        "转 mp4 失败：code=${exportException.errorCode} msg=${exportException.message}",
                        exportException
                    )
                    failure.set(chineseReason(exportException))
                    latch.countDown()
                }
            })
            .build()

        // 上一次失败/取消留下的半成品必须清掉，否则 muxer 会接着写坏文件
        if (mp4.exists() && !mp4.delete()) Log.w(TAG, "清不掉上次的半成品：${mp4.name}")

        val startedAt = System.currentTimeMillis()
        // 不管怎么退出（正常结束 / 卡住 / 超时 / **用户手动停止把协程取消**）都要取消 Transformer：
        // 少了这一步，转封装期间点删除/暂停会留下没释放的 codec 与线程（2026-10-18 审查发现）。
        try {
            transformer.start(
                EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(ts))).build(),
                mp4.absolutePath
            )

        // 轮询：等待结束；同时做**卡住检测**（进度 90 秒没前进就主动失败，别让用户干等 10 分钟）
        // 与整体超时。返回 null = 正常结束，否则是"该怎么跟用户说"的中文原因。
        val stopReason: String? = withContext(Dispatchers.IO) {
            val holder = ProgressHolder()
            var lastPercent = -1
            var lastChangeAt = System.currentTimeMillis()
            while (true) {
                if (latch.await(POLL_SECONDS, TimeUnit.SECONDS)) return@withContext null
                if (System.currentTimeMillis() - startedAt > TIMEOUT_SECONDS * 1000) {
                    return@withContext "转换超时，请重试"
                }
                val state = withContext(Dispatchers.Main) { transformer.getProgress(holder) }
                val percent = if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
                val now = System.currentTimeMillis()
                when {
                    percent > lastPercent -> {
                        lastPercent = percent
                        lastChangeAt = now
                    }

                    now - lastChangeAt > STALL_SECONDS * 1000 -> {
                        Log.w(
                            TAG,
                            "转 mp4 卡住：${STALL_SECONDS}s 没有进展（进度=$percent%），主动失败等上层重试"
                        )
                        return@withContext "转换卡住，请重试"
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

            if (stopReason != null) {
                runCatching { transformer.cancel() }
                mp4.delete()
                return@withContext Outcome.Failed(stopReason)
            }

            success.get()?.let { ok ->
                // 自检：0 字节或时长为 0 一律当失败，绝不把空文件当成功交给用户
                if (ok.bytes == 0L || ok.durationMs <= 0L) {
                    mp4.delete()
                    return@withContext Outcome.Failed("转换结果不完整，请重试")
                }
                val ratio = if (ts.length() > 0) mp4.length() * 100 / ts.length() else 100
                val kind = if (ratio < 95) "占比偏低→media3 走了重编码" else "转封装"
                Log.i(
                    TAG,
                    "转 mp4 完成：${mp4.name}（${mp4.length()} 字节，源 ${ts.length()} 字节，占比 $ratio%，" +
                        "耗时 ${System.currentTimeMillis() - startedAt}ms，$kind）"
                )
                return@withContext ok
            }
            return@withContext Outcome.Failed(failure.get() ?: "转换失败，请重试")
        } finally {
            // 手动停止（协程取消）时也走到这里：取消正在跑的 Transformer，并清掉半成品 mp4
            runCatching { transformer.cancel() }
            if (success.get() == null && mp4.exists() && mp4.length() == 0L) {
                runCatching { mp4.delete() }
            }
        }
    }

    /**
     * 把 media3 的错误码翻成用户看得懂的中文。
     *
     * 用户报的"muxer error"就是这里第一个分支（`ERROR_CODE_MUXING_FAILED`）。
     */
    private fun chineseReason(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_MUXING_FAILED -> "封装 mp4 失败，请重试"
        ExportException.ERROR_CODE_ENCODING_FAILED -> "视频编码失败，请重试"
        ExportException.ERROR_CODE_DECODING_FAILED -> "视频解码失败，请重试"
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到临时文件，请重新下载"
        ExportException.ERROR_CODE_IO_NO_PERMISSION -> "没有读写权限，请检查存储权限"
        ExportException.ERROR_CODE_IO_UNSPECIFIED -> "读写文件失败，请检查存储空间"
        else -> "转换失败，请重试"
    }
}
