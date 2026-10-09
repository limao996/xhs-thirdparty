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
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.components.SectionLabel
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.viewmodel.ProfileViewModel
import com.thirdparty.xhs.ui.theme.bottomNavClearance
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.ui.draw.clip
import com.thirdparty.xhs.BuildConfig
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
    /** 设置 entry（主题 / 应用锁 / 历史上限 / 自动换号都在设置页，这里只留入口） */
    onOpenSettings: (() -> Unit)? = null,
    /** 关于 / 检查更新：两个各自独立的页面，入口在这里（不在设置里） */
    onOpenAbout: (() -> Unit)? = null,
    onOpenUpdate: (() -> Unit)? = null,
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
        // 「切换游客账号」等入口的点击反馈
        val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()
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
                            if (state.error) "可切换游客账号后重试"
                            else "游客 ID：${state.profile?.userId ?: 0}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // VIP expiry, when the account has one
                        val end = state.profile?.vipEnd ?: 0L
                        if (end > System.currentTimeMillis() / 1000) {
                            // 用 LocalConfiguration 里的 locale 作为依赖：直接读 Locale.getDefault()
                            // 在组合里是非可观察的（lint NonObservableLocale），系统语言变了不会重算
                            val locale =
                                androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
                            val formatted = remember(end, locale) {
                                java.text.SimpleDateFormat("MM-dd HH:mm", locale)
                                    .format(java.util.Date(end * 1000))
                            }
                            Text(
                                "会员有效期至 $formatted",
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
        SectionLabel("我的内容", bottom = Spacing.xs)

        ProfileEntry(Icons.Filled.Favorite, "我的收藏", "${state.savedCount} 条", onOpenSaved)
        ProfileEntry(Icons.Filled.History, "最近浏览", "${state.historyCount} 条", onOpenHistory)
        ProfileEntry(Icons.Filled.Group, "我关注的作者", "${state.followedCount} 位", onOpenFollowed)

        Spacer(Modifier.height(Spacing.m))
        SectionLabel("账号", bottom = Spacing.xs)
        if (onRotateGuest != null) {
            // confirm first: switching creates a brand-new account and the previous
            // identity is gone for good (there is no account history any more), so a
            // stray tap is not trivially undone.
            var confirmRotate by remember { mutableStateOf(false) }
            if (confirmRotate) {
                AlertDialog(
                    onDismissRequest = { confirmRotate = false },
                    title = { Text("切换游客账号？") },
                    text = { Text("将切换为新的随机账号，当前账号将无法找回。") },
                    confirmButton = {
                        TextButton(onClick = haptics.rejectClick {
                            confirmRotate = false
                            onRotateGuest()
                        }) { Text("切换") }
                    },
                    dismissButton = {
                        TextButton(onClick = haptics.click { confirmRotate = false }) { Text("取消") }
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
                    modifier = Modifier.cardRow {
                        haptics.tick()
                        confirmRotate = true
                    }
            )
        }

        Spacer(Modifier.height(Spacing.m))
        SectionLabel("其他", bottom = Spacing.xs)
        if (onOpenSettings != null) {
            ProfileEntry(
                Icons.Filled.Settings, "设置",
                "外观 / 指纹解锁 / 最近浏览上限 / 备份 / 清除缓存",
                onOpenSettings
            )
        }
        if (onOpenAbout != null) {
            ProfileEntry(
                Icons.Filled.Info, "关于小黄书",
                "版本 v${BuildConfig.VERSION_NAME} · 许可与免责声明",
                onOpenAbout
            )
        }
        if (onOpenUpdate != null) {
            ProfileEntry(
                Icons.Filled.Autorenew, "检查更新",
                "查看 GitHub 发布页的最新版本",
                onOpenUpdate
            )
        }
    }
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
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()
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
            .clickable {
                haptics.tick()
                onClick()
            }
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

