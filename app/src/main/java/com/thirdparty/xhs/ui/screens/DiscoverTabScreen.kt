@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.App
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.data.FanGroupAuthor
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.components.ConfirmActionDialog
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.FeeBadge
import com.thirdparty.xhs.ui.components.FollowedAuthorRow
import com.thirdparty.xhs.ui.components.XhsAsyncImage
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.components.XhsWaterfallGrid
import com.thirdparty.xhs.ui.components.rememberNoteActions
import com.thirdparty.xhs.ui.components.rememberNoteFlags
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.BottomNavClearance
import com.thirdparty.xhs.ui.theme.bottomNavClearance
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.DiscoverTab
import com.thirdparty.xhs.ui.viewmodel.DiscoverViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.TextButton
import com.thirdparty.xhs.ui.components.FooterRetry
import com.thirdparty.xhs.ui.theme.Corners

/** Clearance for the floating bottom NavigationBar (see theme/BottomNavClearance). */

/**
 * 发现页：顶部 TabRow（发现 / 粉丝圈 / 关注）+ 右下刷新 FAB。
 * 发现 tab：分类筛选横条 + 瀑布流；粉丝圈：推荐作者；关注：本地已关注作者。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverTabScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenAuthor: (Int) -> Unit,
    /** 稍后观看队列入口（与刷新按钮同处右下角，见 CornerFabStack） */
    onOpenWatchLater: () -> Unit = {},
    viewModel: DiscoverViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(DiscoverTab.FEED) }
    // 子 tab 与分类切换的触感反馈
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()
    val scope = rememberCoroutineScope()

    // Switching sub-tabs no longer refreshes anything.
    //
    // 发现 and 粉丝圈 keep whatever is already loaded (the ViewModel loads both once on
    // entry, and the 刷新 FAB is right there if the user wants new content — re-fetching on
    // every switch was network work nobody asked for). 关注 is the exception: it lists the
    // authors followed on THIS device, so it is re-read on entry — a local DB read, no
    // request — which is what keeps it current after a follow/unfollow elsewhere.
    var firstTabEffect by remember { mutableStateOf(true) }
    LaunchedEffect(tab) {
        if (firstTabEffect) { firstTabEffect = false; return@LaunchedEffect }
        if (tab == DiscoverTab.FOLLOW_LOCAL) viewModel.refreshFollowedList()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                DiscoverTab.entries.forEachIndexed { i, t ->
                    Tab(selected = tab == t, onClick = {
                        if (tab != t) {
                            haptics.tick()
                            tab = t
                        }
                    }, text = { Text(t.label) })
                }
            }
            // weight(1f) so the tab content gets the REMAINING height; a plain
            // fillMaxSize() child of a Column would claim the parent's full
            // height and push centred empty states below the visible area
            Box(Modifier.fillMaxWidth().weight(1f)) {
                // Sub-tab state lives in a holder, NOT in the bare `when` below.
                //
                // Leaving a `when` branch DISCARDS that branch's state (only
                // navigation destinations and SaveableStateProviders keep it), so
                // 发现 ⇄ 粉丝圈 used to rebuild each list from scratch and the scroll
                // position of the tab you came back to was gone. Reusable content
                // keeps the inactive branch's state alive without drawing it. This is
                // the same fix the bottom tabs use in HomeScreen — see the comment
                // there — and it is why both directions of the switch keep the offset.
                val subTabStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                subTabStateHolder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        DiscoverTab.FEED -> FeedTab(state, viewModel, onOpenDetail)
                        DiscoverTab.FAN_GROUP -> FanGroupTab(
                            recommended = state.fanGroup,
                            loading = state.fanGroupLoading,
                            hasMore = state.fanGroupHasMore,
                            loadingMore = state.fanGroupMore,
                            moreError = state.fanGroupError && state.fanGroup.isNotEmpty(),
                            onLoadMore = { viewModel.loadMoreFanGroup() },
                            onRetryMore = { viewModel.loadMoreFanGroup() },
                            onOpenAuthor = onOpenAuthor,
                            onOpenDetail = onOpenDetail,
                            error = state.fanGroupError,
                            onRetry = { viewModel.refresh() },
                            resetKey = state.refreshTick
                        )
                        DiscoverTab.FOLLOW_LOCAL -> FollowedMineTab(state.followed, onOpenAuthor)
                    }
                }
            }
        }
        // includes the live system navigation-bar inset; the fixed token alone left
        // the FAB flush against the floating NavigationBar
        val clear = bottomNavClearance()

        // Refresh FAB + 稍后观看 FAB，竖着叠在右下角（见 CornerFabStack）。
        //
        // 刷新在 关注 子 tab 上不给：那一页列的是本机关注的作者，没有远端可刷新 ——
        // 按钮只会把后面的信息流刷一遍。
        // 两个按钮曾经各画各的又停在同一角落，后画的把前一个盖住了（实测被用户当场抓到）。
        com.thirdparty.xhs.ui.components.CornerFabStack(
            onOpenWatchLater = onOpenWatchLater,
            onRefresh = if (tab != DiscoverTab.FOLLOW_LOCAL) {
                { scope.launch { viewModel.refresh() } }
            } else {
                null
            },
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = Spacing.l, bottom = clear)
        )
    }
}

