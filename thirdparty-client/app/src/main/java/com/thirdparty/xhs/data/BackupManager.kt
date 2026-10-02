package com.thirdparty.xhs.data

import android.content.Context
import com.thirdparty.xhs.App
import com.thirdparty.xhs.net.CredentialStore
import com.thirdparty.xhs.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export / import of everything the user would miss after a reinstall.
 *
 * Covers the guest account (identity + session + switch history), favourites,
 * recently-viewed and followed authors, plus the two settings that are not
 * derivable. Notes are stored as their **raw JSON**, which is exactly what the
 * detail page needs to re-open them offline, so a restore is lossless without
 * needing a per-field serializer that would drift as NoteItem grows.
 *
 * The WebDAV credentials are deliberately NOT part of the payload: they are
 * transport config, and shipping them inside the file that gets uploaded is a
 * needless way to leak the password.
 */
object BackupManager {

    /** Bumped whenever the payload shape changes; see [restore]. */
    const val VERSION = 2
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
        val gzipped = bytes.size >= 2 &&
            bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return if (gzipped) {
            java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes))
                .use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }

    /** Everything, as pretty-printed JSON. */
    suspend fun export(context: Context): String = withContext(Dispatchers.IO) {
        val db = XhsDatabase.get(context)
        val store = CredentialStore(context)
        val root = JSONObject()

        root.put("app", MARKER)
        root.put("version", VERSION)
        root.put("exportedAt", System.currentTimeMillis() / 1000)

        root.put("account", JSONObject().apply {
            put("identity", store.deviceId)
            put("token", store.userToken)
            put("hash", store.userHash)
            // the full switch list, so the user keeps the accounts they had
            put("history", JSONArray(store.history.map { "${it.identity}|${it.userId}|${it.name}" }))
        })

        root.put("settings", JSONObject().apply {
            put("theme", themeKey(context))
            put("autoVip", store.autoSwitchOnVipExpiry)
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

        root.put("followed", JSONArray().apply {
            db.followDao().all().forEach { e ->
                put(JSONObject().apply {
                    put("userId", e.userId); put("userName", e.userName)
                    put("headImg", e.headImg); put("signature", e.signature)
                    put("followedAt", e.followedAt)
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
     * otherwise the local favourites/history/follows are replaced wholesale.
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
        val store = CredentialStore(context)
        var counts = StringBuilder()

        // ---- account ----
        root.optJSONObject("account")?.let { a ->
            val identity = a.optString("identity")
            if (identity.isNotBlank()) {
                store.setDevice(identity)
                store.userToken = a.optString("token")
                store.userHash = a.optString("hash")
            }
            val hist = a.optJSONArray("history")
            if (hist != null) {
                val lines = (0 until hist.length()).mapNotNull { hist.optString(it).takeIf { s -> s.isNotBlank() } }
                store.replaceHistory(lines)
            }
            counts.append("账号 ")
        }

        // ---- settings ----
        root.optJSONObject("settings")?.let { s ->
            s.optString("theme").takeIf { it.isNotBlank() }?.let { k ->
                // setThemeMode also persists, so restore survives a restart
                App.INSTANCE.setThemeMode(
                    ThemeMode.entries.firstOrNull { it.key == k } ?: ThemeMode.SYSTEM
                )
            }
            store.autoSwitchOnVipExpiry = s.optBoolean("autoVip")
            counts.append("设置 ")
        }

        if (!merge) {
            db.savedDao().clearAll()
            db.historyDao().clearAll()
            db.followDao().clearLocal()
        }

        var saved = 0
        root.optJSONArray("saved")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                db.savedDao().upsert(
                    SavedNoteEntity(
                        noteId = o.optLong("noteId"),
                        title = o.optString("title"),
                        userName = o.optString("userName"),
                        cover = o.optString("cover"),
                        noteType = o.optInt("noteType"),
                        rawJson = o.optString("rawJson"),
                        savedAt = o.optLong("savedAt")
                    )
                )
                saved++
            }
        }

        var hist = 0
        root.optJSONArray("history")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                db.historyDao().upsert(
                    HistoryEntity(
                        noteId = o.optLong("noteId"),
                        title = o.optString("title"),
                        userName = o.optString("userName"),
                        cover = o.optString("cover"),
                        noteType = o.optInt("noteType"),
                        rawJson = o.optString("rawJson"),
                        viewedAt = o.optLong("viewedAt")
                    )
                )
                hist++
            }
        }
        db.historyDao().trim(store.historyLimit)

        var follows = 0
        root.optJSONArray("followed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
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

        Result(true, "已恢复${counts}｜收藏 $saved｜浏览 $hist｜关注 $follows")
    }
}

/** The persisted 主题 key, read from the same prefs the app writes it to. */
private fun themeKey(context: Context): String =
    context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("theme_mode", ThemeMode.SYSTEM.key) ?: ThemeMode.SYSTEM.key