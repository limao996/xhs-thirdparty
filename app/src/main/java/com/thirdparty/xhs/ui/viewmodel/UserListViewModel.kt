package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AccountUser
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which server-backed account list to show. */
enum class UserListMode { FOLLOWING, FANS }

data class UserListUiState(
    val users: List<AccountUser> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
    val hasMore: Boolean = true
)

/**
 * Backs the 关注 / 粉丝 lists reached from the profile card.
 *
 * The two endpoints are named the opposite way round from their labels — see
 * [XhsRepository.followList] — so the mapping lives in one place here.
 */
class UserListViewModel(
    private val repo: XhsRepository,
    val mode: UserListMode,
    val userId: Int
) : ViewModel() {

    private val _ui = MutableStateFlow(UserListUiState())
    val ui: StateFlow<UserListUiState> = _ui.asStateFlow()

    private var page = 0
    private var loading = false
    private var emptyPages = 0

    init { load(reset = true) }

    fun load(reset: Boolean = false) {
        if (loading) return
        val next = if (reset) 1 else page + 1
        if (!reset && !_ui.value.hasMore) return
        loading = true
        _ui.update {
            it.copy(
                loading = reset && it.users.isEmpty(),
                loadingMore = !reset && it.users.isNotEmpty(),
                error = false
            )
        }
        viewModelScope.launch {
            val batch = runCatching { fetch(next) }.getOrNull()
            if (batch != null) {
                page = next
                _ui.update { s ->
                    val before = s.users.size
                    val merged = (s.users + batch).distinctBy { it.userId }
                    emptyPages = if (merged.size == before && !reset) emptyPages + 1 else 0
                    s.copy(
                        users = merged,
                        loading = false,
                        loadingMore = false,
                        error = false,
                        // an empty page, or two pages adding nothing, ends it
                        hasMore = batch.isNotEmpty() && emptyPages < 2
                    )
                }
            } else {
                _ui.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = it.users.isEmpty()
                    )
                }
            }
            loading = false
        }
    }

    private suspend fun fetch(p: Int): List<AccountUser> = when (mode) {
        UserListMode.FOLLOWING -> repo.followList(userId, p)
        UserListMode.FANS -> repo.fansList(userId, p)
    }
}
