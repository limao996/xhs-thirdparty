package com.thirdparty.xhs.data

import com.thirdparty.xhs.download.DownloadEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按作品聚合的单测：分组、排序、汇总文案、组级进度。 */
class DownloadGroupingTest {

    private fun task(
        id: Long,
        noteId: Long,
        kind: String = DownloadKind.IMAGE,
        status: String = DownloadStatus.DONE,
        imageIndex: Int = 0,
        stage: String = "",
        doneSegments: Int = 0,
        totalSegments: Int = 0,
        error: String? = null,
        title: String = "作品$noteId",
        uri: String? = "content://media/external/images/media/$id"
    ) = DownloadTaskEntity(
        id = id,
        noteId = noteId,
        title = title,
        cover = "",
        kind = kind,
        sourceUrl = "https://example.com/$id",
        imageIndex = imageIndex,
        status = status,
        stage = stage,
        error = error,
        doneSegments = doneSegments,
        totalSegments = totalSegments,
        bytes = 1000,
        savedUri = uri,
        createdAt = id,
        updatedAt = id
    )

    @Test
    fun groups_by_note_and_keeps_video_first_then_images_in_order() {
        val groups = DownloadGroups.of(
            listOf(
                task(1, noteId = 100, kind = DownloadKind.VIDEO, totalSegments = 6, doneSegments = 6),
                task(2, noteId = 200, imageIndex = 1),
                task(3, noteId = 100, imageIndex = 5),
                task(4, noteId = 100, imageIndex = 2),
                task(5, noteId = 200, imageIndex = 0)
            )
        )

        assertEquals(2, groups.size)
        // 作品顺序按首次出现（DAO 已按最新在前）
        assertEquals(100L, groups[0].noteId)
        assertEquals(200L, groups[1].noteId)
        // 组内：视频在前，图片按 imageIndex
        assertEquals(listOf(1L, 4L, 3L), groups[0].items.map { it.id })
        assertEquals(listOf(0, 1), groups[1].images.map { it.imageIndex })
    }

    @Test
    fun settled_only_when_nothing_unfinished() {
        val running = DownloadGroups.of(
            listOf(
                task(1, 100, status = DownloadStatus.DONE),
                task(2, 100, kind = DownloadKind.VIDEO, status = DownloadStatus.RUNNING, totalSegments = 6, doneSegments = 3)
            )
        ).first()
        assertTrue(!running.settled)
        assertEquals(1, running.doneCount)
        assertEquals(1, running.unfinishedCount)

        val settled = DownloadGroups.of(
            listOf(
                task(1, 100, status = DownloadStatus.DONE),
                task(2, 100, status = DownloadStatus.FAILED)
            )
        ).first()
        assertTrue(settled.settled)
        assertEquals(1, settled.failedCount)
    }

    @Test
    fun summary_text_covers_running_paused_and_done() {
        val running = DownloadGroups.of(
            listOf(
                task(1, 100, imageIndex = 0),
                task(2, 100, imageIndex = 1),
                task(3, 100, imageIndex = 2, status = DownloadStatus.RUNNING),
                task(4, 100, kind = DownloadKind.VIDEO, status = DownloadStatus.RUNNING, totalSegments = 6, doneSegments = 4)
            )
        ).first()
        assertEquals("视频 4/6 片 · 图片 2/3 张", running.statusSummary())

        val paused = DownloadGroups.of(
            listOf(task(1, 100, status = DownloadStatus.PAUSED), task(2, 100, status = DownloadStatus.PAUSED))
        ).first()
        assertEquals("已暂停 2 项 · 0/2 已完成", paused.statusSummary())

        val done = DownloadGroups.of(
            listOf(task(1, 100), task(2, 100), task(3, 100, kind = DownloadKind.VIDEO))
        ).first()
        assertEquals("已完成 · 视频 + 2 张图片", done.statusSummary())
        assertTrue(!done.failed)

        // 整组结束但有失败：单独成"失败"组（UI 里不再挂到「已完成」下面），文案直说哪一类失败
        val failed = DownloadGroups.of(
            listOf(task(1, 100, status = DownloadStatus.FAILED), task(2, 100, status = DownloadStatus.DONE))
        ).first()
        assertEquals("1 项失败 · 2 张图片", failed.statusSummary())
        assertTrue(failed.failed)

        val failedVideoOnly = DownloadGroups.of(
            listOf(task(1, 100, kind = DownloadKind.VIDEO, status = DownloadStatus.FAILED))
        ).first()
        assertEquals("视频 下载失败", failedVideoOnly.statusSummary())
        assertTrue(failedVideoOnly.failed)

        val failedImagesOnly = DownloadGroups.of(
            listOf(
                task(1, 100, imageIndex = 0, status = DownloadStatus.FAILED),
                task(2, 100, imageIndex = 1, status = DownloadStatus.FAILED)
            )
        ).first()
        assertEquals("2 张图片下载失败", failedImagesOnly.statusSummary())
    }

