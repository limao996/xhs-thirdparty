package com.thirdparty.xhs.data

import android.content.Context
import com.thirdparty.xhs.App
import com.thirdparty.xhs.net.WebDavClient
import com.thirdparty.xhs.net.CredentialStore
import com.thirdparty.xhs.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

/**
 * Export / import of everything the user would miss after a reinstall.
 *
 * Covers favourites, recently-viewed, followed authors, search history, and every
 * setting that is not derivable (主题 / VIP 自动切换 / 最近浏览上限 / 指纹解锁), plus
 * the WebDAV transport config. Notes are stored as their **raw JSON**, which is exactly
 * what the detail page needs to re-open them offline, so a restore is lossless without
 * needing a per-field serializer that would drift as NoteItem grows.
 *
 * The ACCOUNT is deliberately NOT included (identity / token / hash / VIP window).
 * It is disposable by design — the app registers a fresh one whenever the current
 * VIP window lapses — so restoring an old one buys nothing, while the file is the one
 * thing that leaves the device (本地文件 or WebDAV) and a session token in it is the
 * one field that would actually be worth stealing. A restore keeps the account the app
 * is already using and only replaces the local data.
 *
 * Restoring is tolerant of older payloads: every field is optional and a missing
 * key leaves the current value alone, so a v1/v2 backup (which did carry `account`)
 * never zeroes anything — that block is simply ignored now.
 */
object BackupManager {

    /** Bumped whenever the payload shape changes; see [restore]. */
    const val VERSION = 3

    /** 备份文件（含解压后）的大小上限，防解压炸弹与内存爆掉 */
    const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
    private const val MARKER = "xhs-thirdparty-backup"

    data class Result(val ok: Boolean, val detail: String)

    /**
     * Everything, gzipped.
     *
     * The payload is mostly raw note JSON, which compresses very well (typ. 4-8x),
     * so this is about the bytes actually sent over WebDAV rather than local disk.
     * Gzip is auto-detected on the way back in, so uncompressed backups from an
     * earlier version still restore.
     */
    suspend fun exportCompressed(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val raw = export(context).toByteArray(Charsets.UTF_8)
        java.io.ByteArrayOutputStream(raw.size / 4).use { out ->
            java.util.zip.GZIPOutputStream(out).use { gz -> gz.write(raw) }
            out.toByteArray()
        }
    }

