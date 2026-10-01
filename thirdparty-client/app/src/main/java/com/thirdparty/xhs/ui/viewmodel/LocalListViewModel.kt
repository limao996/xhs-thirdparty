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
    val loading: Boolean = true
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
            _ui.value = LocalListUiState(all = items, visibleCount = LocalListUiState.PAGE_SIZE, loading = false)
        }
    }

    /** Client-side pagination for the local list. */
    fun loadMore() {
        _ui.update {
            if (it.visibleCount >= it.all.size) it
            else it.copy(visibleCount = (it.visibleCount + LocalListUiState.PAGE_SIZE).coerceAtMost(it.all.size))
        }
    }
}