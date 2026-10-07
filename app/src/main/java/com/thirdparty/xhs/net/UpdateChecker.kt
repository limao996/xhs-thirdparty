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
 * 这是全应用**唯一**不经过 AES 包体加密的外网请求（WebDAV 也加密？不 —— 见 WebDavClient，
 * 那是用户自己的服务器，同样不走 AES）。因此这里刻意用一个独立的 OkHttpClient ——
 * 共用的那个带 64 MB 磁盘缓存（为图片 CDN 加的），会把应答一起缓存，结果就是
 * "点检查更新永远看到同一次的结果"。
 *
 * ## 为什么先看 atom feed，而不是 api.github.com
 *
 * 匿名 GitHub API 的额度是 **每 IP 每小时 60 次**，而且和这台机器上其它工具共享 ——
 * 用户"明明没怎么检查过"却被限流（403）就是这么来的（实测碰到过多次）。
 * `releases.atom` 是 GitHub 自己发布页用的公开 feed：**不计 API 额度、不需要 token**，
 * 而且一次就带回 tag、页面地址与更新说明。所以顺序是：atom 优先 → API 兜底（并把
 * 403 如实说成"限流"，不伪装成"已是最新"）。
 *
 * 比较只用 [BuildConfig.VERSION_NAME]（人读的 x.y.z）；versionCode 是时间戳推导的，
 * 只用于安装包覆盖升级，不参与展示。
 */
object UpdateChecker {

    /** 项目主页；关于页与更新提示都指向这里。 */
    const val REPO_URL = "https://github.com/limao996/xhs-thirdparty"
    const val RELEASES_URL = "$REPO_URL/releases"

    private const val ATOM_FEED = "$REPO_URL/releases.atom"
    private const val API_LATEST = "https://api.github.com/repos/limao996/xhs-thirdparty/releases/latest"

    /** 发布资产的命名约定（见 docs/BUILD.md）：`xhs-thirdparty-<version>-release.apk`。 */
    private const val ASSET_PREFIX = "xhs-thirdparty-"
    private const val ASSET_SUFFIX = "-release.apk"

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

    /**
     * 查最新版本。
     *
     * 先 atom（不吃限流），失败再 API；两者都失败时把**更具体的**原因带回界面
     * （API 返回 403 就直说是限流）。
     */
    suspend fun check(currentVersion: String = BuildConfig.VERSION_NAME): Result =
        withContext(Dispatchers.IO) {
            val viaAtom = runCatching { fetchAtom() }.getOrNull()
            if (viaAtom != null) {
                return@withContext when {
                    viaAtom.tag.isEmpty() -> Result.NoRelease(RELEASES_URL)
                    isNewer(viaAtom.tag, currentVersion) -> Result.Newer(
                        viaAtom.tag,
                        viaAtom.pageUrl,
                        viaAtom.notes.take(600),
                        assetUrl(viaAtom.tag)
                    )
                    else -> Result.UpToDate(currentVersion, viaAtom.pageUrl)
                }
            }
            fetchApi(currentVersion)
        }

    // ---- atom feed（首选，不吃 API 额度）--------------------------------------

    private data class AtomRelease(val tag: String, val pageUrl: String, val notes: String)

    private suspend fun fetchAtom(): AtomRelease? {
        val request = Request.Builder()
            .url(ATOM_FEED)
            .header("User-Agent", "xhs-thirdparty/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            val first = body.substringAfter("<entry>", "")
            if (first.isEmpty()) return null
            val link = Regex("""<link[^>]*rel="alternate"[^>]*href="([^"]+)"""")
                .find(first)?.groupValues?.get(1)
                ?: Regex("""<link[^>]*href="([^"]+releases/tag/[^"]+)"""")
                    .find(first)?.groupValues?.get(1)
                ?: return null
            // tag 从链接里取最稳（标题是"小黄书 v1.3.0"这种给人看的文案）
            val tag = link.substringAfter("/releases/tag/", "").trim().removePrefix("v")
            if (tag.isEmpty()) return null
            val notes = Regex("""(?s)<content[^>]*>(.*?)</content>""")
                .find(first)?.groupValues?.get(1)
                ?.let { htmlToText(it) }
                .orEmpty()
            return AtomRelease(tag, link, notes)
        }
    }

    /** `&lt;h2&gt;标题&lt;/h2&gt;` 这种转义后的 HTML → 纯文本（弹窗里只展示几行）。 */
    internal fun htmlToText(raw: String): String = raw
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&amp;", "&")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /**
     * 资产下载地址：本仓库的资产命名是固定的（`xhs-thirdparty-<version>-release.apk`），
     * 所以从 tag 就能拼出来，不用再请求一次 API。拼出来的地址仍要过 [isTrustedDownloadUrl]。
     */
    fun assetUrl(tag: String, repoUrl: String = REPO_URL): String? {
        val v = tag.trim().removePrefix("v")
        if (v.isEmpty()) return null
        val url = "$repoUrl/releases/download/v$v/$ASSET_PREFIX$v$ASSET_SUFFIX"
        return url.takeIf { isTrustedDownloadUrl(it) }
    }

    // ---- API（兜底）----------------------------------------------------------

    private suspend fun fetchApi(currentVersion: String): Result {
        val request = Request.Builder()
            .url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            // GitHub 对没有 User-Agent 的请求直接 403
            .header("User-Agent", "xhs-thirdparty/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        return try {
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
        } ?: assetUrl(tag)
        return if (isNewer(tag, currentVersion)) {
            Result.Newer(tag, pageUrl, notes.take(600), apkUrl)
        } else {
            Result.UpToDate(currentVersion, pageUrl)
        }
    }

    /**
     * atom feed 的解析（internal 供单测直接喂样本）。
     */
    internal fun parseAtom(body: String, currentVersion: String): Result {
        val first = body.substringAfter("<entry>", "")
        if (first.isEmpty()) return Result.NoRelease(RELEASES_URL)
        val link = Regex("""<link[^>]*href="([^"]+releases/tag/[^"]+)"""")
            .find(first)?.groupValues?.get(1) ?: return Result.Failed("atom feed 里没有 tag 链接")
        val tag = link.substringAfter("/releases/tag/", "").removePrefix("v").trim()
        val notes = Regex("""(?s)<content[^>]*>(.*?)</content>""")
            .find(first)?.groupValues?.get(1)?.let(::htmlToText).orEmpty()
        return if (isNewer(tag, currentVersion)) {
            Result.Newer(tag, link, notes.take(600), assetUrl(tag))
        } else {
            Result.UpToDate(currentVersion, link)
        }
    }

    /** 按数字段比较 "1.10.0" 与 "1.9.2"；任一侧解析不了就当"不更新"。 */
    fun isNewer(remote: String, current: String): Boolean {
        val a = numbers(remote) ?: return false
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
     * 应答是**外部数据**，仓库/发布一旦被改，弹窗上的下载按钮就会指向任意 URL
     * （docs/REVIEW.md 附录A-P1-18）。不在白名单里就当作没有下载链接，回落到发布页。
     */
    fun isTrustedDownloadUrl(url: String): Boolean = runCatching {
        val u = java.net.URI(url)
        u.scheme.equals("https", true) && TRUSTED_HOSTS.any { h ->
            u.host.equals(h, true) || u.host.endsWith(".$h", true)
        }
    }.getOrDefault(false)

    private val TRUSTED_HOSTS = listOf("github.com", "githubusercontent.com")

    /** 资产文件名（供下载器落盘与校验用）。 */
    fun assetFileName(version: String): String =
        "$ASSET_PREFIX${version.removePrefix("v")}$ASSET_SUFFIX"
}
