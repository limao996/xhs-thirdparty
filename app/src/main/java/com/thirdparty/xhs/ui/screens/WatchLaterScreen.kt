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
import androidx.compose.runtime.LaunchedEffect
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
        // 拖动期间**不改列表顺序**：只记「从哪一行开始拖」（dragFrom）、「手指移动了多少」
        // （dragOffset）、「现在会落在第几行」（dragTarget），靠 translationY 让开位置，
        // 松手才整段写回数据库。
        //
        // 之前是"每越过半行就和相邻行换位"，那样只能一格一格动：换位会让这一行的**基准位置**
        // 立刻跳一行，而手势的位移是在节点的局部坐标里累加的，基准一跳就正好把累加量抵消掉，
        // 于是手指拖多远都停在第 1/第 2 格（用户实测：只能 1→0 或 1→2，不能 1→3）。
        var dragFrom by remember { mutableIntStateOf(-1) }
        var dragTarget by remember { mutableIntStateOf(-1) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        // 刚落库的顺序。落库后要等 Room 重新读出来（一次磁盘往返），这段时间里如果直接渲染
        // 旧列表，被拖的那一行会先弹回旧位置、再跳到新位置 —— 就是用户看到的"松开后闪一下"。
        // 所以先按这份顺序渲染，等库里读回来的顺序对上再撤掉。
        var committed by remember { mutableStateOf<List<Long>?>(null) }
        val items = committed?.let { ids ->
            val byId = state.items.associateBy { it.noteId }
            ids.mapNotNull { byId[it] }.takeIf { it.size == ids.size }
        } ?: state.items
        LaunchedEffect(state.items, committed) {
            val want = committed ?: return@LaunchedEffect
            if (state.items.map { it.noteId } == want) committed = null
        }
        val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }

        fun land() {
            val from = dragFrom
            val to = dragTarget
            if (from >= 0 && to >= 0 && to != from) {
                val ids = items.map { it.noteId }.toMutableList()
                ids.add(to, ids.removeAt(from))
                committed = ids
                viewModel.setOrder(ids)
                haptics.confirm()
            }
            dragFrom = -1
            dragTarget = -1
            dragOffset = 0f
        }

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
                val isDragging = dragFrom == index
                // 别的行给被拖的那一行让位：往下拖时中间的行整体上移一格，往上拖则相反
                val shift = when {
                    dragFrom < 0 -> 0f
                    isDragging -> dragOffset
                    index in (dragFrom + 1)..dragTarget -> -rowHeightPx
                    index in dragTarget..(dragFrom - 1) -> rowHeightPx
                    else -> 0f
                }
                // 拖动的手势必须和 clickable 挂在同一个节点上（见 QueueRow）：挂在外面一层
                // 的 Box 上时，行内 clickable 会先把事件吃掉，长按永远轮不到拖动。
                val dragModifier = Modifier.pointerInput(item.noteId, index, items.size) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragFrom = index
                            dragTarget = index
                            dragOffset = 0f
                            haptics.longPress()
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                            dragTarget = (index + (dragOffset / rowHeightPx).roundToInt())
                                .coerceIn(0, items.lastIndex)
                        },
                        onDragEnd = { land() },
                        onDragCancel = {
                            // 已经拖到别的位置就照样落库：手势被「取消」（系统抢走指针、
                            // 注入事件流被打断等）时把顺序弹回去，用户会觉得拖动白做了。
                            // 实测：`adb shell input draganddrop` 走的就是 cancel 分支。
                            land()
                        }
                    )
                }
                Box(
                    Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = shift
                            // 拖动中的那一行浮起来一点，别的行保持原样
                            shadowElevation = if (isDragging) 12f else 0f
                        }
                ) {
                    QueueRow(
                        item = item,
                        dragging = isDragging,
                        dragModifier = dragModifier,
                        onClick = {
                            haptics.tick()
                            onOpenDetail(item.noteId)
                        },
                        onRemove = {
                            haptics.reject()
                            viewModel.remove(item.noteId)
                        }
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
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "移出队列")
            }
        }
    }
}
