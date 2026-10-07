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

enum class SearchResultMode { CONTENT, USER }

data class SearchUiState(
    val query: String = "",
    val results: List<NoteItem> = emptyList(),
    val users: List<AuthorInfo> = emptyList(),
    val mode: SearchResultMode = SearchResultMode.CONTENT,
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val empty: Boolean = false,
    /** true when the last request failed (distinct from "no results") */
    val error: Boolean = false,
    val history: List<String> = emptyList(),
    /** true once the user has run at least one search */
    val searched: Boolean = false,
    /** true while a pull-to-refresh is in flight */
    /** bumped on every refresh / new search so the grid scrolls back to the top */
    val refreshTick: Int = 0
)

class SearchViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    private var page = 1
    private var loading = false
    /** short pages are normal upstream — see PagingGuard */
    private val paging = PagingGuard()

    init {
            refreshHistory()
        // 线路在国内、接口在海外：没开 VPN 时首屏必然失败。网络一恢复（开 VPN = 新的默认网络）
        // `App.networkEpoch` 会 +1，这里自动补一次 —— 用户不用手动点重试。
        viewModelScope.launch {
            com.thirdparty.xhs.App.INSTANCE.networkEpoch.drop(1).collect { 
                if (_ui.value.error && _ui.value.query.isNotBlank()) runSearch(_ui.value.query) }
        }

        }

    fun onQueryChange(q: String) { _ui.update { it.copy(query = q) } }

    /**
     * Empty the field **and** drop the results it produced.
     *
     * The clear action used to only set the query to "", which left the previous
     * query's results on screen under an empty field: the page still claimed to be
     * showing search results with nothing to explain what for, and the next typed
     * character appeared to search the old list. 清除 means "back to the initial
     * state", so the results, the searched flag and the paging cursor all go too.
     * The mode (内容/作者) and the local 最近搜索 history are deliberately kept —
     * neither belongs to the query being cleared.
     */
    fun clearQuery() {
        page = 1
        loading = false
        paging.reset()
        _ui.update {
            it.copy(
                query = "",
                results = emptyList(),
                users = emptyList(),
                searched = false,
                searching = false,
                loadingMore = false,
                empty = false,
                error = false,
                hasMore = true,
                // a reset list must start at the top, same as a new search
                refreshTick = it.refreshTick + 1
            )
        }
    }

    fun setMode(mode: SearchResultMode) {
        if (_ui.value.mode == mode) return
        _ui.update { it.copy(
            mode = mode,
            results = emptyList(),
            users = emptyList(),
            empty = false,
            error = false
        ) }
        if (_ui.value.query.isNotBlank()) runSearch(_ui.value.query)
    }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return
        addHistory(q)
        runSearch(q)
    }

    fun chooseHistory(q: String) {
        _ui.update { it.copy(query = q) }
        addHistory(q)
        runSearch(q)
    }

    private fun runSearch(q: String) {
        page = 1
        loading = true
        paging.reset()
        _ui.update {
            it.copy(
                searching = true, empty = false, error = false, hasMore = true,
                results = emptyList(), users = emptyList(), searched = true,
                // new search results start from the top
                refreshTick = it.refreshTick + 1
            )
        }
        viewModelScope.launch {
            // `loading` 必须走 finally 复位：早退分支（下面的两处"query 已变"）会直接
            // return，原来那些路径把 `loading = true` 永久留着，而 `loadMore()` 的第一行
            // 就是 `if (loading) return` —— 结果是**该次搜索之后再也加载不出下一页**
            // （docs/REVIEW.md 附录B-P1-8）。
            try {
                when (_ui.value.mode) {
                    SearchResultMode.CONTENT -> {
                        val list = runCatchingCancellable { repo.searchNote(q, 1) }.getOrNull()
                        // A response that lands after the field was cleared (or after
                        // a newer query was typed) must not repopulate the page it no
                        // longer belongs to — that is exactly how 清除 used to look
                        // broken: it emptied the field, and a second later the old
                        // results were back.
                        if (_ui.value.query.trim() != q) return@launch
                        _ui.update {
                            it.copy(
                                searching = false,
                                results = list ?: it.results,
                                empty = list != null && list.isEmpty(),
                                error = list == null,
                                hasMore = list != null && list.isNotEmpty()
                            )
                        }
                    }
                    SearchResultMode.USER -> {
                        val users = runCatchingCancellable { repo.searchUsers(q, 1) }.getOrNull()
                        if (_ui.value.query.trim() != q) return@launch
                        _ui.update {
                            it.copy(
                                searching = false,
                                users = users ?: it.users,
                                empty = users != null && users.isEmpty(),
                                error = users == null,
                                hasMore = false
                            )
                        }
                    }
                }
            } finally {
                loading = false
            }
        }
    }

    /** Re-run the current query (used by the error state's retry button). */
    fun retry() {
        val q = _ui.value.query.trim()
        if (q.isNotEmpty()) runSearch(q)
    }

    /** Server-side pagination for content results. */
    fun loadMore() {
        val q = _ui.value.query.trim()
        if (loading || q.isEmpty()) return
        if (_ui.value.mode != SearchResultMode.CONTENT) return
        if (!_ui.value.hasMore) return
        loading = true
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val next = page + 1
            val list = runCatchingCancellable { repo.searchNote(q, next) }.getOrNull()
            // 换关键词 / 清空之后，这一页已经不属于当前查询了：必须丢弃，否则旧关键词的第 N 页
            // 会被 append 进新结果里（审计 P2）。runSearch 顶部也有同样的守卫。
            if (_ui.value.query.trim() != q) {
                loading = false
                return@launch
            }
            if (list != null) {
                if (list.isNotEmpty()) page = next
                _ui.update {
                    val before = it.results.size
                    val merged = it.results.appendUnique(list)
                    it.copy(
                        // de-dup: duplicate keys would crash the waterfall grid
                        results = merged,
                        loadingMore = false,
                        hasMore = paging.onPage(list.size, merged.size - before)
                    )
                }
            } else {
                // A failed page must NOT flip hasMore to false — that would end
                // pagination permanently with no way to retry. Leaving hasMore
                // alone lets the next scroll attempt again.
                _ui.update { it.copy(loadingMore = false) }
            }
            loading = false
        }
    }

    private fun refreshHistory() {
        _ui.update { it.copy(history = repo.searchHistory()) }
    }

    /** Clear the local search history (from the "最近搜索" header). */
    fun clearHistory() {
        repo.clearSearchHistory()
        refreshHistory()
    }

    private fun addHistory(q: String) {
        repo.saveSearchHistory(q)
        refreshHistory()
    }
}