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
    val commentsLoading: Boolean = false
)

class DetailViewModel(
    val noteId: Long,
    private val repo: XhsRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

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

    private fun loadComments() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(commentsLoading = true)
            val comments = runCatching { repo.comments(noteId, 1) }.getOrDefault(emptyList())
            _ui.value = _ui.value.copy(comments = comments, commentsLoading = false)
        }
    }

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