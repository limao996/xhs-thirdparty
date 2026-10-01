package com.thirdparty.xhs.ui.screens

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
import androidx.compose.material3.CircularProgressIndicator
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
import com.thirdparty.xhs.ui.theme.ThemeMode
import com.thirdparty.xhs.ui.theme.XhsTheme
import com.thirdparty.xhs.ui.viewmodel.GuestViewModel
import android.widget.Toast

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
    guestViewModel: GuestViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val context = LocalContext.current
    val guest by guestViewModel.accountLabel.collectAsStateWithLifecycle()
    val rotating by guestViewModel.rotating.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(HomeTab.FEED) }
    var feedRefreshTick by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        guestViewModel.ensureFreshGuest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    val immersive = tab == HomeTab.FEED

    // true immersion on the 推荐 tab: hide the system bars
    val view = androidx.compose.ui.platform.LocalView.current
    val activity = LocalContext.current as? android.app.Activity
    DisposableEffect(immersive, activity) {
        val w = activity?.window
        if (w != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(w, view)
            if (immersive) {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // Force dark theme while the immersive 推荐 tab is shown.
    XhsTheme(mode = if (immersive) ThemeMode.DARK else currentThemeMode()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

            // content — instant tab switch (no transition).
            // AnimatedContent cross-faded the outgoing and incoming tabs, which
            // rendered both pages at once and showed an intermediate frame.
            when (tab) {
                HomeTab.FEED -> VideoFeedScreen(
                    onOpenDetail = onOpenDetail,
                    refreshTick = feedRefreshTick
                )
                HomeTab.DISCOVER -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    HomeHeader(guest, rotating, onOpenSearch)
                    // weight(1f) so the content takes only the remaining height
                    // (fillMaxSize would overflow past the bottom nav and break scrolling)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        DiscoverTabScreen(onOpenDetail = onOpenDetail, onOpenAuthor = onOpenAuthor)
                    }
                }
                HomeTab.PROFILE -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    HomeHeader(guest, rotating, onOpenSearch)
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        ProfileScreen(
                            onOpenSaved = onOpenSaved,
                            onOpenHistory = onOpenHistory,
                            onOpenFollowed = onOpenFollowed,
                            onRotateGuest = { guestViewModel.rotate { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
                            rotating = rotating
                        )
                    }
                }
            }

            // immersive translucent header overlay
            if (immersive) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding()
                        .background(Scrim.header)
                        .padding(horizontal = Spacing.m, vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("小黄书", style = MaterialTheme.typography.titleMedium, color = Scrim.onMedia)
                    Spacer(Modifier.width(Spacing.m))
                    Text(guest, style = MaterialTheme.typography.labelSmall,
                        color = Scrim.onMediaVariant, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, "搜索", tint = Scrim.onMedia)
                    }
                }
            }

            // bottom nav — translucent scrim over the video on the 推荐 tab
            NavigationBar(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .then(if (immersive) Modifier.background(Scrim.chrome) else Modifier),
                containerColor = if (immersive) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp
            ) {
                HomeTab.entries.forEach { entry ->
                    val selected = tab == entry
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
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

@Composable
private fun HomeHeader(guest: String, rotating: Boolean, onOpenSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("小黄书", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Text(guest, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.weight(1f))
        if (rotating) {
            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onOpenSearch) {
            Icon(Icons.Filled.Search, contentDescription = "搜索")
        }
    }
}

@Composable
private fun currentThemeMode(): ThemeMode = App.INSTANCE.themeState.collectAsState().value

private fun iconFor(tab: HomeTab): ImageVector = when (tab) {
    HomeTab.FEED -> Icons.Filled.PlayCircle
    HomeTab.DISCOVER -> Icons.Filled.GridView
    HomeTab.PROFILE -> Icons.Filled.Person
}