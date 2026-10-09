package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.thirdparty.xhs.BuildConfig
import com.thirdparty.xhs.net.UpdateChecker
import com.thirdparty.xhs.ui.components.ListSection
import com.thirdparty.xhs.ui.components.openUrl
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 关于：这份应用自己是谁（版本 / 包名 / 客户端协议）、仓库与许可、免责声明。
 *
 * 「检查更新」有自己的页面（[UpdateScreen]，路由 `update`）：这里只放一个跳转入口，
 * 不在这里发起任何网络请求。两个入口都在「我的」页上，不在设置里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit
) {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
            ListSection("版本") {
                ListItem(
                    headlineContent = { Text("版本") },
                    supportingContent = { Text("v${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）") },
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
                        Text("Client-Version ${BuildConfig.CLIENT_VERSION} · Client-Channel ${BuildConfig.CLIENT_CHANNEL}")
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            ListSection("更新") {
                ListItem(
                    headlineContent = { Text("检查更新") },
                    supportingContent = { Text("去 GitHub 发布页看看有没有新版本") },
                    leadingContent = { Icon(Icons.Filled.Autorenew, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { haptics.tick(); onOpenUpdate() }
                )
            }

            ListSection("关于项目") {
                ListItem(
                    headlineContent = { Text("GitHub 仓库") },
                    supportingContent = { Text("limao996/xhs-thirdparty") },
                    leadingContent = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { haptics.tick(); openUrl(context, UpdateChecker.REPO_URL) }
                )
                ListItem(
                    headlineContent = { Text("开源许可") },
                    supportingContent = { Text("MIT License") },
                    leadingContent = { Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { haptics.tick(); showLicense = true }
                )
            }

            Text(
                "本应用是第三方客户端，与「小黄书」官方无任何关系，未获授权，仅供学习与技术研究。 " +
                    "它不提供任何内容；VIP 权限不是靠篡改校验骗出来的，而是自动注册新的游客身份、" +
                    "领取服务端发放给新游客的体验窗口 —— 具体机制见仓库里的 AGENTS.md 与 docs/PROTOCOL.md。" +
                    "如有侵权，请到 GitHub 仓库提 Issue，作者会立即下架。",
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
            confirmButton = {
                TextButton(onClick = haptics.click { showLicense = false }) { Text("关闭") }
            }
        )
    }
}
