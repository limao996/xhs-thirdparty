package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
import kotlin.math.roundToInt

/** 一行的高度（拖动排序按它换算目标位置，所以必须固定）。 */
private val QueueRowHeight = 88.dp

/**
 * 稍后观看队列：按队列顺序排列，**长按拖动**调整顺序，点一行进详情。
 *
 * 排序不走「上移/下移」按钮：那是列表管理的做法，而队列本来就是「拖成我想要的顺序」。
 * 拖动过程只改内存里的顺序（[preview]），松手才整段写回数据库 —— 拖动中每挪一格就写一次
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

        // ---- 排序 ------------------------------------------------------------
        // 只做**上移 / 下移一格**：一次点击 = 与相邻行交换，顺序立刻写回数据库。
        //
        // 曾经做过长按拖动（含边缘自动滚动、双指滚动、内容坐标补偿），实机反馈始终不稳定，
        // 用户要求直接去掉 —— 排序按钮没有任何手势歧义，也不会和列表的滚动打架。
        //
        // `committed` 是"刚落库的顺序"：写库是异步的，等 Room 读回来之前如果按旧列表渲染，
        // 被移动的那一行会先闪回原位再跳过去，所以先按这份顺序画，等库里对上再撤掉。
        var committed by remember { mutableStateOf<List<Long>?>(null) }
        val items = committed?.let { ids ->
            val byId = state.items.associateBy { it.noteId }
            ids.mapNotNull { byId[it] }.takeIf { it.size == ids.size }
        } ?: state.items
        LaunchedEffect(state.items, committed) {
            val want = committed ?: return@LaunchedEffect
            if (state.items.map { it.noteId } == want) committed = null
        }

        fun move(from: Int, to: Int) {
            if (from < 0 || to < 0 || from > items.lastIndex || to > items.lastIndex) return
            val ids = items.map { it.noteId }.toMutableList()
            ids.add(to, ids.removeAt(from))
            committed = ids
            viewModel.setOrder(ids)
            haptics.confirm()
        }

        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
        ) {
            Text(
                "用右侧上下按钮调整顺序",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.l, top = Spacing.s, bottom = Spacing.xs)
            )
            items.forEachIndexed { index, item ->
                QueueRow(
                    item = item,
                    position = index + 1,
                    canMoveUp = index > 0,
                    canMoveDown = index < items.lastIndex,
                    onMoveUp = { move(index, index - 1) },
                    onMoveDown = { move(index, index + 1) },
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
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun QueueRow(
    item: NoteItem,
    /** 第几件（从 1 开始） */
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
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
            // 序号 + 右侧竖直排列的小号上下按钮（原来那个拖动把手去掉了：实测拖不动）
            Text(
                "$position",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
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
            // 上下排序：竖直排列的小号按钮（禁用态在两端，一眼看出到头了）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = {
                        haptics.tick()
                        onMoveUp()
                    },
                    enabled = canMoveUp,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "上移",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        haptics.tick()
                        onMoveDown()
                    },
                    enabled = canMoveDown,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "下移",
                        modifier = Modifier.size(18.dp)
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
