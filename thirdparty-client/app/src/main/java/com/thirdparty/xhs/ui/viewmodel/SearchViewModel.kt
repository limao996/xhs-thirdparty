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
    val history: List<String> = emptyList()
)

class SearchViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    private var page = 1
    private var loading = false

    init { refreshHistory() }

    fun onQueryChange(q: String) { _ui.value = _ui.value.copy(query = q) }

    fun setMode(mode: SearchResultMode) {
        if (_ui.value.mode == mode) return
        _ui.value = _ui.value.copy(mode = mode, results = emptyList(), users = emptyList(), empty = false)
        if (_ui.value.query.isNotBlank()) runSearch(_ui.value.query)
    }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return
        addHistory(q)
        runSearch(q)
    }

    fun chooseHistory(q: String) {
        _ui.value = _ui.value.copy(query = q)
        addHistory(q)
        runSearch(q)
    }

    private fun runSearch(q: String) {
        page = 1
        loading = true
        _ui.update { it.copy(searching = true, empty = false, hasMore = true, results = emptyList(), users = emptyList()) }
        viewModelScope.launch {
            when (_ui.value.mode) {
                SearchResultMode.CONTENT -> {
                    val list = runCatching { repo.searchNote(q, 1) }.getOrNull() ?: emptyList()
                    _ui.update {
                        it.copy(
                            searching = false, results = list,
                            empty = list.isEmpty(), hasMore = list.size >= 10
                        )
                    }
                }
                SearchResultMode.USER -> {
                    val users = runCatching { repo.searchUsers(q, 1) }.getOrNull() ?: emptyList()
                    _ui.update {
                        it.copy(searching = false, users = users, empty = users.isEmpty(), hasMore = false)
                    }
                }
            }
            loading = false
        }
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
            val list = runCatching { repo.searchNote(q, next) }.getOrNull() ?: emptyList()
            if (list.isNotEmpty()) page = next
            _ui.update {
                it.copy(
                    results = it.results + list,
                    loadingMore = false,
                    hasMore = list.size >= 10
                )
            }
            loading = false
        }
    }

    private fun refreshHistory() {
        _ui.value = _ui.value.copy(history = repo.searchHistory())
    }

    private fun addHistory(q: String) {
        repo.saveSearchHistory(q)
        refreshHistory()
    }
}