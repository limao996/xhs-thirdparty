package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.material.icons.filled.DragHandle
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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.thirdparty.xhs.ui.components.XhsAsyncImage
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("稍后观看") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
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

        // ---- 拖动排序 --------------------------------------------------------
        // 拖动期间用 preview 这份列表渲染，松手写回数据库后由 watchLaterVersion 触发的
        // 重新读取接管（见 WatchLaterViewModel）。
        var draggingId by remember { mutableStateOf<Long?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        var preview by remember { mutableStateOf<List<NoteItem>?>(null) }
        val items = preview ?: state.items
        val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }

        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
        ) {
            Text(
                "长按可以拖动排序",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.l, top = Spacing.s, bottom = Spacing.xs)
            )
            items.forEachIndexed { index, item ->
                val isDragging = draggingId == item.noteId
                // 拖动的手势必须和 clickable 挂在同一个节点上（见 QueueRow）：挂在外面一层
                // 的 Box 上时，行内 clickable 会先把事件吃掉，长按永远轮不到拖动。
                val dragModifier = Modifier.pointerInput(item.noteId, state.items) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            draggingId = item.noteId
                            dragOffset = 0f
                            preview = state.items
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            val list = preview ?: return@detectDragGesturesAfterLongPress
                            val from = list.indexOfFirst { it.noteId == item.noteId }
                            if (from < 0) return@detectDragGesturesAfterLongPress
                            // 手指每越过一行的半程，就和那一行换位
                            val target = (from + (dragOffset / rowHeightPx).roundToInt())
                                .coerceIn(0, list.lastIndex)
                            if (target != from) {
                                preview = list.toMutableList().apply {
                                    add(target, removeAt(from))
                                }
                                // 换位之后这一行的基准位置也挪了一格
                                dragOffset -= (target - from) * rowHeightPx
                            }
                        },
                        onDragEnd = {
                            preview?.let { list ->
                                viewModel.setOrder(list.map { it.noteId })
                            }
                            draggingId = null
                            dragOffset = 0f
                            preview = null
                        },
                        onDragCancel = {
                            // 已经拖到别的位置就照样落库：手势被「取消」（系统抢走指针、
                            // 注入事件流被打断等）时把顺序弹回去，用户会觉得拖动白做了。
                            // 实测：`adb shell input draganddrop` 走的就是 cancel 分支。
                            preview?.let { list ->
                                viewModel.setOrder(list.map { it.noteId })
                            }
                            draggingId = null
                            dragOffset = 0f
                            preview = null
                        }
                    )
                }
                Box(
                    Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffset else 0f
                            // 拖动中的那一行浮起来一点，别的行保持原样
                            shadowElevation = if (isDragging) 12f else 0f
                        }
                ) {
                    QueueRow(
                        item = item,
                        position = index + 1,
                        dragging = isDragging,
                        dragModifier = dragModifier,
                        onClick = { onOpenDetail(item.noteId) },
                        onRemove = { viewModel.remove(item.noteId) }
                    )
                }
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
    position: Int,
    dragging: Boolean,
    /** 长按拖动的手势修饰符：必须接在 clickable 之后，同一个节点上 */
    dragModifier: Modifier = Modifier,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .padding(horizontal = Spacing.s, vertical = 2.dp)
            .clip(Corners.large)
            .clickable { onClick() }
            .then(dragModifier)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 拖动把手：纯提示（真个手柄都能长按拖动），所以不做单独的点击区
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = "长按拖动排序",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.s))
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
                Text(
                    "$position. @${item.userName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "移出队列")
            }
        }
    }
}
