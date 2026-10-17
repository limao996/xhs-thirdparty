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
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.AlertDialog
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
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val dupReport by viewModel.report.collectAsStateWithLifecycle()
    val showDuplicates by viewModel.showDuplicates.collectAsStateWithLifecycle()
    // 速度是引擎内存里的观测值（不落库）：只在"正在拉切片"时显示
    val speeds by com.thirdparty.xhs.download.DownloadEngine.currentSpeeds
        .collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val context = LocalContext.current
    // 删除确认：null = 没弹；"clear" = 清空已完成；否则是要删的那条记录
    var pendingDelete by remember { mutableStateOf<DownloadTaskEntity?>(null) }
    var pendingClear by remember { mutableStateOf(false) }
    // 展开的作品（默认折叠：列表要一眼看清有哪些作品）
    var expanded by remember { mutableStateOf(setOf<Long>()) }

    // 进页面静默扫一次重复/孤儿文件，只在入口上更新数字（不打扰）
    LaunchedEffect(Unit) { viewModel.scanDuplicates(context, silent = true) }

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
                    if (state.settled.isNotEmpty()) {
                        IconButton(onClick = {
                            haptics.tick()
                            pendingClear = true
                        }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "清空已结束")
                        }
                    }
                }
            )
        }
    ) { pad ->
        if (state.active.isEmpty() && state.settled.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = if (state.loading) "正在读取下载任务…" else "还没有下载任务",
                    description = if (state.loading) null else "在作品详情页的菜单里选择「下载」，即可保存视频或图片。",
                    icon = Icons.Filled.Download
                )
            }
            return@Scaffold
        }

        // 失败的作品默认展开：失败原因与"重试"要一眼看到，不该藏在折叠里
        LaunchedEffect(state.failed.map { it.noteId }) {
            expanded = expanded + state.failed.map { it.noteId }
        }

        /** 渲染一个分组（进行中 / 失败 / 已完成），三处的行控件完全一致。 */
        fun LazyListScope.downloadSection(
            label: String,
            groups: List<DownloadGroup>,
            keyPrefix: String
        ) {
            if (groups.isEmpty()) return
            item { GroupLabel("$label · ${groups.size} 件作品") }
            items(groups, key = { keyPrefix + it.noteId }) { group ->
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
                    onCancelItem = { id ->
                        val item = group.items.firstOrNull { it.id == id }
                        // 未完成的条目：取消下载，公共目录里还没有文件，直接取消即可；
                        // 已完成/失败的条目：先问清楚"只删记录"还是"连文件一起删"
                        if (item != null && item.status != DownloadStatus.DONE &&
                            item.status != DownloadStatus.FAILED
                        ) {
                            viewModel.cancel(id)
                        } else {
                            pendingDelete = item
                        }
                    },
                    onRetryItem = { viewModel.retry(it) },
                    videoSpeed = group.video?.id?.let { speeds[it] },
                    onOpenGroup = {
                        group.firstOpenable()?.let(::open)
                            ?: Toast.makeText(context, "还没有可打开的文件", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(
                start = Spacing.s, end = Spacing.s, top = Spacing.s, bottom = Spacing.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            downloadSection("进行中", state.active, "a")
            downloadSection("失败", state.failed, "x")
            downloadSection("已完成", state.finished, "f")

            // 列表底部：检查"重复下载留下的多余文件"（先列清单，用户确认后才删）
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Corners.small)
                        .clickable {
                            haptics.tick()
                            viewModel.openDuplicates(context)
                        }
                        .padding(vertical = Spacing.m, horizontal = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.s))
                    Column {
                        val count = dupReport?.all?.size ?: 0
                        Text(
                            when {
                                scanning -> "正在检查重复 / 孤儿文件…"
                                count > 0 -> "检查重复 / 孤儿文件 · 发现 $count 个"
                                else -> "检查重复 / 孤儿文件"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "早期版本重复下载会留下多份文件；记录删掉后文件仍留在相册里，都可以在这里清掉",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 删除一条：让用户选"只删记录"还是"连文件一起删"（用户 2026-10-17 要求）
    pendingDelete?.let { target ->
        DeleteChoiceDialog(
            title = "删除这条下载？",
            recordText = target.savedPath?.ifBlank { null } ?: "这条记录还没有保存到公共目录",
            hasFile = target.savedUri != null,
            onDeleteRecord = {
                pendingDelete = null
                viewModel.delete(target.id, alsoDeleteFile = false)
                // 记录没了但文件还在 → 立刻重扫一次，入口上的"发现 N 个"要跟上
                viewModel.scanDuplicates(context, silent = true)
            },
            onDeleteBoth = {
                pendingDelete = null
                viewModel.delete(target.id, alsoDeleteFile = true)
                viewModel.scanDuplicates(context, silent = true)
            },
            onDismiss = { pendingDelete = null }
        )
    }

    // 清空已结束（完成 + 失败）：同样让用户选是否连文件一起删
    // 重复 / 孤儿文件清单：先让用户看到"要删哪些、共多大"，确认后才删
    if (showDuplicates) {
        val report = dupReport
        AlertDialog(
            onDismissRequest = { viewModel.dismissDuplicates() },
            title = {
                Text(
                    when {
                        report == null -> "正在检查…"
                        report.isEmpty -> "没有多余文件"
                        else -> "发现 ${report.all.size} 个多余文件"
                    }
                )
            },
            text = {
                Column {
                    val text = when {
                        report == null -> "正在扫描公共目录里的下载文件…"
                        report.isEmpty -> "公共目录里的下载文件都是干净的：没有重复，也没有无记录的文件。"
                        else -> buildString {
                            append("共 ${formatBytes(report.bytes)}。删除只影响这些多余文件，")
                            append("下载记录与保留的那一份不受影响。此操作不可撤销。")
                        }
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (report != null && !report.isEmpty) {
                        Spacer(Modifier.height(Spacing.s))
                        val shown = report.all.take(8)
                        shown.forEach { file ->
                            Text(
                                file.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (report.all.size > shown.size) {
                            Text(
                                "…以及另外 ${report.all.size - shown.size} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "其中重复 ${report.duplicates.size} 个、无记录 ${report.orphans.size} 个",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (report == null || report.isEmpty) {
                    TextButton(onClick = { viewModel.dismissDuplicates() }) { Text("知道了") }
                } else {
                    TextButton(onClick = {
                        haptics.reject()
                        viewModel.deleteDuplicates(context) { deleted ->
                            Toast.makeText(context, "已删除 $deleted 个多余文件", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("删除这些文件", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = if (report == null || report.isEmpty) null else {
                { TextButton(onClick = { viewModel.dismissDuplicates() }) { Text("取消") } }
            }
        )
    }

    if (pendingClear) {
        DeleteChoiceDialog(
            title = "清空已结束的记录？",
            recordText = "将移除 ${state.settled.size} 件作品的下载记录（已完成 / 失败）",
            hasFile = state.settled.any { it.items.any { item -> item.savedUri != null } },
            onDeleteRecord = {
                pendingClear = false
                viewModel.clearFinished(alsoDeleteFiles = false)
                viewModel.scanDuplicates(context, silent = true)
            },
            onDeleteBoth = {
                pendingClear = false
                viewModel.clearFinished(alsoDeleteFiles = true) { deleted ->
                    Toast.makeText(context, "已删除 $deleted 个文件", Toast.LENGTH_SHORT).show()
                }
                viewModel.scanDuplicates(context, silent = true)
            },
            onDismiss = { pendingClear = false }
        )
    }
}

/**
 * 删除确认：两个选项 —— **只删记录**（文件留在相册/视频库）或 **记录 + 源文件一起删**。
 *
 * 用户要求"删除和清空要能选择删除源文件"（2026-10-17）。默认落在"只删记录"上：
 * 删文件是不可撤销的，误删掉相册里的东西比留一条记录糟得多。
 */
@Composable
private fun DeleteChoiceDialog(
    title: String,
    recordText: String,
    hasFile: Boolean,
    onDeleteRecord: () -> Unit,
    onDeleteBoth: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    recordText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Spacing.s))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Corners.small)
                        .clickable { haptics.tick(); onDeleteRecord() }
                        .padding(vertical = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(Spacing.m))
                    Column {
                        Text("仅删除记录", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "相册 / 视频库里的文件会保留",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (hasFile) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(Corners.small)
                            .clickable { haptics.reject(); onDeleteBoth() }
                            .padding(vertical = Spacing.m),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(Spacing.m))
                        Column {
                            Text(
                                "删除记录和文件",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "同时从相册 / 视频库里删掉源文件，不可撤销",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { haptics.click(onDismiss); onDismiss() }) { Text("取消") }
        }
    )
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
    /** 这件作品的视频实时速度（字节/秒）；null = 没有在跑或还没采样到 */
    videoSpeed: Long? = null,
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
    // 拉切片时显示实时速度（处理阶段没有可数进度，就只显示秒数）
    val speedText = videoSpeed
        ?.takeIf { !group.processing && group.video?.status == DownloadStatus.RUNNING }
        ?.let { com.thirdparty.xhs.download.DownloadSpeed.format(it) }
    val summary = group.statusSummary() +
        // 处理阶段没有可数进度：给已用秒数；大视频 media3 会落到重编码、耗时以分钟计，
        // 超过 30 秒再补一句"视频较大"，否则用户会以为卡死（2026-10-17 后台 muxer error 的排查结论）
        if (group.processing) {
            " · 已用 $ticker 秒" +
                (DownloadStage.slowHint(group.video?.stage, ticker)?.let { "（$it）" } ?: "")
        } else {
            speedText?.let { " · $it" } ?: ""
        }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().clip(Corners.large)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    // onClickLabel 让"整行可点"对读屏用户也有名字（它做的事是展开/收起）
                    .clickable(
                        onClickLabel = if (expanded) "收起" else "展开"
                    ) { haptics.tick(); onToggleExpand() }
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
                        // 展开后每条都有自己的「打开」，组级那个就多余了（用户 2026-10-17 要求隐藏）。
                        // 另外：没有可打开的文件时（整组失败、或记录里的 uri 已失效）也不摆，
                        // 否则点下去只会得到一句"还没有可打开的文件"。
                        if (!expanded && group.firstOpenable() != null) {
                            IconButton(onClick = { haptics.tick(); onOpenGroup() }) {
                                Icon(Icons.Filled.PlayCircleOutline, contentDescription = "打开")
                            }
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
                            elapsedSeconds = ticker,
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
    /** 处理阶段已用秒数（与组级同一个 ticker）：用于"视频较大，可能需要几分钟"的提示 */
    elapsedSeconds: Int = 0,
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
                itemStatusLine(task, elapsedSeconds),
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
private fun itemStatusLine(task: DownloadTaskEntity, elapsedSeconds: Int? = null): String = when (task.status) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> when {
        DownloadStage.isProcessing(task.stage) ->
            (DownloadStage.label(task.stage) ?: "处理中") + "…" +
                (elapsedSeconds?.let { " · 已用 $it 秒" }.orEmpty()) +
                (elapsedSeconds?.let { DownloadStage.slowHint(task.stage, it) }?.let { "（$it）" } ?: "")

        task.kind == DownloadKind.VIDEO && task.totalSegments > 0 ->
            "下载中 ${task.doneSegments}/${task.totalSegments} 片 · ${formatBytes(task.bytes)}"

        else -> "下载中 · ${formatBytes(task.bytes)}"
    }

    // 暂停原因优先显示：只写「已暂停」用户不知道是自己按的还是被"仅 Wi-Fi"挡住的
    DownloadStatus.PAUSED -> task.error?.takeIf { it.isNotBlank() }
        ?.let { "已暂停 · $it" }
        ?: "已暂停 · ${formatBytes(task.bytes)}"
    DownloadStatus.DONE -> "已完成 · ${formatBytes(task.bytes)}"
    DownloadStatus.FAILED -> "失败：${task.error ?: "未知原因"}"
    else -> task.status
}
