package com.thirdparty.xhs.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import com.thirdparty.xhs.ui.screens.AuthorScreen
import com.thirdparty.xhs.ui.screens.AboutScreen
import com.thirdparty.xhs.ui.screens.CacheScreen
import com.thirdparty.xhs.ui.screens.UpdateScreen
import com.thirdparty.xhs.ui.screens.WatchLaterScreen
import com.thirdparty.xhs.ui.components.WatchLaterFab
import com.thirdparty.xhs.ui.screens.DetailScreen
import com.thirdparty.xhs.ui.screens.FollowedScreen
import com.thirdparty.xhs.ui.screens.HomeScreen
import com.thirdparty.xhs.ui.screens.LocalListScreen
import com.thirdparty.xhs.ui.screens.SearchScreen
import com.thirdparty.xhs.ui.viewmodel.LocalListViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import com.thirdparty.xhs.App
import com.thirdparty.xhs.ui.screens.UserListScreen
import com.thirdparty.xhs.ui.viewmodel.UserListMode
import com.thirdparty.xhs.ui.screens.BackupScreen
import com.thirdparty.xhs.ui.theme.isDark
import kotlinx.coroutines.launch
import com.thirdparty.xhs.ui.screens.SettingsScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Close

/**
 * A Scaffold wrapper hosting the local (Room) list for a given mode.
 * The ViewModel is hoisted here so the app bar can offer a "clear all" action
 * (with confirmation) for 收藏 / 最近浏览.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalListNav(
    title: String,
    mode: LocalListViewModel.Mode,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenWatchLater: () -> Unit
) {
    val viewModel: LocalListViewModel = viewModel(
        key = mode.name,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                LocalListViewModel(App.repo, mode) as T
        }
    )
    val state by viewModel.ui.collectAsStateWithLifecycle()

    // Selection lives here, not inside LocalListScreen, so this Scaffold can swap
    // its TopAppBar for a contextual bar. Put in the screen it left two stacked
    // bars: the page title above and the selection row below it.
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    val selecting = selected.isNotEmpty()

    var confirmClear by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    // 屏幕上自己画的按钮都要有触感（硬约束 19）。这里以前只有一个 `haptics` 都没有。
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()

    Scaffold(
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = selected.size,
                    onSelectAll = { selected = state.all.map { it.noteId }.toSet() },
                    onExit = { confirmExit = true },
                    onDelete = { confirmRemove = true }
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = haptics.click(onBack)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        if (state.all.isNotEmpty()) {
                            IconButton(onClick = haptics.click { confirmClear = true }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "清空")
                            }
                        }
                    }
                )
            }
        },
        // 多选时不叠这个按钮：那时候整页都在选东西，浮动按钮只会碍事
        floatingActionButton = {
            if (!selecting) WatchLaterFab(onOpen = onOpenWatchLater)
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            LocalListScreen(
                mode = mode,
                onOpenDetail = onOpenDetail,
                viewModel = viewModel,
                selected = selected,
                onSelectionChange = { selected = it }
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空$title？") },
            text = { Text("此操作不可撤销，将删除全部 ${state.all.size} 条本地记录。") },
            confirmButton = {
                TextButton(onClick = haptics.rejectClick {
                    viewModel.clear()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = haptics.click { confirmClear = false }) { Text("取消") }
            }
        )
    }

    // Un-favouriting is destructive and has no undo, so it asks first.
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("取消收藏？") },
            text = { Text("将从收藏中移除已选的 ${selected.size} 项，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = haptics.rejectClick {
                    viewModel.removeSaved(selected)
                    selected = emptySet()
                    confirmRemove = false
                }) { Text("取消收藏") }
            },
            dismissButton = {
                TextButton(onClick = haptics.click { confirmRemove = false }) { Text("再想想") }
            }
        )
    }

    // Leaving selection restores the normal title bar, so a mis-tap on the X
    // silently throws away everything that was ticked. Confirm while anything is
    // selected.
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("退出多选？") },
            text = { Text("已选的 ${selected.size} 项会被取消勾选。") },
            confirmButton = {
                TextButton(onClick = haptics.click {
                    selected = emptySet()
                    confirmExit = false
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = haptics.click { confirmExit = false }) { Text("继续多选") }
            }
        )
    }
}

/**
 * Contextual action bar shown in place of the page title while items are ticked
 * (Material 3 "contextual app bar" pattern).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onSelectAll: () -> Unit,
    onExit: () -> Unit,
    onDelete: () -> Unit
) {
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()
    TopAppBar(
        title = { Text("已选 $count 项") },
        navigationIcon = {
            IconButton(onClick = haptics.click(onExit)) {
                Icon(Icons.Filled.Close, contentDescription = "退出多选")
            }
        },
        actions = {
            TextButton(onClick = haptics.click(onSelectAll)) { Text("全选") }
            IconButton(onClick = haptics.rejectClick(onDelete)) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "取消收藏")
            }
        }
    )
}

/** Top-level Navigation Compose graph with polished enter/exit transitions. */
// window.statusBarColor / navigationBarColor are deprecated on API 35+, where
// edge-to-edge makes the bars transparent whether we ask or not. Setting them is still
// what makes a pre-35 device behave the same way, so the call stays and the warning is
// suppressed here rather than left to drown out real ones.
@Suppress("DEPRECATION")
@Composable
fun AppNavHost(
    nav: NavHostController,
    /** a note id arriving from a share link; consumed once */
    deepLinkNoteId: Long? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    // Single owner of the system-bar appearance.
    //
    // Keyed on the destination so it is re-applied on every navigation. Screens
    // used to fight over this: the feed set white icons for its full-bleed video
    // and nothing restored them on the way out, so 搜索/详情 came up with white
    // icons over a light surface.
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val feedImmersive by App.INSTANCE.feedImmersive.collectAsStateWithLifecycle()
    val detailImmersive by App.INSTANCE.detailImmersive.collectAsStateWithLifecycle()
    val imageViewerShown by App.INSTANCE.imageViewerShown.collectAsStateWithLifecycle()
    val barView = androidx.compose.ui.platform.LocalView.current
    val barActivity = androidx.activity.compose.LocalActivity.current
    val barDark = App.INSTANCE.themeState.collectAsStateWithLifecycle().value
        .isDark(androidx.compose.foundation.isSystemInDarkTheme())
    androidx.compose.runtime.DisposableEffect(
        route, feedImmersive, detailImmersive, imageViewerShown, barDark, barActivity
    ) {
        barActivity?.window?.let { w ->
            val c = androidx.core.view.WindowCompat.getInsetsController(w, barView)
            if (detailImmersive) {
                // 真全屏 (video only): hide the bars outright, swipe to bring them back
                c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                c.systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                if (imageViewerShown) {
                    // 图文 fullscreen: the picture runs edge to edge underneath, so the
                    // bars must be transparent (a themed, opaque bar would cut the
                    // picture in two) while staying visible.
                    w.statusBarColor = android.graphics.Color.TRANSPARENT
                    w.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
            }
            // Light icons over media (the feed's full-bleed video, or a picture the bars
            // are floating on); dark icons over every themed, opaque surface.
            val overMedia =
                (route == com.thirdparty.xhs.navigation.Routes.HOME && feedImmersive) ||
                    imageViewerShown
            c.isAppearanceLightStatusBars = !overMedia && !barDark
            c.isAppearanceLightNavigationBars = !overMedia && !barDark
        }
        onDispose { }
    }
    // opening a shared link should land ON that note, not just on the app
    androidx.compose.runtime.LaunchedEffect(deepLinkNoteId) {
        deepLinkNoteId?.let {
            nav.navigate(Routes.detail(it))
            onDeepLinkConsumed()
        }
    }
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = {
            slideInHorizontally(animationSpec = tween(320), initialOffsetX = { it / 8 }) + fadeIn(tween(320))
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(240), targetOffsetX = { -it / 8 }) + fadeOut(tween(240))
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(320), initialOffsetX = { -it / 8 }) + fadeIn(tween(320))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(240), targetOffsetX = { it / 8 }) + fadeOut(tween(240))
        }
    ) {

        composable(Routes.HOME) {
            HomeScreen(
                onOpenDetail = { noteId -> nav.navigate(Routes.detail(noteId)) },
                onOpenSearch = { nav.navigate(Routes.SEARCH) },
                onOpenAuthor = { uid -> nav.navigate(Routes.author(uid)) },
                onOpenSaved = { nav.navigate(Routes.SAVED) },
                onOpenHistory = { nav.navigate(Routes.HISTORY) },
                onOpenFollowed = { nav.navigate(Routes.FOLLOWED) },
                onOpenFollowing = { nav.navigate(Routes.FOLLOWING) },
                onOpenFans = { nav.navigate(Routes.FANS) },
                // 作品 on the account card opens that account's own author page
                onOpenMyNotes = { uid -> nav.navigate(Routes.author(uid)) },
                onOpenBackup = { nav.navigate(Routes.BACKUP) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenAbout = { nav.navigate(Routes.ABOUT) },
                onOpenUpdate = { nav.navigate(Routes.UPDATE) },
                onOpenWatchLater = { nav.navigate(Routes.WATCH_LATER) }
            )
        }

        composable(Routes.SEARCH,
            enterTransition = { fadeIn(tween(240)) + scaleIn(animationSpec = tween(240), initialScale = 0.96f) },
            exitTransition = { fadeOut(tween(180)) + scaleOut(animationSpec = tween(180), targetScale = 0.98f) }
        ) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenDetail = { noteId -> nav.navigate(Routes.detail(noteId)) },
                onOpenAuthor = { uid -> nav.navigate(Routes.author(uid)) },
                onOpenWatchLater = { nav.navigate(Routes.WATCH_LATER) }
            )
        }

        composable(Routes.DETAIL,
            enterTransition = { fadeIn(tween(320)) + scaleIn(animationSpec = tween(320), initialScale = 0.98f) },
            popExitTransition = { fadeOut(tween(240)) + scaleOut(animationSpec = tween(240), targetScale = 0.98f) }
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull() ?: 0L
            DetailScreen(
                noteId = noteId,
                onBack = { nav.popBackStack() },
                onOpenAuthor = { uid -> nav.navigate(Routes.author(uid)) }
            )
        }

        composable(Routes.FOLLOWING) {
            UserListScreen(
                mode = UserListMode.FOLLOWING,
                userId = 0,   // 0 = the signed-in account, matching the client
                onBack = { nav.popBackStack() },
                onOpenAuthor = { uid -> nav.navigate(Routes.author(uid)) }
            )
        }

        composable(Routes.SETTINGS) {
            val settingsContext = LocalContext.current
            SettingsScreen(
                onBack = { nav.popBackStack() },
                themeMode = App.INSTANCE.themeState.collectAsStateWithLifecycle().value,
                onSetTheme = { App.INSTANCE.setThemeMode(it) },
                biometricLock = App.repo.biometricLock,
                biometricAvailable = androidx.biometric.BiometricManager.from(settingsContext)
                    .canAuthenticate(
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS,
                onSetBiometricLock = { on ->
                    App.repo.biometricLock = on
                    App.INSTANCE.notifyLockChanged()
                },
                historyLimit = App.repo.historyLimit,
                onSetHistoryLimit = { n ->
                    App.repo.historyLimit = n
                    App.INSTANCE.appScope.launch {
                        App.INSTANCE.repository.trimHistory()
                    }
                },
                autoVip = App.repo.autoSwitchOnVipExpiry,
                onSetAutoVip = { on ->
                    App.INSTANCE.autoVipSetter?.invoke(on)
                        ?: run { App.repo.autoSwitchOnVipExpiry = on }
                },
                onOpenBackup = { nav.navigate(Routes.BACKUP) },
                onOpenCache = { nav.navigate(Routes.CACHE) }
            )
        }
        composable(Routes.BACKUP) {
            BackupScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.CACHE) {
            CacheScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { nav.popBackStack() },
                onOpenUpdate = { nav.navigate(Routes.UPDATE) }
            )
        }

        composable(Routes.UPDATE) {
            UpdateScreen(onBack = { nav.popBackStack() })
        }

        composable(Routes.FANS) {
            UserListScreen(
                mode = UserListMode.FANS,
                userId = 0,
                onBack = { nav.popBackStack() },
                onOpenAuthor = { uid -> nav.navigate(Routes.author(uid)) }
            )
        }

        composable(Routes.AUTHOR,
            enterTransition = { fadeIn(tween(300)) + scaleIn(animationSpec = tween(300), initialScale = 0.98f) },
            popExitTransition = { fadeOut(tween(220)) + scaleOut(animationSpec = tween(220), targetScale = 0.98f) }
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId")?.toIntOrNull() ?: 0
            AuthorScreen(
                userId = userId,
                onBack = { nav.popBackStack() },
                onOpenDetail = { noteId -> nav.navigate(Routes.detail(noteId)) },
                onOpenWatchLater = { nav.navigate(Routes.WATCH_LATER) }
            )
        }

        composable(Routes.FOLLOWED,
            enterTransition = { slideInHorizontally(tween(320)) { it / 6 } + fadeIn(tween(320)) },
            popExitTransition = { slideOutHorizontally(tween(240)) { it / 6 } + fadeOut(tween(240)) }
        ) {
            FollowedScreen(
                onBack = { nav.popBackStack() },
                onOpenAuthor = { uid -> nav.navigate(Routes.author(uid)) }
            )
        }

        composable(Routes.SAVED,
            enterTransition = { fadeIn(tween(280)) + scaleIn(animationSpec = tween(280), initialScale = 0.97f) },
            popExitTransition = { fadeOut(tween(200)) + scaleOut(animationSpec = tween(200), targetScale = 0.98f) }
        ) {
            LocalListNav(
                "我的收藏", LocalListViewModel.Mode.SAVED,
                { nav.popBackStack() }, { nav.navigate(Routes.detail(it)) },
                { nav.navigate(Routes.WATCH_LATER) }
            )
        }

        composable(Routes.HISTORY,
            enterTransition = { fadeIn(tween(280)) + scaleIn(animationSpec = tween(280), initialScale = 0.97f) },
            popExitTransition = { fadeOut(tween(200)) + scaleOut(animationSpec = tween(200), targetScale = 0.98f) }
        ) {
            LocalListNav(
                "最近浏览", LocalListViewModel.Mode.HISTORY,
                { nav.popBackStack() }, { nav.navigate(Routes.detail(it)) },
                { nav.navigate(Routes.WATCH_LATER) }
            )
        }

        composable(Routes.WATCH_LATER,
            enterTransition = { fadeIn(tween(280)) + scaleIn(animationSpec = tween(280), initialScale = 0.97f) },
            popExitTransition = { fadeOut(tween(200)) + scaleOut(animationSpec = tween(200), targetScale = 0.98f) }
        ) {
            WatchLaterScreen(
                onBack = { nav.popBackStack() },
                onOpenDetail = { noteId -> nav.navigate(Routes.detail(noteId)) }
            )
        }
    }
}