package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AuthorInfo
import com.thirdparty.xhs.data.CommentItem
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

data class DetailUiState(
    val item: NoteItem? = null,
    val loading: Boolean = true,
    val saved: Boolean = false,
    val missing: Boolean = false,
    val author: AuthorInfo? = null,
    val followed: Boolean = false,
    val comments: List<CommentItem> = emptyList(),
    val commentsLoading: Boolean = false,
    val commentsHasMore: Boolean = false,
    /** true when the comment request failed (distinct from "no comments") */
    val commentsError: Boolean = false
)

class DetailViewModel(
    val noteId: Long,
    private val repo: XhsRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

    private var commentPage = 0
    private var commentsInFlight = false

    init {
        load()
        // Follow state lives in the local DB, and a deeper page (the author page)
        // can change it while this screen sits on the back stack. Without re-reading,
        // coming back still showed 「+ 关注」 for someone already followed.
        viewModelScope.launch {
            repo.followVersion.collect {
                val uid = _ui.value.author?.userId ?: _ui.value.item?.userId ?: return@collect
                if (uid > 0) _ui.value = _ui.value.copy(followed = repo.isFollowed(uid))
            }
        }
        // Content follows the account.
        //
        // A switch inside the account gate re-issues the media URLs (they are handed out
        // per account), so the page has to be re-fetched — otherwise the video or 图文
        // images kept pointing at what the OLD account was given, which is exactly the
        // "视频/图文也要考虑自动切换" case.
        viewModelScope.launch {
            // drop(1): the flow replays its current value to a new collector, and an
            // epoch left over from an earlier switch must not trigger a second load.
            repo.accountEpoch.drop(1).collect { if (it > 0) load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            // Ask the account gate BEFORE anything is shown.
            //
            // This page can be opened from the cache (最近浏览 / 我的收藏), and then
            // nothing in the path is an API call: the note, its video URL and its 图文
            // images all come straight from the DB, issued for whatever account was in
            // use when they were stored. A lapsed VIP window is exactly what makes those
            // fail, and the gate inside XhsApi never sees it. Asking here means the
            // account is fixed BEFORE the media is played, and the fresh fetch below
            // re-issues the URLs for the new one.
            runCatching { repo.ensureAccountForRequest() }
            val cached = repo.cachedDetail(noteId)
            if (cached != null) {
                _ui.value = DetailUiState(cached, loading = true, saved = repo.isSaved(noteId))
            }
            val fresh = runCatching { repo.fetchDetail(noteId) }.getOrNull()
            if (fresh != null) {
                val saved = repo.isSaved(noteId)
                _ui.value = DetailUiState(fresh, loading = false, saved = saved)
                loadAuthor(fresh.userId)
                loadComments()
            } else if (cached == null) {
                _ui.value = DetailUiState(loading = false, missing = true)
            }
        }
    }

    private fun loadAuthor(uid: Int) {
        if (uid <= 0) return
        viewModelScope.launch {
            val author = runCatching { repo.authorProfile(uid) }.getOrNull()
            if (author != null) {
                val followed = repo.isFollowed(uid)
                _ui.value = _ui.value.copy(author = author, followed = followed)
            }
        }
    }

    private fun loadComments() = fetchComments(reset = true)

    /** Fetch a comments page; [reset] restarts from page 1. */
    fun fetchComments(reset: Boolean = false) {
        if (commentsInFlight) return
        if (!reset && !_ui.value.commentsHasMore) return
        commentsInFlight = true
        if (reset) commentPage = 0
        viewModelScope.launch {
            val next = if (reset) 1 else commentPage + 1
            _ui.value = _ui.value.copy(commentsLoading = true, commentsError = false)
            val page = runCatching { repo.comments(noteId, next) }.getOrNull()
            if (page != null) {
                if (page.isNotEmpty()) commentPage = next
                val merged = if (reset) page else _ui.value.comments + page
                // Comment paging has a better signal than the lists do: the note
                // itself carries the total comment count. Comparing against it is
                // exact, whereas "fewer than 10 items" is wrong both ways — the
                // backend returns short pages mid-list, and a note with fewer
                // than 10 comments would look like it has more.
                val total = _ui.value.item?.commentCount ?: 0
                _ui.value = _ui.value.copy(
                    comments = merged,
                    commentsLoading = false,
                    commentsHasMore = page.isNotEmpty() && (total <= 0 || merged.size < total),
                    commentsError = false
                )
            } else {
                // a failed request must not masquerade as "no comments"
                _ui.value = _ui.value.copy(
                    commentsLoading = false,
                    commentsError = _ui.value.comments.isEmpty()
                )
            }
            commentsInFlight = false
        }
    }

    /** Used by the UI's "load more comments" action. */
    fun loadMoreComments() = fetchComments(reset = false)

    fun toggleFollow() {
        val author = _ui.value.author ?: return
        viewModelScope.launch {
            val now = repo.toggleFollowLocal(
                author.userId, author.userName, author.headImg, author.signature
            )
            _ui.value = _ui.value.copy(followed = now)
        }
    }

    fun toggleSave() {
        val item = _ui.value.item ?: return
        viewModelScope.launch {
            val now = repo.toggleSaveLocal(item)
            _ui.value = _ui.value.copy(saved = now)
        }
    }
}