@Composable
private fun FeedTab(
    state: com.thirdparty.xhs.ui.viewmodel.DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenDetail: (Long) -> Unit
) {
    val clear = com.thirdparty.xhs.ui.theme.bottomNavClearance()
    // 分类切换的触感反馈
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()
    // Category row: kept here so a tap can bring the chosen chip to the middle of
    // the viewport. Selecting a category by tapping a chip is exactly when the row
    // should follow the choice — otherwise the chip the user just picked can sit
    // half off the edge, or the label they need next is out of view.
    val categoryRowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val categoryIndex = state.categories
        .indexOfFirst { it.id == state.selectedCategory }
        .coerceAtLeast(0)
    // The waterfall is a PAGER over the categories, so a left/right swipe moves through
    // them the way the chips suggest. The chip row stays outside the pager: it has its
    // own horizontal drag.
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = categoryIndex,
        pageCount = { state.categories.size }
    )
    // Guards the two-way sync: while WE are the ones moving the pager (a chip tap), the
    // page changes the animation produces must not be fed back as "the user swiped".
    val syncingToChip = remember { androidx.compose.runtime.mutableStateOf(false) }
    // chip 行的可见位置**跟随 pager 的滑动进度**（用户 2026-10-25：「标签栏动画应该跟随滑窗进度」）。
    //
    // 做法：取 pager 的连续位置 `currentPage + currentPageOffsetFraction`（`currentPage` 是**最近的**页，
    // 所以这个值单调、在翻页时不会跳），拆成整数 base + 小数 frac：
    // - base 变了（= pager 正好压到某一页，此时 frac≈0）→ 把行**绝对定位**到该 chip 居中；
    // - base 不变 → 按"相邻两枚 chip 中心的距离 × frac"补一段连续位移（`dispatchRawDelta`）。
    // 于是拖动过程中行是**连续平移**的，不是翻页点跳一下；松手时 frac→0，行自然落回居中位置。
    //
    // `selectedCategory` 进 key 是为了让选中态变化后重新对齐一次：选中会让 FilterChip 的勾选图标
    // 动画入场、宽度变大，居中位置随之偏移（差 16px 左右，肉眼可见）。
    LaunchedEffect(pagerState, categoryRowState, state.selectedCategory) {
        var lastBase = -1
        var appliedExtra = 0f
        val lastIndex = state.categories.lastIndex
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
            .distinctUntilChanged()
            .collect { raw ->
                if (lastIndex < 0) return@collect
                val rawBase = kotlin.math.floor(raw).toInt()
                val base = rawBase.coerceIn(0, lastIndex)
                val frac = raw - rawBase
                if (base != lastBase) {
                    categoryRowState.scrollToItemCentered(base)
                    lastBase = base
                    appliedExtra = 0f
                }
                val step = categoryRowState.adjacentCentreDistance(base)
                if (step != null) {
                    val want = step * frac
                    val delta = want - appliedExtra
                    if (delta != 0f) {
                        categoryRowState.dispatchRawDelta(delta)
                        appliedExtra = want
                        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                            // 取证用：一次慢拖会连续打出 frac 递增的行，说明行是跟着进度走的。
                            // adb logcat -s XhsDiscover
                            android.util.Log.i(
                                "XhsDiscover",
                                "chipRow follow base=" + base + " frac=" + (Math.round(frac * 100) / 100f) +
                                    " step=" + step + " delta=" + (Math.round(delta * 10) / 10f)
                            )
                        }
                    }
                }
            }
    }
    // chip tap -> pager follows (the chip's own onClick already changed the selection)
    LaunchedEffect(categoryIndex, state.categories.size) {
        if (state.categories.isNotEmpty() && pagerState.currentPage != categoryIndex) {
            syncingToChip.value = true
            try {
                pagerState.animateScrollToPage(categoryIndex)
            } finally {
                syncingToChip.value = false
            }
        }
    }
    // pager -> selection，两路触发（2026-10-25 修「滑动切换标签/内容有延迟」）：
    //
    // ① **滑动中**：手势一旦把目标页（`targetPage`）定下来就立刻选中它 —— 网络请求在手指还没抬起来时
    //    就发出去了，松手时内容通常已经在路上，不会再"停一会儿才加载出来"；
    // ② **停稳**：用 `currentPage` 兜底（含系统把动画时长比例关掉、一步到位的情况）。
    //
    // 原来只在①之后（停稳）才选，而选中又才是发请求的起点 —— 于是"松手 → 等选择 → 等网络"两段延迟叠加，
    // 顶上的分类标签也要等停稳才变高亮，看起来"标签栏也慢半拍"。
    //
    // 程序性滚动（点标签 → `animateScrollToPage`）必须由 `syncingToChip` 挡掉：它会把途经的邻页
    // 当成用户的滑动，那正是老 bug「点标签偶尔切到前一个/后一个」。
    LaunchedEffect(pagerState) {
        snapshotFlow {
            Triple(pagerState.currentPage, pagerState.targetPage, pagerState.isScrollInProgress)
        }
            .distinctUntilChanged()
            .collectLatest { (current, target, scrolling) ->
                if (syncingToChip.value) return@collectLatest
                // 滑动中先只在**过了翻页点**（`currentPage` 已经变成新页）时才预取：
                // 更早（手势刚提交 target）也能发请求，但正在滑出的那一页会立刻被换成一圈转圈，
                // 半屏可见地闪一下。翻页点之后旧页基本离场，视觉上干净，而且仍然远早于"停稳"。
                if (scrolling && current != target) return@collectLatest
                val page = current
                val currentUi = viewModel.ui.value
                currentUi.categories.getOrNull(page)?.let { cat ->
                    if (cat.id != currentUi.selectedCategory) {
                        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                            // 取证用：scrolling=true 说明这次选择发生在**滑动动画中**（不是停稳后）。
                            // adb logcat -s XhsDiscover
                            android.util.Log.i(
                                "XhsDiscover",
                                "pager→select page=" + page + " current=" + current +
                                    " target=" + target + " scrolling=" + scrolling +
                                    " t=" + System.currentTimeMillis()
                            )
                        }
                        viewModel.selectCategory(cat.id)
                    }
                }
            }
    }
    if (state.categories.isEmpty()) {
        // 分类还没回来 / 失败了 / 真的没有 —— 这里**必须早退**：
        // 分类内容是一个 `HorizontalPager(pageCount = { categories.size })`，分类为空时它一页都不画，
        // 整块内容区就是空白。但"空白"要分成三种情况处理，缺了第一种就会出现
        // 「一进发现页就显示『没有可用的分类』」的回归：
        Box(
            Modifier.fillMaxSize().padding(bottom = clear),
            contentAlignment = Alignment.Center
        ) {
            when {
                // 1) 还在加载：转圈（进页面时第一次请求就在路上，此时不能下任何结论）
                state.categoriesLoading -> androidx.compose.material3.LoadingIndicator()

                // 2) 加载失败：说清原因，给重试
                state.categoriesError -> EmptyState(
                    title = "分类加载失败",
                    modifier = Modifier.fillMaxSize(),
                    description = "网络连接失败，网络恢复后将自动重试",
                    actionLabel = "重试",
                    onAction = { viewModel.retryCategories() }
                )

                // 3) 服务端确实没有可用分类
                else -> EmptyState(
                    title = "没有可用的分类",
                    modifier = Modifier.fillMaxSize(),
                    description = "请稍后重试",
                    actionLabel = "重试",
                    onAction = { viewModel.retryCategories() }
                )
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        // category chips (horizontal)
        //
        // Fixed height, with the chips centred in it. The categories arrive in a
        // request of their own, so for the first moment this row has no items and
        // collapses to zero — the grid below then jumps down by the row's height
        // when they land, and the row the user is about to tap was not there a
        // frame earlier. Reserving the height costs nothing (a chip plus its
        // 4dp padding is exactly this tall) and removes the shift.
        LazyRow(
            state = categoryRowState,
            modifier = Modifier.fillMaxWidth().height(CategoryRowHeight),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(state.categories, key = { it.id }) { cat ->
                FilterChip(
                    selected = state.selectedCategory == cat.id,
                    onClick = {
                        if (state.selectedCategory != cat.id) {
                            haptics.tick()
                            viewModel.selectCategory(cat.id)
                        }
                    },
                    label = { Text(cat.name) }
                )
            }
        }

        // The category content: a pager so a left/right swipe moves through the
        // categories. Only the SELECTED page renders the real grid — the ViewModel
        // holds one category's feed, so the others are placeholders that turn into
        // content the moment the swipe settles (see the snapshotFlow above).
        androidx.compose.foundation.pager.HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            // a swipe must not be stolen while a category is still loading
            userScrollEnabled = state.categories.size > 1
        ) { page ->
            val cat = state.categories.getOrNull(page)
            if (cat != null && cat.id == state.selectedCategory) {
                FeedCategoryPage(state, viewModel, onOpenDetail, clear)
            } else {
                Box(
                    Modifier.fillMaxSize().padding(bottom = clear),
                    contentAlignment = Alignment.Center
                ) { LoadingIndicator() }
            }
        }
    }
}

