package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.components.FeeBadge
import com.thirdparty.xhs.ui.components.XhsAsyncImage
import com.thirdparty.xhs.ui.components.rememberHaptics
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.WatchLaterViewModel

/** 一行的高度（固定值，列表布局用）。 */

/**
 * 稍后观看队列：按加入时间排列（**不提供排序**，硬约束 20），点一行进详情。
 *
 * 历史包袱：这里曾实现过三种排序（长按拖动换位 / 内容坐标 + 边缘自动滚动 / 行内上移下移按钮），
 全部删除 —— 用户最终要求**不提供排序**（硬约束 20）。下面这些行号附近还能看到当时留下的注释。
 * 库既没必要，也会让 Room 的响应式列表在手指底下重排。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchLaterScreen(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    viewModel: WatchLaterViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("稍后观看") },
                navigationIcon = {
                    IconButton(onClick = haptics.click(onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = {
                            haptics.tick()
                            confirmClear = true
                        }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空队列")
                        }
                    }
                }
            )
        },
        floatingActionButton = { }
    ) { pad ->
        if (state.items.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(pad).padding(Spacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.loading) "正在读取队列…"
                    else "队列是空的\n在瀑布流或推荐页长按作品，选「稍后观看」加进来",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Scaffold
        }

        // ---- 列表 ------------------------------------------------------------
        // 队列**不提供排序**（用户最终要求）：先后顺序就按加入时间，列表里没有序号、没有排序按钮。
        // 历史的三种排序实现（长按拖动换位 / 内容坐标 + 边缘自动滚动 / 行内上下按钮）全部删除，
        // 只保留"加入顺序"这一个含义 —— 少一个可变的量，就少一类"顺序不对"的问题。
        val items = state.items

        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
        ) {
            Text(
                "按加入时间排列 · 在瀑布流或推荐页长按作品可加入",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.l, top = Spacing.s, bottom = Spacing.xs)
            )
            items.forEach { item ->
                QueueRow(
                    item = item,
                    onClick = { onOpenDetail(item.noteId) },
                    onRemove = { viewModel.remove(item.noteId) }
                )
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空稍后观看队列？") },
            text = { Text("将移除队列里全部 ${state.items.size} 件作品。收藏不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.reject()
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = {
                    haptics.tick()
                    confirmClear = false
                }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun QueueRow(
    item: NoteItem,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val haptics = rememberHaptics()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s, vertical = 2.dp)
            .clip(Corners.large)
            .clickable { haptics.tick(); onClick() }
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = Spacing.s, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            XhsAsyncImage(
                url = item.cover.ifEmpty { item.thumbnail },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(40.dp).height(56.dp).clip(Corners.small)
            )
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title.ifEmpty { "(无标题)" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 作品标签（图文 / 粉丝圈 / VIP / 免费）——和瀑布流卡片用同一个组件，
                    // 队列里也一眼能看出这条是什么
                    FeeBadge(item, compact = true)
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        "@${item.userName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            IconButton(onClick = {
                haptics.reject()
                onRemove()
            }) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "移出队列")
            }
        }
    }
}
