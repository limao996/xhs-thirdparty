package com.thirdparty.xhs.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.net.UpdateChecker
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.AboutUiState
import com.thirdparty.xhs.ui.viewmodel.AboutViewModel

/**
 * 关于：版本信息 / 检查更新 / 项目与许可 / 免责声明。
 *
 * 检查更新访问的是 GitHub 的公开 API（见 net/UpdateChecker），是本应用唯一不经
 * AES 加密包体的请求；它只做「查版本 + 给出发布页链接」，不自己下载安装。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    autoCheck: Boolean,
    onBack: () -> Unit,
    viewModel: AboutViewModel = viewModel()
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }

    // 只会真正触发一次：ViewModel 记住 entered
    LaunchedEffect(autoCheck) { viewModel.onEnter(autoCheck) }

    val newer = state.result as? UpdateChecker.Result.Newer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
            AboutGroup("版本") {
                ListItem(
                    headlineContent = { Text("版本") },
                    supportingContent = { Text("v${state.versionName}（build ${state.versionCode}）") },
                    leadingContent = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ListItem(
                    headlineContent = { Text("包名") },
                    supportingContent = { Text("com.thirdparty.xhs") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ListItem(
                    headlineContent = { Text("客户端协议") },
                    supportingContent = {
                        Text("Client-Version ${state.clientVersion} · Client-Channel ${state.clientChannel}")
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            AboutGroup("更新") {
                ListItem(
                    headlineContent = { Text("检查更新") },
                    supportingContent = { Text(checkStatusText(state)) },
                    leadingContent = { Icon(Icons.Filled.Autorenew, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        if (state.checking) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                if (state.result == null) "检查" else "重试",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { viewModel.check() }
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

            AboutGroup("关于项目") {
                ListItem(
                    headlineContent = { Text("GitHub 仓库") },
                    supportingContent = { Text("limao996/xhs-thirdparty") },
                    leadingContent = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { openUrl(context, UpdateChecker.REPO_URL) }
                )
                ListItem(
                    headlineContent = { Text("开源许可") },
                    supportingContent = { Text("MIT License") },
                    leadingContent = { Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { showLicense = true }
                )
            }

            Text(
                "本应用是第三方客户端，与「小黄书」官方无任何关系，未获授权，仅供学习与技术研究。 " +
                    "它不提供任何内容，也不破解付费校验：它做的是自动注册一次性游客身份，去领取" +
                    "服务端发给新游客的体验权限。如有侵权，请到 GitHub 仓库提 Issue，作者会立即下架。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)
            )
            Spacer(Modifier.height(Spacing.l))
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("开源许可") },
            text = {
                Column {
                    Text("MIT License")
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        "版权所有 (c) 2026 limao996\n\n" +
                            "特此免费授予任何获得本软件及相关文档文件副本的人不受限制地处置本软件的" +
                            "权利，包括但不限于使用、复制、修改、合并、发布、分发、再许可和/或销售" +
                            "本软件副本的权利，但须满足：上述版权声明和本许可声明应包含在本软件的" +
                            "所有副本或实质性部分中。\n\n" +
                            "本软件按「原样」提供，不附带任何形式的担保。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(Spacing.m))
                    Text(
                        "完整文本见仓库根目录的 LICENSE 文件。许可只覆盖客户端源码与文档，" +
                            "不覆盖任何第三方内容、商标或数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } }
        )
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
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
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

private fun checkStatusText(state: AboutUiState): String {
    if (state.checking) return "正在检查…"
    return when (val r = state.result) {
        null -> "从 GitHub Releases 获取最新版本"
        is UpdateChecker.Result.Newer -> "发现新版本 v${r.version}"
        is UpdateChecker.Result.UpToDate -> "已是最新版本（v${r.current}）"
        is UpdateChecker.Result.NoRelease -> "GitHub 上还没有发布版本"
        is UpdateChecker.Result.Failed -> "检查失败：${r.reason}"
    }
}

/** 用系统浏览器打开链接；没有浏览器时明确提示，不静默失败。 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    val ok = runCatching { context.startActivity(intent) }.isSuccess
    if (!ok) Toast.makeText(context, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show()
}

/** 与设置页同款的分组（M3 late-2025 列表：圆角容器 + 组间留白）。 */
@Composable
private fun ColumnScope.AboutGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.s)
    )
    Surface(
        shape = Corners.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m)
    ) {
        Column(content = content)
    }
    Spacer(Modifier.height(Spacing.l))
}
