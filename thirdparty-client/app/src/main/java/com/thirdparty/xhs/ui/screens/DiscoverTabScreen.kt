package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.data.AuthorInfo
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.FeeBadge
import com.thirdparty.xhs.ui.components.XhsAsyncImage
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.components.XhsWaterfallGrid
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.DiscoverTab
import com.thirdparty.xhs.ui.viewmodel.DiscoverViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Clearance for the floating bottom NavigationBar (80dp bar + margin). */
private val BottomNavClearance = 96.dp

/**
 * 发现页：顶部 TabRow（发现 / 粉丝圈 / 关注）+ 右下刷新 FAB。
 * 发现 tab：分类筛选横条 + 瀑布流；粉丝圈：推荐作者；关注：本地已关注作者。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverTabScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenAuthor: (Int) -> Unit,
    viewModel: DiscoverViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(DiscoverTab.FEED) }
    val scope = rememberCoroutineScope()

    // auto-refresh the sub-tab content whenever the user switches tabs
    // (skip the very first composition — the ViewModel already loads then)
    var firstTabEffect by remember { mutableStateOf(true) }
    LaunchedEffect(tab) {
        if (firstTabEffect) { firstTabEffect = false; return@LaunchedEffect }
        viewModel.refreshTab(tab)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                DiscoverTab.entries.forEachIndexed { i, t ->
                    Tab(selected = tab == t, onClick = { if (tab != t) tab = t }, text = { Text(t.label) })
                }
            }
            when (tab) {
                DiscoverTab.FEED -> FeedTab(state, viewModel, onOpenDetail)
                DiscoverTab.FAN_GROUP -> FanGroupTab(state.fanGroup, state.fanGroupLoading, onOpenAuthor)
                DiscoverTab.FOLLOW_LOCAL -> FollowedMineTab(state.followed, onOpenAuthor)
            }
        }
        // refresh FAB — lifted above the floating bottom navigation bar
        FloatingActionButton(
            onClick = { scope.launch { viewModel.refresh() } },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = Spacing.l, bottom = BottomNavClearance)
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
    }
}

@Composable
private fun FeedTab(
    state: com.thirdparty.xhs.ui.viewmodel.DiscoverUiState,
    viewModel: DiscoverViewModel,
    onOpenDetail: (Long) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // category chips (horizontal)
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Spacing.m, vertical = Spacing.xs
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            items(state.categories, key = { it.id }) { cat ->
                FilterChip(
                    selected = state.selectedCategory == cat.id,
                    onClick = { viewModel.selectCategory(cat.id) },
                    label = { Text(cat.name) }
                )
            }
        }

        if (state.feed.items.isEmpty() && state.feed.firstLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }

        // load failed and there is nothing to fall back on -> offer a retry
        if (state.feed.items.isEmpty() && state.feed.error) {
            EmptyState(
                title = "内容加载失败",
                description = "请检查网络后重试",
                actionLabel = "重试",
                onAction = { viewModel.retry() }
            )
            return
        }

        if (state.feed.items.isEmpty()) {
            EmptyState(
                title = "这个分类还没有内容",
                description = "换一个分类试试",
                icon = Icons.Filled.Search
            )
            return
        }

        // shared masonry grid with endless pagination
        XhsWaterfallGrid(
            items = state.feed.items,
            onOpenDetail = onOpenDetail,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.s, end = Spacing.s, top = Spacing.xs, bottom = BottomNavClearance
            ),
            hasMore = state.feed.hasMore,
            onLoadMore = { viewModel.loadMore() }
        )
    }
}

@Composable
private fun FanGroupTab(
    recommended: List<AuthorInfo>,
    loading: Boolean,
    onOpenAuthor: (Int) -> Unit
) {
    if (loading && recommended.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (recommended.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无推荐粉丝圈，去详情页关注喜欢的作者吧",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(recommended, key = { it.userId }) { a ->
            Surface(onClick = { onOpenAuthor(a.userId) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    XhsAvatar(url = a.headImg, contentDescription = a.userName,
                        modifier = Modifier.size(AvatarSize.list))
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(a.userName, style = MaterialTheme.typography.bodyLarge)
                        if (a.signature.isNotBlank()) {
                            Text(a.signature, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(
                        onClick = { onOpenAuthor(a.userId) },
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) { Text("去看看", Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) }
                }
            }
        }
    }
}

@Composable
private fun FollowedMineTab(
    followed: List<com.thirdparty.xhs.data.FollowedEntity>,
    onOpenAuthor: (Int) -> Unit
) {
    if (followed.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有关注任何作者", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(followed, key = { it.userId }) { f ->
            Surface(onClick = { onOpenAuthor(f.userId) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    XhsAvatar(url = f.headImg, contentDescription = f.userName,
                        modifier = Modifier.size(AvatarSize.list))
                    Spacer(Modifier.width(Spacing.m))
                    Column {
                        Text(f.userName, style = MaterialTheme.typography.bodyLarge)
                        if (f.signature.isNotBlank())
                            Text(f.signature, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}