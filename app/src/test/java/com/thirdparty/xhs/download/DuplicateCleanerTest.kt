package com.thirdparty.xhs.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 重复文件判定的单测（纯逻辑部分）。 */
class DuplicateCleanerTest {

    private fun f(name: String, dir: String = "Pictures/小黄书/作品_1/", size: Long = 100) =
        DuplicateCleaner.MediaFile(
            uri = "content://media/external/images/media/${name.hashCode()}",
            displayName = name,
            relativePath = dir,
            sizeBytes = size
        )

    @Test
    fun base_name_strips_duplicate_suffix_before_extension() {
        assertEquals("01.png.jpg", DuplicateCleaner.baseName("01.png.jpg"))
        assertEquals("01.png.jpg", DuplicateCleaner.baseName("01.png (1).jpg"))
        assertEquals("01.png.jpg", DuplicateCleaner.baseName("01.png(2).jpg"))
        // 视频那种把序号插在扩展名前的形式
        assertEquals("标题_1.mp4", DuplicateCleaner.baseName("标题_1(2).mp4"))
        // 不带扩展名
        assertEquals("abc", DuplicateCleaner.baseName("abc (1)"))
    }

    @Test
    fun duplicate_index_reads_the_number() {
        assertEquals(0, DuplicateCleaner.dupIndex("01.jpg"))
        assertEquals(1, DuplicateCleaner.dupIndex("01 (1).jpg"))
        assertEquals(2, DuplicateCleaner.dupIndex("01.png(2).jpg"))
        assertEquals(3, DuplicateCleaner.dupIndex("标题_1(3).mp4"))
    }

    @Test
    fun keeps_the_referenced_file_and_flags_the_rest() {
        val files = listOf(
            f("01.png.jpg"),
            f("01.png (1).jpg")
        )
        val referenced = setOf("Pictures/小黄书/作品_1/01.png.jpg")

        val dup = DuplicateCleaner.duplicates(files, referenced)

        assertEquals(listOf("01.png (1).jpg"), dup.map { it.displayName })
    }

    @Test
    fun without_any_record_keeps_the_unsuffixed_one() {
        // 记录已被删除、只剩文件的孤儿场景：保留没后缀的那个
        val files = listOf(
            f("02.png (1).jpg"),
            f("02.png.jpg"),
            f("02.png (2).jpg")
        )
        val dup = DuplicateCleaner.duplicates(files, emptySet())

        assertEquals(listOf("02.png (1).jpg", "02.png (2).jpg"), dup.map { it.displayName }.sorted())
    }

    @Test
    fun files_in_different_works_are_not_duplicates() {
        // 不同作品目录里的同名文件互不相干（基名相同但目录不同 → 不同分组）
        val files = listOf(
            f("01.jpg", dir = "Pictures/小黄书/A_1/"),
            f("01.jpg", dir = "Pictures/小黄书/B_2/")
        )
        assertTrue(DuplicateCleaner.duplicates(files, emptySet()).isEmpty())
    }

    @Test
    fun single_files_are_never_flagged() {
        assertTrue(DuplicateCleaner.duplicates(listOf(f("01.jpg")), emptySet()).isEmpty())
        assertTrue(
            DuplicateCleaner.duplicates(
                listOf(f("01.jpg"), f("02.jpg"), f("03.jpg")),
                emptySet()
            ).isEmpty()
        )
    }

    @Test
    fun orphan_images_are_flagged_only_when_the_whole_work_dir_is_unreferenced() {
        val files = listOf(
            f("01.jpg", dir = "Pictures/小黄书/孤儿作品_9/"),
            f("02.jpg", dir = "Pictures/小黄书/孤儿作品_9/"),
            f("01.png.jpg", dir = "Pictures/小黄书/在记录里的作品_8/")
        )
        // 记录里只有"在记录里的作品_8"，且它的记录路径是 01.jpg（老版本被 MediaStore 改名成了 01.png.jpg）
        val referenced = setOf("Pictures/小黄书/在记录里的作品_8/01.jpg")

        val orphans = DuplicateCleaner.orphans(files, referenced)

        // 整目录没记录的 → 孤儿；名字被系统改过的 → 不能被误判（否则记录里的 uri 就悬空了）
        assertEquals(
            listOf("01.jpg", "02.jpg"),
            orphans.map { it.displayName }.sorted()
        )
    }

    @Test
    fun orphan_videos_are_flagged_by_exact_path() {
        val files = listOf(
            f("有记录的_1.mp4", dir = "Movies/小黄书/"),
            f("记录已删的_2.mp4", dir = "Movies/小黄书/")
        )
        val referenced = setOf("Movies/小黄书/有记录的_1.mp4")

        val orphans = DuplicateCleaner.orphans(files, referenced)

        assertEquals(listOf("记录已删的_2.mp4"), orphans.map { it.displayName })
    }

    @Test
    fun nothing_is_an_orphan_when_everything_is_referenced() {
        val files = listOf(
            f("01.jpg", dir = "Pictures/小黄书/A_1/"),
            f("v_1.mp4", dir = "Movies/小黄书/")
        )
        val referenced = setOf("Pictures/小黄书/A_1/01.jpg", "Movies/小黄书/v_1.mp4")
        assertTrue(DuplicateCleaner.orphans(files, referenced).isEmpty())
    }
}
