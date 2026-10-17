package com.thirdparty.xhs.download

/**
 * 下载文件的目录与命名规则（纯逻辑，可单测）。
 *
 * 用户拍定的落盘方案：视频 `Movies/小黄书/<标题>_<noteId>.mp4`，
 * 图片 `Pictures/小黄书/<标题>_<noteId>/01.jpg`。带 `noteId` 是为了不重名，
 * 子目录按作品分组是为了在相册/文件管理里能一眼找到同一作品。
 */
object DownloadNames {

    /** 公共目录下的应用子目录（视频与图片各一层）。 */
    const val DIR = "小黄书"

    private const val MAX_TITLE = 48
    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    private val SPACES = Regex("\\s+")

    /** 把作品标题收拾成能当文件名的样子：去掉非法字符、压空白、限长，空则给兜底名。 */
    fun sanitizeTitle(title: String?): String {
        val cleaned = (title ?: "")
            .replace(ILLEGAL, "_")
            .replace(SPACES, " ")
            .trim()
            .trim('.', ' ')
        val capped = if (cleaned.length > MAX_TITLE) cleaned.take(MAX_TITLE).trim() else cleaned
        return capped.ifEmpty { "作品" }
    }

    /** 作品在公共目录里的基名：`<标题>_<noteId>`。 */
    fun baseName(title: String?, noteId: Long): String = "${sanitizeTitle(title)}_$noteId"

    /** 视频相对路径（相对 `Movies/`）。 */
    fun videoRelativePath(title: String?, noteId: Long, extension: String = "mp4"): String =
        "$DIR/${baseName(title, noteId)}.$extension"

    /** 图片相对目录（相对 `Pictures/`）。 */
    fun imageRelativeDir(title: String?, noteId: Long): String = "$DIR/${baseName(title, noteId)}"

    /** 图片文件名：按作品内序号补零，保证排序稳定。 */
    fun imageFileName(index: Int, extension: String = "jpg"): String =
        "%02d.%s".format(index + 1, extension)

    /** 从图片地址猜扩展名（拿不到就用 jpg）。 */
    fun imageExtension(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "")
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ext.lowercase() else "jpg"
    }

    /**
     * 由扩展名给图片 MIME。
     *
     * **必须与文件名对得上**：MediaStore 会用 MIME 去补扩展名，如果文件名是 `01.png` 而 MIME 写死
     * `image/jpeg`，落盘就会变成 `01.png.jpg`（2026-10-17 在模拟器照片目录里实测到）。
     */
    fun imageMimeType(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic", "heif" -> "image/heic"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    /**
     * 同名冲突时追加 `(2)`、`(3)`…（[exists] 由调用方给，便于单测）。
     * 视频带了 noteId 基本不会冲突，但用户可能从别的客户端存过同名文件。
     */
    fun uniqueName(base: String, extension: String, exists: (String) -> Boolean): String {
        val first = if (extension.isEmpty()) base else "$base.$extension"
        if (!exists(first)) return first
        var i = 2
        while (true) {
            val candidate = if (extension.isEmpty()) "$base($i)" else "$base($i).$extension"
            if (!exists(candidate)) return candidate
            i++
            if (i > 999) return candidate
        }
    }
}
