@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.material.icons.filled.Search
import com.thirdparty.xhs.ui.theme.bottomNavClearance
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.background

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
    /** stat taps on the account card */
    onOpenFollowing: (() -> Unit)? = null,
    onOpenFans: (() -> Unit)? = null,
    onOpenMyNotes: ((Int) -> Unit)? = null,
    /** 备份与恢复 */

    /** VIP-expiry auto switch */
    autoVip: Boolean = false,
    onSetAutoVip: ((Boolean) -> Unit)? = null,
    /** app lock: require the device fingerprint/password on open */
    biometricLock: Boolean = false,
    biometricAvailable: Boolean = false,
    onSetBiometricLock: ((Boolean) -> Unit)? = null,
    /** 最近浏览 keep limit */
    historyLimit: Int = 2000,
    onSetHistoryLimit: ((Int) -> Unit)? = null,
    /** 设置 entry */
    onOpenSettings: (() -> Unit)? = null,
    rotating: Boolean = false,
    /** changes whenever the guest account changes, forcing a profile reload */
    reloadKey: Any? = Unit,
    viewModel: ProfileViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    // keyed on the account: after a guest switch the cached profile would
    // otherwise keep showing the previous account's id and VIP state
    LaunchedEffect(reloadKey) { viewModel.load() }

    if (state.loading && state.profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        return
    }

    // The 我的 page stacks ~10 rows (account card + stats + 3 entries + guest
    // switch + scan + cache + theme picker). A plain Column CLIPS whatever does
    // not fit and cannot be scrolled — it must be scrollable. The bottom padding
    // keeps the last rows clear of the floating NavigationBar, which is drawn on
    // top of this area; without it 外观主题 could never be brought into view.
    Column(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomNavClearance())
    ) {
        // Account header, as its own contained card.
        //
        // It used to be a full-bleed colour band while every row below it was a
        // rounded card on that band, so the most important block on the page was
        // the one that did not follow the page's own pattern.
        Surface(
            shape = Corners.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.m, vertical = Spacing.m)
        ) {
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
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            if (state.error) "点击下方「切换游客账号」重试"
                            else "游客 ID：${state.profile?.userId ?: 0}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // VIP expiry, when the account has one
                        val end = state.profile?.vipEnd ?: 0L
                        if (end > System.currentTimeMillis() / 1000) {
                            Text(
                                "会员有效期至 " + java.text.SimpleDateFormat(
                                    "MM-dd HH:mm", java.util.Locale.getDefault()
                                ).format(java.util.Date(end * 1000)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
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
                            if (vip) "VIP用户" else "普通用户",
                            Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // account stats (real values from v2/mine/user-info).
                // Each one is tappable: 关注 / 粉丝 open the server-backed lists,
                // 作品 opens this account's own author page.
                state.profile?.let { p ->
                    Spacer(Modifier.height(Spacing.l))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ProfileStat("关注", p.follows, enabled = onOpenFollowing != null) {
                            onOpenFollowing?.invoke()
                        }
                        ProfileStat("粉丝", p.fans, enabled = onOpenFans != null) {
                            onOpenFans?.invoke()
                        }
                        ProfileStat("作品", p.notes, enabled = onOpenMyNotes != null) {
                            onOpenMyNotes?.invoke(p.userId)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.m))
        SectionLabel("我的内容")

        ProfileEntry(Icons.Filled.Favorite, "我的收藏", "${state.savedCount} 条", onOpenSaved)
        ProfileEntry(Icons.Filled.History, "最近浏览", "${state.historyCount} 条", onOpenHistory)
        ProfileEntry(Icons.Filled.Group, "我关注的作者", "${state.followedCount} 位", onOpenFollowed)

        Spacer(Modifier.height(Spacing.m))
        SectionLabel("账号")
        if (onRotateGuest != null) {
            // confirm first: switching creates a brand-new account and the previous
            // identity is gone for good (there is no account history any more), so a
            // stray tap is not trivially undone.
            var confirmRotate by remember { mutableStateOf(false) }
            if (confirmRotate) {
                AlertDialog(
                    onDismissRequest = { confirmRotate = false },
                    title = { Text("切换游客账号？") },
                    text = { Text("将创建一个全新的随机账号，当前账号将无法找回。") },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmRotate = false
                            onRotateGuest()
                        }) { Text("切换") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmRotate = false }) { Text("取消") }
                    }
                )
            }
            Spacer(Modifier.height(Spacing.m))
            ListItem(
                headlineContent = { Text("切换游客账号") },
                supportingContent = { Text(if (rotating) "正在创建新的随机账号…" else "生成一个全新的随机游客账号（每次不同）") },
                leadingContent = {
                    if (rotating) LoadingIndicator(Modifier.size(24.dp))
                    else Icon(Icons.Filled.SwitchAccount, null, tint = MaterialTheme.colorScheme.primary)
                },
                colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ),
                    // confirm first: switching creates a brand-new account and the
                    // previous identity cannot be recovered, so an accidental tap is
                    // not trivially undone.
                    modifier = Modifier.cardRow { confirmRotate = true }
            )
        }

        Spacer(Modifier.height(Spacing.m))
        SectionLabel("其他")
        if (onOpenSettings != null) {
            ProfileEntry(
                Icons.Filled.Settings, "设置",
                "外观 / 指纹解锁 / 最近浏览上限 / 备份",
                onOpenSettings
            )
        }
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
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            ),
            modifier = Modifier.cardRow { confirmClear = true }
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

    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
private fun ProfileStat(
    label: String,
    value: Int,
    enabled: Boolean = false,
    onClick: () -> Unit = {}
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = Spacing.xs)
    ) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            // a tappable count is tinted so the affordance is visible
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
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
        // Each row is its own rounded container over the contrasting page surface
        // (M3 late-2025 list redesign). Rows used to be full-width slabs on the
        // page colour, which read as one undifferentiated list.
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        modifier = Modifier
            .padding(horizontal = Spacing.m, vertical = 2.dp)
            .clip(Corners.large)
            .clickable { onClick() }
    )
}


/**
 * Makes a full-width list row read as its own rounded card, matching [ProfileEntry].
 *
 * The page sits on `surfaceContainerLow` and every row floats on it as a
 * `surfaceContainerLowest` card with a small gap — the M3 late-2025 "contained
 * list" look. Applied via a modifier so the rows that are plain [ListItem]s
 * (账号 / 其他 sections) cannot drift away from the ones built by [ProfileEntry].
 */
@Composable private fun Modifier.cardRow(onClick: () -> Unit): Modifier = this
    .padding(horizontal = Spacing.m, vertical = 2.dp)
    .clip(Corners.large)
    .clickable { onClick() }

/** Section header, matching the one used on the 设置 page. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.xs)
    )
}