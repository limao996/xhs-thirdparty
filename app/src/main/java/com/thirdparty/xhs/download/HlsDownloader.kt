package com.thirdparty.xhs.download

import android.content.Context
import android.util.Log
import com.thirdparty.xhs.data.DownloadStage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * HLS 视频下载：播放列表 → 切片**并行**下载（默认 4 路）→ AES-128 解密 → 顺序合并成单个 TS
 * → 交给 [HlsRemuxer] 转封装成 mp4。
 *
 * 设计要点：
 * - 切片各自落 `workDir/seg-NNNN.ts`，合并时按序流式复制（**不把整个视频读进内存**），
 *   合并完删掉切片文件；
 * - 并发用 `Semaphore`（`parallel` 可配，来自设置）；
 * - 全程可取消（协程取消会中断下载与合并，临时目录由调用方清理）；
 * - 解密按 `#EXT-X-KEY` 的 IV；没有 IV 时按 RFC 8216 用媒体序号（见 [HlsPlaylist.ivForSequence]）。
 */
class HlsDownloader(
    private val client: OkHttpClient,
    private val parallel: Int = DEFAULT_PARALLEL
) {

    companion object {
        const val DEFAULT_PARALLEL = 4
        private const val TAG = "XhsDownload"
    }

    /** 进度：已完成切片数 / 总数 / 已落盘字节。 */
    data class Progress(val done: Int, val total: Int, val bytes: Long) {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
    }

    sealed interface Outcome {
        /** [bytes] 是最终 mp4 大小，[tsBytes] 是中间 TS 大小（用于日志/诊断）。 */
        data class Ok(val mp4: File, val bytes: Long, val tsBytes: Long, val durationMs: Long) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 完整链路：下载 + 解密 + 合并 + 转封装。
     *
     * @param workDir 临时目录（调用方给 `cacheDir/download/<taskId>`，结束时应由调用方清掉）
     * @param limit 只下前 N 个切片（**仅供验证/探针使用**；正式路径不传）
     * @param onStage 阶段变化回调（`DownloadStage.FETCHING` / `MERGING` / `REMUXING`）——
     *   合并与转封装没有可数进度，界面必须靠它告诉用户"还在干活，不是卡住了"。
     */
    suspend fun remuxToMp4(
        context: Context,
        playlistUrl: String,
        workDir: File,
        limit: Int? = null,
        onProgress: (Progress) -> Unit = {},
        onStage: (String) -> Unit = {}
    ): Outcome {
        val merged = downloadAndMerge(playlistUrl, workDir, limit, onProgress, onStage)
            ?: return Outcome.Failed("下载失败")
        if (merged is Merged.Failed) return Outcome.Failed(merged.reason)
        val ts = (merged as Merged.Ok).file
        val mp4 = File(workDir, "video.mp4")
        onStage(DownloadStage.REMUXING)
        return when (val out = HlsRemuxer.remux(context, ts, mp4)) {
            is HlsRemuxer.Outcome.Failed -> Outcome.Failed(out.reason)
            is HlsRemuxer.Outcome.Ok -> {
                ts.delete()
                Outcome.Ok(out.file, out.bytes, merged.bytes, out.durationMs)
            }
        }
    }

    sealed interface Merged {
        data class Ok(val file: File, val bytes: Long, val segments: Int) : Merged
        data class Failed(val reason: String) : Merged
    }

    /** 下载 + 解密 + 合并成单个 TS（不转封装）。 */
    suspend fun downloadAndMerge(
        playlistUrl: String,
        workDir: File,
        limit: Int? = null,
        onProgress: (Progress) -> Unit = {},
        onStage: (String) -> Unit = {}
    ): Merged = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        onStage(DownloadStage.FETCHING)

        // 若上次已经把切片合并完整（`merged.ts` 是合并写完才改名的，见下），直接复用它：
        // 暂停/被杀如果落在"合并/转封装"这几段，重来时不必把整个视频再下一遍。
        val mergedFile = File(workDir, "merged.ts")
        if (mergedFile.length() > 0 && File(workDir, "merged.ts.part").length() == 0L) {
            val playlist = runCatching { HlsPlaylist.parse(fetchText(playlistUrl), playlistUrl) }
                .getOrNull()
            val segCount = playlist?.segments?.size ?: 0
            Log.i(TAG, "复用已合并的 merged.ts（${mergedFile.length()} 字节），跳过切片下载与合并")
            onProgress(Progress(segCount, segCount, mergedFile.length()))
            return@withContext Merged.Ok(mergedFile, mergedFile.length(), segCount)
        }
        // 上次合并没写完的残留，清掉重来
        File(workDir, "merged.ts.part").takeIf { it.exists() }?.delete()

        val playlist = try {
            HlsPlaylist.parse(fetchText(playlistUrl), playlistUrl)
        } catch (e: Exception) {
            return@withContext Merged.Failed(humanError(e, "播放列表解析失败"))
        }
        val all = playlist.segments
        val wanted = if (limit != null) all.take(limit) else all
        val key = playlist.keyUri?.let {
            runCatching { fetchBytes(it) }.getOrElse { e ->
                return@withContext Merged.Failed(humanError(e, "获取解密密钥失败"))
            }
        }
        Log.i(TAG, "切片 ${wanted.size}/${all.size}，加密=${playlist.encrypted}，并发=$parallel")

        // 切片级断点：暂停/被系统杀掉后重来时，已经落在 workDir 里的切片直接复用。
        // （暂停走的是协程取消，`executeVideo` 里的清理在取消时不会执行，所以切片文件还在。）
        val already = wanted.indices.filter { File(segFile(workDir, it)).length() > 0 }
        val done = AtomicInteger(already.size)
        val bytes = AtomicLong(already.sumOf { File(segFile(workDir, it)).length() })
        if (already.isNotEmpty()) {
            Log.i(TAG, "跳过已下载的 ${already.size} 片（断点续传）")
            onProgress(Progress(done.get(), wanted.size, bytes.get()))
        }
        val semaphore = Semaphore(parallel.coerceAtLeast(1))
        var failure: String? = null

        coroutineScope {
            val jobs = wanted.mapIndexed { index, segment ->
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (failure != null) return@withPermit
                        val target = File(segFile(workDir, index))
                        if (target.length() > 0) return@withPermit
                        try {
                            val enc = fetchBytes(segment.uri)
                            val plain = if (key != null) {
                                val iv = playlist.keyIv ?: HlsPlaylist.ivForSequence(segment.sequence)
                                decrypt(key, iv, enc)
                            } else {
                                enc
                            }
                            target.writeBytes(plain)
                            bytes.addAndGet(plain.size.toLong())
                            onProgress(Progress(done.incrementAndGet(), wanted.size, bytes.get()))
                        } catch (e: Exception) {
                            failure = humanError(e, "切片 ${index + 1} 下载失败")
                        }
                    }
                }
            }
            jobs.joinAll()
        }
        failure?.let { return@withContext Merged.Failed(it) }

        // 合并阶段：这一步在大文件上要几秒到几十秒（顺序复制 + 删切片），
        // 期间给界面报阶段与字节进度，别让用户以为卡在 22/22。
        //
        // 写临时名再改名：这样"`merged.ts` 存在"就一定意味着合并**完整**了 ——
        // 暂停/被杀如果发生在合并中途，下次重来可以直接续（见上面的 already 逻辑与 remuxToMp4）。
        onStage(DownloadStage.MERGING)
        val out = File(workDir, "merged.ts")
        val partial = File(workDir, "merged.ts.part")
        val mergedBytes = AtomicLong(0)
        FileOutputStream(partial).use { sink ->
            wanted.indices.forEach { index ->
                val f = File(segFile(workDir, index))
                if (!f.exists()) return@withContext Merged.Failed("下载内容不完整，请重试")
                f.inputStream().use { it.copyTo(sink, 256 * 1024) }
                mergedBytes.addAndGet(f.length())
                onProgress(Progress(wanted.size, wanted.size, mergedBytes.get()))
                f.delete()
            }
        }
        if (!partial.renameTo(out)) return@withContext Merged.Failed("下载内容合并失败，请重试")
        Log.i(TAG, "合并完成：${out.length()} 字节（$bytes）")
        Merged.Ok(out, out.length(), wanted.size)
    }

    private fun segFile(workDir: File, index: Int) = "$workDir/seg-%04d.ts".format(index)

    /**
     * 把底层异常收成用户能看懂的一句话（文案约定 27：写清"发生了什么 + 怎么办"）。
     * 界面上直接显示这句话，所以**不要**把 `Failed to connect to /127.0.0.1:9` 这种原文透出去。
     */
    private fun humanError(e: Throwable, action: String): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "无法连接服务器，请检查网络"

        is java.net.SocketTimeoutException -> "连接超时，请重试"

        is IllegalStateException -> e.message?.takeIf { it.isNotBlank() } ?: "$action，请重试"
        else -> "$action，请重试"
    }

    private fun fetchText(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body!!.string()
        }

    private fun fetchBytes(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body!!.bytes()
        }

    private fun decrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }
}
