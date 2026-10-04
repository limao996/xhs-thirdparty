package com.thirdparty.xhs.data

import com.thirdparty.xhs.App
import java.io.File

/**
 * The caches this app actually keeps, so 设置 → 清除缓存 can offer them by name.
 *
 * Every entry here is *regenerable*: deleting it costs a re-download or a
 * re-decode and nothing else. User data (收藏 / 最近浏览 / 关注的作者, the guest
 * account) is deliberately NOT in this list — it lives in Room and the account
 * prefs, it is the only copy, and it is what 备份与恢复 is for. Listing it as
 * "cache" would invite the user to delete their own library.
 *
 * The set is small because the app really only has three: one disk cache for the
 * images and API responses OkHttp is allowed to store, the decoded-bitmap LRU in
 * memory, and whatever strays land in `cache/`. There is no video cache — media3
 * plays straight from the network (see ui/components/VideoPlayer.kt), so nothing
 * about a played video survives the screen.
 */
enum class CacheKind(val title: String, val hint: String) {
    IMAGE_DISK(
        "图片与封面缓存",
        "磁盘上的封面、头像与图片（上限 64 MB）。清除后再次浏览会重新下载。"
    ),
    IMAGE_MEMORY(
        "图片内存缓存",
        "已解码图片占用的内存。清除后只会重新解码一次，不需要重新下载。"
    ),
    TEMP_FILES(
        "其它临时文件",
        "缓存目录里剩下的临时文件（中断的写入残留、拉取到一半的文件）。应用随时会重建。"
    )
}

/** One cache and how much it currently occupies, in bytes. */
data class CacheEntry(val kind: CacheKind, val bytes: Long)

object AppCaches {

    /** Sub-directory of `cacheDir` that OkHttp owns (see App.httpClient). */
    private const val IMAGE_CACHE_DIR = "http_cache"

    /** Every cache, with its current size. Safe to call from a background thread. */
    fun entries(): List<CacheEntry> = CacheKind.entries.map { CacheEntry(it, bytesOf(it)) }

    fun totalBytes(): Long = CacheKind.entries.sumOf { bytesOf(it) }

    /**
     * Current size of one cache. Never throws: a cache that cannot be measured
     * reports 0 rather than taking the screen down with it.
     */
    fun bytesOf(kind: CacheKind): Long = runCatching {
        when (kind) {
            CacheKind.IMAGE_DISK -> App.repo.httpCacheSizeBytes()
            CacheKind.IMAGE_MEMORY -> com.thirdparty.xhs.ui.components.imageMemoryCacheBytes()
            CacheKind.TEMP_FILES -> tempFilesBytes()
        }
    }.getOrDefault(0L)

    /**
     * Clear [kinds] and return how many bytes that actually freed.
     *
     * Measured before/after instead of trusting the pre-clear sizes: the bitmap
     * LRU and OkHttp both keep their own books, and the honest number to show the
     * user is what the counters say afterwards.
     */
    fun clear(kinds: Set<CacheKind>): Long {
        if (kinds.isEmpty()) return 0L
        val before = totalBytes()
        if (CacheKind.IMAGE_DISK in kinds) App.repo.clearHttpCache()
        if (CacheKind.IMAGE_MEMORY in kinds) {
            com.thirdparty.xhs.ui.components.clearImageMemoryCache()
        }
        if (CacheKind.TEMP_FILES in kinds) clearTempFiles()
        return (before - totalBytes()).coerceAtLeast(0L)
    }

    private fun cacheDir(): File? = App.INSTANCE.cacheDir

    /**
     * Whether a file directly inside `cache/` is ours to delete.
     *
     * Two things are never touched: the image cache directory (it has its own
     * checkbox, and evicting it through OkHttp keeps the client's books right)
     * and `xhs_local.db.lck` — that is SQLite's lock file, which happens to live
     * in `cache/` but is *not* a cache. Deleting it frees 0 bytes and can only
     * break locking, so "其它临时文件" must never include it.
     */
    private fun isClearableTemp(file: File): Boolean =
        file.name != IMAGE_CACHE_DIR && !file.name.endsWith(".lck")

    /** Everything directly inside `cache/` that [isClearableTemp] allows. */
    private fun tempFilesBytes(): Long {
        val dir = cacheDir() ?: return 0L
        return (dir.listFiles() ?: emptyArray())
            .filter { isClearableTemp(it) }
            .sumOf { sizeOf(it) }
    }

    private fun clearTempFiles() {
        val dir = cacheDir() ?: return
        (dir.listFiles() ?: emptyArray())
            .filter { isClearableTemp(it) }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    private fun sizeOf(file: File): Long =
        if (file.isDirectory) (file.listFiles() ?: emptyArray()).sumOf { sizeOf(it) }
        else file.length()
}

/** Human-readable byte size, shared by the settings row and the cache page. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
