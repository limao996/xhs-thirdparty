@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
            // mode switch (内容 / 作者)
            //
            // Was PrimaryTabRow. Tabs express page-level navigation, and their
            // underline reads as a bigger commitment than a two-way filter on the
            // same result set. Material's component for 2–5 mutually exclusive
            // options is the segmented button — one control, both states visible,
            // and it sits on the same row rather than implying a second screen.
            //
            // (M3 Expressive's ButtonGroup would also fit visually, but its API
            // requires an overflowIndicator because it is built for connected
            // groups that can overflow a toolbar; forcing one in for a fixed pair
            // would be more machinery than the control needs.)
            SingleChoiceSegmentedButtonRow(
                // fillMaxWidth: the row wraps its contents by default, which left a
                // small control stranded at the left of a wide empty area. The two
                // options are equally important, so they get equal halves.
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = Spacing.m, vertical = Spacing.xs)
            ) {
                val modes = listOf(SearchResultMode.CONTENT to "内容", SearchResultMode.USER to "作者")
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                    ) {
                        Text(label)
                    }
                }
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
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

            // weight(1f) gives the results area the REMAINING height, so centred
            // empty/error states stay on screen.
            // imePadding: with enableEdgeToEdge() the window draws behind the IME
            // and android:windowSoftInputMode="adjustResize" no longer shrinks the
            // layout — without this the results / empty state sit behind the
            // keyboard while the user is typing.
            Box(Modifier.fillMaxWidth().weight(1f).imePadding()) {
            when {
                state.searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
                state.error -> EmptyState(
                    title = "搜索失败",
                    modifier = Modifier.fillMaxSize(),
                    description = "请检查网络后重试",
                    actionLabel = "重试",
                    onAction = { viewModel.retry() }
                )
                state.empty -> EmptyState(
                    title = if (state.mode == SearchResultMode.USER) "没有找到相关作者" else "没有找到相关内容",
                    modifier = Modifier.fillMaxSize(),
                    description = "换个关键词试试",
                    icon = Icons.Filled.Search
                )
                // nothing searched yet and nothing in history -> tell the user what to do
                !state.searched && state.history.isEmpty() -> EmptyState(
                    title = "搜索短视频 / 笔记 / 作者",
                    modifier = Modifier.fillMaxSize(),
                    // the 内容 / 作者 tabs sit ABOVE this block, so the old
                    // "在下方切换" pointed the wrong way
                    description = "输入关键词，在上方切换「内容」或「作者」",
                    icon = Icons.Filled.Search
                )
                // No pull-to-refresh here: the list is produced by the query, so
                // the way to refresh it is to search again — an accidental drag
                // just wasted a request.
                state.mode == SearchResultMode.CONTENT && state.results.isNotEmpty() ->
                    XhsWaterfallGrid(
                        items = state.results,
                        onOpenDetail = onOpenDetail,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = Spacing.s, end = Spacing.s, top = Spacing.xs, bottom = Spacing.l
                        ),
                        hasMore = state.hasMore,
                        loadingMore = state.loadingMore,
                        resetKey = state.refreshTick,
                        onLoadMore = { viewModel.loadMore() }
                    )
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