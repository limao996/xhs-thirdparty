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
    val hasMore: Boolean = true,
    /** true when the profile request failed */
    val profileError: Boolean = false,
    /** true when the works request failed and there is nothing to show */
    val notesError: Boolean = false,
    /** true while a pull-to-refresh is in flight */
    val refreshing: Boolean = false
)

class AuthorViewModel(
    val userId: Int,
    private val repo: XhsRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthorUiState())
    val ui: StateFlow<AuthorUiState> = _ui.asStateFlow()

    private var page = 0
    private var loading = false

    /** A userId of 0 means the caller had no usable author id (e.g. a note
     *  whose payload lacked `user_id`); there is nothing to fetch. */
    val validUserId: Boolean get() = userId > 0

    init { if (validUserId) load() }

    fun load() {
        if (!validUserId) {
            _ui.update { it.copy(notesLoading = false, profileError = true) }
            return
        }
        viewModelScope.launch {
            val author = runCatching { repo.authorProfile(userId) }.getOrNull()
            if (author != null) {
                _ui.update {
                    it.copy(
                        author = author,
                        followed = repo.isFollowed(userId),
                        profileError = false
                    )
                }
            } else {
                _ui.update { it.copy(profileError = it.author == null) }
            }
        }
        loadMore()
    }

    /** Retry after a failure (profile and/or works). */
    fun retry() {
        page = 0
        loading = false
        _ui.update { it.copy(profileError = false, notesError = false, notes = emptyList(), hasMore = true) }
        load()
    }

    /**
     * Pull-to-refresh: reload the profile and the first page of works, replacing
     * the list on success and keeping it on failure.
     */
    fun refresh() {
        page = 0
        loading = false
        _ui.update { it.copy(refreshing = true, notesError = false, profileError = false, hasMore = true) }
        viewModelScope.launch {
            val author = runCatching { repo.authorProfile(userId) }.getOrNull()
            _ui.update {
                if (author != null) {
                    it.copy(author = author, followed = repo.isFollowed(userId), profileError = false)
                } else {
                    it.copy(profileError = it.author == null)
                }
            }
        }
        viewModelScope.launch {
            val list = runCatching { repo.authorNotes(userId, 1) }.getOrNull()
            if (list != null) {
                if (list.isNotEmpty()) page = 1
                _ui.update {
                    it.copy(
                        notes = list,
                        notesLoading = false,
                        refreshing = false,
                        hasMore = list.size >= 10,
                        notesError = false
                    )
                }
            } else {
                _ui.update { it.copy(refreshing = false, notesLoading = false, notesError = it.notes.isEmpty()) }
            }
        }
    }

    fun loadMore() {
        if (!validUserId) return
        if (loading || !_ui.value.hasMore) return
        loading = true
        _ui.update { it.copy(loadingMore = it.notes.isNotEmpty(), notesError = false) }
        viewModelScope.launch {
            val list = runCatching { repo.authorNotes(userId, page + 1) }.getOrNull()
            if (list != null) {
                if (list.isNotEmpty()) page++
                _ui.update {
                    it.copy(
                        notes = it.notes + list,
                        notesLoading = false,
                        loadingMore = false,
                        hasMore = list.size >= 10,
                        notesError = false
                    )
                }
            } else {
                _ui.update {
                    it.copy(
                        notesLoading = false,
                        loadingMore = false,
                        notesError = it.notes.isEmpty()
                    )
                }
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