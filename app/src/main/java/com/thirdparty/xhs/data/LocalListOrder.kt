package com.thirdparty.xhs.data

/**
 * 「我的收藏 / 最近浏览」的排序维度（用户 2026-10-25 要求）。
 *
 * 两个页面共用一套：字段与方向都放在这里，界面只负责把 [label] 显示出来，
 * 避免 UI 与逻辑各维护一份文案（[label] 里不含"最近/最早"这类方向词 —— 方向是独立的一维，
 * 由 [LocalSortOrder] 表达，否则「最近收藏」配上正序就自相矛盾了）。
 */
enum class LocalSortField(val label: String) {
    /** 收藏时间 / 浏览时间：**保持传进来的顺序**（DAO 已按时间倒序），正序时整体反转。 */
    TIME("时间"),

    /** 标题（不区分大小写字典序；中文按 **Unicode 码位**，不是拼音序）。 */
    TITLE("标题"),

    /** 作者名（同上：不区分大小写，中文按码位）。 */
    AUTHOR("作者"),

    /** 点赞量。 */
    LIKES("点赞量"),
}

/** 正序 / 倒序。[DESC] 是默认值：时间维度下等于"新的在前"。 */
enum class LocalSortOrder { DESC, ASC }

/**
 * 本地列表（收藏 / 最近浏览）的**筛选 + 排序**纯函数。
 *
 * 语义（每条都有单测钉着，见 `LocalListOrderTest`）：
 * 1. 搜索匹配**标题**与**作者名**，忽略大小写、忽略首尾空格；空查询等于不过滤；
 * 2. 时间维度不重排、只按方向整体反转 —— DAO 已经是 `savedAt / viewedAt DESC`，没必要再查一次库；
 * 3. 标题 / 作者 / 点赞量按各自字段排，**相同值保持原有顺序**（`sortedWith` 是稳定排序）；
 * 4. 不改动入参：界面里的 `all` 还要用于计数与切回默认顺序。
 */
fun applyLocalFilter(
    items: List<NoteItem>,
    query: String,
    field: LocalSortField,
    order: LocalSortOrder
): List<NoteItem> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) {
        items
    } else {
        items.filter { item ->
            item.title.contains(q, ignoreCase = true) ||
                item.userName.contains(q, ignoreCase = true)
        }
    }
    if (field == LocalSortField.TIME) {
        // 已经是"新 → 旧"，正序就是反过来
        return if (order == LocalSortOrder.DESC) filtered else filtered.asReversed()
    }
    val comparator = when (field) {
        LocalSortField.TITLE -> compareBy<NoteItem> { it.title.lowercase() }
        LocalSortField.AUTHOR -> compareBy<NoteItem> { it.userName.lowercase() }
        LocalSortField.LIKES -> compareBy<NoteItem> { it.likeCount }
        LocalSortField.TIME -> error("TIME 已在上面单独处理")
    }
    return if (order == LocalSortOrder.ASC) {
        filtered.sortedWith(comparator)
    } else {
        filtered.sortedWith(comparator.reversed())
    }
}
