package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.App
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.theme.ThemeMode
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.viewmodel.ProfileViewModel

/**
 * 我的：账号信息（用户名/ID/VIP）+ 收藏 / 最近浏览 / 我关注的作者 / 切换游客 / 外观主题。
 * 全部使用 Material3 令牌（无硬编码颜色）。
 */
@Composable
fun ProfileScreen(
    onOpenSaved: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenFollowed: () -> Unit,
    onRotateGuest: (() -> Unit)? = null,
    rotating: Boolean = false,
    viewModel: ProfileViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }

    if (state.loading && state.profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // account header
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(Spacing.l)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XhsAvatar(
                        url = state.profile?.headImg,
                        contentDescription = "头像",
                        modifier = Modifier.size(AvatarSize.profile)
                    )
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.profile?.userName ?: if (state.error) "账号信息加载失败" else "游客",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            if (state.error) "点击下方「切换游客账号」重试"
                            else "游客 ID：${state.profile?.userId ?: 0}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val vip = state.profile?.isVip == true
                    Surface(
                        shape = Corners.small,
                        color = if (vip) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (vip) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            if (vip) "会员 VIP" else "普通用户",
                            Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // account stats (real values from v2/mine/user-info)
                state.profile?.let { p ->
                    Spacer(Modifier.height(Spacing.l))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ProfileStat("关注", p.follows)
                        ProfileStat("粉丝", p.fans)
                        ProfileStat("作品", p.notes)
                    }
                }
            }
        }
        HorizontalDivider()

        ProfileEntry(Icons.Filled.Favorite, "我的收藏", "${state.savedCount} 条", onOpenSaved)
        ProfileEntry(Icons.Filled.History, "最近浏览", "${state.historyCount} 条", onOpenHistory)
        ProfileEntry(Icons.Filled.Group, "我关注的作者", "${state.followedCount} 位", onOpenFollowed)

        if (onRotateGuest != null) {
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("切换游客账号") },
                supportingContent = { Text(if (rotating) "切换中…" else "获取一个新的游客会话") },
                leadingContent = {
                    if (rotating) CircularProgressIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Filled.SwitchAccount, null, tint = MaterialTheme.colorScheme.primary)
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.clickable { onRotateGuest() }
            )
        }

        HorizontalDivider()

        // image cache management (the disk cache can hold up to 64MB)
        var confirmClear by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text("清除图片缓存") },
            supportingContent = { Text(formatBytes(state.cacheBytes)) },
            leadingContent = {
                Icon(
                    Icons.Filled.DeleteSweep,
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.clickable { confirmClear = true }
        )
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清除图片缓存？") },
                text = { Text("将删除已缓存的封面与头像（${formatBytes(state.cacheBytes)}），下次浏览时重新下载。") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.clearCache()
                        confirmClear = false
                    }) { Text("清除") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) { Text("取消") }
                }
            )
        }

        HorizontalDivider()
        ThemeSwitcher()
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun ProfileStat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProfileEntry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.clickable { onClick() }
    )
}

@Composable
private fun ThemeSwitcher() {
    val currentMode by App.INSTANCE.themeState.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.m)) {
        Text(
            "外观主题",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf(
                ThemeMode.SYSTEM to "跟随系统",
                ThemeMode.LIGHT to "浅色",
                ThemeMode.DARK to "深色"
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = currentMode == mode,
                    onClick = { App.INSTANCE.setThemeMode(mode) },
                    label = { Text(label) }
                )
            }
        }
    }
}