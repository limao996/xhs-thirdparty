package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import com.thirdparty.xhs.BuildConfig
import com.thirdparty.xhs.net.UpdateChecker
import com.thirdparty.xhs.ui.theme.Spacing

/** 应用内下载的状态（由宿主持有，弹窗只负责画）。 */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Running(val writtenBytes: Long, val totalBytes: Long) : UpdateDownloadState {
        val percent: Int
            get() = if (totalBytes > 0) ((writtenBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }
    data class Ready(val versionName: String?) : UpdateDownloadState
    data class Failed(val reason: String) : UpdateDownloadState
}

/**
 * 「进来就告诉你有没有新版」的那个弹窗（设置里手动查的是 检查更新 页面）。
 *
 * 只在真的有新版时出现：查不到、被限流、断网、没有正式版都不会弹任何东西 ——
 * 启动时打扰用户必须是因为确有其事。
 *
 * 四条出口：
 * - **应用内下载**：直接下 APK 并用系统安装器安装（下完按钮变「安装」）；
 * - **浏览器打开**：去发布页自己下（老习惯，也留着）；
 * - **以后再说**：本次启动不再提；
 * - **跳过这个版本**：记住版本号，以后启动也不再提，直到有更新的版本。
 *
 * 另外两点是刻意的：
 * 1. `dismissOnClickOutside = false` —— 以前会有"刚弹出来就没了"的情况（点外面/系统把
 *    启动那一下的触摸补投进来就会 dismiss）。现在误触不会关掉弹窗，要关得按「以后再说」或返回键。
 * 2. 下载进度画在弹窗里，失败给出**具体原因**（不是笼统的"下载失败"），并提供重试。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateChecker.Result.Newer,
    download: UpdateDownloadState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onBrowser: () -> Unit,
    onLater: () -> Unit,
    onSkipVersion: () -> Unit
) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onLater,
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Filled.SystemUpdate, null) },
        title = { Text("发现新版本 v${info.version}") },
        text = {
            Column {
                Text(
                    "当前版本 v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!info.notes.isNullOrBlank()) {
                    Spacer(Modifier.height(Spacing.m))
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(Spacing.m))
                when (download) {
                    is UpdateDownloadState.Idle -> Text(
                        if (info.apkUrl != null) {
                            "可在应用内直接下载安装，也可前往发布页手动下载。"
                        } else {
                            "未找到安装包直链，请前往发布页下载。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    is UpdateDownloadState.Running -> {
                        Text(
                            if (download.totalBytes > 0) {
                                "正在下载 ${download.percent}%（${mb(download.writtenBytes)} / ${mb(download.totalBytes)}）"
                            } else {
                                "正在下载 ${mb(download.writtenBytes)}"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(Spacing.s))
                        if (download.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { download.percent / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    is UpdateDownloadState.Ready -> Text(
                        "下载完成${download.versionName?.let { "（v$it）" } ?: ""}，点击「安装」继续。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    is UpdateDownloadState.Failed -> Text(
                        "下载失败：${download.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(Spacing.s))
                TextButton(onClick = haptics.rejectClick(onSkipVersion)) {
                    Text("跳过这个版本")
                }
            }
        },
        confirmButton = {
            when (download) {
                is UpdateDownloadState.Ready ->
                    TextButton(onClick = haptics.confirmClick(onInstall)) { Text("安装") }

                is UpdateDownloadState.Running ->
                    TextButton(onClick = {}, enabled = false) { Text("下载中…") }

                is UpdateDownloadState.Failed ->
                    TextButton(onClick = haptics.click(onDownload)) { Text("重试下载") }

                is UpdateDownloadState.Idle ->
                    TextButton(
                        onClick = haptics.click(onDownload),
                        enabled = info.apkUrl != null
                    ) { Text("应用内下载") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = haptics.click(onBrowser)) { Text("浏览器打开") }
                TextButton(onClick = haptics.click(onLater)) { Text("以后再说") }
            }
        }
    )
}

private fun mb(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb < 1024) "${(kb * 10).toInt() / 10.0} KB" else "${(kb / 1024 * 10).toInt() / 10.0} MB"
}
