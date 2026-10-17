package com.thirdparty.xhs.download

import android.content.Context
import android.util.Log
import androidx.core.net.toUri

/**
 * 重复下载文件的检查与清理。
 *
 * 为什么需要：在加"重复点下载去重"（`data/DownloadDedupe.kt`）之前，同一条内容可以被下载多次，
 * 落到公共目录时会被系统改名，于是同一个作品目录里出现
 * `01.png.jpg` 与 `01.png (1).jpg` 这种成对文件（2026-10-17 在模拟器上实测到 24 个文件对 12 条记录）。
 * 这些文件不会再被任何记录引用，只占空间，用户自己很难分辨哪个该留。
 *
 * 判定规则（纯函数 [duplicates]，可单测）：
 * - 把文件名里的重复后缀（` (1)` / `(2)` 这类）去掉，得到"基名"；
 * - 同一目录 + 同一基名的文件归为一组；
 * - 一组里只保留**一个**：优先保留被记录引用的那个，其次保留没带重复后缀的那个，再次保留后缀序号最小的；
 * - 其余全部算重复。
 *
 * 删除只走 MediaStore（本应用是这些文件的创建者），**不动数据库记录**。
 */
object DuplicateCleaner {

    private const val TAG = "XhsDownload"

    /** 公共目录里一个媒体文件（够判定用的字段）。 */
    data class MediaFile(
        val uri: String,
        val displayName: String,
        /** 形如 `Pictures/小黄书/标题_123/`（含结尾斜杠） */
        val relativePath: String,
        val sizeBytes: Long
    ) {
        /** 与下载记录 `savedPath` 对齐的完整显示路径。 */
        val fullPath: String get() = relativePath + displayName
    }

    /** 重复后缀：`(1)`、` (2)`（应用自己拼的和系统补的都算）。 */
    private val DUP_SUFFIX = Regex("""\s*\((\d+)\)$""")

    /** 去掉重复后缀后的基名；返回 [name] 本身表示没有后缀。 */
    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return stem.replace(DUP_SUFFIX, "") + ext
    }

    /** 冗余后缀的序号（没有后缀返回 0）。 */
    fun dupIndex(name: String): Int {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val m = DUP_SUFFIX.find(stem) ?: return 0
        return m.groupValues[1].toIntOrNull() ?: 0
    }

    /**
     * 从 [files] 里挑出该删的重复文件。
     *
     * @param referenced 仍被下载记录引用的文件完整路径（`savedPath`）
     */
    fun duplicates(files: List<MediaFile>, referenced: Set<String>): List<MediaFile> {
        return files
            .groupBy { it.relativePath + baseName(it.displayName) }
            .values
            .filter { it.size > 1 }
            .flatMap { group ->
                // 保留优先级：被记录引用 > 无重复后缀 > 后缀序号小
                val keep = group.firstOrNull { it.fullPath in referenced }
                    ?: group.filter { dupIndex(it.displayName) == 0 }.minByOrNull { it.displayName }
                    ?: group.minByOrNull { dupIndex(it.displayName) }!!
                group.filter { it.uri != keep.uri }
            }
    }

    /** 扫描 `Movies/小黄书` 与 `Pictures/小黄书` 下的文件。 */
    suspend fun scan(context: Context): List<MediaFile> = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        val out = mutableListOf<MediaFile>()
        out += query(
            context,
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.SIZE
        )
        out += query(
            context,
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.SIZE
        )
        out.filter { it.relativePath.contains("小黄书") }
    }

    private fun query(
        context: Context,
        collection: android.net.Uri,
        nameColumn: String,
        sizeColumn: String
    ): List<MediaFile> {
        val result = mutableListOf<MediaFile>()
        val projection = arrayOf(
            android.provider.MediaStore.MediaColumns._ID,
            nameColumn,
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
            sizeColumn
        )
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val dir = c.getString(2).orEmpty()
                    result += MediaFile(
                        uri = collection.buildUpon().appendPath(id.toString()).build().toString(),
                        displayName = name,
                        relativePath = dir,
                        sizeBytes = c.getLong(3)
                    )
                }
            }
        }.onFailure { Log.w(TAG, "扫描公共目录失败：${it.message}") }
        return result
    }

    /** 删除一组文件，返回成功删除的个数。 */
    suspend fun delete(context: Context, files: List<MediaFile>): Int = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        var done = 0
        files.forEach { file ->
            runCatching {
                if (context.contentResolver.delete(file.uri.toUri(), null, null) > 0) done++
            }.onFailure { Log.w(TAG, "删除重复文件失败：${file.fullPath}（${it.message}）") }
        }
        Log.i(TAG, "清理重复文件：请求 ${files.size} 个，成功 $done 个")
        done
    }
}
