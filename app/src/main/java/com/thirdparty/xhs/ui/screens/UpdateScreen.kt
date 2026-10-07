package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.net.UpdateChecker
import com.thirdparty.xhs.ui.components.ListSection
import com.thirdparty.xhs.ui.components.openUrl
import com.thirdparty.xhs.ui.components.rememberHaptics
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.UpdateUiState
import com.thirdparty.xhs.ui.viewmodel.UpdateViewModel

/**
 * 检查更新：独立页面，只做「查 GitHub Releases 上有没有新版本」这一件事。
 *
 * 检查走 `releases.atom`（网页 feed，不吃 GitHub API 的 60 次/小时额度，见 net/UpdateChecker），
 * 不带账号信息。发现新版后有两条路：**应用内下载并安装**（UpdateDownloader），或去浏览器打开发布页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    onBack: () -> Unit,
    viewModel: UpdateViewModel = viewModel()
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNotes by remember { mutableStateOf(false) }
    // 应用内下载：状态与文件都留在这里（和启动弹窗里那套是同一个下载器）
    var download by remember {
        mutableStateOf<com.thirdparty.xhs.ui.components.UpdateDownloadState>(
            com.thirdparty.xhs.ui.components.UpdateDownloadState.Idle
        )
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val downloadedFile: java.io.File? =
        (download as? com.thirdparty.xhs.ui.components.UpdateDownloadState.Ready)
            ?.let { com.thirdparty.xhs.net.UpdateDownloader.targetFile(context, state.result.let { r -> (r as? UpdateChecker.Result.Newer)?.version ?: "" }) }
            ?.takeIf { it.exists() }
    // 检查更新页各组件的点击反馈（用户反馈「检查更新」按钮没有触感）
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()

    // 只会真正触发一次：ViewModel 记住 entered
    LaunchedEffect(Unit) { viewModel.onEnter() }

    val newer = state.result as? UpdateChecker.Result.Newer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检查更新") },
                navigationIcon = {
                    IconButton(onClick = haptics.click(onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            ListSection("当前版本") {
                ListItem(
                    headlineContent = { Text("小黄书") },
                    supportingContent = {
                        Text("v${state.versionName}（build ${state.versionCode}）")
                    },
                    leadingContent = { Icon(Icons.Filled.Autorenew, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            ListSection("状态") {
                ListItem(
                    headlineContent = { Text(if (state.checking) "正在检查…" else "检查结果") },
                    supportingContent = { Text(checkStatusText(state)) },
                    leadingContent = {
                        if (state.checking) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Autorenew, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                if (newer != null) {
                    Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)) {
                        NewerBanner(
                            version = newer.version,
                            download = download,
                            onDownload = {
                                val url = newer.apkUrl
                                if (url == null) {
                                    openUrl(context, newer.pageUrl)
                                } else {
                                    download =
                                        com.thirdparty.xhs.ui.components.UpdateDownloadState
                                            .Running(0L, -1L)
                                    scope.launch {
                                        val outcome =
                                            com.thirdparty.xhs.net.UpdateDownloader.download(
                                                context = context,
                                                url = url,
                                                version = newer.version,
                                                progress = { w, t ->
                                                    download =
                                                        com.thirdparty.xhs.ui.components
                                                            .UpdateDownloadState.Running(w, t)
                                                }
                                            )
                                        download = when (outcome) {
                                            is com.thirdparty.xhs.net.UpdateDownloader.Outcome.Ready ->
                                                com.thirdparty.xhs.ui.components
                                                    .UpdateDownloadState.Ready(outcome.versionName)

                                            is com.thirdparty.xhs.net.UpdateDownloader.Outcome.Failed ->
                                                com.thirdparty.xhs.ui.components
                                                    .UpdateDownloadState.Failed(outcome.reason)
                                        }
                                    }
                                }
                            },
                            onInstall = {
                                downloadedFile?.let { f ->
                                    runCatching {
                                        context.startActivity(
                                            com.thirdparty.xhs.net.UpdateDownloader
                                                .installIntent(context, f)
                                        )
                                    }.onFailure {
                                        runCatching {
                                            context.startActivity(
                                                com.thirdparty.xhs.net.UpdateDownloader
                                                    .unknownSourcesSettingsIntent(context)
                                            )
                                        }
                                    }
                                }
                            },
                            onOpenPage = { openUrl(context, newer.pageUrl) },
                            onShowNotes = if (newer.notes.isNotEmpty()) ({ showNotes = true }) else null
                        )
                    }
                }
            }

            Button(
                onClick = {
                    haptics.tick()
                    viewModel.check()
                },
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m)
            ) {
                Text(if (state.checkedAt == 0L) "检查更新" else "重新检查")
            }

            Text(
                "只查询 GitHub 上的公开发布信息（走 releases.atom，不占用 API 额度）。发现新版后可以直接在本" +
                    "应用内下载安装，也可以去浏览器打开发布页 —— 装不装由你决定。「没有正式版」「限流」「断网」" +
                    "都会如实写在上面的状态里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)
            )
            Spacer(Modifier.height(Spacing.l))
        }
    }

    if (showNotes && newer != null) {
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text("v${newer.version} 更新说明") },
            text = { Text(newer.notes) },
            confirmButton = {
                TextButton(onClick = haptics.click {
                    showNotes = false
                    openUrl(context, newer.apkUrl ?: newer.pageUrl)
                }) { Text("打开下载页") }
            },
            dismissButton = {
                TextButton(onClick = haptics.click { showNotes = false }) { Text("关闭") }
            }
        )
    }
}

/** 有更新时才出现的行动区：一个下载按钮 + 可选的更新说明入口。 */
/**
 * 有更新时才出现的行动区。
 *
 * 主按钮是**应用内下载**（下完变成「安装」），旁边是「浏览器打开」与「更新说明」——
 * 两条路都给，用户自己挑（有人习惯用浏览器看发布页，有人嫌来回切麻烦）。
 */
