package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.App
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.PullToRefreshBox
import com.thirdparty.xhs.ui.components.XhsWaterfallGrid
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.LocalListViewModel

/**
 * 收藏 / 最近浏览 —— 瀑布流（2 列 Masonry）+ 客户端「加载更多」。
 * 纯本地 Room 数据。
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

    if (state.loading && state.all.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (state.all.isEmpty()) {
        EmptyState(
            title = if (mode == LocalListViewModel.Mode.SAVED) "还没有收藏" else "还没有浏览记录",
            modifier = Modifier.fillMaxSize(),
            description = if (mode == LocalListViewModel.Mode.SAVED) "在详情页点右上角的心形即可收藏"
            else "看过的内容会自动出现在这里",
            icon = if (mode == LocalListViewModel.Mode.SAVED) Icons.Filled.FavoriteBorder
            else Icons.Filled.History
        )
        return
    }

    PullToRefreshBox(
        refreshing = state.refreshing,
        onRefresh = { viewModel.refresh() }
    ) {
        XhsWaterfallGrid(
            items = state.visible,
            onOpenDetail = onOpenDetail,
            contentPadding = PaddingValues(
                start = Spacing.s, end = Spacing.s, top = Spacing.s, bottom = Spacing.l
            ),
            hasMore = state.hasMore,
            onLoadMore = { viewModel.loadMore() }
        )
    }
}