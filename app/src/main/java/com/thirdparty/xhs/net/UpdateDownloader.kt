package com.thirdparty.xhs.net

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import androidx.core.net.toUri

/**
 * 应用内下载新版本的 APK，并给出"安装"的 Intent。
 *
 * 两个刻意的选择：
 *
 * 1. **下载走独立的 OkHttpClient**（同 `UpdateChecker`）：共用的那个带 64 MB 磁盘缓存，
 *    会把几 MB 的 APK 缓存下来，占用户的存储、还会让"重试下载"拿到旧文件。
 * 2. **装之前先校验包名**：APK 来自网络，万一下载地址被换成了别的应用，直接
 *    `startActivity(安装)` 就等于让用户装一个陌生 App。用 `getPackageArchiveInfo`
 *    读出包名，必须与本应用一致才允许继续（版本号只做提示）。
 *
 * 安装本身交给系统安装器（`ACTION_VIEW` + `application/vnd.android.package-archive` +
 * FileProvider 的 content:// URI）。需要 `REQUEST_INSTALL_PACKAGES` 权限，且
 * Android 8.0+ 首次还要用户在系统里允许"安装未知应用" —— 那是系统的开关，应用无法代劳，
 * 所以这里失败时会把用户送去那个设置页。
 */
object UpdateDownloader {

    /** 下载结果的进度回调：已写字节 / 总字节（总长未知时为 -1）。 */
    fun interface Progress {
        fun onProgress(written: Long, total: Long)
    }

    sealed interface Outcome {
        data class Ready(val file: File, val versionName: String?) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 落盘位置：`cacheDir/updates/`（系统清理缓存时会一起回收，不占用户存储配额）。 */
    fun targetFile(context: Context, version: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, UpdateChecker.assetFileName(version))
    }

    suspend fun download(
        context: Context,
        url: String,
        version: String,
        progress: Progress = Progress { _, _ -> }
    ): Outcome = withContext(Dispatchers.IO) {
        // 外部数据：地址必须还是 GitHub 的，否则不下载
        if (!UpdateChecker.isTrustedDownloadUrl(url)) {
            return@withContext Outcome.Failed("下载地址不可信，已中止")
        }
        val dest = targetFile(context, version)
        val tmp = File(dest.parentFile, dest.name + ".part")
        runCatching {
            val request = Request.Builder().url(url).get()
                .header("User-Agent", "xhs-thirdparty/${com.thirdparty.xhs.BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Outcome.Failed("下载失败（HTTP ${response.code}）")
                }
                val body = response.body ?: return@withContext Outcome.Failed("下载内容为空")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            written += n
                            progress.onProgress(written, total)
                        }
                    }
                }
            }
        }.onFailure { e ->
            tmp.delete()
            return@withContext Outcome.Failed(friendlyNetworkReason(e))
        }
        if (!tmp.exists() || tmp.length() <= 0L) {
            tmp.delete()
            return@withContext Outcome.Failed("下载内容为空")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            return@withContext Outcome.Failed("安装包保存失败")
        }
        val info = packageInfo(context, dest)
        if (info == null) {
            dest.delete()
            return@withContext Outcome.Failed("安装包无法识别")
        }
        if (info.first != context.packageName) {
            dest.delete()
            return@withContext Outcome.Failed("安装包与本应用不匹配，已删除")
        }
        Outcome.Ready(dest, info.second)
    }

    /** (packageName, versionName)；不是有效 APK 时返回 null。 */
    private fun packageInfo(context: Context, file: File): Pair<String, String?>? = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_ACTIVITIES
        ) ?: return null
        info.applicationInfo?.sourceDir = file.absolutePath
        info.applicationInfo?.publicSourceDir = file.absolutePath
        info.packageName to info.versionName
    }.getOrNull()

    /**
     * 安装 Intent（系统安装器）。失败时给出"去允许安装未知应用"的兜底 Intent。
     */
    fun installIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 系统设置里"安装未知应用"的授权页（用户拒绝安装后跳这里）。 */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
