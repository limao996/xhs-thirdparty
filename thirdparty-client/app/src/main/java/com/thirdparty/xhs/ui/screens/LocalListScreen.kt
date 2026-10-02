package com.thirdparty.xhs.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.XhsWaterfallGrid
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.LocalListViewModel
import androidx.compose.material.icons.automirrored.filled.ArrowBack

/**
 * 收藏 / 最近浏览 —— 瀑布流（2 列 Masonry）+ 客户端「加载更多」。
 * 纯本地 Room 数据。
 *
 * 收藏支持**长按进入多选**批量取消（最近浏览不提供：它没有"取消浏览"的语义）。
 *
 * Selection is HOISTED to the caller (see [selected] / [onSelectionChange]) so the
 * hosting Scaffold can swap its own TopAppBar for a contextual selection bar —
 * that is the Material 3 pattern, and it avoids stacking two bars on top of each
 * other. This composable stays stateless about the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalListScreen(
    mode: LocalListViewModel.Mode,
    onOpenDetail: (Long) -> Unit,
    viewModel: LocalListViewModel,
    selected: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    /** host draws the contextual bar; the screen only reports the tap */
    onRequestSelectAll: () -> Unit = {},
    onRequestDelete: () -> Unit = {},
    /** false when the host renders its own app bar (then we render nothing) */
    showOwnAppBar: Boolean = false,
    title: String = "",
    onBack: () -> Unit = {}
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(mode) { viewModel.reload() }

    val selecting = selected.isNotEmpty()
    val canSelect = mode == LocalListViewModel.Mode.SAVED

    // back gesture leaves selection mode rather than the screen
    BackHandler(enabled = selecting) { onSelectionChange(emptySet()) }

    Column(Modifier.fillMaxSize()) {
        if (showOwnAppBar) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
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
                        onSelectionChange(
                            if (item.noteId in selected) selected - item.noteId
                            else selected + item.noteId
                        )
                    } else null
                )
        }
    }
}