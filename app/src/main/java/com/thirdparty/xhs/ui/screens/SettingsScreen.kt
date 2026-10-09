package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.thirdparty.xhs.ui.components.SectionLabel
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.theme.ThemeMode
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import com.thirdparty.xhs.data.AppCaches
import com.thirdparty.xhs.data.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.thirdparty.xhs.ui.theme.Corners
import androidx.compose.foundation.layout.fillMaxWidth

/**
 * 设置.
 *
 * Everything that is a *preference* lives here; 我的 keeps the account card and
 * the user's own content (收藏 / 最近浏览 / 关注的作者). Mixing the two made 我的 a
 * long list where the account controls and the app settings were interleaved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onSetTheme: (ThemeMode) -> Unit,
    biometricLock: Boolean,
    biometricAvailable: Boolean,
    onSetBiometricLock: (Boolean) -> Unit,
    historyLimit: Int,
    onSetHistoryLimit: (Int) -> Unit,
    autoVip: Boolean,
    onSetAutoVip: (Boolean) -> Unit,
    onOpenBackup: () -> Unit,
    /** 清除缓存：多选要清的缓存类型 */
    onOpenCache: () -> Unit
) {
    var pickTheme by remember { mutableStateOf(false) }
    // 设置项的点击反馈（系统触感）
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()
    // The lock / limit / auto-switch values come from SharedPreferences, which is
    // NOT observable. Reading App.repo.* directly meant no recomposition after a
    // change: the switch stayed visually put and the next tap computed
    // !staleValue again, so it could never be toggled off. Mirror them locally.
    var bioOn by remember { mutableStateOf(biometricLock) }
    var autoOn by remember { mutableStateOf(autoVip) }
    var limit by remember { androidx.compose.runtime.mutableIntStateOf(historyLimit) }
    var pickLimit by remember { mutableStateOf(false) }

    // 数据 组的说明里要显示缓存总共占多少。量一次就够了：这张页面每次被打开
    // 都会重新组合（清缓存在另一个页面，回来时数值自然刷新），而且量磁盘缓存
    // 会 flush OkHttp 的日志，不能放在主线程。
    var cacheBytes by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { AppCaches.totalBytes() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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

            // M3 (late 2025) list redesign: **contained groups separated by gaps**,
            // rather than full-width rows divided by hairlines. The group sits on a
            // contrasting container colour and each section reads as one object.
            SettingsGroup("外观") {
                ListItem(
                    headlineContent = { Text("外观主题") },
                    supportingContent = { Text(themeMode.label()) },
                    leadingContent = { Icon(Icons.Filled.Palette, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { haptics.tick(); pickTheme = true }
                )
            }

            SettingsGroup("安全") {
                ListItem(
                    headlineContent = { Text("指纹解锁") },
                    supportingContent = {
                        Text(
                            if (biometricAvailable) "每次打开或切回都要验指纹（或设备密码）"
                            else "先在系统里录入指纹或设个锁屏密码，才能开启"
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(
                            checked = bioOn,
                            enabled = biometricAvailable,
                            onCheckedChange = {
                                haptics.tick()
                                bioOn = it
                                onSetBiometricLock(it)
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        // never let the toggle be flipped on where it cannot be honoured
                        if (biometricAvailable) {
                            haptics.tick()
                            bioOn = !bioOn
                            onSetBiometricLock(bioOn)
                        }
                    }
                )
            }

            SettingsGroup("内容") {
                ListItem(
                    headlineContent = { Text("最近浏览上限") },
                    supportingContent = { Text("当前保留 $limit 条，超出后自动清理最旧的") },
                    leadingContent = { Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { haptics.tick(); pickLimit = true }
                )

                ListItem(
                    headlineContent = { Text("VIP 到期自动切换") },
                    supportingContent = { Text("当前账号的 VIP 用完时，自动换成有 VIP 的新账号") },
                    leadingContent = { Icon(Icons.Filled.Autorenew, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = {
                        Switch(checked = autoOn, onCheckedChange = {
                            haptics.tick()
                            autoOn = it
                            onSetAutoVip(it)
                        })
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        haptics.tick()
                        autoOn = !autoOn
                        onSetAutoVip(autoOn)
                    }
                )
            }

            SettingsGroup("数据") {
                ListItem(
                    headlineContent = { Text("备份与恢复") },
                    supportingContent = { Text("本地文件或 WebDAV，含收藏 / 浏览 / 关注（不含账号）") },
                    leadingContent = { Icon(Icons.Filled.CloudUpload, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { haptics.tick(); onOpenBackup() }
                )
                ListItem(
                    headlineContent = { Text("清除缓存") },
                    supportingContent = {
                        Text(
                            if (cacheBytes > 0) "图片 / 临时文件，共 ${formatBytes(cacheBytes)}；可逐项勾选"
                            else "图片 / 临时文件；可逐项勾选"
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.DeleteSweep, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { haptics.tick(); onOpenCache() }
                )
            }
        }
    }

    if (pickTheme) {
        AlertDialog(
            onDismissRequest = { pickTheme = false },
            title = { Text("外观主题") },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        ListItem(
                            headlineContent = { Text(mode.label()) },
                            leadingContent = {
                                RadioButton(selected = mode == themeMode, onClick = {
                                    haptics.tick()
                                    onSetTheme(mode)
                                    pickTheme = false
                                })
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.clickable {
                                haptics.tick()
                                onSetTheme(mode)
                                pickTheme = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = haptics.click { pickTheme = false }) { Text("关闭") } }
        )
    }

    if (pickLimit) {
        AlertDialog(
            onDismissRequest = { pickLimit = false },
            title = { Text("最近浏览上限") },
            text = {
                Column {
                    listOf(500, 1000, 2000, 5000, 10000).forEach { n ->
                        ListItem(
                            headlineContent = { Text("$n 条" + if (n == limit) "（当前）" else "") },
                            leadingContent = {
                                RadioButton(selected = n == limit, onClick = {
                                    haptics.tick()
                                    limit = n
                                    onSetHistoryLimit(n)
                                    pickLimit = false
                                })
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.clickable {
                                haptics.tick()
                                limit = n
                                onSetHistoryLimit(n)
                                pickLimit = false
                            }
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = haptics.click { pickLimit = false }) { Text("关闭") } }
        )
    }

}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

/** Section header, shared with the 我的 page for a consistent rhythm. */
/**
 * A contained list group (M3 late-2025 list redesign).
 *
 * Sections used to be full-width rows divided by hairlines, which reads as one
 * continuous list. Grouping each section onto its own rounded container over a
 * contrasting page surface — with a gap between groups — makes the sections
 * legible as separate objects without needing dividers at all.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SettingsGroup(
    label: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    SectionLabel(label)
    Surface(
        shape = Corners.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m)
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
    Spacer(Modifier.height(Spacing.l))
}

