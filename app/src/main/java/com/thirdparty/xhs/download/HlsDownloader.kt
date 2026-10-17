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
    private val parallel: Int = DEFAULT_PARALLEL,
    /**
     * 每拉一个分片前的账号检查（与观看同一条 VIP 链路）。
     *
     * 长时间下载时 VIP 窗口可能中途用完：观看侧靠"请求前过闸门"续上，下载侧原来完全没有这一步
     * （用户 2026-10-17：下载也要像观看一样检查 VIP 状态）。闸门内部是缓存判断，
     * VIP 还有效时开销可忽略。
     */
    private val ensureAccount: (suspend () -> Unit)? = null
) {

    companion object {
        const val DEFAULT_PARALLEL = 4

        /** 分片重试的退避（1s / 3s / 8s）：这个 CDN 的分片常"传到一半断"，给足喘息时间。 */
        private val RETRY_BACKOFF_MS = longArrayOf(1000L, 3000L, 8000L)        /** 抓取阶段「一片都没完成」多久算搁死（主动失败，交给上层/用户重试）。 */
        private const val STALL_MS = 90_000L
        private const val TAG = "XhsDownload"
    }

    /** 进度：已完成切片数 / 总数 / 已落盘字节。 */
    data class Progress(val done: Int, val total: Int, val bytes: Long) {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).coerceIn(0, 100)
    }

    sealed interface Outcome {
        /** [bytes] 是最终 mp4 大小，[tsBytes] 是中间 TS 大小（用于日志/诊断）。 */
        data class Ok(val mp4: File, val bytes: Long, val tsBytes: Long, val durationMs: Long) : Outcome
        data class Failed(val reason: String, val retryable: Boolean = false) : Outcome
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
        if (merged is Merged.Failed) return Outcome.Failed(merged.reason, merged.retryable)
        val ts = (merged as Merged.Ok).file
        val mp4 = File(workDir, "video.mp4")
        onStage(DownloadStage.REMUXING)
        // 失败自动重试一次：切后台/息屏时 media3 的重编码可能被系统打断（报 muxer/codec 错），
        // 这种失败通常是瞬时的，重试一次基本能过；合并好的 TS 还在，重试只重跑转封装本身。
        var last = HlsRemuxer.remux(context, ts, mp4)
        if (last is HlsRemuxer.Outcome.Failed) {
            Log.w(TAG, "转 mp4 第一次失败（${last.reason}），重试一次")
            last = HlsRemuxer.remux(context, ts, mp4)
        }
        return when (last) {
            is HlsRemuxer.Outcome.Failed -> Outcome.Failed(last.reason)
            is HlsRemuxer.Outcome.Ok -> {
                ts.delete()
                Outcome.Ok(last.file, last.bytes, merged.bytes, last.durationMs)
            }
        }
    }

    sealed interface Merged {
        data class Ok(val file: File, val bytes: Long, val segments: Int) : Merged

        /**
         * @param retryable 网络类失败（分片超时 / 连接被断 / 播放列表拉不到）：上层可以**自动重排队**，
         *   重来时已下好的分片会被复用（切片级断点），所以重试成本只是"没下完的那部分"。
         *   解析失败、转封装失败这类不算（重试大概率还是同样的结果）。
         */
        data class Failed(val reason: String, val retryable: Boolean = false) : Merged
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
        // 失败原因用 AtomicReference：**多个切片协程并发写 / 读**，普通 var 在弱内存模型下别的线程
        // 可能看不到，于是它们会一直闷头拉下去 —— 表现就是「进度卡住、状态永远 RUNNING」
        // （2026-10-17 排查大视频卡住时发现；局部变量不能用 @Volatile）。
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)

        // 自适应并发闸门：失败攒起来时**主动降并发**，成功几次再慢慢升回去。
        // 依据是实测：在这台模拟器的线路上，8 路各 1 MB 的并发传输会让**每一条**都在几百 KB 处超时
        // （日志里整屏 `已收 xxx 字节 … timeout`），降到 1~2 路反而能持续跑下去。
        val maxParallel = parallel.coerceAtLeast(1)
        val gate = object {
            val effective = AtomicInteger(maxParallel)
            val inflight = AtomicInteger(0)

            suspend fun acquire() {
                while (inflight.get() >= effective.get()) kotlinx.coroutines.delay(150)
                inflight.incrementAndGet()
            }

            fun release() {
                inflight.decrementAndGet()
            }

            fun onFail() {
                val now = effective.updateAndGet { (it - 1).coerceAtLeast(1) }
                if (now < maxParallel) Log.w(TAG, "网络吃紧，并发降到 $now 路")
            }

            fun onOk() {
                effective.updateAndGet { (it + 1).coerceAtMost(maxParallel) }
            }
        }

        // 抓取阶段的看门狗：连续 STALL_MS **一个字节都没进来**才算搁死，主动失败。
        // 判据用"字节有没有增长"而不是"有没有整片完成"：分片内断点续传时，一片可能要分几次才拉完，
        // 每次都在推进就不该被判失败。
        val lastOkAt = AtomicLong(System.currentTimeMillis())
        val watchdog = launch(Dispatchers.IO) {
            while (failure.get() == null && done.get() < wanted.size) {
                kotlinx.coroutines.delay(15_000)
                val idle = System.currentTimeMillis() - lastOkAt.get()
                if (done.get() >= wanted.size) break
                if (idle > STALL_MS) {
                    failure.compareAndSet(null, "网络不稳定，请重试")
                    Log.w(TAG, "抓取卡住 ${idle / 1000}s（${done.get()}/${wanted.size} 片），主动失败")
                    break
                }
            }
        }
        // 半成品也计入进度：弱网下一片可能要分好几次才拉完，只按"整片完成"报进度会让界面看着卡住
        // （用户 2026-10-17 反馈的"卡进度"）。partial 记的是 .part 里的密文字节。
        val partialBytes = AtomicLong(0)
        var lastReportAt = 0L
        val onChunk: (Long) -> Unit = { n ->
            lastOkAt.set(System.currentTimeMillis())
            val total = partialBytes.addAndGet(n) + bytes.get()
            val now = System.currentTimeMillis()
            if (now - lastReportAt > 500) {
                lastReportAt = now
                onProgress(Progress(done.get(), wanted.size, total))
            }
        }

        coroutineScope {
            val jobs = wanted.mapIndexed { index, segment ->
                launch(Dispatchers.IO) {
                    gate.acquire()
                    try {
                        if (failure.get() != null) return@launch
                        val target = File(segFile(workDir, index))
                        if (target.length() > 0) return@launch
                        try {
                            // 与观看一致：拉每一片之前确认账号带 VIP（闸门里有缓存，不贵）
                            ensureAccount?.invoke()
                            // 分片内断点续传：把**密文**按字节范围续到 `NN.ts.part`，拉完再解密成 `NN.ts`。
                            // 这个 CDN 有 `Accept-Ranges: bytes`，而弱网下 1 MB 分片常传到一半断 ——
                            // 整片重试等于每次从头再来（用户 2026-10-17 报的大视频卡住，主因之一）。
                            val part = File(segFile(workDir, index) + ".part")
                            fetchSegmentToFile(
                                url = segment.uri,
                                part = part,
                                onBytes = onChunk,
                                shouldAbort = { failure.get() != null },
                                onAttemptFailed = { gate.onFail() }
                            )
                            val plain = if (key != null) {
                                val iv = playlist.keyIv ?: HlsPlaylist.ivForSequence(segment.sequence)
                                decrypt(key, iv, part.readBytes())
                            } else {
                                part.readBytes()
                            }
                            target.writeBytes(plain)
                            partialBytes.addAndGet(-part.length())
                            part.delete()
                            bytes.addAndGet(plain.size.toLong())
                            gate.onOk()
                            onProgress(Progress(done.incrementAndGet(), wanted.size, bytes.get()))
                        } catch (e: Exception) {
                            gate.onFail()
                            failure.compareAndSet(null, humanError(e, "切片 ${index + 1} 下载失败"))
                        }
                    } finally {
                        gate.release()
                    }
                }
            }
            jobs.joinAll()
        }
        watchdog.cancel()
        // 抓取阶段的失败都能靠"重排队 + 复用已下好的分片"救回来（网络类）
        failure.get()?.let { return@withContext Merged.Failed(it, retryable = true) }

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

    /**
     * 拉一个分片到 [part]（**密文**，调用方负责解密）—— 带**分片内按字节范围续传**。
     *
     * 这个 CDN 的分片 1 MB 上下，弱网下经常"传到一半断"（本机直连同一片也出现过
     * `Response ended prematurely`，2026-10-17 实测）。CDN 明确支持 `Accept-Ranges: bytes`，
     * 所以断掉之后**从已收到的字节数接着拉**，而不是整片从头再来：
     *
     * - 请求带 `Range: bytes=<已收>-`；服务端回 206 就**追加**写，回 200 说明不支持范围请求 → 截断重写；
     * - 每收到一段就回调 [onBytes]（上传"还在动"的信号，看门狗据此判活）；
     * - 网络类失败最多重试 [attempts] 次，退避 1s / 3s / 8s；HTTP 4xx 直接放弃（服务端明确拒绝）；
     * - 部分数据**留在 part 文件里**：任务级自动重排队重来时也接着传。
     *
     * @param shouldAbort 已经判定整体失败时提前放弃，别把重试时间白白花掉
     */
    private suspend fun fetchSegmentToFile(
        url: String,
        part: File,
        onBytes: (Long) -> Unit,
        shouldAbort: () -> Boolean,
        onAttemptFailed: () -> Unit = {},
        attempts: Int = 6
    ) {
        var last: Exception? = null
        for (i in 0 until attempts) {
            if (shouldAbort()) throw java.io.IOException("已放弃")
            val have = if (part.exists()) part.length() else 0L
            try {
                val builder = Request.Builder().url(url)
                if (have > 0) builder.header("Range", "bytes=$have-")
                client.newCall(builder.build()).execute().use { resp ->
                    val code = resp.code
                    // 服务端忽略 Range 时（200）要从头写；206 则追加
                    val append = have > 0 && code == 206
                    if (!resp.isSuccessful && code != 206) {
                        throw IllegalStateException("HTTP $code")
                    }
                    if (have > 0 && !append) {
                        Log.w(TAG, "服务端忽略了 Range（HTTP $code），从头重拉这一片")
                    }
                    if (!append) part.delete()
                    resp.body!!.byteStream().use { input ->
                        java.io.FileOutputStream(part, append).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                onBytes(n.toLong())
                            }
                        }
                    }
                }
                return
            } catch (e: java.io.IOException) {
                last = e
                val now = if (part.exists()) part.length() else 0L
                Log.w(
                    TAG,
                    "分片下载中断（第 ${i + 1}/$attempts 次，已收 $now 字节）：${e.message}"
                )
                // **每次中断**就通知闸门（不是等 6 次用完）：第一次断往往就是线路吃紧的信号，
                // 及时把并发降下来，后面的传输才稳（2026-10-17 实测：8 路时几乎每条都在几百 KB 处断）
                onAttemptFailed()
                if (i < attempts - 1) {
                    kotlinx.coroutines.delay(RETRY_BACKOFF_MS.getOrElse(i) { 8000L })
                }
            }
        }
        throw last ?: java.io.IOException("网络错误")
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
