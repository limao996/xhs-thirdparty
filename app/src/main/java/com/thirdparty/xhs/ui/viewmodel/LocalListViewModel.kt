package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.LocalSortField
import com.thirdparty.xhs.data.LocalSortOrder
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.data.applyLocalFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.thirdparty.xhs.common.runCatchingCancellable

/**
 * Local Room-backed list state (shared by Saved and History screens).
 *
 * 性能约定（用户 2026-10-25：「数量多了不要卡顿（尽量异步）」）：
 * - [filtered] 是**算好存下来的结果**，不是组合期现算的派生属性 —— 界面每次重组只读，不重算；
 * - 过滤 + 排序在 [Dispatchers.Default] 上算，算完才写回状态；
 * - [allIds] 与 [all] 一起更新，"全选"不用每次点击再 map 一遍。
 */
data class LocalListUiState(
    val all: List<NoteItem> = emptyList(),
    /** 所有条目的 id，供"全选"用（与 [all] 同批更新） */
    val allIds: Set<Long> = emptySet(),
    /** 过滤 + 排序后的完整结果（后台算好，界面只读） */
    val filtered: List<NoteItem> = emptyList(),
    val visibleCount: Int = PAGE_SIZE,
    val loading: Boolean = true,
    /** true while a pull-to-refresh is in flight */
    /** bumped on refresh so the grid scrolls back to the top */
    val refreshTick: Int = 0,
    /** 搜索词：匹配标题与作者名（收藏 / 最近浏览共用） */
    val query: String = "",
    /** 排序维度，见 [LocalSortField]；默认按时间 */
    val sortField: LocalSortField = LocalSortField.TIME,
    /** 排序方向，默认倒序（时间维度 = 新的在前） */
    val sortOrder: LocalSortOrder = LocalSortOrder.DESC
) {
    /** 当前已展开的部分（客户端分页；只截 20 条，开销可忽略） */
    val visible: List<NoteItem> get() = filtered.take(visibleCount)

    val hasMore: Boolean get() = visibleCount < filtered.size

    /** 过滤后还剩几条 —— 界面用它区分「还没有收藏」与「没有匹配的内容」 */
    val resultCount: Int get() = filtered.size

    val searching: Boolean get() = query.isNotBlank()

    companion object {
        const val PAGE_SIZE = 20
        /** 输入停顿多久才真的去算（打字时不必每个字符都全量排序一次） */
        const val QUERY_DEBOUNCE_MS = 150L
    }
}

class LocalListViewModel(
    private val repo: XhsRepository,
    private val mode: Mode
) : ViewModel() {

    enum class Mode { SAVED, HISTORY }

    private val _ui = MutableStateFlow(LocalListUiState())
    val ui: StateFlow<LocalListUiState> = _ui.asStateFlow()

    /** 正在跑的过滤任务；新输入进来时取消，避免排队做无用功 */
    private var filterJob: Job? = null

    /** 代数号：计算完成时对不上就丢弃结果（避免旧结果覆盖新输入） */
    private var filterGeneration = 0

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true) }
            val items = when (mode) {
                Mode.SAVED -> repo.savedList()
                Mode.HISTORY -> repo.history()
            }
            // id 集合在算过滤的同一条后台线程上顺手建好，别让界面点"全选"时再 map 一遍
            val ids = withContext(Dispatchers.Default) { items.mapTo(HashSet(items.size)) { it.noteId } }
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
                    allIds = ids,
                    visibleCount = maxOf(prev.visibleCount, LocalListUiState.PAGE_SIZE),
                    loading = false
                )
            }
            recompute()
        }
    }

    /**
     * 后台重算「过滤 + 排序」并写回状态。
     *
     * @param debounceMs 输入类变更给一点停顿（见 [LocalListUiState.QUERY_DEBOUNCE_MS]）；
     *   排序/数据变化传 0，立即生效。
     */
    private fun recompute(debounceMs: Long = 0) {
        filterJob?.cancel()
        val generation = ++filterGeneration
        val snapshot = _ui.value
        filterJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val startedAt = System.currentTimeMillis()
            var workerThread = ""
            val result = withContext(Dispatchers.Default) {
                workerThread = Thread.currentThread().name
                applyLocalFilter(snapshot.all, snapshot.query, snapshot.sortField, snapshot.sortOrder)
            }
            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                // 性能取证用：条数 / 过滤后条数 / 计算耗时 / 实际计算线程。
                // 用 `adb logcat -s XhsListPerf` 就能看到"排序在哪个线程、花了多少毫秒"。
                android.util.Log.i(
                    "XhsListPerf",
                    "过滤排序 ${snapshot.all.size} 条 → ${result.size} 条，" +
                        "用时 ${System.currentTimeMillis() - startedAt}ms，线程=$workerThread，" +
                        "维度=${snapshot.sortField} ${snapshot.sortOrder}"
                )
            }
            // 计算期间用户又改了输入 → 这次结果已经过期，丢掉
            if (generation != filterGeneration) return@launch
            _ui.update { prev -> prev.copy(filtered = result) }
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

    /** 最近浏览页的多选删除：一次事务删掉选中的几条。 */
    fun removeHistory(ids: Set<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatchingCancellable { repo.removeHistory(ids) }
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
     * 搜索词（标题 / 作者名的一部分）。
     *
     * 改词就把已展开的条数收回首页大小、并 bump `refreshTick` —— 后者是瀑布流的 `resetKey`，
     * 这样结果集一变列表就回到顶部，而不是停在原来那一条的滚动位置。
     * 计算本身有 150ms 防抖：连续打字时只有最后一次会真的全量过滤 + 排序。
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
        recompute(debounceMs = LocalListUiState.QUERY_DEBOUNCE_MS)
    }

    /** 切换排序维度（时间 / 标题 / 作者 / 点赞量），立即生效并重置滚动位置。 */
    fun onSortFieldChange(field: LocalSortField) {
        _ui.update {
            if (it.sortField == field) it
            else it.copy(
                sortField = field,
                visibleCount = LocalListUiState.PAGE_SIZE,
                refreshTick = it.refreshTick + 1
            )
        }
        recompute()
    }

    /** 切换正序 / 倒序。 */
    fun onToggleSortOrder() {
        _ui.update {
            it.copy(
                sortOrder = if (it.sortOrder == LocalSortOrder.DESC) LocalSortOrder.ASC
                else LocalSortOrder.DESC,
                visibleCount = LocalListUiState.PAGE_SIZE,
                refreshTick = it.refreshTick + 1
            )
        }
        recompute()
    }
}
