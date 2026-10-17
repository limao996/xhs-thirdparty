package com.thirdparty.xhs.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream

/**
 * 把下载好的文件写进**公共目录**（用户能在相册/文件管理里看到），走 MediaStore。
 *
 * 版本差异（这是本文件存在的理由）：
 * - **API 29+**：用 `RELATIVE_PATH` + `IS_PENDING` 直接插 MediaStore，**不需要任何权限**；
 * - **API 26–28**：没有 `RELATIVE_PATH`，得先自己建目录再以 `DATA` 绝对路径插入，
 *   并且需要运行时 `WRITE_EXTERNAL_STORAGE`（清单里已用 `maxSdkVersion="28"` 限定）。
 *
 * 落盘规则（用户拍定）：视频 `Movies/小黄书/<标题>_<noteId>.mp4`；
 * 图片 `Pictures/小黄书/<标题>_<noteId>/01.jpg`。
 */
object MediaStoreWriter {

    sealed interface Result {
        data class Ok(val uri: Uri, val displayPath: String, val bytes: Long) : Result
        data class Failed(val reason: String) : Result
    }

    /** API 26–28 需要存储权限才能写公共目录；29+ 不需要。 */
    fun needsLegacyStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * 保存视频（mp4）到 `Movies/小黄书/`。
     *
     * @param displayName 不含目录的文件名（调用方用 [DownloadNames.uniqueName] 处理重名）
     */
    fun saveVideo(context: Context, source: File, displayName: String): Result =
        save(
            context = context,
            source = source,
            collection = videoCollection(),
            mimeType = "video/mp4",
            relativeDir = DownloadNames.DIR,
            publicDir = Environment.DIRECTORY_MOVIES,
            displayName = displayName
        )

    /**
     * 保存图片到 `Pictures/小黄书/<标题>_<noteId>/`。
     *
     * @param relativeDir 作品子目录名（[DownloadNames.imageRelativeDir] 的结果，形如 `小黄书/标题_1`）
     */
    fun saveImage(
        context: Context,
        bytes: ByteArray,
        relativeDir: String,
        displayName: String,
        mimeType: String = "image/jpeg"
    ): Result {
        val temp = File(context.cacheDir, "pending-image-" + displayName.hashCode())
        return try {
            temp.writeBytes(bytes)
            save(
                context = context,
                source = temp,
                collection = imageCollection(),
                mimeType = mimeType,
                relativeDir = relativeDir,
                publicDir = Environment.DIRECTORY_PICTURES,
                displayName = displayName
            )
        } finally {
            temp.delete()
        }
    }

    private fun save(
        context: Context,
        source: File,
        collection: Uri,
        mimeType: String,
        relativeDir: String,
        publicDir: String,
        displayName: String
    ): Result {
        if (!source.exists()) return Result.Failed("文件已不存在，请重新下载")
        val targetDir = "$publicDir/$relativeDir"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, targetDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(publicDir),
                    relativeDir
                )
                if (!dir.exists() && !dir.mkdirs()) return Result.Failed("无法创建保存目录，请检查存储空间")
                put(MediaStore.MediaColumns.DATA, File(dir, displayName).absolutePath)
            }
        }

        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
            ?: return Result.Failed("系统拒绝写入，请检查存储空间后重试")

        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                source.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: return Result.Failed("无法写入文件，请检查存储空间")
            source.length()
        }.getOrElse { e ->
            runCatching { resolver.delete(uri, null, null) }
            return Result.Failed("写入文件失败，请检查存储空间后重试")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            runCatching { resolver.update(uri, done, null, null) }
        }
        return Result.Ok(uri, "$targetDir/$displayName", written)
    }

    private fun videoCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun imageCollection(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
}
