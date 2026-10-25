package com.thirdparty.xhs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [applySavedFilter] 的单测：搜索匹配范围、三种排序、叠加顺序、不改入参。 */
class SavedFilterTest {

    private fun note(id: Long, title: String, author: String) = NoteItem(
        noteId = id,
        title = title,
        userName = author,
        // 其余是必填但没有语义的字段，给中性值即可（本测试只关心标题/作者/顺序）
        cover = "",
        thumbnail = "",
        noteType = 1,
        likeCount = 0,
        collectCount = 0,
        commentCount = 0
    )

    /** DAO 返回的顺序：最近收藏在前（savedAt DESC）。 */
    private val newestFirst = listOf(
        note(3, "Zebra 合集", "作者甲"),
        note(2, "apple pie", "作者乙"),
        note(1, "Banana", "作者丙"),
    )

    @Test
    fun empty_query_keeps_everything() {
        assertEquals(newestFirst, applySavedFilter(newestFirst, "", SavedSort.SAVED_DESC))
        assertEquals(newestFirst, applySavedFilter(newestFirst, "   ", SavedSort.SAVED_DESC))
    }

    @Test
    fun search_matches_title_and_author_ignoring_case() {
        assertEquals(
            listOf("Zebra 合集"),
            applySavedFilter(newestFirst, "zebra", SavedSort.SAVED_DESC).map { it.title }
        )
        // 作者名也能搜到，且大小写不敏感
        assertEquals(
            listOf("apple pie"),
            applySavedFilter(newestFirst, "作者乙", SavedSort.SAVED_DESC).map { it.title }
        )
        assertEquals(
            listOf("apple pie"),
            applySavedFilter(newestFirst, "APPLE", SavedSort.SAVED_DESC).map { it.title }
        )
    }

    @Test
    fun search_trims_surrounding_spaces() {
        assertEquals(
            listOf("Banana"),
            applySavedFilter(newestFirst, "  banana  ", SavedSort.SAVED_DESC).map { it.title }
        )
    }

    @Test
    fun sort_orders() {
        assertEquals(listOf(3L, 2L, 1L), applySavedFilter(newestFirst, "", SavedSort.SAVED_DESC).map { it.noteId })
        assertEquals(listOf(1L, 2L, 3L), applySavedFilter(newestFirst, "", SavedSort.SAVED_ASC).map { it.noteId })
        // 标题：apple pie < Banana < Zebra（不区分大小写）
        assertEquals(
            listOf("apple pie", "Banana", "Zebra 合集"),
            applySavedFilter(newestFirst, "", SavedSort.TITLE).map { it.title }
        )
    }

    @Test
    fun filter_then_sort() {
        val items = listOf(
            note(5, "Banana 二", "作者甲"),
            note(4, "Banana 一", "作者甲"),
            note(3, "Zebra", "作者乙"),
        )
        assertEquals(
            listOf("Banana 一", "Banana 二"),
            applySavedFilter(items, "banana", SavedSort.TITLE).map { it.title }
        )
        // 最早收藏 = 反转当前过滤结果
        assertEquals(
            listOf("Banana 一", "Banana 二"),
            applySavedFilter(items, "banana", SavedSort.SAVED_ASC).map { it.title }
        )
    }

    @Test
    fun no_match_returns_empty_and_input_is_untouched() {
        assertTrue(applySavedFilter(newestFirst, "不存在的关键词", SavedSort.SAVED_DESC).isEmpty())
        // 排序不得就地打乱入参（界面里的 all 还要用于计数与切回默认顺序）
        assertEquals(listOf(3L, 2L, 1L), newestFirst.map { it.noteId })
    }
}
