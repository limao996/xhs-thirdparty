package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.thirdparty.xhs.common.runCatchingCancellable

data class WatchLaterUiState(
    val items: List<NoteItem> = emptyList(),
    val loading: Boolean = true
)

/**
 * 稍后观看队列页。
 *
 * 队列**不提供排序**（硬约束 20）：按加入时间排列，增删都写进本地库，
 * 观察 [XhsRepository.watchLaterVersion] 再读一次列表就够了 —— 不需要在内存里维护第二份顺序。
 */
class WatchLaterViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(WatchLaterUiState())
    val ui: StateFlow<WatchLaterUiState> = _ui.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            repo.watchLaterVersion.collect { load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            val items = runCatchingCancellable { repo.watchLaterList() }.getOrDefault(emptyList())
            _ui.value = WatchLaterUiState(items = items, loading = false)
        }
    }

    fun remove(noteId: Long) {
        viewModelScope.launch { repo.removeFromWatchLater(noteId) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clearWatchLater() }
    }
}
