package com.thirdparty.xhs.navigation

import androidx.compose.runtime.Immutable

/** Central route registry for Navigation Compose. */
object Routes {
    const val HOME = "home"
    const val DETAIL = "detail/{noteId}"
    const val SEARCH = "search"
    const val PROFILE = "profile"
    const val AUTHOR = "author/{userId}"
    const val SAVED = "saved"
    const val HISTORY = "history"
    const val FOLLOWED = "followed"

    fun detail(noteId: Long): String = "detail/$noteId"
    fun author(userId: Int): String = "author/$userId"
}

/** Bottom navigation destinations hosted inside the home shell. */
@Immutable
enum class HomeTab(val route: String, val label: String) {
    FEED("tab/feed", "推荐"),
    DISCOVER("tab/discover", "发现"),
    PROFILE("tab/profile", "我的")
}