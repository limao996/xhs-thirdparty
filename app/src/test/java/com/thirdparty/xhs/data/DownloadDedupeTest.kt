package com.thirdparty.xhs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载去重（重复点下载）的单测。 */
class DownloadDedupeTest {

    private fun task(
        id: Long = 1,
        noteId: Long = 100,
        kind: String = DownloadKind.IMAGE,
        imageIndex: Int = 0,
        status: String = DownloadStatus.DONE,
        title: String = "作品"
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
    fun key_distinguishes_image_index_and_kind() {
        assertEquals("100|IMAGE|2", DownloadDedupe.itemKey(100, DownloadKind.IMAGE, 2))
        assertEquals("100|VIDEO|0", DownloadDedupe.itemKey(100, DownloadKind.VIDEO, 0))
        // 同一作品、同一类型、不同序号 → 不同键
        assertTrue(
            DownloadDedupe.itemKey(100, DownloadKind.IMAGE, 1) !=
                DownloadDedupe.itemKey(100, DownloadKind.IMAGE, 2)
        )
    }

    @Test
    fun skips_items_already_downloaded_or_in_progress() {
        val existing = listOf(
            task(id = 1, imageIndex = 0, status = DownloadStatus.DONE),
            task(id = 2, imageIndex = 1, status = DownloadStatus.RUNNING),
            task(id = 3, kind = DownloadKind.VIDEO, status = DownloadStatus.QUEUED)
        )
        val incoming = listOf(
            task(id = 10, imageIndex = 0),                       // 已下载 → 跳过
            task(id = 11, imageIndex = 1),                       // 下载中 → 跳过
            task(id = 12, kind = DownloadKind.VIDEO),            // 排队中 → 跳过
            task(id = 13, imageIndex = 2)                        // 新图片 → 入队
        )
        val plan = DownloadDedupe.planEnqueue(incoming, existing)

        assertEquals(listOf(13L), plan.added.map { it.id })
        assertEquals(3, plan.skippedCount)
        assertTrue(plan.hasAnything)
    }

    @Test
    fun failed_rows_are_replaced_not_duplicated() {
        val existing = listOf(
            task(id = 1, imageIndex = 0, status = DownloadStatus.FAILED),
            task(id = 2, imageIndex = 1, status = DownloadStatus.FAILED)
        )
        val incoming = listOf(
            task(id = 10, imageIndex = 0),
            task(id = 11, imageIndex = 1)
        )
        val plan = DownloadDedupe.planEnqueue(incoming, existing)

        // 失败的不算重复：重新入队，并且旧失败行要被清掉（同一内容不留两条）
        assertEquals(listOf(10L, 11L), plan.added.map { it.id })
        assertEquals(0, plan.skippedCount)
        assertEquals(listOf(1L, 2L), plan.superseded.map { it.id })
    }

    @Test
    fun duplicate_within_same_batch_is_skipped_once() {
        val plan = DownloadDedupe.planEnqueue(
            incoming = listOf(task(id = 10, imageIndex = 0), task(id = 11, imageIndex = 0)),
            existing = emptyList()
        )
        assertEquals(1, plan.added.size)
        assertEquals(1, plan.skippedCount)
    }

    @Test
    fun badges_mark_downloaded_and_in_progress_only() {
        assertNull(DownloadDedupe.badgeFor(null))
        assertNull(DownloadDedupe.badgeFor(task(status = DownloadStatus.FAILED)))
        assertEquals("已下载", DownloadDedupe.badgeFor(task(status = DownloadStatus.DONE)))
        assertEquals("下载中", DownloadDedupe.badgeFor(task(status = DownloadStatus.RUNNING)))
        assertEquals("下载中", DownloadDedupe.badgeFor(task(status = DownloadStatus.QUEUED)))
        assertEquals("已暂停", DownloadDedupe.badgeFor(task(status = DownloadStatus.PAUSED)))
    }

    @Test
    fun occupied_keys_ignore_failed_rows() {
        val keys = DownloadDedupe.occupiedKeys(
            listOf(
                task(id = 1, imageIndex = 0, status = DownloadStatus.DONE),
                task(id = 2, imageIndex = 1, status = DownloadStatus.FAILED),
                task(id = 3, kind = DownloadKind.VIDEO, status = DownloadStatus.PAUSED)
            )
        )
        assertEquals(
            setOf(
                DownloadDedupe.itemKey(100, DownloadKind.IMAGE, 0),
                DownloadDedupe.itemKey(100, DownloadKind.VIDEO, 0)
            ),
            keys
        )
    }
}
