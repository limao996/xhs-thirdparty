package com.thirdparty.xhs.data

import android.content.Context
import com.thirdparty.xhs.net.XhsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Coordinates network fetches with the purely local favorite/history storage.
 */
class XhsRepository(context: Context, httpClient: OkHttpClient) {

    private val appContext = context.applicationContext
    private val api by lazy { XhsApi(appContext, httpClient) }
    private val db by lazy { XhsDatabase.get(appContext) }
    private val savedDao get() = db.savedDao()
    private val historyDao get() = db.historyDao()
    private val followDao get() = db.followDao()

    /** Feed page (discover). Throws on network/API error; caller handles state. */
    suspend fun discoverPage(categoryId: Int, groupId: Int, page: Int): List<NoteItem> =
        withContext(Dispatchers.IO) {
            val res = api.call(
                "v2/home/discover-note",
                mapOf("category_id" to categoryId, "group_id" to groupId, "page" to page)
            )
            val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
            (0 until arr.length()).map { NoteItem(arr.optJSONObject(it)) }
        }

    /** Search notes by keyword (v2/search/note-list). */
    suspend fun searchNote(keyword: String, page: Int): List<NoteItem> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            val res = api.call(
                "v2/search/note-list",
                mapOf("q" to keyword, "group_id" to 0, "page" to page)
            )
            val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
            (0 until arr.length()).map { NoteItem(arr.optJSONObject(it)) }
        }

    /** Search authors (v2/search/user-list). */
    suspend fun searchUsers(keyword: String, page: Int): List<AuthorInfo> =
        withContext(Dispatchers.IO) {
            if (keyword.isBlank()) return@withContext emptyList()
            val res = api.call("v2/search/user-list", mapOf("q" to keyword, "page" to page))
            val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
            val out = mutableListOf<AuthorInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    AuthorInfo(
                        userId = o.optInt("user_id"),
                        userName = o.optString("user_name"),
                        headImg = o.optString("user_head_img"),
                        signature = "",
                        vpStatus = o.optInt("user_vp_status"),
                        isFollow = o.optInt("is_follow") == 1
                    )
                )
            }
            out
        }

    /** Page of PLAYABLE short-video items for the immersive feed.
     * Feed summaries lack the HLS url, so video items are hydrated via note/view
     * (also records browsing history). Non-video entries are filtered out.
     */
    suspend fun videoFeedPage(page: Int, perNote: Int = 6): List<NoteItem> =
        withContext(Dispatchers.IO) {
            val summaries = discoverPage(0, 0, page)
            if (summaries.isEmpty()) return@withContext emptyList()

            // Hydrate ALL summaries in parallel (previously sequential, which
            // made the first feed load take ~10 round-trips and show a spinner).
            val resolved = coroutineScope {
                summaries.map { s ->
                    async { s to runCatching { api.call("v2/note/view", mapOf("note_id" to s.noteId)) }.getOrNull() }
                }.awaitAll()
            }

            val videos = mutableListOf<NoteItem>()
            for ((_, detail) in resolved) {
                if (videos.size >= perNote) break
                val data = detail?.optJSONObject("data") ?: continue
                if (data.optString("note_media_url").isNotEmpty()) videos.add(NoteItem(data))
            }

            // NOTE: no history write here. A page is fetched (and neighbours are
            // preloaded) before the user watches anything, so recording the whole
            // page would pollute 最近浏览 with unwatched videos. History is
            // recorded in recordView() when a page actually becomes current.
            videos
        }

    /**
     * Record that the user actually watched [item] (the feed's current page, or
     * a note they opened). This is what 最近浏览 should reflect.
     */
    suspend fun recordView(item: NoteItem) = withContext(Dispatchers.IO) {
        historyDao.upsert(
            HistoryEntity(
                noteId = item.noteId,
                title = item.title,
                userName = item.userName,
                cover = item.cover,
                noteType = item.noteType,
                rawJson = item.rawJson,
                viewedAt = System.currentTimeMillis()
            )
        )
        historyDao.trim()
    }

    /**
     * Open a note detail over the network. On success the snapshot is written
     * into local browsing history (so opening again later can work offline).
     */
    suspend fun fetchDetail(noteId: Long): NoteItem? = withContext(Dispatchers.IO) {
        val res = api.call("v2/note/view", mapOf("note_id" to noteId))
        if (res.optInt("result") != 1) return@withContext null
        val data = res.optJSONObject("data") ?: return@withContext null
        val item = NoteItem(data)
        historyDao.upsert(
            HistoryEntity(
                noteId = item.noteId,
                title = item.title,
                userName = item.userName,
                cover = item.cover,
                noteType = item.noteType,
                rawJson = item.rawJson,
                viewedAt = System.currentTimeMillis()
            )
        )
        historyDao.trim()
        item
    }

    /** Load a detail from the local cache only (works offline for browsed/saved). */
    suspend fun cachedDetail(noteId: Long): NoteItem? = withContext(Dispatchers.IO) {
        val h = historyDao.byId(noteId)
        val s = savedDao.byId(noteId)
        val snap = when {
            h != null && h.rawJson.isNotEmpty() -> h.rawJson
            s != null && s.rawJson.isNotEmpty() -> s.rawJson
            else -> return@withContext null
        }
        NoteItem(JSONObject(snap))
    }

    // ---- favorites, purely local ------------------------------------------
    suspend fun isSaved(noteId: Long): Boolean =
        withContext(Dispatchers.IO) { savedDao.byId(noteId) != null }

    suspend fun save(item: NoteItem) = withContext(Dispatchers.IO) {
        savedDao.upsert(
            SavedNoteEntity(
                noteId = item.noteId,
                title = item.title,
                userName = item.userName,
                cover = item.cover,
                noteType = item.noteType,
                rawJson = item.rawJson,
                savedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun unsave(noteId: Long) = withContext(Dispatchers.IO) { savedDao.remove(noteId) }

    /** Toggle a localStorage favorite; returns the new saved state. */
    suspend fun toggleSaveLocal(item: NoteItem): Boolean =
        withContext(Dispatchers.IO) {
            if (savedDao.byId(item.noteId) == null) {
                savedDao.upsert(
                    SavedNoteEntity(
                        noteId = item.noteId,
                        title = item.title,
                        userName = item.userName,
                        cover = item.cover,
                        noteType = item.noteType,
                        rawJson = item.rawJson.ifEmpty { fullSnapshot(item).toString() },
                        savedAt = System.currentTimeMillis()
                    )
                )
                true
            } else {
                savedDao.remove(item.noteId)
                false
            }
        }

    suspend fun savedList(): List<NoteItem> =
        withContext(Dispatchers.IO) { savedDao.all().map { NoteItem(JSONObject(it.rawJson)) } }

    /** Just the set of locally-saved note ids (for badge lookups). */
    suspend fun savedIds(): Set<Long> =
        withContext(Dispatchers.IO) { savedDao.all().map { it.noteId }.toSet() }

    /** Wipe all local favourites. */
    suspend fun clearSaved() = withContext(Dispatchers.IO) { savedDao.clearAll() }

    // ---- browsing history, purely local ------------------------------------
    suspend fun history(): List<NoteItem> =
        withContext(Dispatchers.IO) { historyDao.recent().map { NoteItem(JSONObject(it.rawJson)) } }

    /** Wipe all local browsing history. */
    suspend fun clearHistory() = withContext(Dispatchers.IO) { historyDao.clearAll() }

    /** Build a full detail JSON snapshot for a summary-level item (used when saving a feed item). */
    private suspend fun fullSnapshot(item: NoteItem): JSONObject {
        // best effort: if we only have the summary, wrap what we have into a
        // detail-shaped object so offline rendering can still show title/cover.
        val detail = fetchDetail(item.noteId)
        return if (detail != null) JSONObject(detail.rawJson) else summaryJson(item)
    }

    private fun summaryJson(item: NoteItem): JSONObject = JSONObject().apply {
        put("note_id", item.noteId)
        put("note_title", item.title)
        put("user_name", item.userName)
        put("note_cover", item.cover)
        put("note_thumbnail", item.thumbnail)
        put("note_type", item.noteType)
        put("like_count", item.likeCount)
        put("collect_count", item.collectCount)
        put("comment_count", item.commentCount)
        put("note_content", item.content)
        put("note_media_url", item.mediaUrl)
    }

    // ---- guest session ------------------------------------------------------
    suspend fun rotateGuest() = withContext(Dispatchers.IO) { api.loginAsGuest() }

    /** Current guest identity (the user_hash the backend echoes for our account). */
    fun currentGuestHash(): String = api.currentUserHash()

    // ---- profile + discover + comments + authors ---------------------------
    /** My own profile incl. VIP status (v2/mine/user-info). */
    suspend fun myProfile(): UserProfile? = withContext(Dispatchers.IO) {
        val res = api.call("v2/mine/user-info", emptyMap())
        if (res.optInt("result") != 1) null
        else UserProfile.from(res.optJSONObject("data") ?: return@withContext null)
    }

    /** Discover category list (v2/home/discover-category). */
    suspend fun categories(): List<Category> = withContext(Dispatchers.IO) {
        val res = api.call("v2/home/discover-category", emptyMap())
        val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
        (0 until arr.length()).map { Category.from(arr.optJSONObject(it)) }
    }

    /** Comments for a note (v2/note-comment/comment-list). */
    suspend fun comments(noteId: Long, page: Int): List<CommentItem> = withContext(Dispatchers.IO) {
        val res = api.call("v2/note-comment/comment-list", mapOf("note_id" to noteId, "page" to page))
        val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
        (0 until arr.length()).map { CommentItem.from(arr.optJSONObject(it)) }
    }

    /** Author's profile (v2/member/user-info). */
    suspend fun authorProfile(userId: Int): AuthorInfo? = withContext(Dispatchers.IO) {
        val res = api.call("v2/member/user-info", mapOf("user_id" to userId))
        res.optJSONObject("data")?.let { AuthorInfo.from(it) }
    }

    /** Author's notes (v2/member/note-list). */
    suspend fun authorNotes(userId: Int, page: Int): List<NoteItem> = withContext(Dispatchers.IO) {
        val res = api.call("v2/member/note-list", mapOf("user_id" to userId, "page" to page))
        val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
        (0 until arr.length()).map { NoteItem(arr.optJSONObject(it)) }
    }

    /** Recommended fan-group authors for the "粉丝圈" tab (v2/member/fun-group-list). */
    suspend fun funGroupRecommend(myUserId: Int): List<AuthorInfo> = withContext(Dispatchers.IO) {
        val res = api.call("v2/member/fun-group-list", mapOf("user_id" to myUserId))
        val data = res.optJSONObject("data")
        val arr = data?.optJSONArray("recommend_list") ?: org.json.JSONArray()
        val out = mutableListOf<AuthorInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optJSONObject("user_info")
            out.add(
                AuthorInfo(
                    userId = o.optInt("user_id"),
                    userName = u?.optString("user_name").orEmpty().ifEmpty { o.optString("user_name") },
                    headImg = u?.optString("user_head_img").orEmpty().ifEmpty { o.optString("user_head_img") },
                    signature = u?.optString("user_signature").orEmpty().ifEmpty { o.optString("user_signature") },
                    vpStatus = o.optInt("user_vp_status"),
                    isFollow = o.optInt("is_follow") == 1
                )
            )
        }
        out
    }

    /** My own numeric guest id (v2/mine/user-info -> user_info.user_id). */
    suspend fun myUserId(): Int = withContext(Dispatchers.IO) {
        runCatching { myProfile()?.userId ?: 0 }.getOrDefault(0)
    }

    // ---- local follow (关注 · 本地) --------------------------------------
    suspend fun isFollowed(uid: Int): Boolean =
        withContext(Dispatchers.IO) { followDao.exists(uid) > 0 }

    suspend fun toggleFollowLocal(uid: Int, name: String, head: String, signature: String): Boolean =
        withContext(Dispatchers.IO) {
            if (followDao.exists(uid) == 0) {
                followDao.upsert(FollowedEntity(uid, name, head, signature))
                true
            } else {
                followDao.remove(uid); false
            }
        }

    suspend fun followedAuthors(): List<FollowedEntity> =
        withContext(Dispatchers.IO) { followDao.all() }

    // ---- local search history ---------------------------------------------
    fun searchHistory(): List<String> {
        val s = appContext.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)
        return s.getString("history", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }

    fun saveSearchHistory(q: String) {
        val s = appContext.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)
        val list = (listOf(q) + searchHistory().filter { it != q }).take(10)
        s.edit().putString("history", list.joinToString("\n")).apply()
    }

    /** Wipe the locally stored search history. */
    fun clearSearchHistory() {
        appContext.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)
            .edit().remove("history").apply()
    }
}