@Composable
private fun NewerBanner(
    version: String,
    download: com.thirdparty.xhs.ui.components.UpdateDownloadState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPage: () -> Unit,
    onShowNotes: (() -> Unit)?
) {
    val haptics = rememberHaptics()
    Surface(
        shape = Corners.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Spacing.l)) {
            Text("发现新版本 v$version", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.s))
            when (download) {
                is com.thirdparty.xhs.ui.components.UpdateDownloadState.Running -> {
                    Text(
                        if (download.totalBytes > 0) {
                            "正在下载 ${download.percent}%"
                        } else {
                            "正在下载…"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    androidx.compose.material3.LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is com.thirdparty.xhs.ui.components.UpdateDownloadState.Failed -> {
                    Text(
                        "下载失败：${download.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }

                is com.thirdparty.xhs.ui.components.UpdateDownloadState.Ready -> {
                    Text(
                        "安装包已下载完成，点「安装」交给系统安装器。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }

                is com.thirdparty.xhs.ui.components.UpdateDownloadState.Idle -> Unit
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = haptics.click(
                        when (download) {
                            is com.thirdparty.xhs.ui.components.UpdateDownloadState.Ready -> onInstall
                            else -> onDownload
                        }
                    ),
                    enabled = download !is com.thirdparty.xhs.ui.components.UpdateDownloadState.Running
                ) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.s))
                    Text(
                        when (download) {
                            is com.thirdparty.xhs.ui.components.UpdateDownloadState.Ready -> "安装"
                            is com.thirdparty.xhs.ui.components.UpdateDownloadState.Failed -> "重试下载"
                            is com.thirdparty.xhs.ui.components.UpdateDownloadState.Running -> "下载中…"
                            is com.thirdparty.xhs.ui.components.UpdateDownloadState.Idle -> "应用内下载"
                        }
                    )
                }
                Spacer(Modifier.size(Spacing.s))
                TextButton(onClick = haptics.click(onOpenPage)) { Text("浏览器打开") }
                if (onShowNotes != null) {
                    TextButton(onClick = haptics.click(onShowNotes)) { Text("更新说明") }
                }
            }
        }
    }
}

private fun checkStatusText(state: UpdateUiState): String {
    if (state.checking) return "正在向 GitHub 查询最新版本…"
    return when (val r = state.result) {
        null -> "还没有查过"
        is UpdateChecker.Result.Newer -> "有新版 v${r.version}，当前是 v${state.versionName}"
        is UpdateChecker.Result.UpToDate -> "已是最新版本（v${r.current}）"
        is UpdateChecker.Result.NoRelease -> "GitHub 上还没有发布版本"
        is UpdateChecker.Result.Failed -> "检查失败：${r.reason}"
    }
}
