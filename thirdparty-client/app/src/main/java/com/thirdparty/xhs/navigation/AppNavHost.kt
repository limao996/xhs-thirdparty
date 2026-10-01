package com.thirdparty.xhs.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.navigation.compose.composable
import com.thirdparty.xhs.ui.screens.AuthorScreen
import com.thirdparty.xhs.ui.screens.DetailScreen
import com.thirdparty.xhs.ui.screens.FollowedScreen
import com.thirdparty.xhs.ui.screens.HomeScreen
import com.thirdparty.xhs.ui.screens.LocalListScreen
import com.thirdparty.xhs.ui.screens.SearchScreen
import com.thirdparty.xhs.ui.viewmodel.LocalListViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar

import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding

/** A Scaffold wrapper hosting the local (Room) list for a given mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalListNav(title: String, mode: LocalListViewModel.Mode, onBack: () -> Unit, onOpenDetail: (Long) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            LocalListScreen(mode = mode, onOpenDetail = onOpenDetail)
        }
    }
}

/** Top-level Navigation Compose graph with polished enter/exit transitions. */
@Composable
fun AppNavHost(nav: NavHostController) {
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
                onOpenFollowed = { nav.navigate(Routes.FOLLOWED) }
            )
        }

        composable(Routes.SEARCH,
            enterTransition = { fadeIn(tween(240)) + scaleIn(animationSpec = tween(240), initialScale = 0.96f) },
            exitTransition = { fadeOut(tween(180)) + scaleOut(animationSpec = tween(180), targetScale = 0.98f) }
        ) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onOpenDetail = { noteId -> nav.navigate(Routes.detail(noteId)) },
                onOpenAuthor = { uid -> nav.navigate(Routes.author(uid)) }
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

        composable(Routes.AUTHOR,
            enterTransition = { fadeIn(tween(300)) + scaleIn(animationSpec = tween(300), initialScale = 0.98f) },
            popExitTransition = { fadeOut(tween(220)) + scaleOut(animationSpec = tween(220), targetScale = 0.98f) }
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId")?.toIntOrNull() ?: 0
            AuthorScreen(
                userId = userId,
                onBack = { nav.popBackStack() },
                onOpenDetail = { noteId -> nav.navigate(Routes.detail(noteId)) }
            )
        }

        composable("followed",
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
            LocalListNav("我的收藏", LocalListViewModel.Mode.SAVED, { nav.popBackStack() }) { nav.navigate(Routes.detail(it)) }
        }

        composable(Routes.HISTORY,
            enterTransition = { fadeIn(tween(280)) + scaleIn(animationSpec = tween(280), initialScale = 0.97f) },
            popExitTransition = { fadeOut(tween(200)) + scaleOut(animationSpec = tween(200), targetScale = 0.98f) }
        ) {
            LocalListNav("最近浏览", LocalListViewModel.Mode.HISTORY, { nav.popBackStack() }) { nav.navigate(Routes.detail(it)) }
        }
    }
}