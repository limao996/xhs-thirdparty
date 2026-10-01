package com.thirdparty.xhs.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One image of a photo post. [ratio] is width/height taken from the backend's
 * `image_size` field ("1080*720"), so the viewer can render it at its true
 * proportions instead of stretching or cropping.
 */
data class NoteImage(
    val url: String,
    val ratio: Float = DEFAULT_RATIO
) {
    companion object {
        const val DEFAULT_RATIO = 3f / 4f
    }
}

/**
 * Denormalised local snapshot of a note. Enough to render in lists and, via
 * [rawJson], to re-open the full detail (images / video / text) offline.
 */
data class NoteItem(
    val noteId: Long,
    val userId: Int = 0,
    val title: String,
    val userName: String,
    val cover: String,
    val thumbnail: String,
    val noteType: Int,
    val likeCount: Int,
    val collectCount: Int,
    val commentCount: Int,
    val noteCin: Int = 0,
    val content: String = "",
    val mediaUrl: String = "",
    val images: List<NoteImage> = emptyList(),
    val rawJson: String = ""
) {
    /** Whether this work is paid (has a coin price). */
    val isPaid: Boolean get() = noteCin > 0

    /** Rehydrate a NoteItem straight from a detail-level JSON object. */
    constructor(o: JSONObject) : this(
        noteId = o.optLong("note_id"),
        userId = o.optInt("user_id"),
        title = o.optString("note_title"),
        userName = o.optString("user_name"),
        cover = o.optString("note_cover"),
        thumbnail = o.optString("note_thumbnail"),
        noteType = o.optInt("note_type"),
        likeCount = o.optInt("like_count"),
        collectCount = o.optInt("collect_count"),
        commentCount = o.optInt("comment_count"),
        noteCin = o.optInt("note_cin"),
        content = o.optString("note_content"),
        mediaUrl = o.optString("note_media_url"),
        images = NoteItem.parseImages(o),
        rawJson = o.toString()
    )

    val isVideo: Boolean
        get() = mediaUrl.isNotEmpty()

    companion object {
        private fun parseImages(o: JSONObject): List<NoteImage> {
            val list = o.optJSONArray("note_image_list") ?: JSONArray()
            val out = mutableListOf<NoteImage>()
            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                val url = obj.optString("image_url")
                if (url.isEmpty()) continue
                out.add(NoteImage(url, ratioOf(obj.optString("image_size"))))
            }
            return out
        }

        /** "1080*720" -> 1.5 ; falls back to the portrait default. */
        private fun ratioOf(size: String): Float {
            val parts = size.split('*')
            if (parts.size != 2) return NoteImage.DEFAULT_RATIO
            val w = parts[0].trim().toFloatOrNull() ?: return NoteImage.DEFAULT_RATIO
            val h = parts[1].trim().toFloatOrNull() ?: return NoteImage.DEFAULT_RATIO
            if (w <= 0f || h <= 0f) return NoteImage.DEFAULT_RATIO
            return w / h
        }
    }
}