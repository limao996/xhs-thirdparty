package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.FollowedEntity
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.launch

/** 本地关注的作者列表，可直接取消关注。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedScreen(
    onBack: () -> Unit,
    onOpenAuthor: (Int) -> Unit
) {
    var list by remember { mutableStateOf<List<FollowedEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        list = App.repo.followedAuthors()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我关注的作者") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { pad ->
        when {
            !loaded -> Box(Modifier.fillMaxSize().padding(pad))
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad)) {
                EmptyState(
                    title = "还没有关注任何作者",
                    modifier = Modifier.fillMaxSize(),
                    description = "在作者主页或详情页点「关注」即可",
                    icon = Icons.Filled.Group
                )
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Spacing.l)
            ) {
                items(list, key = { it.userId }) { f ->
                    Surface(
                        onClick = { onOpenAuthor(f.userId) },
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            XhsAvatar(
                                url = f.headImg,
                                contentDescription = f.userName,
                                modifier = Modifier.size(AvatarSize.list)
                            )
                            Spacer(Modifier.width(Spacing.m))
                            Column(Modifier.weight(1f)) {
                                Text(f.userName, style = MaterialTheme.typography.bodyLarge)
                                if (f.signature.isNotBlank()) {
                                    Text(
                                        f.signature,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                            Spacer(Modifier.width(Spacing.s))
                            OutlinedButton(onClick = {
                                // unfollow locally and drop the row
                                scope.launch {
                                    App.repo.toggleFollowLocal(f.userId, f.userName, f.headImg, f.signature)
                                    list = list.filterNot { it.userId == f.userId }
                                }
                            }) { Text("已关注") }
                        }
                    }
                }
            }
        }
    }
}