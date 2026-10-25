package com.thirdparty.xhs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [applyLocalFilter] 的单测：搜索范围、四个排序维度 × 正/倒序、稳定性、不改入参。 */
class LocalListOrderTest {

    private fun note(
        id: Long,
        title: String,
        author: String,
        likes: Int = 0
    ) = NoteItem(
        noteId = id,
        title = title,
        userName = author,
        cover = "",
        thumbnail = "",
        noteType = 1,
        likeCount = likes,
        collectCount = 0,
        commentCount = 0
    )

    /** DAO 返回的顺序：新的在前（savedAt / viewedAt DESC）。 */
    private val newestFirst = listOf(
        note(3, "Zebra 合集", "作者乙", likes = 10),
        note(2, "apple pie", "作者丙", likes = 99),
        note(1, "Banana", "作者甲", likes = 5),
    )

    @Test
    fun empty_query_keeps_everything() {
        assertEquals(
            listOf(3L, 2L, 1L),
            applyLocalFilter(newestFirst, "", LocalSortField.TIME, LocalSortOrder.DESC).map { it.noteId }
        )
        assertEquals(
            listOf(3L, 2L, 1L),
            applyLocalFilter(newestFirst, "   ", LocalSortField.TIME, LocalSortOrder.DESC).map { it.noteId }
        )
    }

    @Test
    fun search_matches_title_and_author_ignoring_case_and_spaces() {
        assertEquals(
            listOf("Zebra 合集"),
            applyLocalFilter(newestFirst, "zebra", LocalSortField.TIME, LocalSortOrder.DESC).map { it.title }
        )
        // 作者名也搜得到（中文作者名，正是 adb 输入不了、只能靠单测覆盖的那类）
        assertEquals(
            listOf("Banana"),
            applyLocalFilter(newestFirst, "作者甲", LocalSortField.TIME, LocalSortOrder.DESC).map { it.title }
        )
        assertEquals(
            listOf("apple pie"),
            applyLocalFilter(newestFirst, "  APPLE  ", LocalSortField.TIME, LocalSortOrder.DESC).map { it.title }
        )
    }

    @Test
    fun time_field_only_reverses() {
        assertEquals(
            listOf(3L, 2L, 1L),
            applyLocalFilter(newestFirst, "", LocalSortField.TIME, LocalSortOrder.DESC).map { it.noteId }
        )
        assertEquals(
            listOf(1L, 2L, 3L),
            applyLocalFilter(newestFirst, "", LocalSortField.TIME, LocalSortOrder.ASC).map { it.noteId }
        )
    }

    @Test
    fun title_field_both_directions() {
        // 正序：apple pie < Banana < Zebra（不区分大小写）
        assertEquals(
            listOf("apple pie", "Banana", "Zebra 合集"),
            applyLocalFilter(newestFirst, "", LocalSortField.TITLE, LocalSortOrder.ASC).map { it.title }
        )
        assertEquals(
            listOf("Zebra 合集", "Banana", "apple pie"),
            applyLocalFilter(newestFirst, "", LocalSortField.TITLE, LocalSortOrder.DESC).map { it.title }
        )
    }

    @Test
    fun author_field_both_directions() {
        // 注意：中文字符串按 **Unicode 码位** 比较（不是拼音）：丙 U+4E19 < 乙 U+4E59 < 甲 U+7532。
        // 这与应用里其它"按标题/作者排"的地方一致；要拼音序得引入 collator，不在本次范围。
        assertEquals(
            listOf("apple pie", "Zebra 合集", "Banana"),
            applyLocalFilter(newestFirst, "", LocalSortField.AUTHOR, LocalSortOrder.ASC).map { it.title }
        )
        assertEquals(
            listOf("Banana", "Zebra 合集", "apple pie"),
            applyLocalFilter(newestFirst, "", LocalSortField.AUTHOR, LocalSortOrder.DESC).map { it.title }
        )
    }

    @Test
    fun likes_field_both_directions() {
        assertEquals(
            listOf(5, 10, 99),
            applyLocalFilter(newestFirst, "", LocalSortField.LIKES, LocalSortOrder.ASC).map { it.likeCount }
        )
        assertEquals(
            listOf(99, 10, 5),
            applyLocalFilter(newestFirst, "", LocalSortField.LIKES, LocalSortOrder.DESC).map { it.likeCount }
        )
    }

