package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thirdparty.xhs.BuildConfig
import com.thirdparty.xhs.net.UpdateChecker
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 「进来就告诉你有没有新版」的那个弹窗（设置里手动查的是 检查更新 页面）。
 *
 * 只在真的有新版时出现：查不到、被限流、断网、没有正式版都不会弹任何东西 ——
 * 启动时打扰用户必须是因为确有其事。
 *
 * 三个出口各有含义：打开下载页（去 GitHub）、以后再说（本次启动不再提）、
 * 跳过这个版本（记住这个版本号，以后启动也不再提，直到有更新的版本）。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateChecker.Result.Newer,
    onOpenPage: () -> Unit,
    onLater: () -> Unit,
    onSkipVersion: () -> Unit
) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onLater,
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
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(Spacing.m))
                Text(
                    "只会打开 GitHub 发布页，不会自动下载或安装。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = haptics.click(onOpenPage)) { Text("打开下载页") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = haptics.rejectClick(onSkipVersion)) { Text("跳过这个版本") }
                TextButton(onClick = haptics.click(onLater)) { Text("以后再说") }
            }
        }
    )
}
