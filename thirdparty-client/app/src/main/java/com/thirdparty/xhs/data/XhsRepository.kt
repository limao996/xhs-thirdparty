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
import com.thirdparty.xhs.net.CredentialStore

/**
 * Coordinates network fetches with the purely local favorite/history storage.
 */
class XhsRepository(context: Context, httpClient: OkHttpClient) {

    private val appContext = context.applicationContext
    private val http = httpClient
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

    // ---- on-disk HTTP cache (covers / avatars, up to 64MB) ------------------
    /** Current size of the on-disk image cache in bytes. */
    fun httpCacheSizeBytes(): Long = runCatching {
        http.cache?.let { c ->
            c.flush()
            c.size()
        } ?: 0L
    }.getOrDefault(0L)

    /** Evict every cached image response (memory + disk). */
    fun clearHttpCache() {
        runCatching { http.cache?.evictAll() }
        com.thirdparty.xhs.ui.components.clearImageMemoryCache()
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

    /**
     * Snapshot of a note for local storage (收藏 / 最近浏览).
     *
     * Must carry every field the UI renders from the item itself, otherwise a
     * locally-stored note renders differently from the same note in a live list.
     * It previously omitted `note_cin` and `group_id`, so every saved note was
     * labelled 免费 and fan-group works lost their 粉丝圈 tag, and it omitted
     * `note_cover_size`, so waterfall cells fell back to the default ratio.
     */
    private fun summaryJson(item: NoteItem): JSONObject = JSONObject().apply {
        put("note_id", item.noteId)
        put("user_id", item.userId)
        put("note_title", item.title)
        put("user_name", item.userName)
        put("note_cover", item.cover)
        put("note_thumbnail", item.thumbnail)
        put("note_type", item.noteType)
        put("like_count", item.likeCount)
        put("collect_count", item.collectCount)
        put("comment_count", item.commentCount)
        put("note_cin", item.noteCin)
        put("group_id", item.groupId)
        // re-encoded so NoteItem's ratio parser reads the same format back
        put("note_cover_size", ratioToSize(item.coverRatio))
        put("note_content", item.content)
        put("note_media_url", item.mediaUrl)
        put("share_url", item.shareUrl)
    }

    /** Re-encode an aspect ratio as the "w*h" string NoteItem's parser expects. */
    private fun ratioToSize(ratio: Float): String {
        val r = if (ratio.isFinite() && ratio > 0f) ratio else NoteItem.DEFAULT_COVER_RATIO
        // scale so both sides stay reasonably sized integers
        val w = 1000
        val h = Math.round(w / r).coerceAtLeast(1)
        return "$w*$h"
    }

    /**
     * Log in as one specific identity (manual account switch).
     * Unlike [rotateGuest] this targets a chosen pooled account.
     */
    suspend fun switchGuestTo(mac: String): Boolean = withContext(Dispatchers.IO) {
        api.loginAsDevice(mac).optInt("result") == 1
    }

    /** A brand-new randomly generated identity (the manual switch path). */
    fun freshRandomMac(): String = api.freshRandomMac()

    /** Previously used accounts, most recent first. */
    fun accountHistory(): List<com.thirdparty.xhs.net.HistoryAccount> = api.accountHistory()

    /** Record the account now in use (called after a successful login). */
    suspend fun rememberCurrentAccount() = withContext(Dispatchers.IO) {
        val p = myProfile() ?: return@withContext
        api.rememberAccount(p.userId, p.userName)
    }

    /** Forget one history entry. */
    fun forgetAccount(identity: String) = api.forgetAccount(identity)

    /** Whether to switch accounts once the current VIP window expires. */
    var autoSwitchOnVipExpiry: Boolean
        get() = api.autoSwitchOnVipExpiry
        set(v) { api.autoSwitchOnVipExpiry = v }

    /**
     * Switch to a fresh account that HAS VIP, used when the current one's window
     * has run out and the user enabled the automatic switch.
     *
     * Each newly registered identity is granted a fresh VIP window by the backend
     * (that is why a plain random switch usually lands on VIP already), but this
     * still verifies the new account rather than assuming — if the backend stops
     * handing out VIP, repeated attempts would otherwise silently keep swapping.
     *
     * Returns true when the account actually changed to a VIP one.
     */
    suspend fun switchToVipAccount(): Boolean = withContext(Dispatchers.IO) {
        if (!api.autoSwitchOnVipExpiry) return@withContext false
        val nowS0 = System.currentTimeMillis() / 1000
        // Decide from the CACHED window first. A VIP end does not move on its own,
        // so the 5s poll must not spend a request every time — it only needs the
        // server when the local value says the window has lapsed (or is unknown).
        val cached = api.cachedVipEnd
        if (cached > 0L && cached - nowS0 > VIP_MIN_REMAINING_S) return@withContext false
        val current = myProfile() ?: return@withContext false
        // "有效期不足" covers both an already-expired window and one about to
        // lapse: switching exactly at expiry would drop the user mid-action, so a
        // window with under a minute left counts as insufficient too.
        val nowS = System.currentTimeMillis() / 1000
        val stillEnough = current.isVip &&
            (current.vipEnd <= 0L || (current.vipEnd - nowS) > VIP_MIN_REMAINING_S)
        if (stillEnough) return@withContext false
        repeat(VIP_SWITCH_ATTEMPTS) {
            val id = api.freshRandomMac()
            if (api.loginAsDevice(id).optInt("result") == 1) {
                val next = myProfile()
                if (next?.isVip == true) {
                    runCatching { rememberCurrentAccount() }
                    return@withContext true
                }
            }
        }
        false
    }

    private companion object {
        /** how many fresh accounts to try before giving up on finding VIP */
        const val VIP_SWITCH_ATTEMPTS = 5
        /** a window with less than this much left counts as "insufficient" */
        const val VIP_MIN_REMAINING_S = 60L
    }

    /** The identity currently in use. */
    fun currentDeviceMac(): String = api.currentDeviceMac()

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
            // keep the local VIP cache warm so the auto-switch poll can decide offline
            ?.also { api.cachedVipEnd = it.vipEnd }
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

    /**
     * The reply thread of ONE comment (v2/note-comment/comment-reply-list).
     *
     * Needed because the comment list only embeds a short preview of the replies:
     * `reply_data.data_count` is the true total and `reply_data.list` holds just
     * the first few. The examined client pages this endpoint with
     * `{note_id, parent_comment_id, page}`.
     */
    suspend fun commentReplies(noteId: Long, parentCommentId: Int, page: Int): List<CommentReply> =
        withContext(Dispatchers.IO) {
            val res = api.call(
                "v2/note-comment/comment-reply-list",
                mapOf("note_id" to noteId, "parent_comment_id" to parentCommentId, "page" to page)
            )
            val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
            (0 until arr.length()).map { CommentReply.from(arr.optJSONObject(it)) }
        }

    /** Author's profile (v2/member/user-info). */
    suspend fun authorProfile(userId: Int): AuthorInfo? = withContext(Dispatchers.IO) {
        val res = api.call("v2/member/user-info", mapOf("user_id" to userId))
        res.optJSONObject("data")?.let { AuthorInfo.from(it) }
    }

    /**
     * Accounts this user follows (v2/member/follow-list).
     *
     * Note the naming is the opposite of what the endpoints suggest — confirmed
     * against the examined client, where MyAttentionActivity (我的关注) calls
     * follow-list and MyFansActivity (我的粉丝) calls fun-list:
     *
     *     v2/member/follow-list  ->  关注
     *     v2/member/fun-list     ->  粉丝
     */
    suspend fun followList(userId: Int, page: Int): List<AccountUser> =
        accountUsers("v2/member/follow-list", userId, page)

    /** Accounts following this user (v2/member/fun-list — see [followList]). */
    suspend fun fansList(userId: Int, page: Int): List<AccountUser> =
        accountUsers("v2/member/fun-list", userId, page)

    private suspend fun accountUsers(path: String, userId: Int, page: Int): List<AccountUser> =
        withContext(Dispatchers.IO) {
            // userId <= 0 means "my own list": the examined client omits user_id in
            // that case (it only sends it when viewing somebody else).
            val body = mutableMapOf<String, Any>("page" to page)
            if (userId > 0) body["user_id"] = userId
            val res = api.call(path, body)
            val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { AccountUser.from(it) }
            }
        }