    @Test
    fun equal_values_keep_original_order() {
        val same = listOf(
            note(9, "同名", "同一作者", likes = 1),
            note(8, "同名", "同一作者", likes = 1),
            note(7, "同名", "同一作者", likes = 1),
        )
        assertEquals(
            listOf(9L, 8L, 7L),
            applyLocalFilter(same, "", LocalSortField.LIKES, LocalSortOrder.DESC).map { it.noteId }
        )
        assertEquals(
            listOf(9L, 8L, 7L),
            applyLocalFilter(same, "", LocalSortField.TITLE, LocalSortOrder.ASC).map { it.noteId }
        )
    }

    @Test
    fun filters_before_sorting_and_never_mutates_input() {
        val items = listOf(
            note(5, "Banana 二", "作者甲", likes = 1),
            note(4, "Banana 一", "作者甲", likes = 9),
            note(3, "Zebra", "作者乙", likes = 5),
        )
        // 先筛后排：只要 banana，按点赞倒序
        assertEquals(
            listOf("Banana 一", "Banana 二"),
            applyLocalFilter(items, "banana", LocalSortField.LIKES, LocalSortOrder.DESC).map { it.title }
        )
        assertTrue(applyLocalFilter(items, "不存在的关键词", LocalSortField.TIME, LocalSortOrder.DESC).isEmpty())
        // 入参顺序不变（界面里的 all 还要用于计数）
        assertEquals(listOf(5L, 4L, 3L), items.map { it.noteId })
    }

    // ---- 我关注的作者（用户 2026-10-25：关注页也加搜索和排序）----------------

    private fun author(id: Int, name: String, signature: String = "") =
        FollowedEntity(userId = id, userName = name, headImg = "", signature = signature)

    /** DAO 顺序：最近关注在前（followedAt DESC）。 */
    private val followed = listOf(
        author(3, "Zebra", "接推广"),
        author(2, "apple", ""),
        author(1, "香蕉君", "互关必回"),
    )

    @Test
    fun author_time_field_only_reverses() {
        assertEquals(
            listOf(3, 2, 1),
            applyAuthorFilter(followed, "", AuthorSortField.TIME, LocalSortOrder.DESC).map { it.userId }
        )
        assertEquals(
            listOf(1, 2, 3),
            applyAuthorFilter(followed, "", AuthorSortField.TIME, LocalSortOrder.ASC).map { it.userId }
        )
    }

    @Test
    fun author_name_field_both_directions() {
        // 香蕉君(U+9999) > apple / Zebra 的码位；apple < Zebra
        assertEquals(
            listOf("apple", "Zebra", "香蕉君"),
            applyAuthorFilter(followed, "", AuthorSortField.NAME, LocalSortOrder.ASC).map { it.userName }
        )
        assertEquals(
            listOf("香蕉君", "Zebra", "apple"),
            applyAuthorFilter(followed, "", AuthorSortField.NAME, LocalSortOrder.DESC).map { it.userName }
        )
    }

    @Test
    fun author_search_matches_nickname_and_signature() {
        assertEquals(
            listOf("Zebra"),
            applyAuthorFilter(followed, "zebra", AuthorSortField.TIME, LocalSortOrder.DESC).map { it.userName }
        )
        // 签名里也能搜到（"互关必回"这类关键词很实用）
        assertEquals(
            listOf("香蕉君"),
            applyAuthorFilter(followed, "互关", AuthorSortField.TIME, LocalSortOrder.DESC).map { it.userName }
        )
        assertEquals(
            listOf("Zebra"),
            applyAuthorFilter(followed, "  推广  ", AuthorSortField.TIME, LocalSortOrder.DESC).map { it.userName }
        )
        assertTrue(applyAuthorFilter(followed, "不存在", AuthorSortField.TIME, LocalSortOrder.DESC).isEmpty())
        // 不改入参
        assertEquals(listOf(3, 2, 1), followed.map { it.userId })
    }
}
