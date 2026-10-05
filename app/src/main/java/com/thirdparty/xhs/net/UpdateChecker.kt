package com.thirdparty.xhs.net

import com.thirdparty.xhs.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 检查 GitHub Releases 上有没有新版本。
 *
 * 这是全应用**唯一**不经过 AES 包体加密的请求：它不问 xiaohuangbook 的接口，而是
 * 直接向 GitHub 的公开 API 取最新 release。因此这里刻意用一个独立的 OkHttpClient ——
 * 共用的那个带 64 MB 磁盘缓存（为图片 CDN 加的），会把 API 应答一起缓存，
 * 结果就是"点检查更新永远看到同一次的结果"。
 *
 * 比较只用 [BuildConfig.VERSION_NAME]（人读的 x.y.z）；versionCode 是时间戳推导的，
 * 只用于安装包覆盖升级，不参与展示。
 */
object UpdateChecker {

    /** 项目主页；关于页与更新提示都指向这里。 */
    const val REPO_URL = "https://github.com/limao996/xhs-thirdparty"
    const val RELEASES_URL = "$REPO_URL/releases"

    private const val API_LATEST =
        "https://api.github.com/repos/limao996/xhs-thirdparty/releases/latest"

    /** 检查结果。失败要把原因如实带回界面，不能伪造成"已是最新"。 */
    sealed interface Result {
        /** GitHub 上的版本不高于本机。 */
        data class UpToDate(val current: String, val pageUrl: String) : Result

        /** 有更新可用。 */
        data class Newer(
            val version: String,
            val pageUrl: String,
            val notes: String,
            val apkUrl: String?
        ) : Result

        /** 仓库还在，但一个 release 都没发。 */
        data class NoRelease(val pageUrl: String) : Result

        /** 网络不通 / 被限流 / 应答解析失败。 */
        data class Failed(val reason: String) : Result
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): Result =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(API_LATEST)
                .header("Accept", "application/vnd.github+json")
                // GitHub 对没有 User-Agent 的请求直接 403
                .header("User-Agent", "xhs-thirdparty/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            try {
                // await()（OkHttpAwait）而不是阻塞的 execute()：作用域被取消时会 cancel 掉连接，
                // 否则 socket 要一直跑到超时（docs/REVIEW.md 附录A-P1-17）。
                client.newCall(request).await().use { response ->
                    when {
                        response.code == 404 -> Result.NoRelease(RELEASES_URL)
                        response.code == 403 -> Result.Failed("GitHub 限流（HTTP 403），过一会儿再试")
                        !response.isSuccessful -> Result.Failed("GitHub 返回 HTTP ${response.code}")
                        else -> parse(response.body?.string().orEmpty(), currentVersion)
                    }
                }
            } catch (e: java.io.IOException) {
                // 连不上（没有翻墙 / DNS 被污染 / 没有默认网络）都会走到这里
                Result.Failed(e.message?.take(160) ?: "网络不可用")
            } catch (e: org.json.JSONException) {
                Result.Failed("GitHub 应答解析失败")
            }
        }

    /** internal 而非 private：单元测试直接喂 GitHub 的应答样本（见 UpdateCheckerTest）。 */
    internal fun parse(body: String, currentVersion: String): Result {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").removePrefix("v").trim()
        if (tag.isEmpty()) return Result.Failed("应答里没有 tag_name")
        val pageUrl = json.optString("html_url").ifEmpty { RELEASES_URL }
        val notes = json.optString("body").trim()
        val apkUrl = json.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotEmpty() && isTrustedDownloadUrl(it) }
        }
        return if (isNewer(tag, currentVersion)) {
            Result.Newer(tag, pageUrl, notes.take(600), apkUrl)
        } else {
            Result.UpToDate(currentVersion, pageUrl)
        }
    }

    /** 按数字段比较 "1.10.0" 与 "1.9.2"；任一侧解析不了就当"不更新"。 */
    fun isNewer(remote: String, current: String): Boolean {        val a = numbers(remote) ?: return false
        val b = numbers(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int>? {
        val cleaned = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        if (cleaned.isEmpty()) return null
        return cleaned.split('.').map { it.toIntOrNull() ?: return null }.ifEmpty { null }
    }

    /**
     * 只信 GitHub 自己的下载地址。
     *
     * 应答是**外部数据**，仓库/发布一旦被改，弹窗上的「打开下载页」就会指向任意 URL
     * （docs/REVIEW.md 附录A-P1-18）。不在白名单里就当作没有 apk 链接，回落到发布页。
     */
    fun isTrustedDownloadUrl(url: String): Boolean = runCatching {
        val u = java.net.URI(url)
        u.scheme.equals("https", true) && TRUSTED_HOSTS.any { h ->
            u.host.equals(h, true) || u.host.endsWith(".$h", true)
        }
    }.getOrDefault(false)

    private val TRUSTED_HOSTS = listOf("github.com", "githubusercontent.com")
}
