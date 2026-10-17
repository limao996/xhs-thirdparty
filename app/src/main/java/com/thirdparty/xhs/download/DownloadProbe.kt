package com.thirdparty.xhs.download

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * **验证探针（DEBUG 专用，T7 做出正式 UI 后删除）**：跑一遍真实作品的 HLS 下载链路
 * （解析 → 切片并行下载 → AES-128 解密 → 合并 → 转封装 mp4），并把**并行 1 路与 4 路的耗时**
 * 各跑一次做对比，用来证明"多线程切片下载"确实有提速。
 *
 * 触发：
 * ```
 * adb shell "run-as com.thirdparty.xhs.debug sh -c 'echo 1 > files/run-download-probe'"
 * adb shell am force-stop com.thirdparty.xhs.debug
 * adb shell am start -n com.thirdparty.xhs.debug/com.thirdparty.xhs.MainActivity
 * adb logcat -s XhsDownload XhsProbe
 * ```
 */
object DownloadProbe {

    private const val TAG = "XhsProbe"
    private const val MARKER = "run-download-probe"

    /** 真实视频源（2026-10-17 探测所得，HLS + AES-128）。 */
    private const val PLAYLIST = "https://xn--z6uom.ienglish.store/static/m3u8/20201209/afqbZZ/p.m3u8"

    /** 只跑前 N 片，够证明链路且不浪费时间/流量。 */
    private const val LIMIT = 12

    fun runIfRequested(context: Context) {
        if (!com.thirdparty.xhs.BuildConfig.DEBUG) return
        val marker = File(context.filesDir, MARKER)
        if (!marker.exists()) return
        marker.delete()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { spike(context) }.onFailure {
                Log.e(TAG, "探针失败：${it.message}", it)
            }
        }
    }

    private suspend fun spike(context: Context) = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().build()
        for (parallel in listOf(1, 4)) {
            val work = File(context.cacheDir, "probe-p$parallel").apply {
                deleteRecursively(); mkdirs()
            }
            val started = System.currentTimeMillis()
            val outcome = HlsDownloader(client, parallel = parallel).remuxToMp4(
                context = context,
                playlistUrl = PLAYLIST,
                workDir = work,
                limit = LIMIT
            )
            val ms = System.currentTimeMillis() - started
            when (outcome) {
                is HlsDownloader.Outcome.Failed ->
                    Log.e(TAG, "并发 $parallel：失败 ${outcome.reason}（${ms}ms）")

                is HlsDownloader.Outcome.Ok ->
                    Log.i(
                        TAG,
                        "并发 $parallel：成功 mp4=${outcome.bytes} 字节 TS=${outcome.tsBytes} " +
                            "时长=${outcome.durationMs}ms 总耗时=${ms}ms（$LIMIT 片）"
                    )
            }
            work.deleteRecursively()
        }
        Log.i(TAG, "探针结束")
    }
}
