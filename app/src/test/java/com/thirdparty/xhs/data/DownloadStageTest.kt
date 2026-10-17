package com.thirdparty.xhs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段映射的单测。
 *
 * 存在的理由：切片下完之后的 合并 / 转封装 / 保存 三段都要在界面上说清"在干什么"，
 * 这段映射（以及"哪几段没有可数进度"）是纯逻辑，钉住它就不会在改动里被改错。
 */
class DownloadStageTest {

    @Test
    fun labels_are_chinese_and_cover_every_stage() {
        assertEquals("下载中", DownloadStage.label(DownloadStage.FETCHING))
        assertEquals("合并中", DownloadStage.label(DownloadStage.MERGING))
        assertEquals("转换为 mp4", DownloadStage.label(DownloadStage.REMUXING))
        assertEquals("保存中", DownloadStage.label(DownloadStage.SAVING))
    }

    @Test
    fun unknown_or_empty_stage_has_no_label() {
        assertNull(DownloadStage.label(null))
        assertNull(DownloadStage.label(""))
        assertNull(DownloadStage.label("SOMETHING_ELSE"))
    }

    @Test
    fun only_post_processing_stages_are_marked_as_processing() {
        // 拉切片阶段有可数进度（x/y 片），界面用确定进度条
        assertFalse(DownloadStage.isProcessing(DownloadStage.FETCHING))
        assertFalse(DownloadStage.isProcessing(null))
        assertFalse(DownloadStage.isProcessing(""))
        // 这三段没有可数进度：界面与通知都要换成"不确定进度 + 已用时间"
        assertTrue(DownloadStage.isProcessing(DownloadStage.MERGING))
        assertTrue(DownloadStage.isProcessing(DownloadStage.REMUXING))
        assertTrue(DownloadStage.isProcessing(DownloadStage.SAVING))
    }

    @Test
    fun slow_hint_only_for_long_remux() {
        // 大视频 media3 会落到重编码、耗时以分钟计；超过 30 秒要给出"视频较大"的预期，避免被当成卡死
        assertNull(DownloadStage.slowHint(DownloadStage.REMUXING, 0))
        assertNull(DownloadStage.slowHint(DownloadStage.REMUXING, 29))
        assertEquals("视频较大，可能需要几分钟", DownloadStage.slowHint(DownloadStage.REMUXING, 30))
        assertEquals("视频较大，可能需要几分钟", DownloadStage.slowHint(DownloadStage.REMUXING, 600))
        // 只有"转 mp4"这一段提示（合并/保存通常很快）
        assertNull(DownloadStage.slowHint(DownloadStage.MERGING, 300))
        assertNull(DownloadStage.slowHint(DownloadStage.SAVING, 300))
        assertNull(DownloadStage.slowHint(null, 300))
    }
}
