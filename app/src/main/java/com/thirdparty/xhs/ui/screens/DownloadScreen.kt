package com.thirdparty.xhs.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.data.DownloadGroup
import com.thirdparty.xhs.data.DownloadKind
import com.thirdparty.xhs.data.DownloadStage
import com.thirdparty.xhs.data.DownloadStatus
import com.thirdparty.xhs.data.DownloadTaskEntity
import com.thirdparty.xhs.data.formatBytes
import com.thirdparty.xhs.ui.components.ConfirmActionDialog
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.XhsAsyncImage
import com.thirdparty.xhs.ui.components.rememberHaptics
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.DownloadViewModel

/**
 * 下载管理：**按作品一行**，行内汇总"视频 / 图片"各自的进度；点一行展开看单条（视频、每张图片）。
 *
 * 为什么按作品成行：一个作品可能有 1 个视频 + 几十张图片，一行一个文件会把列表刷屏、用户找不到作品
 * （2026-10-17 用户反馈）。需要逐张操作时再展开，展开里每张图片都能单独「打开」预览。
 *
 * 进行中的作品在「进行中」组，整组结束（全部完成或有失败）后落到「已完成」组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    // 展开的作品（默认折叠：列表要一眼看清有哪些作品）
    var expanded by remember { mutableStateOf(setOf<Long>()) }

    /** 打开一个条目（视频或图片）：用 MediaStore 的 uri 交给系统相册/播放器。 */
    fun open(item: DownloadTaskEntity) {
        val uri = item.savedUri?.let(Uri::parse)
        if (uri == null) {
            Toast.makeText(context, "找不到文件记录，可能已被删除", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (item.kind == DownloadKind.VIDEO) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "没有可打开该文件的应用", Toast.LENGTH_SHORT).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("下载管理") },
                navigationIcon = {
                    IconButton(onClick = haptics.click(onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (state.hasActive) {
                        if (state.pausedCount > 0) {
                            TextButton(onClick = {
                                haptics.tick()
                                viewModel.resumeAll()
                            }) { Text("全部继续") }
                        }
                        if (state.runningCount > 0) {
                            TextButton(onClick = {
                                haptics.tick()
                                viewModel.pauseAll()
                            }) { Text("全部暂停") }
                        }
                    }
                    if (state.finished.isNotEmpty()) {
                        IconButton(onClick = {
                            haptics.tick()
                            confirmClear = true
                        }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空已完成")
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (state.active.isEmpty() && state.finished.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = if (state.loading) "正在读取下载任务…" else "还没有下载任务",
                    description = if (state.loading) null else "在作品详情页的菜单里选择「下载」，即可保存视频或图片。",
                    icon = Icons.Filled.Download
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(
                start = Spacing.s, end = Spacing.s, top = Spacing.s, bottom = Spacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            if (state.active.isNotEmpty()) {
                item { GroupLabel("进行中 · ${state.active.size} 件作品") }
                items(state.active, key = { "a${it.noteId}" }) { group ->
                    GroupCard(
                        group = group,
                        expanded = group.noteId in expanded,
                        onToggleExpand = {
                            expanded = if (group.noteId in expanded) {
                                expanded - group.noteId
                            } else {
                                expanded + group.noteId
                            }
                        },
                        onPauseGroup = { viewModel.pauseGroup(group) },
                        onResumeGroup = { viewModel.resumeGroup(group) },
                        onRetryGroup = { viewModel.retryGroup(group) },
                        onOpenItem = ::open,
                        onCancelItem = { viewModel.cancel(it) },
                        onRetryItem = { viewModel.retry(it) },
                        onOpenGroup = {
                            group.firstOpenable()?.let(::open)
                                ?: Toast.makeText(context, "还没有可打开的文件", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
            if (state.finished.isNotEmpty()) {
                item { GroupLabel("已完成 · ${state.finished.size} 件作品") }
                items(state.finished, key = { "f${it.noteId}" }) { group ->
                    GroupCard(
                        group = group,
                        expanded = group.noteId in expanded,
                        onToggleExpand = {
                            expanded = if (group.noteId in expanded) {
                                expanded - group.noteId
                            } else {
                                expanded + group.noteId
                            }
                        },
                        onPauseGroup = {},
                        onResumeGroup = {},
                        onRetryGroup = { viewModel.retryGroup(group) },
                        onOpenItem = ::open,
                        onCancelItem = { viewModel.cancel(it) },
                        onRetryItem = { viewModel.retry(it) },
                        onOpenGroup = {
                            group.firstOpenable()?.let(::open)
                                ?: Toast.makeText(context, "还没有可打开的文件", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmActionDialog(
            title = "清空已完成？",
            text = "将移除 ${state.finished.size} 件作品的下载记录。已保存到相册或视频库的文件不会删除。",
            confirmText = "清空",
            onConfirm = {
                confirmClear = false
                viewModel.clearFinished()
            },
            onDismiss = { confirmClear = false }
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xs, top = Spacing.s, bottom = Spacing.xs)
    )
}

/** 作品一行：封面 + 标题 + 汇总状态 + 组级操作；展开后是这一件的每条（视频 / 每张图片）。 */
@Composable
private fun GroupCard(
    group: DownloadGroup,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPauseGroup: () -> Unit,
    onResumeGroup: () -> Unit,
    onRetryGroup: () -> Unit,
    onOpenGroup: () -> Unit,
    onOpenItem: (DownloadTaskEntity) -> Unit,
    onCancelItem: (Long) -> Unit,
    onRetryItem: (Long) -> Unit
) {
    val haptics = rememberHaptics()
    // 处理阶段没有可数进度：用秒数心跳表示"还在动"
    var ticker by remember(group.noteId) { mutableIntStateOf(0) }
    if (group.processing) {
        LaunchedEffect(group.noteId) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                ticker += 1
            }
        }
    }
    val fraction = group.progressFraction()
    val summary = group.statusSummary() +
        if (group.processing) " · 已用 $ticker 秒" else ""

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().clip(Corners.large)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { haptics.tick(); onToggleExpand() }
                    .padding(start = Spacing.s, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(width = 36.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                    if (group.cover.isNotEmpty()) {
                        XhsAsyncImage(
                            url = group.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(Corners.small)
                        )
                    } else {
                        Icon(
                            if (group.images.isEmpty()) Icons.Filled.Movie else Icons.Filled.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.title.ifEmpty { "(无标题)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (group.failedCount > 0 && group.settled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!group.settled) {
                        Spacer(Modifier.height(Spacing.xs))
                        if (fraction == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (group.settled) {
                        IconButton(onClick = { haptics.tick(); onOpenGroup() }) {
                            Icon(Icons.Filled.PlayCircleOutline, contentDescription = "打开")
                        }
                        if (group.failedCount > 0) {
                            IconButton(onClick = { haptics.tick(); onRetryGroup() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "重试失败项")
                            }
                        }
                    } else if (group.queuedOrRunning > 0) {
                        IconButton(onClick = { haptics.tick(); onPauseGroup() }) {
                            Icon(Icons.Filled.Pause, contentDescription = "暂停全部")
                        }
                    } else if (group.pausedCount > 0) {
                        IconButton(onClick = { haptics.tick(); onResumeGroup() }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "继续全部")
                        }
                    }
                    IconButton(onClick = { haptics.tick(); onToggleExpand() }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开"
                        )
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = Spacing.s, end = Spacing.xs, bottom = Spacing.s)) {
                    group.items.forEach { item ->
                        ItemRow(
                            task = item,
                            onOpen = { onOpenItem(item) },
                            onCancel = { onCancelItem(item.id) },
                            onRetry = { onRetryItem(item.id) }
                        )
                    }
                }
            }
        }
    }
}

/** 展开后的单条：视频 / 某一张图片。 */
@Composable
private fun ItemRow(
    task: DownloadTaskEntity,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = task.status == DownloadStatus.DONE) { haptics.tick(); onOpen() }
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (task.kind == DownloadKind.VIDEO) Icons.Filled.Movie else Icons.Filled.Image,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(Spacing.s))
        Column(Modifier.weight(1f)) {
            Text(
                if (task.kind == DownloadKind.VIDEO) "视频" else "图片 ${"%02d".format(task.imageIndex + 1)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            Text(
                itemStatusLine(task),
                style = MaterialTheme.typography.labelSmall,
                color = if (task.status == DownloadStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when (task.status) {
            DownloadStatus.DONE -> IconButton(onClick = { haptics.tick(); onOpen() }) {
                Icon(Icons.Filled.PlayCircleOutline, contentDescription = "打开")
            }

            DownloadStatus.FAILED -> IconButton(onClick = { haptics.tick(); onRetry() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "重试")
            }

            else -> Unit
        }
        IconButton(onClick = { haptics.reject(); onCancel() }) {
            Icon(
                Icons.Filled.DeleteOutline,
                contentDescription = if (task.status == DownloadStatus.DONE) "删除记录" else "取消下载"
            )
        }
    }
}

/** 单条的状态说明（与组级汇总同一套口径）。 */
private fun itemStatusLine(task: DownloadTaskEntity): String = when (task.status) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> when {
        DownloadStage.isProcessing(task.stage) -> (DownloadStage.label(task.stage) ?: "处理中") + "…"
        task.kind == DownloadKind.VIDEO && task.totalSegments > 0 ->
            "下载中 ${task.doneSegments}/${task.totalSegments} 片 · ${formatBytes(task.bytes)}"

        else -> "下载中 · ${formatBytes(task.bytes)}"
    }

    DownloadStatus.PAUSED -> "已暂停 · ${formatBytes(task.bytes)}"
    DownloadStatus.DONE -> "已完成 · ${formatBytes(task.bytes)}"
    DownloadStatus.FAILED -> "失败：${task.error ?: "未知原因"}"
    else -> task.status
}