    /** Author's notes (v2/member/note-list). */
    suspend fun authorNotes(userId: Int, page: Int): List<NoteItem> = withContext(Dispatchers.IO) {
        val res = api.call("v2/member/note-list", mapOf("user_id" to userId, "page" to page))
        val arr = res.optJSONObject("data")?.optJSONArray("list") ?: org.json.JSONArray()
        (0 until arr.length()).map { NoteItem(arr.optJSONObject(it)) }
    }

    /**
     * Recommended fan-group authors for the "粉丝圈" tab
     * (v2/member/fun-group-list → recommend_list).
     *
     * Paginated — but on `page_num`, NOT `page`: with `page` the backend ignores
     * the argument and every request returns the same first batch (verified; page
     * 2+ comes back empty). `page_num` returns a fresh batch of 3 authors per
     * page.
     *
     * Each entry carries `user_notes` (total works) and a `note_list` preview of
     * up to 3 works — both are surfaced so the tab shows real content instead of
     * a bare name list.
     */
    suspend fun funGroupRecommend(myUserId: Int, page: Int = 1): List<FanGroupAuthor> =
        withContext(Dispatchers.IO) {
            val res = api.call(
                "v2/member/fun-group-list",
                mapOf("user_id" to myUserId, "page_num" to page)
            )
        val data = res.optJSONObject("data")
        val arr = data?.optJSONArray("recommend_list") ?: org.json.JSONArray()
        val out = mutableListOf<FanGroupAuthor>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val authorId = o.optInt("user_id")
            val authorName = o.optString("user_name")
            val previews = mutableListOf<NoteItem>()
            val noteArr = o.optJSONArray("note_list")
            if (noteArr != null) {
                for (j in 0 until noteArr.length()) {
                    val nn = noteArr.optJSONObject(j) ?: continue
                    val cover = nn.optString("note_cover")
                    if (cover.isEmpty()) continue
                    previews.add(
                        NoteItem(
                            noteId = nn.optLong("note_id"),
                            userId = authorId,
                            title = nn.optString("note_title"),
                            userName = authorName,
                            cover = cover,
                            thumbnail = cover,
                            noteType = nn.optInt("note_type"),
                            // the preview payload carries no engagement counts;
                            // 0 keeps the UI honest (the card hides them anyway)
                            likeCount = 0,
                            collectCount = 0,
                            commentCount = 0,
                            noteCin = nn.optInt("note_cin"),
                            coverRatio = parseRatio(
                                nn.optString("note_cover_size"),
                                NoteItem.DEFAULT_COVER_RATIO
                            ),
                            rawJson = nn.toString()
                        )
                    )
                }
            }
            out.add(
                FanGroupAuthor(
                    userId = authorId,
                    userName = authorName,
                    headImg = o.optString("user_head_img"),
                    noteCount = o.optInt("user_notes"),
                    notes = previews
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