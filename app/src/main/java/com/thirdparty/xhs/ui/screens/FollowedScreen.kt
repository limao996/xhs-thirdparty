package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.AuthorSortField
import com.thirdparty.xhs.data.FollowedEntity
import com.thirdparty.xhs.data.LocalSortOrder
import com.thirdparty.xhs.data.applyAuthorFilter
import com.thirdparty.xhs.ui.components.ConfirmActionDialog
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.FollowedAuthorRow
import com.thirdparty.xhs.ui.components.ListFilterBar
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * 搜索 + 排序（用户 2026-10-25 要求）：与收藏/最近浏览同一套 `ListFilterBar`；
 * 搜索匹配昵称与签名，排序维度是关注时间 / 昵称，另有正序/倒序。
 * 过滤排序在 `Dispatchers.Default` 上算（与列表页同一条纪律：不在组合里现算）。
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

    // 搜索 / 排序状态 + 算好的结果（界面只读，不在组合期重算）
    var query by remember { mutableStateOf("") }
    var sortField by remember { mutableStateOf(AuthorSortField.TIME) }
    var sortOrder by remember { mutableStateOf(LocalSortOrder.DESC) }
    var visible by remember { mutableStateOf<List<FollowedEntity>>(emptyList()) }
    var showSortDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        list = App.repo.followedAuthors()
        loaded = true
    }

    // 列表 / 搜索词 / 排序变化 → 后台重算（输入停顿 150ms，和列表页一致）
    LaunchedEffect(list, query, sortField, sortOrder) {
        if (query.isNotEmpty()) delay(150)
        visible = withContext(Dispatchers.Default) {
            applyAuthorFilter(list, query, sortField, sortOrder)
        }
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (loaded && list.isNotEmpty()) {
                ListFilterBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "搜索昵称 / 签名",
                    fieldLabel = sortField.label,
                    onOpenSort = { showSortDialog = true },
                    order = sortOrder,
                    onToggleOrder = {
                        sortOrder = if (sortOrder == LocalSortOrder.DESC) LocalSortOrder.ASC
                        else LocalSortOrder.DESC
                    },
                    haptics = haptics
                )
            }

            when {
                !loaded -> Box(Modifier.fillMaxSize())

                list.isEmpty() -> EmptyState(
                    title = "还没有关注任何作者",
                    modifier = Modifier.fillMaxSize(),
                    description = "可在作者主页或作品详情页关注",
                    icon = Icons.Filled.Group
                )

                // 有关注、但当前搜索词一条都没匹配上（文案约定 27：说清发生了什么、能怎么办）
                visible.isEmpty() -> EmptyState(
                    title = "没有匹配的作者",
                    modifier = Modifier.fillMaxSize(),
                    description = "昵称或签名里包含「${query.trim()}」的作者没有找到",
                    icon = Icons.Filled.SearchOff,
                    actionLabel = "清除搜索",
                    onAction = { haptics.tick(); query = "" }
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Spacing.l)
                ) {
                    items(visible, key = { it.userId }) { f ->
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
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("排序方式") },
            text = {
                Column {
                    AuthorSortField.entries.forEach { option ->
                        Row2(
                            selected = sortField == option,
                            label = option.label,
                            onClick = {
                                haptics.tick()
                                sortField = option
                                showSortDialog = false
                            }
                        )
                    }
                    Text(
                        "正序 / 倒序用列表右侧的箭头切换",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { haptics.click { showSortDialog = false } }) { Text("取消") }
            }
        )
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

/** 单选项行（排序对话框里用；与列表页的样式保持一致）。 */
@Composable
private fun Row2(selected: Boolean, label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
