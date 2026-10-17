package com.thirdparty.xhs.download

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TS → MP4 的**转封装**（pure remux，不重编码、画质无损）。
 *
 * 为什么需要：接口给的视频全是 HLS（`p.m3u8` + `localhost.tN.ts`，AES-128），没有 mp4 直链，
 * 而用户要的是能直接播放的单文件 mp4。
 *
 * **为什么用 `Transformer` 而不是自己驱动 `TsExtractor` + `Mp4Muxer`**：2026-10-17 先试了手写驱动，
 * extractor 会在"跳到尾部读时长"与"回到开头"之间来回请求 `RESULT_SEEK`，mp4 产出 0 字节
 * （见 GOTCHAS H29）；换成 media3 公开的 `Transformer` 后一次通过：8,370,888 B 的 TS →
 * 8,358,452 B 的 mp4，时长 60.08 s（与源切片吻合），耗时 3.75 s（体积几乎不变 = 确实只换了容器）。
 *
 * `Transformer` 必须在有 Looper 的线程创建与启动，所以内部切到 Main。
 *
 * `Transformer` 属于 media3 的 `@UnstableApi`：按项目既有约定用 **`androidx.annotation.OptIn`**
 * 标注（Kotlin 的 `@OptIn` 对 Java 注解无效，见 `VideoPlayer` 的同类写法）。
 */
@OptIn(UnstableApi::class)
object HlsRemuxer {

    private const val TIMEOUT_SECONDS = 10 * 60L

    sealed interface Outcome {
        data class Ok(val file: File, val durationMs: Long, val bytes: Long) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun remux(context: Context, ts: File, mp4: File): Outcome = withContext(Dispatchers.Main) {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Outcome>()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    result.set(
                        Outcome.Ok(mp4, exportResult.durationMs, mp4.length())
                    )
                    latch.countDown()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: androidx.media3.transformer.ExportException
                ) {
                    result.set(Outcome.Failed(exportException.message ?: "转换失败，请重试"))
                    latch.countDown()
                }
            })
            .build()

        transformer.start(
            EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(ts))).build(),
            mp4.absolutePath
        )
        // 转换在别的线程跑，这里等结果（最长 3 分钟）
        val finished = withContext(Dispatchers.IO) { latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        if (!finished) {
            transformer.cancel()
            Outcome.Failed("转换超时，请重试")
        } else {
            val outcome = result.get() ?: return@withContext Outcome.Failed("转换失败，请重试")
            // 自检：0 字节或时长为 0 一律当失败，绝不把空文件当成功交给用户
            if (outcome is Outcome.Ok && (outcome.bytes == 0L || outcome.durationMs <= 0L)) {
                mp4.delete()
                return@withContext Outcome.Failed("转换结果不完整，请重试")
            }
            outcome
        }
    }
}
