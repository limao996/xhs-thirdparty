package com.thirdparty.xhs.download

import android.util.Log
import com.thirdparty.xhs.net.XhsCrypto
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 图片下载：取字节 →（必要时）解密 → 交给 [MediaStoreWriter] 存进相册。
 *
 * **必须复用现有的图片解密链路**：接口的图片里，凡是 URL 含 `codstatic` 的，
 * CDN 返回的是 **AES/ECB/NoPadding** 加密的字节（见 `net/XhsCrypto.kt` 的 `zdecrypt` 与
 * `ui/components/XhsAsyncImage.kt` 的加载路径）。不解密就存盘，用户拿到的会是一堆乱码图片。
 */
class ImageDownloader(private val client: OkHttpClient) {

    sealed interface Outcome {
        /**
         * [uri] 是 MediaStore 里的行 uri，**必须往库里存**：下载管理页的「打开/预览」靠它
         * （早期版本这里传了 null，结果图片行点打开只会提示"找不到文件记录"，2026-10-17 用户反馈）。
         */
        data class Ok(val bytes: Long, val displayPath: String, val uri: String?) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    companion object {
        /** CDN 上加密图片的地址特征（与 `XhsAsyncImage` 判断一致）。 */
        private const val COD_STATIC = "codstatic"
        private const val TAG = "XhsDownload"

        /** 取回并解密一张图片（探针/服务共用）。 */
        fun fetchDecrypted(client: OkHttpClient, url: String): ByteArray {
            val raw = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body!!.bytes()
            }
            if (raw.isEmpty()) throw IllegalStateException("下载内容为空")
            return if (url.contains(COD_STATIC)) XhsCrypto.zdecrypt(raw) else raw
        }
    }

    /** 下载 + 解密 + 落盘到 `Pictures/…`（[relativeDir] 见 [DownloadNames.imageRelativeDir]）。 */
    fun saveToGallery(
        context: android.content.Context,
        url: String,
        relativeDir: String,
        displayName: String
    ): Outcome = try {
        val bytes = fetchDecrypted(client, url)
        Log.i(TAG, "图片解密后 ${bytes.size} 字节：$displayName")
        when (val saved = MediaStoreWriter.saveImage(context, bytes, relativeDir, displayName)) {
            is MediaStoreWriter.Result.Failed -> Outcome.Failed(saved.reason)
            // 带上公共目录前缀（Pictures/…）与 MediaStore uri：前者用于显示，后者用于预览/打开
            is MediaStoreWriter.Result.Ok -> Outcome.Ok(saved.bytes, saved.displayPath, saved.uri.toString())
        }
    } catch (e: Exception) {
        Outcome.Failed(humanError(e))
    }

    /** 与 [HlsDownloader] 同样的口径：界面直接显示这句话，不要透出英文原文。 */
    private fun humanError(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException ->
            "无法连接服务器，请检查网络"

        is java.net.SocketTimeoutException -> "连接超时，请重试"

        is IllegalStateException -> e.message?.takeIf { it.isNotBlank() } ?: "下载失败，请重试"
        else -> "下载失败，请重试"
    }
}
