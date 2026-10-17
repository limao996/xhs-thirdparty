package com.thirdparty.xhs.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目录与命名规则的单测（用户拍定的方案：按作品建子目录 + 带 noteId）。 */
class DownloadNamesTest {

    @Test
    fun sanitizes_illegal_characters_and_whitespace() {
        assertEquals("a_b_c", DownloadNames.sanitizeTitle("a/b:c"))
        assertEquals("标题 空格", DownloadNames.sanitizeTitle("  标题   空格  "))
        assertEquals("作品", DownloadNames.sanitizeTitle(""))
        assertEquals("作品", DownloadNames.sanitizeTitle(null))
        assertEquals("作品", DownloadNames.sanitizeTitle("   "))
        // 结尾的点与空格在部分文件系统上会出问题
        assertEquals("名字", DownloadNames.sanitizeTitle("名字... "))
    }

    @Test
    fun caps_very_long_titles() {
        val long = "长".repeat(200)
        val cleaned = DownloadNames.sanitizeTitle(long)
        assertTrue("应被截断", cleaned.length <= 48)
        assertTrue(cleaned.isNotEmpty())
    }

    @Test
    fun video_path_is_under_app_dir_with_note_id() {
        assertEquals("小黄书/标题_123.mp4", DownloadNames.videoRelativePath("标题", 123))
        assertEquals("小黄书/作品_9.mp4", DownloadNames.videoRelativePath("", 9))
    }

    @Test
    fun image_paths_are_grouped_by_note() {
        assertEquals("小黄书/标题_123", DownloadNames.imageRelativeDir("标题", 123))
        assertEquals("01.jpg", DownloadNames.imageFileName(0))
        assertEquals("12.png", DownloadNames.imageFileName(11, "png"))
    }

    @Test
    fun image_extension_falls_back_to_jpg() {
        assertEquals("jpg", DownloadNames.imageExtension("https://x/a/b/c.jpg"))
        assertEquals("png", DownloadNames.imageExtension("https://x/a/b/c.PNG?sign=1"))
        assertEquals("jpg", DownloadNames.imageExtension("https://x/a/b/noext"))
        assertEquals("jpg", DownloadNames.imageExtension(""))
        assertEquals("webp", DownloadNames.imageExtension("https://x/a/b/c.webp"))
    }

    @Test
    fun unique_name_appends_counter_on_conflict() {
        val taken = mutableSetOf("标题_1.mp4")
        val first = DownloadNames.uniqueName("标题_1", "mp4") { false }
        assertEquals("标题_1.mp4", first)

        val second = DownloadNames.uniqueName("标题_1", "mp4") { it in taken }
        assertEquals("标题_1(2).mp4", second)
        taken.add(second)
        val third = DownloadNames.uniqueName("标题_1", "mp4") { it in taken }
        assertEquals("标题_1(3).mp4", third)
        assertFalse("不能返回已存在的名字", third in taken)
    }
}
