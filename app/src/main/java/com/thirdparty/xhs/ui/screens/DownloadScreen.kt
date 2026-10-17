package com.thirdparty.xhs.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
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
 * 下载管理：进行中 / 已完成两组，逐条 暂停 / 继续 / 取消 / 重试 / 打开 / 删除。
 *
 * 入口在「我的」页（`ProfileScreen` 的「其他」分组）。
 * 页面上**不画进度动画**，进度只来自库里那两个数字（`doneSegments/totalSegments`），
 * 服务那边每完成一片写一次库，列表就跟着动。
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
                        if (state.activeCount > 0) {
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = Spacing.s, end = Spacing.s, top = Spacing.s,
                bottom = Spacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            if (state.active.isNotEmpty()) {
                item { GroupLabel("进行中 · ${state.active.size} 项") }
                items(state.active, key = { it.id }) { task ->
                    DownloadRow(
                        task = task,
                        onPause = { viewModel.pause(task.id) },
                        onResume = { viewModel.resume(task.id) },
                        onCancel = { viewModel.cancel(task.id) }
                    )
                }
            }
            if (state.finished.isNotEmpty()) {
                item { GroupLabel("已完成 · ${state.finished.size} 项") }
                items(state.finished, key = { it.id }) { task ->
                    DownloadRow(
                        task = task,
                        onOpen = {
                            val uri = task.savedUri?.let(Uri::parse)
                            if (uri == null) {
                                Toast.makeText(context, "找不到文件记录，可能已被删除", Toast.LENGTH_SHORT).show()
                            } else {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(
                                        uri,
                                        if (task.kind == DownloadKind.VIDEO) "video/*" else "image/*"
                                    )
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(intent) }
                                    .onFailure {
                                        Toast.makeText(context, "没有可打开该文件的应用", Toast.LENGTH_SHORT).show()
                                    }
                            }
                        },
                        onRetry = { viewModel.retry(task.id) },
                        onCancel = { viewModel.cancel(task.id) }
                    )
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmActionDialog(
            title = "清空已完成？",
            text = "将移除 ${state.finished.size} 条已完成 / 失败的记录。已保存到相册或视频库的文件不会删除。",
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

@Composable
private fun DownloadRow(
    task: DownloadTaskEntity,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = {},
    onOpen: (() -> Unit)? = null
) {
    val haptics = rememberHaptics()
    // 合并 / 转封装 / 保存这几段没有可数进度，用"已用秒数"当"还在动"的信号：
    // 只在处理阶段心跳（每秒一次），拉切片阶段不必要地重组
    val isProcessing = task.status == DownloadStatus.RUNNING && DownloadStage.isProcessing(task.stage)
    var ticker by remember(task.id, isProcessing) {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    if (isProcessing) {
        LaunchedEffect(task.id) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                ticker += 1
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().clip(Corners.large)
    ) {
        Row(
            Modifier.padding(start = Spacing.s, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(width = 36.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                if (task.cover.isNotEmpty()) {
                    XhsAsyncImage(
                        url = task.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(Corners.small)
                    )
                } else {
                    Icon(
                        if (task.kind == DownloadKind.VIDEO) Icons.Filled.Movie else Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title.ifEmpty { "(无标题)" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    statusLine(task, elapsedSeconds = if (isProcessing) ticker else null),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (task.status == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.status == DownloadStatus.RUNNING) {
                    if (isProcessing) {
                        // 合并 / 转封装 / 保存**没有可数进度**：给不确定进度条，
                        // "动着的条 + 已用秒数"就是"还在干活"的信号
                        Spacer(Modifier.height(Spacing.xs))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (task.totalSegments > 1) {
                        Spacer(Modifier.height(Spacing.xs))
                        LinearProgressIndicator(
                            progress = { task.doneSegments.toFloat() / task.totalSegments.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> IconButton(onClick = {
                        haptics.tick(); onPause()
                    }) { Icon(Icons.Filled.Pause, contentDescription = "暂停") }

                    DownloadStatus.PAUSED -> IconButton(onClick = {
                        haptics.tick(); onResume()
                    }) { Icon(Icons.Filled.PlayArrow, contentDescription = "继续") }

                    DownloadStatus.FAILED -> IconButton(onClick = {
                        haptics.tick(); onRetry()
                    }) { Icon(Icons.Filled.Refresh, contentDescription = "重试") }

                    DownloadStatus.DONE -> if (onOpen != null) {
                        IconButton(onClick = { haptics.tick(); onOpen() }) {
                            Icon(Icons.Filled.PlayCircleOutline, contentDescription = "打开")
                        }
                    }
                }
                IconButton(onClick = {
                    haptics.reject()
                    onCancel()
                }) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = if (task.status == DownloadStatus.DONE) "删除记录" else "取消下载"
                    )
                }
            }
        }
    }
}

/**
 * 任务行的状态说明（文案按应用口吻：说明发生了什么 + 进度数字）。
 *
 * 进行中要区分"拉切片"与"合并 / 转封装 / 保存"：后三段没有可数进度，
 * 只说"下载中 22/22 片"会让用户以为卡住（2026-10-17 用户反馈）。
 * [elapsedSeconds] 非空时给出已用秒数，进一步证明"还在动"。
 */
private fun statusLine(task: DownloadTaskEntity, elapsedSeconds: Int? = null): String = when (task.status) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> {
        val stage = DownloadStage.label(task.stage)
        val suffix = elapsedSeconds?.let { " · 已用 $it 秒" }.orEmpty()
        when {
            DownloadStage.isProcessing(task.stage) ->
                (stage ?: "处理中") + "…" + suffix

            task.kind == DownloadKind.VIDEO && task.totalSegments > 0 ->
                "下载中 ${task.doneSegments}/${task.totalSegments} 片 · ${formatBytes(task.bytes)}"

            else -> "下载中 · ${formatBytes(task.bytes)}"
        }
    }

    DownloadStatus.PAUSED -> "已暂停 · ${formatBytes(task.bytes)}"
    DownloadStatus.DONE -> "已完成 · ${formatBytes(task.bytes)}" +
        (task.savedPath?.let { " · $it" } ?: "")

    DownloadStatus.FAILED -> "失败：${task.error ?: "未知原因"}"
    else -> task.status
}
