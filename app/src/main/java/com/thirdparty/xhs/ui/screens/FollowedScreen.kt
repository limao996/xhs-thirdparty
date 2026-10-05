package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.FollowedEntity
import com.thirdparty.xhs.ui.components.ConfirmActionDialog
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.FollowedAuthorRow
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 本地关注的作者列表，可直接取消关注。
 *
 * The rows come from [FollowedAuthorRow], the same composable the 关注 tab inside
 * 发现 renders — the two lists previously looked different (row height, padding,
 * and only this one could unfollow) even though they show the same local table.
 *
 * The list holds its own copy so a removal can drop the row without waiting for a
 * database round-trip; `loaded` keeps the empty state from flashing before the
 * first read returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedScreen(
    onBack: () -> Unit,
    onOpenAuthor: (Int) -> Unit
) {
    var list by remember { mutableStateOf<List<FollowedEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    // which author is waiting for a 取消关注 confirmation (null = none). One dialog
    // for the whole list, so the row does not have to hold dialog state itself.
    var pendingUnfollow by remember { mutableStateOf<FollowedEntity?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()

    LaunchedEffect(Unit) {
        list = App.repo.followedAuthors()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // the count belongs in the header: it is the one thing a reader of
                // this page wants to know before scrolling
                title = { Text(if (loaded) "我关注的作者 · ${list.size}" else "我关注的作者") },
                navigationIcon = {
                    IconButton(onClick = haptics.click(onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
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
                contentPadding = PaddingValues(bottom = Spacing.l)
            ) {
                items(list, key = { it.userId }) { f ->
                    FollowedAuthorRow(
                        name = f.userName,
                        signature = f.signature,
                        avatarUrl = f.headImg,
                        onClick = { onOpenAuthor(f.userId) },
                        // ask first: the row disappears on tap and there is no undo
                        onUnfollow = { pendingUnfollow = f }
                    )
                }
            }
        }
    }

    pendingUnfollow?.let { f ->
        ConfirmActionDialog(
            title = "取消关注？",
            text = "将不再关注「${f.userName}」。",
            confirmText = "取消关注",
            onConfirm = {
                pendingUnfollow = null
                // unfollow locally and drop the row
                scope.launch {
                    App.repo.toggleFollowLocal(f.userId, f.userName, f.headImg, f.signature)
                    list = list.filterNot { it.userId == f.userId }
                }
            },
            onDismiss = { pendingUnfollow = null }
        )
    }
}
