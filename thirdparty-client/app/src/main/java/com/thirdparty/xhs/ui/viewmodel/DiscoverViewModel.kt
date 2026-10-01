package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AuthorInfo
import com.thirdparty.xhs.data.Category
import com.thirdparty.xhs.data.FollowedEntity
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.data.appendUnique
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Discover sub-tabs at the top of the 发现 screen. */
enum class DiscoverTab(val label: String) {
    FEED("发现"),
    FAN_GROUP("粉丝圈"),
    FOLLOW_LOCAL("关注")
}

/** Waterfall feed for a single category (v2/home/discover-note with category_id). */
data class FeedSection(
    val items: List<NoteItem> = emptyList(),
    val firstLoading: Boolean = false,
    val hasMore: Boolean = true,
    /** true while the next page is in flight (drives the trailing spinner) */
    val loadingMore: Boolean = false,
    /** true when the last load failed — the UI shows a retry affordance */
    val error: Boolean = false
)

data class DiscoverUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategory: Int = 0,
    val feed: FeedSection = FeedSection(),
    val fanGroup: List<AuthorInfo> = emptyList(),
    val fanGroupLoading: Boolean = false,
    val fanGroupError: Boolean = false,
    val followed: List<FollowedEntity> = emptyList(),
    /** true while a pull-to-refresh is in flight */
    val refreshing: Boolean = false
)

class DiscoverViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(DiscoverUiState())
    val ui: StateFlow<DiscoverUiState> = _ui.asStateFlow()

    private var feedPage = 0
    private var feedLoading = false
    private var myId = 0

    init {
        viewModelScope.launch {
            val cats = runCatching { repo.categories() }.getOrDefault(emptyList())
            _ui.update { it.copy(categories = cats) }
            myId = runCatching { repo.myUserId() }.getOrDefault(0)
            loadFanGroup()
        }
        loadMore()
        refreshFollowed()
    }

    fun selectCategory(id: Int) {
        if (_ui.value.selectedCategory == id) return
        _ui.update { it.copy(selectedCategory = id, feed = FeedSection()) }
        feedPage = 0
        feedLoading = false
        loadMore()
    }

    /** Retry after a failure (used by the error state's button). */
    fun retry() {
        if (_ui.value.feed.items.isEmpty()) {
            feedPage = 0
            feedLoading = false
            _ui.update { it.copy(feed = it.feed.copy(error = false, firstLoading = true)) }
            loadMore(force = true)
        } else {
            loadMore(force = true)
        }
    }

    fun loadMore(force: Boolean = false) {
        if (feedLoading || (!force && !_ui.value.feed.hasMore)) return
        feedLoading = true
        _ui.update {
            it.copy(feed = it.feed.copy(
                firstLoading = it.feed.items.isEmpty(),
                // when there is already content we are paginating, so show the
                // trailing spinner instead of the full-screen one
                loadingMore = it.feed.items.isNotEmpty(),
                error = false
            ))
        }
        val catId = _ui.value.selectedCategory
        viewModelScope.launch {
            val result = runCatching { repo.discoverPage(categoryId = catId, groupId = 0, page = feedPage + 1) }
            val list = result.getOrNull()
            if (list != null) {
                feedPage++
                _ui.update { s ->
                    s.copy(feed = s.feed.copy(
                        // page boundaries are not stable upstream; duplicate keys
                        // would crash the staggered grid
                        items = s.feed.items.appendUnique(list),
                        firstLoading = false,
                        loadingMore = false,
                        hasMore = list.size >= 10,
                        error = false
                    ))
                }
            } else {
                // keep whatever we already have; just surface the failure
                _ui.update { s -> s.copy(feed = s.feed.copy(firstLoading = false, loadingMore = false, error = true)) }
            }
            feedLoading = false
        }
    }

    /** Full refresh: reload the feed (for the FAB), category list, fan-group recs & followed. */
    fun refresh(onDone: (() -> Unit)? = null) {
        feedLoading = true
        _ui.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val cats = runCatching { repo.categories() }.getOrNull()
            val catId = _ui.value.selectedCategory
            val list = runCatching { repo.discoverPage(categoryId = catId, groupId = 0, page = 1) }.getOrNull()
            feedPage = if (list != null) 1 else 0
            _ui.update { s ->
                s.copy(
                    refreshing = false,
                    categories = cats ?: s.categories,
                    // only replace the feed when the refresh actually succeeded
                    feed = if (list != null) {
                        s.feed.copy(
                            items = list,
                            firstLoading = false,
                            hasMore = list.size >= 10,
                            error = false
                        )
                    } else {
                        s.feed.copy(firstLoading = false, error = true)
                    }
                )
            }
            myId = runCatching { repo.myUserId() }.getOrDefault(myId)
            loadFanGroup()
            refreshFollowed()
            feedLoading = false
            onDone?.invoke()
        }
    }

    private fun loadFanGroup() {
        _ui.update { it.copy(fanGroupLoading = true, fanGroupError = false) }
        viewModelScope.launch {
            val recs = if (myId > 0) runCatching { repo.funGroupRecommend(myId) }.getOrNull() else null
            _ui.update {
                it.copy(
                    fanGroup = recs ?: it.fanGroup,
                    fanGroupLoading = false,
                    fanGroupError = recs == null
                )
            }
        }
    }

    private fun refreshFollowed() {
        viewModelScope.launch {
            val followed = runCatching { repo.followedAuthors() }.getOrDefault(emptyList())
            _ui.update { it.copy(followed = followed) }
        }
    }

    /**
     * Auto-refresh a specific sub-tab when the user switches to it.
     * Keeps the currently displayed items so switching tabs never flashes empty.
     */
    fun refreshTab(tab: DiscoverTab) {
        when (tab) {
            DiscoverTab.FEED -> {
                if (_ui.value.feed.items.isEmpty()) {
                    feedPage = 0
                    feedLoading = false
                    _ui.update { it.copy(feed = it.feed.copy(firstLoading = true, error = false)) }
                    loadMore(force = true)
                } else {
                    // silent background refresh
                    refresh()
                }
            }
            DiscoverTab.FAN_GROUP -> {
                if (myId <= 0) viewModelScope.launch { myId = runCatching { repo.myUserId() }.getOrDefault(0); loadFanGroup() }
                else loadFanGroup()
            }
            DiscoverTab.FOLLOW_LOCAL -> refreshFollowed()
        }
    }
}