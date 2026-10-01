package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AuthorInfo
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthorUiState(
    val author: AuthorInfo? = null,
    val followed: Boolean = false,
    val notes: List<NoteItem> = emptyList(),
    val notesLoading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true
)

class AuthorViewModel(
    val userId: Int,
    private val repo: XhsRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthorUiState())
    val ui: StateFlow<AuthorUiState> = _ui.asStateFlow()

    private var page = 0
    private var loading = false

    init { load() }

    fun load() {
        viewModelScope.launch {
            val author = runCatching { repo.authorProfile(userId) }.getOrNull()
            if (author != null) {
                val followed = repo.isFollowed(userId)
                _ui.update { it.copy(author = author, followed = followed) }
            }
        }
        loadMore()
    }

    fun loadMore() {
        if (loading || !_ui.value.hasMore) return
        loading = true
        _ui.update { it.copy(loadingMore = it.notes.isNotEmpty()) }
        viewModelScope.launch {
            val list = runCatching { repo.authorNotes(userId, page + 1) }.getOrDefault(emptyList())
            if (list.isNotEmpty()) page++
            _ui.update {
                it.copy(
                    notes = it.notes + list,
                    notesLoading = false,
                    loadingMore = false,
                    hasMore = list.size >= 10
                )
            }
            loading = false
        }
    }

    fun toggleFollow() {
        val a = _ui.value.author ?: return
        viewModelScope.launch {
            val now = repo.toggleFollowLocal(a.userId, a.userName, a.headImg, a.signature)
            _ui.update { it.copy(followed = now) }
        }
    }
}