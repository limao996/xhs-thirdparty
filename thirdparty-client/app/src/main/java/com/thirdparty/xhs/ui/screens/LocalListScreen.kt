package com.thirdparty.xhs.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.App
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.XhsWaterfallGrid
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.LocalListViewModel

/**
 * 收藏 / 最近浏览 —— 瀑布流（2 列 Masonry）+ 客户端「加载更多」。
 * 纯本地 Room 数据。
 *
 * 收藏支持**长按进入多选**批量取消（最近浏览不提供：它没有"取消浏览"的语义）。
 */
@Composable
fun LocalListScreen(
    mode: LocalListViewModel.Mode,
    onOpenDetail: (Long) -> Unit,
    viewModel: LocalListViewModel = viewModel(
        key = mode.name,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                LocalListViewModel(App.repo, mode) as T
        }
    )
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(mode) { viewModel.reload() }

    // Empty set == not selecting. Deriving the mode from the set (rather than a
    // separate boolean) means the two can never disagree, e.g. showing the
    // selection bar with nothing ticked.
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selecting = selected.isNotEmpty()
    val canSelect = mode == LocalListViewModel.Mode.SAVED

    // back gesture leaves selection mode rather than the screen
    BackHandler(enabled = selecting) { selected = emptySet() }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            SelectionBar(
                count = selected.size,
                onSelectAll = { selected = state.all.map { it.noteId }.toSet() },
                onCancel = { selected = emptySet() },
                onDelete = {
                    viewModel.removeSaved(selected)
                    selected = emptySet()
                }
            )
        }

        when {
            state.loading && state.all.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

            state.all.isEmpty() ->
                EmptyState(
                    title = if (mode == LocalListViewModel.Mode.SAVED) "还没有收藏" else "还没有浏览记录",
                    modifier = Modifier.fillMaxSize(),
                    description = if (mode == LocalListViewModel.Mode.SAVED)
                        "在详情页点右上角的心形即可收藏；长按可批量取消"
                    else "看过的内容会自动出现在这里",
                    icon = if (mode == LocalListViewModel.Mode.SAVED) Icons.Filled.FavoriteBorder
                    else Icons.Filled.History
                )

            else ->
                // No pull-to-refresh: this list is local and reloads on entry, so a
                // drag could only misfire.
                XhsWaterfallGrid(
                    items = state.visible,
                    onOpenDetail = onOpenDetail,
                    contentPadding = PaddingValues(
                        start = Spacing.s, end = Spacing.s, top = Spacing.s, bottom = Spacing.l
                    ),
                    hasMore = state.hasMore,
                    resetKey = state.refreshTick,
                    onLoadMore = { viewModel.loadMore() },
                    selectedIds = selected,
                    selectionMode = selecting,
                    onLongPress = if (canSelect) { item ->
                        selected = if (item.noteId in selected) selected - item.noteId
                        else selected + item.noteId
                    } else null
                )
        }
    }
}

/** Toolbar shown while items are ticked. */
@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.s, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "退出多选")
            }
            Text(
                "已选 $count 项",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onSelectAll) { Text("全选") }
            Spacer(Modifier.padding(horizontal = 2.dp))
            TextButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("取消收藏")
            }
        }
    }
}
