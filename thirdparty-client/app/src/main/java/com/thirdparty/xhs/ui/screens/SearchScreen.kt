package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.data.AuthorInfo
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.FeeBadge
import com.thirdparty.xhs.ui.components.XhsAsyncImage
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.components.PullToRefreshBox
import com.thirdparty.xhs.ui.components.XhsWaterfallGrid
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.theme.Thumb
import com.thirdparty.xhs.ui.viewmodel.SearchResultMode
import com.thirdparty.xhs.ui.viewmodel.SearchViewModel

/** MD3 搜索页：SearchBar + 主题内容/用户切换 + 结果列表（作者可进主页）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenAuthor: (Int) -> Unit,
    viewModel: SearchViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    SearchBar(
                        query = state.query,
                        onQueryChange = { viewModel.onQueryChange(it) },
                        onSearch = { viewModel.search() },
                        active = false,
                        onActiveChange = {},
                        placeholder = { Text("搜索短视频 / 笔记 / 作者") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索") },
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    ) { }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // mode tabs (内容 / 作者) — clean underline style
            PrimaryTabRow(
                selectedTabIndex = if (state.mode == SearchResultMode.CONTENT) 0 else 1,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Tab(
                    selected = state.mode == SearchResultMode.CONTENT,
                    onClick = { viewModel.setMode(SearchResultMode.CONTENT) },
                    text = { Text("内容") }
                )
                Tab(
                    selected = state.mode == SearchResultMode.USER,
                    onClick = { viewModel.setMode(SearchResultMode.USER) },
                    text = { Text("作者") }
                )
            }

            if (state.results.isEmpty() && state.users.isEmpty() && state.history.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.s, top = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "最近搜索",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearHistory() }) { Text("清空") }
                }
                // wrap layout (not horizontal scroll)
                FlowRow(
                    maxItemsInEachRow = Int.MAX_VALUE,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    state.history.forEach { h ->
                        InputChip(
                            selected = false,
                            onClick = { viewModel.chooseHistory(h) },
                            label = { Text(h) }
                        )
                    }
                }
            }

            when {
                state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error -> EmptyState(
                    title = "搜索失败",
                    description = "请检查网络后重试",
                    actionLabel = "重试",
                    onAction = { viewModel.retry() }
                )
                state.empty -> EmptyState(
                    title = if (state.mode == SearchResultMode.USER) "没有找到相关作者" else "没有找到相关内容",
                    description = "换个关键词试试",
                    icon = Icons.Filled.Search
                )
                // nothing searched yet and nothing in history -> tell the user what to do
                !state.searched && state.history.isEmpty() -> EmptyState(
                    title = "搜索短视频 / 笔记 / 作者",
                    description = "在下方切换「内容」或「作者」来搜索",
                    icon = Icons.Filled.Search
                )
                state.mode == SearchResultMode.CONTENT && state.results.isNotEmpty() ->
                    PullToRefreshBox(
                        refreshing = state.refreshing,
                        onRefresh = { viewModel.refresh() }
                    ) {
                        XhsWaterfallGrid(
                            items = state.results,
                            onOpenDetail = onOpenDetail,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = Spacing.s, end = Spacing.s, top = Spacing.xs, bottom = Spacing.l
                            ),
                            hasMore = state.hasMore,
                            loadingMore = state.loadingMore,
                            onLoadMore = { viewModel.loadMore() }
                        )
                    }
                state.mode == SearchResultMode.USER && state.users.isNotEmpty() ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
                    ) {
                        items(state.users, key = { it.userId }) { user ->
                            UserSearchRow(user, onClick = { onOpenAuthor(user.userId) })
                        }
                    }
            }
        }
    }
}

@Composable
private fun UserSearchRow(user: AuthorInfo, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs)
    ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            XhsAvatar(url = user.headImg, contentDescription = user.userName,
                modifier = Modifier.size(AvatarSize.list))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(user.userName, style = MaterialTheme.typography.bodyLarge)
                if (user.signature.isNotBlank()) {
                    Text(user.signature, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(if (user.vpStatus >= 1) "VIP" else "", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}