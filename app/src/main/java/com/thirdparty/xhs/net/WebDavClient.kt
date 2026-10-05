package com.thirdparty.xhs.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Minimal WebDAV client — enough to keep a backup file on a personal cloud drive.
 *
 * Only PUT / GET / MKCOL are needed, so this talks plain HTTP with Basic auth
 * rather than pulling in a WebDAV library. The base URL, user and password live in
 * the app's own prefs.
 *
 * **注意**：WebDAV 配置（含密码）**会**写进备份文件（见 `BackupManager` 的 `webdav` 段）——
 * 理由是整个备份本来就是用户自己的、又已经带着账号 token，排除密码只会让人每次恢复后重输。
 * 所以备份文件本身要放在可信位置。另外 Basic 认证走明文：公网地址必须是 https，
 * 只有私网地址才允许 http（见 [WebDavConfig.validate]）。
 */
class WebDavClient(
    private val client: OkHttpClient,
    private val prefs: WebDavConfig
) {
    data class WebDavConfig(
        val url: String,
        val user: String,
        val password: String
    ) {
        val configured: Boolean get() = url.isNotBlank()
    }

    /**
     * Backups always live in this fixed sub-collection under the configured root,
     * so the user only supplies a server root (e.g. `https://dav.jianguoyun.com/dav/`)
     * and never has to decide where the file goes.
     *
     * Kept ASCII on purpose: a Chinese name would have to be percent-encoded in
     * every request, and some WebDAV servers compare the raw path.
     */
    private fun dirUrl(): String = prefs.url.trimEnd('/') + "/" + DIR + "/"

    /** `<root>/xhs/<name>` — the one place backups are written and read. */
    private fun fileUrl(name: String): String = dirUrl() + name.trimStart('/')

    /** Where the file lived before the fixed directory existed, for old backups. */
    private fun legacyFileUrl(name: String): String =
        prefs.url.trimEnd('/') + "/" + name.trimStart('/')

    private fun authed(builder: Request.Builder): Request.Builder =
        builder.apply {
            if (prefs.user.isNotBlank()) {
                header("Authorization", Credentials.basic(prefs.user, prefs.password))
            }
        }

    /**
     * Upload raw bytes (creates or overwrites). Used for the gzipped backup, which
     * is why the media type is application/gzip rather than json.
     */
    suspend fun upload(name: String, content: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = content.toRequestBody("application/gzip".toMediaType())
            val req = authed(Request.Builder().url(fileUrl(name)).put(body)).build()
            client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("上传失败 HTTP ${resp.code} ${resp.message}")
                }
            }
        }
    }

    /**
     * Download raw bytes from the fixed backup directory.
     *
     * Falls back to the pre-existing flat location when nothing is found there, so
     * a backup uploaded by an earlier version is still recoverable.
     */
    suspend fun downloadBytes(name: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val fromDir = fetchBytes(fileUrl(name))
            if (fromDir != null) return@runCatching fromDir
            val legacy = fetchBytes(legacyFileUrl(name))
                ?: throw IOException("云端还没有备份文件（已查找 $DIR/ 与根目录）")
            legacy
        }
    }

    /** null when the server answers 404; throws on any other failure. */
    private suspend fun fetchBytes(url: String): ByteArray? {
        val req = authed(Request.Builder().url(url).get()).build()
        client.newCall(req).await().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code} ${resp.message}")
            return resp.body?.bytes() ?: throw IOException("云端返回了空内容")
        }
    }

    /** Upload (creates or overwrites). Returns a human-readable result. */
    suspend fun upload(name: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = content.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = authed(Request.Builder().url(fileUrl(name)).put(body)).build()
            client.newCall(req).await().use { resp ->
                // 201 Created / 204 No Content are the normal answers; some servers
                // reply 200. Anything else is a real failure.
                if (!resp.isSuccessful) {
                    throw IOException("上传失败 HTTP ${resp.code} ${resp.message}")
                }
            }
        }
    }

    /** Download; fails clearly when the file is not there yet. */
    suspend fun download(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = authed(Request.Builder().url(fileUrl(name)).get()).build()
            client.newCall(req).await().use { resp ->
                if (resp.code == 404) throw IOException("云端还没有备份文件")
                if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code} ${resp.message}")
                resp.body?.string() ?: throw IOException("云端返回了空内容")
            }
        }
    }

    /**
     * Make sure the target collection exists.
     *
     * Reports failures rather than swallowing them: a wrong URL or password shows
     * up here first, and silently ignoring it turned "your credentials are wrong"
     * into a confusing PUT error further down. 405 means the server does not allow
     * MKCOL but the collection is already there, which is fine.
     */
    suspend fun ensureDir(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = authed(
                Request.Builder().url(dirUrl()).method("MKCOL", null)
            ).build()
            client.newCall(req).await().use { resp ->
                when {
                    resp.isSuccessful || resp.code == 405 -> Unit
                    resp.code == 401 || resp.code == 403 ->
                        throw IOException("认证失败（HTTP ${resp.code}），请检查账号与应用密码")
                    resp.code == 404 -> throw IOException("服务器地址不存在（HTTP 404），请检查路径")
                    else -> throw IOException("无法访问服务器（HTTP ${resp.code} ${resp.message}）")
                }
            }
        }
    }

    /** Does a real round-trip so the user can validate the config on demand. */
    suspend fun testConnection(): Result<Unit> = ensureDir()

    companion object {
        /** Backup file name on the drive. */
        const val FILE_NAME = "xhs-thirdparty-backup.json.gz"

        /** Fixed sub-collection under the configured root; see [dirUrl]. */
        const val DIR = "xhs"

        fun config(context: Context): WebDavConfig {
            val p = context.getSharedPreferences("webdav", Context.MODE_PRIVATE)
            return WebDavConfig(
                url = p.getString("url", "") ?: "",
                user = p.getString("user", "") ?: "",
                password = p.getString("password", "") ?: ""
            )
        }

        /**
         * 校验用户填的服务器地址，返回错误说明或 null。
         *
         * 为什么单独校验：全局 `usesCleartextTraffic="true"` 是为了让**局域网**里的 http WebDAV
         * 能用，但 WebDAV 走的是 Basic 认证 —— 地址填成公网 `http://` 时，账号密码就是 base64
         * 明文过网（docs/REVIEW.md 附录A-P1-13 / C-P2-26）。
         * 所以允许：https 任意主机；http 仅限私网地址（10./172.16-31./192.168./localhost/::1）。
         */
        fun validate(url: String): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return "请先填写服务器地址"
            if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
                return "地址要以 https:// 或 http:// 开头"
            }
            if (trimmed.startsWith("https://", true)) return null
            val host = runCatching { java.net.URI(trimmed).host }.getOrNull().orEmpty()
            if (host.isEmpty()) return "地址格式不对"
            val isPrivate = host == "localhost" || host == "::1" || host == "127.0.0.1" ||
                host.startsWith("10.") || host.startsWith("192.168.") ||
                Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
            return if (isPrivate) null
            else "公网地址必须用 https://（http 会把账号密码明文发出去）"
        }

        fun save(context: Context, cfg: WebDavConfig) {
            context.getSharedPreferences("webdav", Context.MODE_PRIVATE).edit()
                .putString("url", cfg.url.trim())
                .putString("user", cfg.user.trim())
                .putString("password", cfg.password)
                .apply()
        }
    }
}