    @Test
    fun paused_group_shows_why_it_is_paused() {
        // 仅 Wi-Fi 把任务挡下时，库里写了原因；界面必须显示出来，否则用户不知道是自己按的还是被设置挡住的
        val g = DownloadGroups.of(
            listOf(
                task(1, 100, status = DownloadStatus.PAUSED, error = DownloadEngine.WAITING_WIFI_REASON),
                task(2, 100, imageIndex = 1, status = DownloadStatus.PAUSED)
            )
        ).first()
        assertEquals("等待 Wi-Fi（仅 Wi-Fi 下载已开启）", g.pauseReason)
        assertEquals("等待 Wi-Fi", g.pauseReasonShort)
        assertEquals("已暂停 2 项（等待 Wi-Fi） · 0/2 已完成", g.statusSummary())

        // 用户自己按的暂停（没有原因）不该显示括号
        val manual = DownloadGroups.of(
            listOf(task(1, 100, status = DownloadStatus.PAUSED))
        ).first()
        assertNull(manual.pauseReason)
        assertEquals("已暂停 1 项 · 0/1 已完成", manual.statusSummary())
    }

    @Test
    fun processing_stage_makes_progress_indeterminate() {
        val merging = DownloadGroups.of(
            listOf(
                task(1, 100, kind = DownloadKind.VIDEO, status = DownloadStatus.RUNNING,
                    totalSegments = 6, doneSegments = 6, stage = DownloadStage.MERGING)
            )
        ).first()
        assertTrue(merging.processing)
        assertNull("处理阶段没有可数进度", merging.progressFraction())
        assertEquals("合并中…", merging.statusSummary())

        val fetching = DownloadGroups.of(
            listOf(
                task(1, 100, kind = DownloadKind.VIDEO, status = DownloadStatus.RUNNING,
                    totalSegments = 6, doneSegments = 3, stage = DownloadStage.FETCHING)
            )
        ).first()
        assertEquals(0.5f, fetching.progressFraction()!!, 0.001f)
        assertEquals("视频 3/6 片", fetching.statusSummary())
    }

    @Test
    fun images_group_progress_counts_items() {
        val g = DownloadGroups.of(
            listOf(
                task(1, 100, imageIndex = 0, status = DownloadStatus.DONE),
                task(2, 100, imageIndex = 1, status = DownloadStatus.RUNNING)
            )
        ).first()
        assertEquals(0.5f, g.progressFraction()!!, 0.001f)
    }

    @Test
    fun first_openable_prefers_video_then_first_finished_image() {
        val withVideo = DownloadGroups.of(
            listOf(
                task(1, 100, imageIndex = 0),
                task(2, 100, kind = DownloadKind.VIDEO, uri = "content://media/external/video/media/2")
            )
        ).first()
        assertEquals(2L, withVideo.firstOpenable()?.id)

        val noVideo = DownloadGroups.of(
            listOf(
                task(1, 100, imageIndex = 0, uri = null),
                task(2, 100, imageIndex = 1)
            )
        ).first()
        assertEquals(2L, noVideo.firstOpenable()?.id)

        val nothing = DownloadGroups.of(listOf(task(1, 100, uri = null))).first()
        assertNull(nothing.firstOpenable())
    }
}
