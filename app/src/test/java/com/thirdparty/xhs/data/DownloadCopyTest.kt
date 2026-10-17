package com.thirdparty.xhs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 通知文案的单测（用户 2026-10-17 两轮反馈：太乱 / 重复 / 只讲当前作品）。 */
class DownloadCopyTest {

    @Test
    fun running_title_is_the_work_itself() {
        assertEquals("原神琴团长", DownloadCopy.runningTitle("原神琴团长"))
        assertEquals("下载中", DownloadCopy.runningTitle(""))
    }

    @Test
    fun running_text_never_repeats_the_stage() {
        // 进度行里已经有阶段（转换为 mp4…），这里**不再**追加阶段标签 ——
        // 之前两处都贴，通知里出现过「转换为 mp4 · 转换为 mp4」
        assertEquals("转换为 mp4…", DownloadCopy.runningText("转换为 mp4…", 0, null))
        assertEquals("图片 26/43 张", DownloadCopy.runningText("图片 26/43 张", 0, null))
        assertEquals(
            "转换为 mp4… · 已用 32 秒 · 视频较大，可能需要几分钟",
            DownloadCopy.runningText("转换为 mp4…", 32, "视频较大，可能需要几分钟")
        )
        assertEquals("正在准备…", DownloadCopy.runningText("", 0, null))
    }

    @Test
    fun sub_text_and_paused_text_talk_about_works() {
        assertEquals("下载中 · 已完成 2 / 5 件作品", DownloadCopy.worksSubText("下载中", 2, 5))
        assertNull(DownloadCopy.worksSubText("下载中", 0, 0))
        assertEquals("已完成 2 / 5 件作品 · 点开可继续", DownloadCopy.pausedText(2, 5))
        assertEquals("点开可继续", DownloadCopy.pausedText(0, 0))
    }

    @Test
    fun finished_text_counts_by_kind() {
        assertEquals("完成 1 个视频、20 张图片，点开查看", DownloadCopy.finishedText(1, 20, 0, 0))
        assertEquals("完成 20 张图片，点开查看", DownloadCopy.finishedText(0, 20, 0, 0))
        assertEquals("完成 1 个视频、18 张图片；2 张图片失败", DownloadCopy.finishedText(1, 18, 0, 2))
        assertEquals("2 张图片下载失败，点开可重试", DownloadCopy.finishedText(0, 0, 0, 2))
        assertEquals("没有需要下载的内容", DownloadCopy.finishedText(0, 0, 0, 0))
    }
}
