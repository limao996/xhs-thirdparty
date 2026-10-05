@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.App
import com.thirdparty.xhs.navigation.HomeTab
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.theme.bottomNavClearance
import com.thirdparty.xhs.ui.components.WatchLaterFab
import com.thirdparty.xhs.ui.components.rememberHaptics
import com.thirdparty.xhs.ui.theme.ThemeMode
import com.thirdparty.xhs.ui.theme.XhsTheme
import com.thirdparty.xhs.ui.viewmodel.GuestViewModel
import android.widget.Toast
import androidx.compose.runtime.remember
import com.thirdparty.xhs.ui.theme.isDark

/**
 * Root shell. The 推荐 tab is full-bleed immersive: the header and bottom nav
 * float translucently over the video and the area forces the dark theme.
 * Other tabs use normal MD3 chrome.
 */
@Composable
fun HomeScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAuthor: (Int) -> Unit,
    onOpenSaved: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenFollowed: () -> Unit,
    onOpenFollowing: () -> Unit,
    onOpenFans: () -> Unit,
    onOpenMyNotes: (Int) -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 关于 / 检查更新：入口在「我的」页，各是一个独立页面 */
    onOpenAbout: () -> Unit,
    onOpenUpdate: () -> Unit,
    /** 稍后观看队列（浮动按钮） */
    onOpenWatchLater: () -> Unit,
    onSetBiometricLock: ((Boolean) -> Unit)? = null,
    onSetHistoryLimit: ((Int) -> Unit)? = null,
    guestViewModel: GuestViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val context = LocalContext.current
    val guest by guestViewModel.accountLabel.collectAsStateWithLifecycle()
    val rotating by guestViewModel.rotating.collectAsStateWithLifecycle()
    val autoVip by guestViewModel.autoVip.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(HomeTab.FEED) }
    var feedRefreshTick by rememberSaveable { mutableStateOf(0) }
    // 底部三 tab 切换的触感反馈（系统 API，尊重用户的触感开关）
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) {
        guestViewModel.ensureFreshGuest()
    }

    // A backup restore swaps out the stored account; reload the cached account
    // state so the top bar and 我的 page stop showing the pre-restore id.
    val dataEpoch by App.INSTANCE.dataEpoch.collectAsStateWithLifecycle()
    LaunchedEffect(dataEpoch) {
        if (dataEpoch == 0) return@LaunchedEffect
        guestViewModel.refreshAccountState()
    }

    val immersive = tab == HomeTab.FEED

    // 推荐 tab: BOTH system bars stay visible but fully transparent with white
    // icons — the video keeps drawing behind them (edge-to-edge), so nothing is
    // covered while time/battery and the gesture bar remain reachable.
    // The header has statusBarsPadding(), and the bottom NavigationBar inherits
    // MD3's default window insets, so each lifts clear of its bar automatically.
    val darkNow = currentThemeMode().isDark(androidx.compose.foundation.isSystemInDarkTheme())
    val view = androidx.compose.ui.platform.LocalView.current
    val activity = androidx.activity.compose.LocalActivity.current
    // Report the tab state; AppNavHost owns the actual system-bar configuration.
    //
    // Configuring the bars here as well meant that navigating away to 搜索 or 详情
    // left the feed's white-on-light bars in place: this effect is keyed on
    // `immersive`, which does not change on the way out, and its onDispose did
    // nothing. One owner, keyed on the current destination, cannot drift.
    androidx.compose.runtime.DisposableEffect(guestViewModel) {
        App.INSTANCE.autoVipSetter = { guestViewModel.setAutoVip(it) { } }
        onDispose { App.INSTANCE.autoVipSetter = null }
    }

    androidx.compose.runtime.LaunchedEffect(immersive) {
        App.INSTANCE.feedImmersive.value = immersive
    }

    // Force dark theme while the immersive 推荐 tab is shown.
    XhsTheme(mode = if (immersive) ThemeMode.DARK else currentThemeMode()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

            // content — instant tab switch (no transition).
            // AnimatedContent cross-faded the outgoing and incoming tabs, which
            // rendered both pages at once and showed an intermediate frame.
            // Tabs are a plain `when`, and leaving a branch DISCARDS its
            // rememberSaveable state — only navigation destinations get a
            // SaveableStateProvider. Without this holder, switching 推荐 → 发现 →
            // 推荐 rebuilt the feed pager from scratch and threw the viewer back
            // to the first video (and likewise for every other tab's scroll
            // position). Keying the holder by tab keeps each tab's state alive.
            val tabStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            // Single owner of the feed's chrome visibility.
            //
            // A tap on the 推荐 video is meant to clear the clip of chrome so it can
            // be watched unobstructed. The header and the bottom navigation belong to
            // this shell, not to the feed, so the flag lives HERE and is handed down:
            // the feed flips it, both the shell and the feed read it.
            //
            // It used to be the other way round — the feed held the flag and reported
            // it up through a callback + LaunchedEffect. The shell never saw the
            // change, so a tap removed the caption and left the header and the bottom
            // bar sitting over the video. One owner at the top cannot drift.
            var feedInfoVisible by remember { mutableStateOf(true) }
            tabStateHolder.SaveableStateProvider(tab.name) {
            when (tab) {
                HomeTab.FEED -> VideoFeedScreen(
                    onOpenDetail = onOpenDetail,
                    refreshTick = feedRefreshTick,
                    infoVisible = feedInfoVisible,
                    onInfoVisibleChange = { feedInfoVisible = it },
                    onOpenWatchLater = onOpenWatchLater
                )
                HomeTab.DISCOVER -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    HomeHeader(guest, rotating, onOpenSearch)
                    // weight(1f) so the content takes only the remaining height
                    // (fillMaxSize would overflow past the bottom nav and break scrolling)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        DiscoverTabScreen(
                            onOpenDetail = onOpenDetail,
                            onOpenAuthor = onOpenAuthor,
                            onOpenWatchLater = onOpenWatchLater
                        )
                    }
                }
                HomeTab.PROFILE -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    HomeHeader(guest, rotating, onOpenSearch)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        ProfileScreen(
                            onOpenSaved = onOpenSaved,
                            onOpenHistory = onOpenHistory,
                            onOpenFollowed = onOpenFollowed,
                            onRotateGuest = {
                                guestViewModel.switchRandom {
                                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onOpenFollowing = onOpenFollowing,
                            onOpenFans = onOpenFans,
                            onOpenMyNotes = { uid -> onOpenMyNotes(uid) },
                            onOpenSettings = onOpenSettings,
                            onOpenAbout = onOpenAbout,
                            onOpenUpdate = onOpenUpdate,
                            biometricLock = App.INSTANCE.repository.biometricLock,
                            biometricAvailable = androidx.biometric.BiometricManager.from(context).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS,
                            onSetBiometricLock = onSetBiometricLock,
                            historyLimit = App.INSTANCE.repository.historyLimit,
                            onSetHistoryLimit = onSetHistoryLimit,
                            autoVip = autoVip,
                            onSetAutoVip = { on ->
                                guestViewModel.setAutoVip(on) {
                                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                                }
                            },
                            rotating = rotating,
                            reloadKey = guest
                        )
                    }
                }
            }
            }

            // immersive translucent header overlay.
            //
            // background BEFORE statusBarsPadding on purpose: the other order
            // insets the content first and paints the scrim only BELOW that inset,
            // leaving the status-bar strip unpainted — a visible seam between the
            // clock and the title bar. Painting first makes the same translucent
            // colour run behind the status bar, so the bar reads as part of the
            // header and the seam is gone.
            //
            // Faded with the bottom bar rather than removed with a plain `if`: the
            // two are one piece of chrome as far as the viewer is concerned, so a
            // tap must retire them together and with the same motion.
            //
            // `immersive &&` is load-bearing: this header belongs to the 推荐 feed
            // only. Every other tab draws its own opaque HomeHeader, so painting
            // this translucent one over them duplicated the account line and laid a
            // scrim across 发现's category chips (~278dp tall, they sit right under
            // it). The bottom navigation, by contrast, really is on every tab.
            AnimatedVisibility(
                visible = immersive && feedInfoVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Scrim.header)
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.m, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("小黄书", style = MaterialTheme.typography.titleMedium, color = Scrim.onMedia)
                    Spacer(Modifier.width(Spacing.m))
                    Text(guest, style = MaterialTheme.typography.labelSmall,
                        color = Scrim.onMediaVariant, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        // 推荐页这个标题栏是另写的（不是 HomeHeader），触感要单独补
                        haptics.tick()
                        onOpenSearch()
                    }) {
                        Icon(Icons.Filled.Search, "搜索", tint = Scrim.onMedia)
                    }
                }
            }

            // 「我的」页不放入口：那里是账号与本地数据的入口清单，浮一个队列按钮只会碍事
            // （用户明确要求）。队列入口的归属现在是：推荐页 = 视频信息栏上方的信息条，
            // 发现页 = 右下角与刷新按钮同排的那一个（见 DiscoverTabScreen 的 CornerFabStack）。

            // bottom nav — translucent scrim over the video on the 推荐 tab.
            // Hidden together with the header when the feed's chrome is retired:
            // leaving it up meant a tap only cleared part of the overlay.
            AnimatedVisibility(
                visible = !immersive || feedInfoVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                NavigationBar(
                    modifier = if (immersive) Modifier.background(Scrim.chrome) else Modifier,
                    containerColor = if (immersive) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    HomeTab.entries.forEach { entry ->
                        val selected = tab == entry
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                haptics.tick()
                                if (entry == HomeTab.FEED && tab == HomeTab.FEED) feedRefreshTick++
                                else tab = entry
                            },
                            colors = if (immersive) NavigationBarItemDefaults.colors(
                                selectedIconColor = Scrim.onMedia,
                                selectedTextColor = Scrim.onMedia,
                                indicatorColor = Scrim.chrome,
                                unselectedIconColor = Scrim.onMediaVariant,
                                unselectedTextColor = Scrim.onMediaVariant
                            ) else NavigationBarItemDefaults.colors(),
                            icon = { Icon(iconFor(entry), contentDescription = entry.label) },
                            label = { Text(entry.label) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(guest: String, rotating: Boolean, onOpenSearch: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("小黄书", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(Spacing.s))
        Text(guest, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.weight(1f))
        if (rotating) {
            LoadingIndicator(Modifier.width(20.dp).height(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = {
            haptics.tick()
            onOpenSearch()
        }) {
            Icon(Icons.Filled.Search, contentDescription = "搜索")
        }
    }
}

@Composable
private fun currentThemeMode(): ThemeMode =
    App.INSTANCE.themeState.collectAsStateWithLifecycle().value

private fun iconFor(tab: HomeTab): ImageVector = when (tab) {
    HomeTab.FEED -> Icons.Filled.PlayCircle
    HomeTab.DISCOVER -> Icons.Filled.GridView
    HomeTab.PROFILE -> Icons.Filled.Person
}