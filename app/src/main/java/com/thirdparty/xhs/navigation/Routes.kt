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
    /** "关注" — accounts this user follows (v2/member/follow-list) */
    const val FOLLOWING = "following"
    /** "粉丝" — accounts following this user (v2/member/fun-list) */
    const val FANS = "fans"
    /** 备份与恢复 */
    const val BACKUP = "backup"
    /** 设置 */
    const val SETTINGS = "settings"
    /** 关于：版本 / 包名 / 协议 / 仓库 / 许可。入口在「我的」页。 */
    const val ABOUT = "about"
    /** 检查更新：独立页面，只查 GitHub Releases。入口在「我的」页。 */
    const val UPDATE = "update"

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