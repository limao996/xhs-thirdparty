package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WatchLaterUiState(
    val items: List<NoteItem> = emptyList(),
    val loading: Boolean = true
)

/**
 * 稍后观看队列页。
 *
 * 顺序改动（上移 / 下移 / 删除）都写进本地库，所以观察 [XhsRepository.watchLaterVersion]
 * 再读一次列表就够了 —— 不需要在内存里维护第二份顺序，那份总会和库里不一致。
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
            val items = runCatching { repo.watchLaterList() }.getOrDefault(emptyList())
            _ui.value = WatchLaterUiState(items = items, loading = false)
        }
    }

    /** 长按拖动结束：把新的顺序整段写回去。 */
    fun setOrder(orderedIds: List<Long>) {
        viewModelScope.launch { repo.setWatchLaterOrder(orderedIds) }
    }

    fun remove(noteId: Long) {
        viewModelScope.launch { repo.removeFromWatchLater(noteId) }
    }

    fun clearAll() {
        viewModelScope.launch { repo.clearWatchLater() }
    }
}
