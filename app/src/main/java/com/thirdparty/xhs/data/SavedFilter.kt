package com.thirdparty.xhs.data

/**
 * 「我的收藏」的排序方式（用户 2026-10-25 要求：收藏页加排序与搜索）。
 *
 * [label] 直接当界面文案用，取值都写在同一个枚举里，避免 UI 与逻辑各维护一份。
 */
enum class SavedSort(val label: String) {
    /** 最近收藏在前 —— 与 `SavedNoteDao.all()` 的 `ORDER BY savedAt DESC` 一致，是默认值。 */
    SAVED_DESC("最近收藏"),

    /** 最早收藏在前 —— 收藏时间是倒序返回的，所以直接反转即可，不必再查一次库。 */
    SAVED_ASC("最早收藏"),

    /** 按标题排（不区分大小写的字典序）。 */
    TITLE("标题"),
}

/**
 * 对收藏列表做**本地**筛选 + 排序（纯函数，带单测）。
 *
 * 为什么单独抽出来：收藏页原来只有"收藏时间倒序"一种顺序，收藏多了既翻不到也找不到。
 * 需求是加排序与搜索，但真正容易出错的不是界面而是这三条语义，这里逐条钉住：
 *
 * 1. 搜索匹配**标题**与**作者名**，忽略大小写、忽略首尾空格；空查询等于不过滤；
 * 2. 排序三档：最近收藏（保持传入顺序，即 DAO 的 `savedAt DESC`）、最早收藏（反转）、标题；
 * 3. 三者可以叠加（先筛后排），且**不改动入参**（返回新列表，界面里的 `all` 不被就地打乱）。
 *
 * 数据全部来自本地 Room，不发网络请求。
 */
fun applySavedFilter(items: List<NoteItem>, query: String, sort: SavedSort): List<NoteItem> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) {
        items
    } else {
        items.filter { item ->
            item.title.contains(q, ignoreCase = true) ||
                item.userName.contains(q, ignoreCase = true)
        }
    }
    return when (sort) {
        SavedSort.SAVED_DESC -> filtered
        SavedSort.SAVED_ASC -> filtered.asReversed()
        SavedSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
    }
}
