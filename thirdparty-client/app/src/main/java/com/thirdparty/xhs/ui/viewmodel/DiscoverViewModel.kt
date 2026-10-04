package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AuthorInfo
import com.thirdparty.xhs.data.Category
import com.thirdparty.xhs.data.FanGroupAuthor
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
    val fanGroup: List<FanGroupAuthor> = emptyList(),
    val fanGroupLoading: Boolean = false,
    val fanGroupError: Boolean = false,
    /** true while the next batch of fan-group authors is in flight */
    val fanGroupMore: Boolean = false,
    /** whether another fan-group page exists (the backend serves 3 per page) */
    val fanGroupHasMore: Boolean = true,
    val followed: List<FollowedEntity> = emptyList(),
    /** true while a pull-to-refresh is in flight */
    /** bumped on every refresh so the grid scrolls back to the top */
    val refreshTick: Int = 0
)

class DiscoverViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(DiscoverUiState())
    val ui: StateFlow<DiscoverUiState> = _ui.asStateFlow()

    private var feedPage = 0
    private var feedLoading = false
    /** consecutive pages that added nothing new — two in a row ends pagination */
    private var emptyPages = 0
    /** fan-group authors paginate on `page_num`, 3 per page */
    private var fanGroupPage = 0
    private var fanGroupLoading = false
    private var emptyFanGroupPages = 0
    private var myId = 0

    init {
        // 关注 tab 的列表要跟着其它页面的关注操作走
        viewModelScope.launch { repo.followVersion.collect { refreshFollowed() } }
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
        emptyPages = 0
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
                    val before = s.feed.items.size
                    val merged = s.feed.items.appendUnique(list)
                    val added = merged.size - before
                    // The old rule (hasMore = list.size >= 10) ended pagination
                    // whenever the backend returned a short page — which it does
                    // often, because its page boundaries are not stable. Only an
                    // empty page, or two consecutive pages that add nothing new,
                    // really means "no more".
                    emptyPages = if (added == 0) emptyPages + 1 else 0
                    s.copy(feed = s.feed.copy(
                        // page boundaries are not stable upstream; duplicate keys
                        // would crash the staggered grid
                        items = merged,
                        firstLoading = false,
                        loadingMore = false,
                        hasMore = list.isNotEmpty() && emptyPages < 2,
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
        fanGroupPage = 0
        emptyFanGroupPages = 0
        viewModelScope.launch {
            val cats = runCatching { repo.categories() }.getOrNull()
            val catId = _ui.value.selectedCategory
            val list = runCatching { repo.discoverPage(categoryId = catId, groupId = 0, page = 1) }.getOrNull()
            feedPage = if (list != null) 1 else 0
            if (list != null) emptyPages = 0
            _ui.update { s ->
                s.copy(
                    // a refresh always returns the user to the top of the list
                    refreshTick = s.refreshTick + 1,
                    categories = cats ?: s.categories,
                    // only replace the feed when the refresh actually succeeded
                    feed = if (list != null) {
                        s.feed.copy(
                            items = list,
                            firstLoading = false,
                            hasMore = list.isNotEmpty(),
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

    private fun loadFanGroup(reset: Boolean = true) {
        if (fanGroupLoading) return
        val next = if (reset) 1 else fanGroupPage + 1
        if (!reset && !_ui.value.fanGroupHasMore) return
        fanGroupLoading = true
        _ui.update {
            it.copy(
                fanGroupLoading = it.fanGroup.isEmpty(),
                fanGroupMore = it.fanGroup.isNotEmpty(),
                fanGroupError = false
            )
        }
        viewModelScope.launch {
            val recs = if (myId > 0) {
                runCatching { repo.funGroupRecommend(myId, next) }.getOrNull()
            } else null
            if (recs != null) {
                fanGroupPage = next
                _ui.update { s ->
                    val before = s.fanGroup.size
                    // a RESET (the refresh FAB / re-entering the tab) REPLACES the list.
                    // It used to merge unconditionally, so a refresh appended page 1 to
                    // the pages already on screen: the recommendations came back the
                    // same, the list did not change, and the refresh looked like it did
                    // nothing at all.
                    val merged = if (reset) recs
                    else (s.fanGroup + recs).distinctBy { it.userId }
                    if (reset) emptyFanGroupPages = 0
                    else emptyFanGroupPages =
                        if (merged.size == before) emptyFanGroupPages + 1 else 0
                    s.copy(
                        fanGroup = merged,
                        fanGroupLoading = false,
                        fanGroupMore = false,
                        fanGroupError = false,
                        // a reset also returns the user to the top of the list
                        refreshTick = if (reset) s.refreshTick + 1 else s.refreshTick,
                        // a short page is normal here (3 authors per page) — only an
                        // empty page, or two pages that add nothing, is the end
                        fanGroupHasMore = recs.isNotEmpty() && emptyFanGroupPages < 2
                    )
                }
            } else {
                _ui.update {
                    it.copy(
                        fanGroupLoading = false,
                        fanGroupMore = false,
                        fanGroupError = it.fanGroup.isEmpty()
                    )
                }
            }
            fanGroupLoading = false
        }
    }

    /** Endless scroll from the 粉丝圈 tab. */
    fun loadMoreFanGroup() = loadFanGroup(reset = false)

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