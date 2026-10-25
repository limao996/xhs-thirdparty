package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.SavedSort
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.data.applySavedFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.thirdparty.xhs.common.runCatchingCancellable

/** Local Room-backed list state (shared by Saved and History screens). */
data class LocalListUiState(
    val all: List<NoteItem> = emptyList(),
    val visibleCount: Int = PAGE_SIZE,
    val loading: Boolean = true,
    /** true while a pull-to-refresh is in flight */
    /** bumped on refresh so the grid scrolls back to the top */
    val refreshTick: Int = 0,
    /** 收藏页的搜索词：匹配标题与作者名（最近浏览不用它） */
    val query: String = "",
    /** 收藏页的排序方式，见 [SavedSort] */
    val sort: SavedSort = SavedSort.SAVED_DESC
) {
    /** 过滤 + 排序之后的完整结果（纯函数，见 [applySavedFilter]） */
    val filtered: List<NoteItem> get() = applySavedFilter(all, query, sort)

    /** 当前已展开的部分（客户端分页） */
    val visible: List<NoteItem> get() = filtered.take(visibleCount)

    val hasMore: Boolean get() = visibleCount < filtered.size

    /** 过滤后还剩几条 —— 界面用它区分「还没有收藏」与「没有匹配的收藏」 */
    val resultCount: Int get() = filtered.size

    val searching: Boolean get() = query.isNotBlank()

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
            ids.forEach { runCatchingCancellable { repo.unsave(it) } }
            reload()
        }
    }
    /** Client-side pagination for the local list. */
    fun loadMore() {
        _ui.update {
            // 分页要按「过滤后的总数」算，否则搜索时会把可见条数撑到超出结果集
            val total = it.resultCount
            if (it.visibleCount >= total) it
            else it.copy(visibleCount = (it.visibleCount + LocalListUiState.PAGE_SIZE).coerceAtMost(total))
        }
    }

    /**
     * 收藏页的搜索词（标题 / 作者名的一部分）。
     *
     * 改词就把已展开的条数收回首页大小、并 bump `refreshTick` —— 后者是瀑布流的 `resetKey`，
     * 这样结果集一变列表就回到顶部，而不是停在原来那一条的滚动位置。
     */
    fun onQueryChange(query: String) {
        _ui.update {
            if (it.query == query) it
            else it.copy(
                query = query,
                visibleCount = LocalListUiState.PAGE_SIZE,
                refreshTick = it.refreshTick + 1
            )
        }
    }

    /** 收藏页的排序方式（最近收藏 / 最早收藏 / 标题），同样会重置滚动位置。 */
    fun onSortChange(sort: SavedSort) {
        _ui.update {
            if (it.sort == sort) it
            else it.copy(
                sort = sort,
                visibleCount = LocalListUiState.PAGE_SIZE,
                refreshTick = it.refreshTick + 1
            )
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