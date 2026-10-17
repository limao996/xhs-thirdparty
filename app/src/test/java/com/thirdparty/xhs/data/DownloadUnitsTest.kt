package com.thirdparty.xhs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 执行单元归并的单测（图集不能一张图占一个名额）。 */
class DownloadUnitsTest {

    private fun task(
        id: Long,
        noteId: Long,
        kind: String = DownloadKind.IMAGE,
        imageIndex: Int = 0,
        status: String = DownloadStatus.QUEUED,
        title: String = "作品$noteId"
    ) = DownloadTaskEntity(
        id = id,
        noteId = noteId,
        title = title,
        kind = kind,
        sourceUrl = "https://example.com/$id",
        imageIndex = imageIndex,
        status = status,
        createdAt = id,
        updatedAt = id
    )

    @Test
    fun all_images_of_one_work_become_a_single_unit() {
        val units = DownloadUnit.of(
            (0 until 20).map { task(id = it.toLong() + 1, noteId = 100, imageIndex = it) }
        )
        assertEquals("20 张图 = 1 个单元", 1, units.size)
        assertEquals(20, units[0].images.size)
        assertEquals(20, units[0].tasks.size)
        assertTrue(units[0].isImageSet)
        assertEquals("i100", units[0].key)
    }

    @Test
    fun video_is_its_own_unit_and_image_set_is_another() {
        val units = DownloadUnit.of(
            listOf(
                task(1, noteId = 100, kind = DownloadKind.VIDEO),
                task(2, noteId = 100, imageIndex = 0),
                task(3, noteId = 100, imageIndex = 1)
            )
        )
        assertEquals(2, units.size)
        // 先出现的是视频 → 视频单元在前
        assertEquals("v1", units[0].key)
        assertEquals("i100", units[1].key)
        assertEquals(2, units[1].images.size)
    }

    @Test
    fun different_works_are_different_units_in_first_seen_order() {
        val units = DownloadUnit.of(
            listOf(
                task(1, noteId = 200, imageIndex = 0),
                task(2, noteId = 300, kind = DownloadKind.VIDEO),
                task(3, noteId = 200, imageIndex = 1)
            )
        )
        // 视频单元的键是 `v<任务 id>`（视频一件作品只有一个），图集是 `i<作品 id>`
        assertEquals(listOf("i200", "v2"), units.map { it.key })
        assertEquals(2, units[0].images.size)
    }

    @Test
    fun images_are_ordered_by_index_inside_a_unit() {
        val units = DownloadUnit.of(
            listOf(
                task(1, noteId = 100, imageIndex = 5),
                task(2, noteId = 100, imageIndex = 0),
                task(3, noteId = 100, imageIndex = 3)
            )
        )
        assertEquals(listOf(0, 3, 5), units[0].images.map { it.imageIndex })
    }

    @Test
    fun key_of_matches_unit_grouping() {
        assertEquals("v7", DownloadUnit.keyOf(task(7, 100, kind = DownloadKind.VIDEO)))
        assertEquals("i100", DownloadUnit.keyOf(task(7, 100, kind = DownloadKind.IMAGE, imageIndex = 3)))
        assertEquals(emptyList<DownloadUnit>(), DownloadUnit.of(emptyList()))
    }
}
