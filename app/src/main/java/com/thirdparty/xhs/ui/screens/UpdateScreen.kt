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
import androidx.compose.runtime.setValue
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
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.UpdateUiState
import com.thirdparty.xhs.ui.viewmodel.UpdateViewModel

/**
 * 检查更新：独立页面，只做「查 GitHub Releases 上有没有新版本」这一件事。
 *
 * 这是本应用唯一不经 AES 加密包体的请求（见 net/UpdateChecker）：GET 一个公开 JSON，
 * 不带账号信息。它只给出发布页链接，不自己下载、不自己安装。
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

    // 只会真正触发一次：ViewModel 记住 entered
    LaunchedEffect(Unit) { viewModel.onEnter() }

    val newer = state.result as? UpdateChecker.Result.Newer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检查更新") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                            onOpenPage = { openUrl(context, newer.apkUrl ?: newer.pageUrl) },
                            onShowNotes = if (newer.notes.isNotEmpty()) ({ showNotes = true }) else null
                        )
                    }
                }
            }

            Button(
                onClick = { viewModel.check() },
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m)
            ) {
                Text(if (state.checkedAt == 0L) "检查更新" else "重新检查")
            }

            Text(
                "只查询 GitHub 上的公开发布信息，不会自动下载或安装。有新版本时会给出下载页链接，" +
                    "由你自己决定装不装。「没有正式版」「被限流」「断网」都会如实写在上面的状态里。",
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
                TextButton(onClick = {
                    showNotes = false
                    openUrl(context, newer.apkUrl ?: newer.pageUrl)
                }) { Text("打开下载页") }
            },
            dismissButton = { TextButton(onClick = { showNotes = false }) { Text("关闭") } }
        )
    }
}

/** 有更新时才出现的行动区：一个下载按钮 + 可选的更新说明入口。 */
@Composable
private fun NewerBanner(version: String, onOpenPage: () -> Unit, onShowNotes: (() -> Unit)?) {
    Surface(
        shape = Corners.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Spacing.l)) {
            Text("发现新版本 v$version", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpenPage) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(Spacing.s))
                    Text("打开下载页")
                }
                if (onShowNotes != null) {
                    Spacer(Modifier.size(Spacing.s))
                    TextButton(onClick = onShowNotes) { Text("更新说明") }
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
