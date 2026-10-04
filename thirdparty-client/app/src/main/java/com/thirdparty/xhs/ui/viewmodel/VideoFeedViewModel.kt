package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.data.appendUnique
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state for the immersive short-video feed. */
data class VideoFeedUiState(
    val items: List<NoteItem> = emptyList(),
    val firstLoading: Boolean = false,
    val error: Boolean = false
)

class VideoFeedViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(VideoFeedUiState())
    val ui: StateFlow<VideoFeedUiState> = _ui.asStateFlow()

    private var page = 0
    private var loading = false

    init {
        // A network that only becomes usable later (the user turns a VPN on after the first
        // requests failed) must not leave the feed sitting on 「内容加载失败」 — retry the
        // initial load when nothing arrived.
        viewModelScope.launch {
            com.thirdparty.xhs.App.INSTANCE.networkEpoch.drop(1).collect {
                if (_ui.value.items.isEmpty() && !loading) loadMore()
            }
        }
    }

    fun loadMore(forceRefresh: Boolean = false) {
        if (loading) return
        if (forceRefresh) {
            page = 0
            _ui.update { it.copy(items = emptyList(), firstLoading = true) }
        }
        loading = true
        _ui.update { it.copy(firstLoading = it.items.isEmpty(), error = false) }
        viewModelScope.launch {
            try {
                val list = repo.videoFeedPage(page + 1)
                page++
                _ui.update { s ->
                    s.copy(
                        items = s.items.appendUnique(list),
                        firstLoading = false,
                        error = false
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(firstLoading = false, error = it.items.isEmpty()) }
            } finally {
                loading = false
            }
        }
    }

    /** Refresh from page 1. Keeps the currently playing item on screen while the
     *  request is in flight, and only replaces the list when it succeeds. */
    fun refresh() {
        if (loading) return
        loading = true
        _ui.update { it.copy(firstLoading = it.items.isEmpty(), error = false) }
        viewModelScope.launch {
            val list = runCatching { repo.videoFeedPage(1) }.getOrNull()
            if (list != null) {
                page = 1
                _ui.update {
                    it.copy(
                        items = list,
                        firstLoading = false,
                        error = list.isEmpty()
                    )
                }
            } else {
                _ui.update { it.copy(firstLoading = false, error = it.items.isEmpty()) }
            }
            loading = false
        }
    }

    /**
     * The page actually became the one being watched — record it in 最近浏览.
     * Preloaded neighbours deliberately do NOT get recorded.
     */
    fun recordView(item: NoteItem) {
        viewModelScope.launch { runCatching { repo.recordView(item) } }
    }
}