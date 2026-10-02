@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thirdparty.xhs.App
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.UserListMode
import com.thirdparty.xhs.ui.viewmodel.UserListViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 关注 / 粉丝 list, opened from the counts on the profile card.
 *
 * Both are server-backed and paginated; tapping a row opens that account's page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen(
    mode: UserListMode,
    userId: Int,
    onBack: () -> Unit,
    onOpenAuthor: (Int) -> Unit,
    viewModel: UserListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "userlist-${mode.name}-$userId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                UserListViewModel(App.repo, mode, userId) as T
        }
    )
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val title = if (mode == UserListMode.FOLLOWING) "关注" else "粉丝"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { pad ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                LoadingIndicator()
            }
            state.users.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad)) {
                EmptyState(
                    title = if (mode == UserListMode.FOLLOWING) "还没有关注任何人" else "还没有粉丝",
                    modifier = Modifier.fillMaxSize(),
                    description = if (mode == UserListMode.FOLLOWING) "在作者主页点「关注」即可"
                    else "别人关注你之后会出现在这里",
                    icon = Icons.Filled.Group
                )
            }
            else -> {
                val listState = rememberLazyListState()
                if (state.hasMore) {
                    LaunchedEffect(listState, state.users.size, state.loadingMore) {
                        snapshotFlow {
                            val info = listState.layoutInfo
                            val total = info.totalItemsCount
                            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                            total > 0 && last >= total - 2
                        }.distinctUntilChanged()
                            .collect { nearEnd -> if (nearEnd) viewModel.load(reset = false) }
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(pad),
                    contentPadding = PaddingValues(bottom = Spacing.l)
                ) {
                    items(state.users, key = { it.userId }) { u ->
                        Surface(
                            onClick = { onOpenAuthor(u.userId) },
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                XhsAvatar(
                                    url = u.headImg,
                                    contentDescription = u.userName,
                                    modifier = Modifier.size(AvatarSize.list)
                                )
                                Spacer(Modifier.width(Spacing.m))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(u.userName, style = MaterialTheme.typography.bodyLarge)
                                        if (u.isVip) {
                                            Spacer(Modifier.width(Spacing.xs))
                                            Text(
                                                "VIP",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    if (u.signature.isNotBlank()) {
                                        Text(
                                            u.signature,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (state.hasMore) {
                        item(key = "__more__") {
                            Box(
                                Modifier.fillMaxWidth().padding(Spacing.l),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.loadingMore) LoadingIndicator(Modifier.size(24.dp))
                            }
                        }
                    } else {
                        item(key = "__end__") {
                            Box(
                                Modifier.fillMaxWidth().padding(Spacing.l),
                                contentAlignment = Alignment.Center
                            ) {
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
        }
    }
}