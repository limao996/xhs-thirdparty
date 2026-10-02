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
 * rather than pulling in a WebDAV library. The base URL, user and password are
 * kept in the app's own prefs and are deliberately NOT part of the backup
 * payload (the file that gets uploaded should not carry the credentials that
 * guard it).
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

    /** `/dir/sub` + `file.json` -> a full, single-slash-joined URL. */
    private fun fileUrl(name: String): String =
        prefs.url.trimEnd('/') + "/" + name.trimStart('/')

    private fun authed(builder: Request.Builder): Request.Builder =
        builder.apply {
            if (prefs.user.isNotBlank()) {
                header("Authorization", Credentials.basic(prefs.user, prefs.password))
            }
        }

    /** Upload (creates or overwrites). Returns a human-readable result. */
    suspend fun upload(name: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = content.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = authed(Request.Builder().url(fileUrl(name)).put(body)).build()
            client.newCall(req).execute().use { resp ->
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
            client.newCall(req).execute().use { resp ->
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
                Request.Builder().url(prefs.url.trimEnd('/') + "/").method("MKCOL", null)
            ).build()
            client.newCall(req).execute().use { resp ->
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
        const val FILE_NAME = "xhs-thirdparty-backup.json"

        fun config(context: Context): WebDavConfig {
            val p = context.getSharedPreferences("webdav", Context.MODE_PRIVATE)
            return WebDavConfig(
                url = p.getString("url", "") ?: "",
                user = p.getString("user", "") ?: "",
                password = p.getString("password", "") ?: ""
            )
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
