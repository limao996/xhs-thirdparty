package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.PullToRefreshBox
import com.thirdparty.xhs.ui.components.XhsWaterfallGrid
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.AuthorViewModel

/** 作者主页：头像 + 关注(本地) + 作品瀑布。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorScreen(
    userId: Int,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    viewModel: AuthorViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "author-$userId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                AuthorViewModel(userId, com.thirdparty.xhs.App.repo) as T
        }
    )
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.author?.userName ?: "作者主页") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // author header (MD3 container surface)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XhsAvatar(
                        url = state.author?.headImg,
                        contentDescription = "头像",
                        modifier = Modifier.size(AvatarSize.profile)
                    )
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(state.author?.userName ?: "加载中…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            state.author?.signature?.ifBlank { "暂无简介" } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    Spacer(Modifier.width(Spacing.s))
                    Surface(
                        onClick = { viewModel.toggleFollow() },
                        shape = MaterialTheme.shapes.small,
                        color = if (state.followed) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.primary,
                        contentColor = if (state.followed) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onPrimary
                    ) {
                        Text(
                            if (state.followed) "已关注" else "+ 关注",
                            Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            // weight(1f) gives the works area the REMAINING height (a bare
            // fillMaxSize child of a Column would claim the parent's full height
            // and push centred states off-screen)
            Box(Modifier.fillMaxWidth().weight(1f)) {
            if (state.notesLoading && state.notes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (!viewModel.validUserId) {
                // the caller had no usable author id — a network retry cannot help
                EmptyState(
                    title = "作者信息不可用",
                    modifier = Modifier.fillMaxSize(),
                    description = "这条内容没有提供作者信息",
                    icon = Icons.Filled.Person
                )
            } else if (state.notes.isEmpty() && (state.notesError || state.profileError)) {
                EmptyState(
                    title = "加载失败",
                    modifier = Modifier.fillMaxSize(),
                    description = "请检查网络后重试",
                    actionLabel = "重试",
                    onAction = { viewModel.retry() }
                )
            } else if (state.notes.isEmpty()) {
                EmptyState(
                    title = "作者还没有发布内容",
                    modifier = Modifier.fillMaxSize(),
                    description = "换个作者看看吧",
                    icon = Icons.Filled.PhotoLibrary
                )
            } else {
                // waterfall with endless pagination + pull-to-refresh
                PullToRefreshBox(
                    refreshing = state.refreshing,
                    onRefresh = { viewModel.refresh() }
                ) {
                    XhsWaterfallGrid(
                        items = state.notes,
                        onOpenDetail = onOpenDetail,
                        contentPadding = PaddingValues(
                            start = Spacing.s, end = Spacing.s, top = Spacing.s, bottom = Spacing.l
                        ),
                        hasMore = state.hasMore,
                        loadingMore = state.loadingMore,
                        resetKey = state.refreshTick,
                        onLoadMore = { viewModel.loadMore() }
                    )
                }
            }
            }
        }
    }
}