/**
 * The content of ONE category page: its loading / error / empty / grid state.
 *
 * Extracted from [FeedTab] when the waterfall became a pager — the page lambda needs
 * the branches (and their early returns) in a composable of its own.
 */
@Composable
private fun FeedCategoryPage(
    state: com.thirdparty.xhs.ui.viewmodel.DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenDetail: (Long) -> Unit,
    clear: androidx.compose.ui.unit.Dp
) {
    if (state.feed.items.isEmpty() && state.feed.firstLoading) {
        // Reserve the floating NavigationBar's height before centring, or the
        // indicator is centred on the full screen and reads as sitting low —
        // the bar covers the bottom ~96dp, so the visible gap above is smaller
        // than the gap below.
        Box(
            Modifier.fillMaxSize().padding(bottom = clear),
            contentAlignment = Alignment.Center
        ) { LoadingIndicator() }
        return
    }

    // load failed and there is nothing to fall back on -> offer a retry
    if (state.feed.items.isEmpty() && state.feed.error) {
        EmptyState(
            title = "内容加载失败",
            modifier = Modifier.fillMaxSize(),
            description = "网络连接失败，网络恢复后将自动重试",
            actionLabel = "重试",
            onAction = { viewModel.retry() }
        )
        return
    }

    if (state.feed.items.isEmpty()) {
        EmptyState(
            title = "这个分类还没有内容",
            modifier = Modifier.fillMaxSize(),
            description = "可切换其他分类",
            icon = Icons.Filled.Search
        )
        return
    }

    // shared masonry grid with endless pagination.
    // No pull-to-refresh: this tab already has a dedicated refresh FAB (see
    // the FloatingActionButton below), so the gesture was pure redundancy and
    // fired accidental requests while scrolling.
    XhsWaterfallGrid(
        items = state.feed.items,
        onOpenDetail = onOpenDetail,
        // 长按菜单：收藏 / 稍后观看（菜单要显示当前状态，所以两个 id 集合都要给）
        flags = rememberNoteFlags(),
        actions = rememberNoteActions(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = Spacing.s, end = Spacing.s, top = Spacing.xs, bottom = clear
        ),
        hasMore = state.feed.hasMore,
        loadingMore = state.feed.loadingMore,
        // 下一页失败要看得见（硬约束 26）
        moreError = state.feed.error,
        // Identity of the list on screen: a refresh, or another category, starts
        // at the top.
        //
        // refreshTick alone does not cover a category switch, and selectedCategory
        // alone does not either: 推荐 -> 最新 -> 推荐 comes back to the SAME id, so
        // the pager page (which is its own saveable scope) restored the offset the
        // user had on 推荐 into the freshly refetched 推荐 list — swiping back
        // landed mid-list of content whose top was never shown. feedEpoch only ever
        // grows, so every visit to a category is a new identity, while a sub-tab
        // switch or a detail round trip keeps the same one and keeps the position.
        resetKey = state.refreshTick to state.feedEpoch,
        onLoadMore = { viewModel.loadMore() }
    )
}

