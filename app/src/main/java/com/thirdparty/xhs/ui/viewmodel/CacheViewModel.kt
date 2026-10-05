package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AppCaches
import com.thirdparty.xhs.data.CacheEntry
import com.thirdparty.xhs.data.CacheKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update

data class CacheUiState(
    val entries: List<CacheEntry> = emptyList(),
    val selected: Set<CacheKind> = emptySet(),
    val loading: Boolean = true,
    val clearing: Boolean = false,
    /**
     * Bytes freed by the last clear, waiting to be shown. One-shot: the screen
     * shows it and calls [CacheViewModel.consumeFreed], so a recomposition (or a
     * rotation) cannot show the same message twice.
     */
    val freed: Long? = null
)

/**
 * 清除缓存：把每一项缓存的实际大小量出来，清掉用户勾选的那几项。
 *
 * Everything runs off the main thread — measuring the disk cache flushes the
 * OkHttp journal, and deleting the bitmap LRU touches every cached bitmap.
 */
class CacheViewModel : ViewModel() {

    private val _ui = MutableStateFlow(CacheUiState())
    val ui: StateFlow<CacheUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) { AppCaches.entries() }
            // keep a selection the user still sees on screen; drop the rest
            val alive = _ui.value.selected.intersect(entries.map { it.kind }.toSet())
            _ui.update { it.copy(entries = entries, selected = alive, loading = false) }
        }
    }

    fun toggle(kind: CacheKind) {
        val next = _ui.value.selected.toMutableSet()
        if (!next.add(kind)) next.remove(kind)
        _ui.update { it.copy(selected = next) }
    }

    /**
     * 反选：勾上的取消、没勾的勾上。
     *
     * 用来替代原来的「全选 / 全不选」两个按钮 —— 清缓存时常见的是"除了图片之外都清"，
     * 全选再取消一项要点两次，反选一次就够。
     */
    fun invertSelection() {
        val all = _ui.value.entries.map { it.kind }.toSet()
        _ui.update { it.copy(selected = all - _ui.value.selected) }
    }

    fun clearSelected() {
        val kinds = _ui.value.selected
        if (kinds.isEmpty() || _ui.value.clearing) return
        _ui.update { it.copy(clearing = true) }
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { AppCaches.clear(kinds) }
            val entries = withContext(Dispatchers.IO) { AppCaches.entries() }
            _ui.update { it.copy(
                entries = entries,
                selected = emptySet(),
                clearing = false,
                freed = freed
            ) }
        }
    }

    fun consumeFreed() {
        _ui.update { it.copy(freed = null) }
    }
}