    /** Accepts either gzipped or plain-text payloads and returns the JSON. */
    fun decode(bytes: ByteArray): String {
        // 上限，防"解压炸弹"：gzip 能把很小的文件膨胀成几个 G，而下面是一次性 readBytes()
        require(bytes.size <= MAX_BACKUP_BYTES) {
            "备份文件过大（${bytes.size / 1024 / 1024} MB，上限 ${MAX_BACKUP_BYTES / 1024 / 1024} MB）"
        }
        val gzipped = bytes.size >= 2 &&
            bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (!gzipped) return bytes.toString(Charsets.UTF_8)
        // 解压时也限流：读满上限就停，别让 GZIPInputStream.readBytes() 无上限地撑爆内存
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { gz ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = gz.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_BACKUP_BYTES) {
                    throw java.io.IOException("备份解压后超过上限（${MAX_BACKUP_BYTES / 1024 / 1024} MB）")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    /** Everything, as pretty-printed JSON. */
    suspend fun export(context: Context): String = withContext(Dispatchers.IO) {
        val db = XhsDatabase.get(context)
        val store = CredentialStore(context)
        val root = JSONObject()

        root.put("app", MARKER)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis() / 1000)

        // No `account` block: see the class comment. An older build's backup may still
        // contain one; restore ignores it.

        root.put("settings", JSONObject().apply {
            put("theme", themeKey(context))
            put("autoVip", store.autoSwitchOnVipExpiry)
            // These were missing: a restore used to silently drop them, so the
            // user's 最近浏览上限 / 指纹解锁 silently reverted to defaults.
            put("historyLimit", store.historyLimit)
            put("biometricLock", store.biometricLock)
        })

        // Search history lived in its own prefs file and was never exported.
        root.put("searchHistory", JSONArray(repoSearchHistory(context)))

        // WebDAV config. The password IS included: the backup already carries the
        // account token, so leaving it out added no security while forcing everyone
        // to re-type their 应用密码 after a restore. The file is the user's own.
        root.put("webdav", JSONObject().apply {
            val cfg = WebDavClient.config(context)
            put("url", cfg.url); put("user", cfg.user); put("password", cfg.password)
        })

        root.put("saved", JSONArray().apply {
            db.savedDao().all().forEach { e ->
                put(JSONObject().apply {
                    put("noteId", e.noteId); put("title", e.title)
                    put("userName", e.userName); put("cover", e.cover)
                    put("noteType", e.noteType); put("rawJson", e.rawJson)
                    put("savedAt", e.savedAt)
                })
            }
        })

        root.put("history", JSONArray().apply {
            db.historyDao().recent(store.historyLimit).forEach { e ->
                put(JSONObject().apply {
                    put("noteId", e.noteId); put("title", e.title)
                    put("userName", e.userName); put("cover", e.cover)
                    put("noteType", e.noteType); put("rawJson", e.rawJson)
                    put("viewedAt", e.viewedAt)
                })
            }
        })

        root.put("followed", JSONArray().apply {            db.followDao().all().forEach { e ->
                put(JSONObject().apply {
                    put("userId", e.userId); put("userName", e.userName)
                    put("headImg", e.headImg); put("signature", e.signature)
                    put("followedAt", e.followedAt)
                })
            }
        })

        // 稍后观看队列：之前完全没进备份（类注释却说导出"everything the user would
        // miss after a reinstall"），换机/重装就丢队列（docs/REVIEW.md 附录C-P1-4）。
        root.put("watchLater", JSONArray().apply {
            db.watchLaterDao().all().forEach { e ->
                put(JSONObject().apply {
                    put("noteId", e.noteId); put("title", e.title)
                    put("userName", e.userName); put("cover", e.cover)
                    put("noteType", e.noteType); put("rawJson", e.rawJson)
                    put("position", e.position); put("addedAt", e.addedAt)
                })
            }
        })

        root.toString(2)
    }

    /**
     * Apply a backup.
     *
     * [merge] keeps whatever is already on the device and adds the file's rows
     * (existing entries win on the same key);
     * otherwise the local favourites/history/follows/queue are replaced wholesale.
     * The account is always adopted as-is, since it identifies the session.
     */
    suspend fun restore(
        context: Context,
        json: String,
        merge: Boolean
    ): Result = withContext(Dispatchers.IO) {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return@withContext Result(false, "不是有效的备份文件")
        }
        if (root.optString("app") != MARKER) {
            return@withContext Result(false, "不是本应用的备份文件")
        }
        val version = root.optInt("version")
        if (version > VERSION) {
            return@withContext Result(false, "备份来自更新的版本（v$version），请先升级应用")
        }

        val db = XhsDatabase.get(context)
        // used for the settings below only — the account is deliberately left alone
        val store = CredentialStore(context)
        var counts = StringBuilder()

        // The `account` block of an older backup is ignored ON PURPOSE: a restore keeps
        // the account the app is already using (see the class comment).

        // ---- settings ----
        root.optJSONObject("settings")?.let { s ->
            s.optString("theme").takeIf { it.isNotBlank() }?.let { k ->
                // setThemeMode also persists, so restore survives a restart
                App.INSTANCE.setThemeMode(
                    ThemeMode.entries.firstOrNull { it.key == k } ?: ThemeMode.SYSTEM
                )
            }
            // 只有键存在才写：`autoVip` 是"VIP 到期自动切换"的总开关，缺键时无条件写 false
            // 会**静默关掉核心机制**（见 docs/REVIEW.md 附录C-P1-9）。
            if (s.has("autoVip")) store.autoSwitchOnVipExpiry = s.optBoolean("autoVip")
            // only trust these when the key is actually present, so restoring an
            // older backup (which lacked them) does not zero the user's settings
            if (s.has("historyLimit")) store.historyLimit = s.optInt("historyLimit")
            // 不再从备份恢复 vipEnd：导出从来不写这个字段，所以它只可能来自旧版或被手工改过的
            // 文件；写进去等于让外部文件决定"VIP 是否还有效"，填一个大值就能让自动换号**永久停摆**
            // （见 docs/REVIEW.md 附录A-P0-7）。VIP 缓存只允许由 myProfile() 写入。
            if (s.has("biometricLock")) {
                // Enabling the app lock on a device where no biometric/lock screen
                // is enrolled would lock the user out of their own app, so the
                // preference is restored only where it can actually be honoured.
                store.biometricLock = s.optBoolean("biometricLock") &&
                    BiometricLockAvailable(context)
            }
            counts.append("设置 ")
        }

        root.optJSONArray("searchHistory")?.let { arr ->
            restoreSearchHistory(context, arr)
            counts.append("搜索记录 ")
        }

        root.optJSONObject("webdav")?.let { w ->
            val url = w.optString("url")
            if (url.isNotBlank()) {
                WebDavClient.save(
                    context,
                    WebDavClient.WebDavConfig(
                        url = url,
                        user = w.optString("user"),
                        password = w.optString("password")
                    )
                )
                counts.append("WebDAV ")
            }
        }

        // 空/残缺备份 + 覆盖 = 让用户白清一次库（旧代码还会回一个"成功"）。
        // 这里先看有没有任何可恢复的内容，没有就中止（docs/REVIEW.md 附录C-P1-10）。
        val hasPayload = listOf("saved", "history", "followed", "watchLater", "settings", "searchHistory")
            .any { key -> root.optJSONArray(key)?.length() ?: 0 > 0 || root.optJSONObject(key) != null }
        if (!hasPayload) {
            return@withContext Result(false, "备份里没有可恢复的内容（可能已损坏）")
        }

        // 覆盖模式：清空 + 写入必须在**同一个事务**里。原来是一串独立的 suspend 写，
        // 中途进程被杀/磁盘满就会停在"已清空、没写回"的状态（见 docs/REVIEW.md 附录C-P0-3）。
        // 计数放在事务内累加，事务提交后才有意义。
        var saved = 0
        var hist = 0
        var follows = 0
        var queued = 0
        var skipped = 0
        db.withTransaction {
            if (!merge) {
                db.savedDao().clearAll()
                db.historyDao().clearAll()
                db.followDao().clearLocal()
                // 队列之前不在 clear 列表里：覆盖恢复后旧队列会和新数据混在一起
                db.watchLaterDao().clearAll()
            }

            root.optJSONArray("saved")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = validRow(arr.optJSONObject(i)) ?: run { skipped++; null }
                    if (row == null) continue
                    db.savedDao().upsert(
                        SavedNoteEntity(
                            noteId = row.noteId,
                            title = row.title,
                            userName = row.userName,
                            cover = row.cover,
                            noteType = row.noteType,
                            rawJson = row.rawJson,
                            savedAt = row.savedAt
                        )
                    )
                    saved++
                }
            }

