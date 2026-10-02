package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Local Room-backed list state (shared by Saved and History screens). */
data class LocalListUiState(
    val all: List<NoteItem> = emptyList(),
    val visibleCount: Int = PAGE_SIZE,
    val loading: Boolean = true,
    /** true while a pull-to-refresh is in flight */
    /** bumped on refresh so the grid scrolls back to the top */
    val refreshTick: Int = 0
) {
    val visible: List<NoteItem> get() = all.take(visibleCount)
    val hasMore: Boolean get() = visibleCount < all.size

    companion object {
        const val PAGE_SIZE = 20
    }
}

class LocalListViewModel(
    private val repo: XhsRepository,
    private val mode: Mode
) : ViewModel() {

    enum class Mode { SAVED, HISTORY }

    private val _ui = MutableStateFlow(LocalListUiState())
    val ui: StateFlow<LocalListUiState> = _ui.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val items = when (mode) {
                Mode.SAVED -> repo.savedList()
                Mode.HISTORY -> repo.history()
            }
            // Update the DATA only — never shrink how much is visible.
            //
            // This used to assign a whole new state, resetting visibleCount to
            // PAGE_SIZE. Combined with LocalListScreen's LaunchedEffect(mode)
            // re-running on every re-entry, coming back from a note collapsed the
            // list from however far the user had scrolled back down to 20 items,
            // which invalidated the grid's saved scroll position.
            _ui.update { prev ->
                prev.copy(
                    all = items,
                    visibleCount = maxOf(prev.visibleCount, LocalListUiState.PAGE_SIZE),
                    loading = false
                )
            }
        }
    }

    /**
     * Drop the given favourites. Used by the multi-select action in 我的收藏 —
     * one by one, because the DAO exposes a single-id delete and the batch is a
     * handful of tap-selected items at most.
     */
    fun removeSaved(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { runCatching { repo.unsave(it) } }
            reload()
        }
    }
    /** Client-side pagination for the local list. */
    fun loadMore() {
        _ui.update {
            if (it.visibleCount >= it.all.size) it
            else it.copy(visibleCount = (it.visibleCount + LocalListUiState.PAGE_SIZE).coerceAtMost(it.all.size))
        }
    }

    /** Wipe the underlying local table (收藏 / 最近浏览) and reset the list. */
    fun clear() {
        viewModelScope.launch {
            when (mode) {
                Mode.SAVED -> repo.clearSaved()
                Mode.HISTORY -> repo.clearHistory()
            }
            _ui.value = LocalListUiState(all = emptyList(), loading = false)
        }
    }
}