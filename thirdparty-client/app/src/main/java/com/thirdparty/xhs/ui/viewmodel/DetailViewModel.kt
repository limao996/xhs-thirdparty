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

    init { load() }

    fun load() {
        viewModelScope.launch {
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