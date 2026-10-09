package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.Category
import com.thirdparty.xhs.data.FanGroupAuthor
import com.thirdparty.xhs.data.FollowedEntity
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.data.appendUnique
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.thirdparty.xhs.common.runCatchingCancellable

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
    /**
     * 分类（顶部那一排 chip）加载失败。
     *
     * 以前这里失败是**完全静默**的（`getOrDefault(emptyList())`），于是发现页顶部就是一片空白，
     * 用户只觉得"发现页坏了"却看不到任何提示 —— 用户报的"发现页缺少访问失败提示"。
     */
    val categoriesError: Boolean = false,
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
    val refreshTick: Int = 0,
    /**
     * Bumped whenever the feed content is REPLACED by another list — a category switch.
     *
     * The grid keys its saved scroll position on the feed identity, and a category
     * switch alone would be invisible to that key: 推荐 -> 最新 -> 推荐 is the SAME
     * category id, so the pager page it lives on (which is its own saveable scope,
     * see GOTCHAS C8) handed the OLD offset back and the user landed in the middle
     * of a freshly fetched list whose top they had never seen. A counter that only
     * ever grows makes every "new list" a genuinely new identity.
     *
     * Deliberately NOT bumped by a sub-tab switch or a detail round trip: those must
     * keep the position.
     */
    val feedEpoch: Int = 0
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
            loadCategories()
            myId = runCatchingCancellable { repo.myUserId() }.getOrDefault(0)
            loadFanGroup()
        }
        loadMore()
        refreshFollowed()
        // 网络恢复后补载**失败过**的那几块，而不是"空的"那几块。
        //
        // 用"空"当信号会误伤：某个分类本来就没有内容、或者用户确实还没关注任何人时，
        // 每次网络状态变化都会再取一遍（用户 2026-10-09 报的"频繁重建"）。
        viewModelScope.launch {
            com.thirdparty.xhs.App.INSTANCE.networkEpoch.drop(1).collect {
                if (_ui.value.feed.error && !feedLoading) {
                    feedPage = 0
                    feedLoading = false
                    loadMore(force = true)
                }
                if (_ui.value.fanGroupError && !fanGroupLoading) {
                    // 粉丝圈请求要用自己的 user id，而它只在构造时取一次 —— 离线启动会留在 0，
                    // 为 0 时请求根本不会发（`recs = null`）。这里重读一次，否则网络回来了这个
                    // 标签页还是空的。
                    if (myId <= 0) myId = runCatchingCancellable { repo.myUserId() }.getOrDefault(0)
                    loadFanGroup()
                }
                // 分类：整块失败态时补一次（实测网络恢复 8 秒仍未恢复的那个问题）
                if (_ui.value.categoriesError) loadCategories()
            }
        }
    }

    fun selectCategory(id: Int) {
        if (_ui.value.selectedCategory == id) return
        // feedEpoch grows on every switch so the grid starts the NEW list at the top
        // even when the user comes back to a category they already scrolled.
        _ui.update {
            it.copy(selectedCategory = id, feed = FeedSection(), feedEpoch = it.feedEpoch + 1)
        }
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
            val result = runCatchingCancellable { repo.discoverPage(categoryId = catId, groupId = 0, page = feedPage + 1) }
            val list = result.getOrNull()
            // The user may have moved to another category while this was in flight (the
            // waterfall is a pager now, so swiping makes that easy). Merging the old
            // category's page into the new one would mix them, so drop it — and leave
            // `feedLoading` to whoever started the newer load.
            if (_ui.value.selectedCategory != catId) return@launch
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

    /**
     * Full refresh (the 刷新 FAB): reload the category list, the feed and the fan-group
     * recommendations.
     *
     * Both lists are CLEARED first and put into their loading state, so the refresh is
     * visible: the old grid used to stay on screen until the new one landed, which read as
     * "the button did nothing" (and left the user looking at content they had just asked to
     * replace).
     */
    fun refresh(onDone: (() -> Unit)? = null) {
        feedLoading = true
        fanGroupPage = 0
        emptyFanGroupPages = 0
        fanGroupLoading = false
        _ui.update { s ->
            s.copy(
                // a refresh always returns the user to the top of the list
                refreshTick = s.refreshTick + 1,
                feed = FeedSection(firstLoading = true),
                fanGroup = emptyList(),
                fanGroupLoading = true,
                fanGroupMore = false,
                fanGroupError = false
            )
        }
        viewModelScope.launch {
            val cats = runCatchingCancellable { repo.categories() }.getOrNull()
            val catId = _ui.value.selectedCategory
            val list = runCatchingCancellable { repo.discoverPage(categoryId = catId, groupId = 0, page = 1) }.getOrNull()
            feedPage = if (list != null) 1 else 0
            if (list != null) emptyPages = 0
            _ui.update { s ->
                s.copy(
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
            myId = runCatchingCancellable { repo.myUserId() }.getOrDefault(myId)
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
                runCatchingCancellable { repo.funGroupRecommend(myId, next) }.getOrNull()
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
            val followed = runCatchingCancellable { repo.followedAuthors() }.getOrDefault(emptyList())
            _ui.update { it.copy(followed = followed) }
        }
    }

    /**
     * Re-read the 关注 list (the authors followed on THIS device).
     *
     * Only the local DB is touched — no request — so this is cheap enough to run on every
     * switch to that tab, and it is what keeps the tab current after a follow/unfollow
     * happened elsewhere in the app.
     *
     * 发现 / 粉丝圈 deliberately have NO counterpart any more: switching to them used to
     * re-fetch (a silent feed refresh, another fan-group page). The content is already
     * there and stays there, and if the user wants fresh content the 刷新 FAB is one tap
     * away; auto-refreshing on every tab switch was just network work nobody asked for.
     */
    fun refreshFollowedList() = refreshFollowed()

    /**
     * 取分类那一排 chip。
     *
     * 失败时**保留旧列表**（有就继续用，别把界面清空），并且在"一个都没有"时把
     * `categoriesError` 立起来，让发现页显示可点的失败提示（硬约束 26）。
     */
    private suspend fun loadCategories() {
        val cats = runCatchingCancellable { repo.categories() }.getOrNull()
        _ui.update {
            if (cats == null) {
                it.copy(categoriesError = it.categories.isEmpty() || it.categoriesError)
            } else {
                it.copy(categories = cats, categoriesError = false)
            }
        }
    }

    /** 分类失败后的手动重试（发现页顶部那条提示上的按钮）。 */
    fun retryCategories() {
        _ui.update { it.copy(categoriesError = false) }
        viewModelScope.launch { loadCategories() }
    }
}