            root.optJSONArray("history")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = validRow(arr.optJSONObject(i)) ?: run { skipped++; null }
                    if (row == null) continue
                    db.historyDao().upsert(
                        HistoryEntity(
                            noteId = row.noteId,
                            title = row.title,
                            userName = row.userName,
                            cover = row.cover,
                            noteType = row.noteType,
                            rawJson = row.rawJson,
                            viewedAt = row.viewedAt
                        )
                    )
                    hist++
                }
            }
            db.historyDao().trim(store.historyLimit)

            root.optJSONArray("followed")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optInt("userId") <= 0) { skipped++; continue }
                    db.followDao().upsert(
                        FollowedEntity(
                            userId = o.optInt("userId"),
                            userName = o.optString("userName"),
                            headImg = o.optString("headImg"),
                            signature = o.optString("signature"),
                            followedAt = o.optLong("followedAt")
                        )
                    )
                    follows++
                }
            }

            // 队列：position 直接沿用备份里的顺序（队列不提供排序，顺序就是加入顺序）
            root.optJSONArray("watchLater")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = validRow(arr.optJSONObject(i)) ?: run { skipped++; null }
                    if (row == null) continue
                    val o = arr.optJSONObject(i)
                    db.watchLaterDao().upsert(
                        WatchLaterEntity(
                            noteId = row.noteId,
                            title = row.title,
                            userName = row.userName,
                            cover = row.cover,
                            noteType = row.noteType,
                            rawJson = row.rawJson,
                            position = o?.optInt("position") ?: i,
                            addedAt = o?.optLong("addedAt") ?: 0L
                        )
                    )
                    queued++
                }
            }
        }

        val tail = if (skipped > 0) "｜跳过无效 $skipped" else ""
        return@withContext Result(
            true,
            "已恢复${counts}｜收藏 $saved｜浏览 $hist｜关注 $follows｜队列 $queued$tail"
        )
    }

    /**
     * 校验一条笔记记录：`noteId > 0` 且 `rawJson` 能被解析成 JSON 对象。
     *
     * 不校验的后果是实打实的崩：列表面板会 `NoteItem(JSONObject(rawJson))`，
     * 而 `JSONObject("")` 抛 `JSONException`，那条异常在 `viewModelScope.launch` 里没人接
     * （见 docs/REVIEW.md 附录A-P0-8）。
     */
    private fun validRow(o: JSONObject?): Row? {
        if (o == null) return null
        val noteId = o.optLong("noteId")
        val raw = o.optString("rawJson")
        if (noteId <= 0L || raw.isBlank()) return null
        val ok = runCatching { JSONObject(raw) }.isSuccess
        if (!ok) return null
        return Row(
            noteId = noteId,
            title = o.optString("title"),
            userName = o.optString("userName"),
            cover = o.optString("cover"),
            noteType = o.optInt("noteType"),
            rawJson = raw,
            savedAt = o.optLong("savedAt"),
            viewedAt = o.optLong("viewedAt")
        )
    }

    private data class Row(
        val noteId: Long,
        val title: String,
        val userName: String,
        val cover: String,
        val noteType: Int,
        val rawJson: String,
        val savedAt: Long,
        val viewedAt: Long
    )
}

/** The persisted 主题 key, read from the same prefs the app writes it to. */
private fun themeKey(context: Context): String =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("theme_mode", ThemeMode.SYSTEM.key) ?: ThemeMode.SYSTEM.key

/**
 * Search history lives in its own prefs file (`search_history`) as one
 * newline-joined string — mirroring XhsRepository's format so both agree.
 */
private fun repoSearchHistory(context: Context): List<String> =
    context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
        .getString("history", "")
        ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

private fun restoreSearchHistory(context: Context, arr: JSONArray) {
    val list = (0 until arr.length())
        .mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        .distinct()
        .take(10)   // same cap the app applies when saving
    context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
        .edit { putString("history", list.joinToString("\n")) }
}

/**
 * True when this device can actually present a biometric/passcode prompt.
 *
 * Restoring 指纹解锁 onto a device without one would lock the user out of the
 * app entirely, which is strictly worse than not restoring the setting.
 */
private fun BiometricLockAvailable(context: Context): Boolean =
    runCatching {
        androidx.biometric.BiometricManager.from(context)
            .canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }.getOrDefault(false)
