package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AuthorInfo
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.data.appendUnique
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.thirdparty.xhs.common.runCatchingCancellable
import kotlinx.coroutines.flow.drop

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
    /** bumped on refresh so the grid scrolls back to the top */
    val refreshTick: Int = 0
)

class AuthorViewModel(
    val userId: Int,
    private val repo: XhsRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthorUiState())
    val ui: StateFlow<AuthorUiState> = _ui.asStateFlow()

    private var page = 0
    private var loading = false
    /** short pages are normal upstream — see PagingGuard */
    private val paging = PagingGuard()

    /** A userId of 0 means the caller had no usable author id (e.g. a note
     *  whose payload lacked `user_id`); there is nothing to fetch. */
    val validUserId: Boolean get() = userId > 0

    init {
        // 线路在国内、接口在海外：没开 VPN 时首屏必然失败。网络一恢复（开 VPN = 新的默认网络）
        // `App.networkEpoch` 会 +1，这里自动补一次 —— 用户不用手动点重试。
        viewModelScope.launch {
            com.thirdparty.xhs.App.INSTANCE.networkEpoch.drop(1).collect { 
                if (_ui.value.profileError) load() }
        }

        if (validUserId) load()
        // re-read when the follow state changes anywhere; see XhsRepository.followVersion
        viewModelScope.launch {
            if (!validUserId) return@launch
            repo.followVersion.collect {
                _ui.update { it.copy(followed = repo.isFollowed(userId)) }
            }
        }
    }

    fun load() {
        if (!validUserId) {
            _ui.update { it.copy(notesLoading = false, profileError = true) }
            return
        }
        viewModelScope.launch {
            val author = runCatchingCancellable { repo.authorProfile(userId) }.getOrNull()
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

    fun loadMore() {
        if (!validUserId) return
        if (loading || !_ui.value.hasMore) return
        loading = true
        _ui.update { it.copy(loadingMore = it.notes.isNotEmpty(), notesError = false) }
        viewModelScope.launch {
            val list = runCatchingCancellable { repo.authorNotes(userId, page + 1) }.getOrNull()
            if (list != null) {
                if (list.isNotEmpty()) page++
                _ui.update {
                    val before = it.notes.size
                    val merged = it.notes.appendUnique(list)
                    it.copy(
                        // de-dup: the grid keys by noteId and crashes on duplicates
                        notes = merged,
                        notesLoading = false,
                        loadingMore = false,
                        hasMore = paging.onPage(list.size, merged.size - before),
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