@Composable
private fun FanGroupTab(
    recommended: List<FanGroupAuthor>,
    loading: Boolean,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    /** 下一页失败：底部给重试（见 FooterRetry / 硬约束 26） */
    moreError: Boolean = false,
    onRetryMore: (() -> Unit)? = null,
    onOpenAuthor: (Int) -> Unit,
    onOpenDetail: (Long) -> Unit,
    /** true when the last load failed with nothing to show */
    error: Boolean = false,
    onRetry: () -> Unit = {},
    /** bumped by a refresh; the list scrolls back to the top when it changes */
    resetKey: Int = 0
) {
    val clear = com.thirdparty.xhs.ui.theme.bottomNavClearance()
    // 粉丝圈里的作品长按出菜单（与瀑布流那套一致：收藏 / 稍后观看）
    var menuFor by remember { mutableStateOf<com.thirdparty.xhs.data.NoteItem?>(null) }
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()
    // 作者卡片上的关注按钮：本地关注表 + 版本号驱动刷新（和作者主页/关注列表同一份数据）
    val followVersion by App.repo.followVersion.collectAsStateWithLifecycle()
    var followedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    LaunchedEffect(followVersion) {
        followedIds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            App.repo.followedAuthors().map { it.userId }.toSet()
        }
    }
    val followScope = rememberCoroutineScope()
    // 取消关注前先确认（和作者主页/关注列表一致）
    var confirmUnfollow by remember { mutableStateOf<FanGroupAuthor?>(null) }
    confirmUnfollow?.let { author ->
        com.thirdparty.xhs.ui.components.ConfirmActionDialog(
            title = "取消关注？",
            text = "将不再关注「${author.userName}」。",
            confirmText = "取消关注",
            onConfirm = {
                confirmUnfollow = null
                followScope.launch {
                    App.repo.toggleFollowLocal(
                        author.userId, author.userName, author.headImg, ""
                    )
                }
            },
            onDismiss = { confirmUnfollow = null }
        )
    }
    val noteFlags = com.thirdparty.xhs.ui.components.rememberNoteFlags()
    val noteActions = com.thirdparty.xhs.ui.components.rememberNoteActions()
    menuFor?.let { note ->
        com.thirdparty.xhs.ui.components.NoteActionDialog(
            title = note.title,
            saved = note.noteId in noteFlags.savedIds,
            inWatchLater = note.noteId in noteFlags.watchLaterIds,
            onToggleSave = { noteActions.toggleSave(note) },
            onToggleWatchLater = { noteActions.toggleWatchLater(note) },
            onDismiss = { menuFor = null }
        )
    }
    if (loading && recommended.isEmpty()) {
        // same clearance rule as the feed branch: centre in the space the floating
        // NavigationBar leaves, not in the whole screen
        Box(
            Modifier.fillMaxSize().padding(bottom = clear),
            contentAlignment = Alignment.Center
        ) { LoadingIndicator() }
        return
    }
    if (recommended.isEmpty() && error) {
        // A refresh now CLEARS the list first, so a failed one must say so — otherwise
        // this tab would claim 「暂无推荐粉丝圈」 for what is really a network failure.
        EmptyState(
            title = "内容加载失败",
            modifier = Modifier.fillMaxSize(),
            description = "网络连接失败，网络恢复后将自动重试",
            actionLabel = "重试",
            onAction = onRetry
        )
        return
    }
    if (recommended.isEmpty()) {
        EmptyState(
            title = "暂无推荐粉丝圈",
            modifier = Modifier.fillMaxSize(),
            description = "可在作者主页或作品详情页关注",
            icon = Icons.Filled.Group
        )
        return
    }
    // Scroll position saved against [resetKey]: a refresh (new key) starts the list at
    // the top, while an unchanged key keeps the user's place — which is what makes
    // "open an author / a work and come back" land on the same row again.
    //
    // This was once an unguarded `LaunchedEffect(resetKey) { scrollToItem(0) }`: it
    // also ran on every RE-ENTRY into the composition, so the list kept its restored
    // offset for a frame and was then thrown back to the top. A guard comparing
    // `resetKey` with the key the current position belonged to fixed that half but
    // lost the other: a refresh that happened while the user was on another screen
    // left no trace in a `remember`ed flag, so the stale offset survived it. Grouping
    // the state itself by the key covers both halves.
    val listState = key(resetKey) { rememberLazyListState() }
    // Endless pagination, keyed on the item count so it re-evaluates after every
    // batch — the backend serves only 3 authors per page here.
    if (hasMore) {
        LaunchedEffect(listState, recommended.size, loadingMore) {
            snapshotFlow {
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                total > 0 && last >= total - 2
            }
                .distinctUntilChanged()
                .collect { nearEnd -> if (nearEnd) onLoadMore() }
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = clear)
    ) {
        items(recommended, key = { it.userId }) { a ->
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
                // author header —— 整行可点（进作者主页，带波纹），右侧是关注按钮
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            haptics.tick()
                            onOpenAuthor(a.userId)
                        }
                        .padding(horizontal = Spacing.l),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XhsAvatar(
                        url = a.headImg,
                        contentDescription = a.userName,
                        modifier = Modifier.size(AvatarSize.list)
                    )
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(a.userName, style = MaterialTheme.typography.bodyLarge)
                        if (a.noteCount > 0) {
                            Text(
                                "共 ${a.noteCount} 个作品",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 「去看看」改成关注按钮（与详情页/关注页同一套小号按钮）：
                    // 关注直接生效，**取消关注先弹窗确认**（误触会让作者从关注列表里消失）
                    val followed = a.userId in followedIds
                    com.thirdparty.xhs.ui.components.FollowPill(
                        followed = followed,
                        onClick = {
                            if (followed) {
                                haptics.reject()
                                confirmUnfollow = a
                            } else {
                                haptics.confirm()
                                followScope.launch {
                                    App.repo.toggleFollowLocal(a.userId, a.userName, a.headImg, "")
                                }
                            }
                        }
                    )
                }

                // the author's latest works (previously discarded by the parser)
                if (a.notes.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.s))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
                    ) {
                        a.notes.take(3).forEach { n ->
                            FanGroupNoteCard(
                                item = n,
                                onClick = {
                                    haptics.tick()
                                    onOpenDetail(n.noteId)
                                },
                                // 粉丝圈里的作品也要能长按出菜单（收藏 / 稍后观看）
                                onLongClick = {
                                    haptics.longPress()
                                    menuFor = n
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // keep the last row left-aligned when fewer than 3 notes
                        repeat(3 - a.notes.take(3).size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                HorizontalDivider()
            }
        }
        // trailing row: spinner while the next batch is in flight, or the end note
        if (hasMore) {
            item(key = "__more__") {
                Box(
                    Modifier.fillMaxWidth().padding(Spacing.l),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        // 下一页失败必须能看见（硬约束 26）
                        moreError && !loadingMore -> FooterRetry(onClick = onRetryMore)
                        loadingMore -> LoadingIndicator(Modifier.size(24.dp))
                    }
                }
            }
        } else {
            item(key = "__end__") {
                Box(Modifier.fillMaxWidth().padding(Spacing.l), contentAlignment = Alignment.Center) {
                    Text(
                        "没有更多了",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Compact work preview used inside the 粉丝圈 tab. */
@Composable
private fun FanGroupNoteCard(
    item: com.thirdparty.xhs.data.NoteItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 长按：弹作品菜单（收藏 / 稍后观看），由 [FanGroupTab] 渲染 */
    onLongClick: () -> Unit = {}
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Column {
            Box {
                XhsAsyncImage(
                    url = item.cover,
                    contentDescription = item.title,
                    // A FIXED 3:4 crop, not the note's own ratio.
                    //
                    // Three of these sit side by side under one author, and sizing
                    // each by its own ratio gave three different heights — a ragged
                    // edge the eye reads as broken layout. Front covers of one
                    // author are the one place a uniform thumb grid is right; the
                    // actual ratio is still what the note's own page uses.
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                    contentScale = ContentScale.Crop
                )
                FeeBadge(item, compact = true, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs))
            }
            Text(
                item.title,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                // two lines ALWAYS reserved, so a one-line title does not make its
                // card shorter than its neighbours either
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(Spacing.s)
            )
        }
    }
}

@Composable
private fun FollowedMineTab(
    followed: List<com.thirdparty.xhs.data.FollowedEntity>,
    onOpenAuthor: (Int) -> Unit
) {
    val clear = com.thirdparty.xhs.ui.theme.bottomNavClearance()
    val scope = rememberCoroutineScope()
    // which author is waiting for a 取消关注 confirmation (null = none).
    // Declared before the empty check's early `return` so the dialog survives a
    // recomposition of the list; with no rows there is nothing to confirm anyway.
    var pendingUnfollow by remember {
        mutableStateOf<com.thirdparty.xhs.data.FollowedEntity?>(null)
    }
    if (followed.isEmpty()) {
        EmptyState(
            title = "还没有关注任何作者",
            modifier = Modifier.fillMaxSize(),
            description = "可在作者主页或作品详情页关注",
            icon = Icons.Filled.Group
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        // clear the floating bottom navigation bar
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = clear)
    ) {
        items(followed, key = { it.userId }) { f ->
            // the same row the standalone 我关注的作者 page uses — this tab used to
            // be a read-only copy of it, so the only way to unfollow an author was
            // to leave 发现 and open that page
            FollowedAuthorRow(
                name = f.userName,
                signature = f.signature,
                avatarUrl = f.headImg,
                onClick = { onOpenAuthor(f.userId) },
                // ask first — the row is gone the moment this commits
                onUnfollow = { pendingUnfollow = f }
            )
        }
    }

    pendingUnfollow?.let { f ->
        ConfirmActionDialog(
            title = "取消关注？",
            text = "将不再关注「${f.userName}」。",
            confirmText = "取消关注",
            onConfirm = {
                pendingUnfollow = null
                // The ViewModel collects repo.followVersion, so this both
                // unfollows and refreshes the list it is rendering.
                scope.launch {
                    App.repo.toggleFollowLocal(f.userId, f.userName, f.headImg, f.signature)
                }
            },
            onDismiss = { pendingUnfollow = null }
        )
    }
}

/** Height reserved for the category chip row (a FilterChip plus its padding). */
private val CategoryRowHeight = 48.dp

/**
 * Brings [index] to the middle of the viewport.
 *
 * Two things were wrong before, and both were visible when tapping a chip:
 *
 *  1. It called `animateScrollToItem(index)` first, which aligns the item to the
 *     START — so an already-visible chip slid left and then slid back to the middle.
 *     Reported as "总是把标签移动到左侧再居中". Now an item that is already on screen
 *     is not realigned at all: it just gets the one correction that centres it. An
 *     item that is off screen is placed near the middle instantly (a negative
 *     `scrollOffset` stops short of the start) and then corrected.
 *
 *  2. It measured the chip immediately. Selecting a `FilterChip` ANIMATES a leading
 *     check icon in, so the width at that moment is the narrower pre-selection one
 *     and the correction landed ~13dp off centre — the "居中并没有对齐" half. So the
 *     width is now watched until it stops changing, and only then does the single
 *     animated correction run.
 */
/**
 * 相邻两枚 chip 的**中心距离**（像素）：半宽之和 + 两者的间距。
 *
 * 用来把 pager 的翻页进度（0..1）换算成 chip 行该平移多少 —— 只需要两枚 chip 的宽度与间距，
 * 不需要它们的绝对位置（`LazyRow` 也没法给出屏幕外条目的绝对位置）。任一枚没被布局就返回 null，
 * 调用方跳过这一段的跟随（宁可不动，也不要按错误的比例乱滑）。
 */
private fun androidx.compose.foundation.lazy.LazyListState.adjacentCentreDistance(
    index: Int
): Float? {
    val a = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val b = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index + 1 } ?: return null
    val gap = (b.offset - (a.offset + a.size)).toFloat()
    return (a.size + b.size) / 2f + gap
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToItemCentered(
    index: Int
) {
    val start = layoutInfo.viewportStartOffset
    val end = layoutInfo.viewportEndOffset
    if (end - start <= 0) return
    val viewportCentre = (start + end) / 2
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) {
        scrollToItem(index, -viewportCentre)
    }
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val delta = (info.offset + info.size / 2) - viewportCentre
    if (com.thirdparty.xhs.BuildConfig.DEBUG) {
        android.util.Log.i(
            "XhsDiscover",
            "snap idx=" + index + " offset=" + info.offset + " size=" + info.size +
                " centre=" + viewportCentre + " delta=" + delta
        )
    }
    if (delta != 0) scrollBy(delta.toFloat())
}