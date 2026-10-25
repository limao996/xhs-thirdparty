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
    if (field == LocalSortField.LIKES) {
        // 点赞量是 Int，比较不产生临时对象
        return if (order == LocalSortOrder.DESC) filtered.sortedByDescending { it.likeCount }
        else filtered.sortedBy { it.likeCount }
    }
    // 标题 / 作者要先转小写再比 —— 每个元素**只算一次** key，别放进比较器里
    // （`compareBy { it.title.lowercase() }` 会在每次比较时都新建字符串：n log n 次分配，
    //  收藏/浏览上千条时就是肉眼可见的卡顿；用户 2026-10-25 特别提醒过"数量多了不要卡顿"）。
    val keySelector: (NoteItem) -> String = when (field) {
        LocalSortField.TITLE -> { item -> item.title.lowercase() }
        LocalSortField.AUTHOR -> { item -> item.userName.lowercase() }
        else -> error("不可达")
    }
    val keyed = filtered.map { it to keySelector(it) }
    val sorted = if (order == LocalSortOrder.ASC) keyed.sortedBy { it.second }
    else keyed.sortedByDescending { it.second }
    return sorted.map { it.first }
}

/** 「我关注的作者」的排序维度（用户 2026-10-25：关注页也要搜索和排序）。 */
enum class AuthorSortField(val label: String) {
    /** 关注时间：DAO 已按 `followedAt DESC` 返回，正序时整体反转。 */
    TIME("关注时间"),

    /** 昵称（不区分大小写，中文按码位）。 */
    NAME("昵称"),
}

/**
 * 「我关注的作者」的筛选 + 排序（纯函数，与 [applyLocalFilter] 同一套语义）。
 *
 * 搜索匹配**昵称**与**签名**（签名里常写"互关""接推广"这类关键词，找起来比只搜昵称有用）；
 * 排序只有两个维度：关注时间与昵称。同样不改动入参，昵称比较的 key 只算一次。
 */
fun applyAuthorFilter(
    items: List<FollowedEntity>,
    query: String,
    field: AuthorSortField,
    order: LocalSortOrder
): List<FollowedEntity> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) {
        items
    } else {
        items.filter { item ->
            item.userName.contains(q, ignoreCase = true) ||
                item.signature.contains(q, ignoreCase = true)
        }
    }
    if (field == AuthorSortField.TIME) {
        return if (order == LocalSortOrder.DESC) filtered else filtered.asReversed()
    }
    val keyed = filtered.map { it to it.userName.lowercase() }
    val sorted = if (order == LocalSortOrder.ASC) keyed.sortedBy { it.second }
    else keyed.sortedByDescending { it.second }
    return sorted.map { it.first }
}
