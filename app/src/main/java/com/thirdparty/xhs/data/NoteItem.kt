package com.thirdparty.xhs.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parse a "1080*720"-style size string into a width/height ratio.
 * Returns [fallback] when the field is missing or malformed.
 */
internal fun parseRatio(size: String?, fallback: Float): Float {
    if (size.isNullOrBlank()) return fallback
    val parts = size.split('*')
    if (parts.size != 2) return fallback
    val w = parts[0].trim().toFloatOrNull() ?: return fallback
    val h = parts[1].trim().toFloatOrNull() ?: return fallback
    if (w <= 0f || h <= 0f) return fallback
    return w / h
}

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
    /** canonical web link for this note (`share_url`), used by the share action */
    val shareUrl: String = "",
    /**
     * Cover width/height, from `note_cover_size` (e.g. "375*489"). The masonry
     * grid sizes each cell with this so proportions match the real content
     * instead of a synthetic height. Falls back to portrait when absent.
     */
    val coverRatio: Float = DEFAULT_COVER_RATIO,
    /**
     * Fan-group id. 0 = ordinary work; > 0 = published inside the author's
     * fan group, i.e. fans-only content.
     *
     * Verified against the live backend: `group_id` is 1 on exactly the notes
     * the original app labels 粉絲團專享 (13/13 of them carried note_cin=18,
     * while group_id=0 covered cin 0/2/8/10/18), and it is present in
     * `member/note-list` and `note/view` alike. The general discover feed always
     * reports 0.
     */
    val groupId: Int = 0,
    val rawJson: String = ""
) {

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
        shareUrl = o.optString("share_url"),
        coverRatio = parseRatio(o.optString("note_cover_size"), DEFAULT_COVER_RATIO),
        groupId = o.optInt("group_id"),
        rawJson = o.toString()
    )

    val isVideo: Boolean
        get() = mediaUrl.isNotEmpty()

    companion object {
        /** portrait-ish default used when the backend omits `note_cover_size` */
        const val DEFAULT_COVER_RATIO = 3f / 4f

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
        private fun ratioOf(size: String): Float = parseRatio(size, NoteImage.DEFAULT_RATIO)
    }
}

/**
 * Append [more] to this list, dropping any item whose [NoteItem.noteId] is
 * already present.
 *
 * The waterfall grids key their items by noteId, and Lazy layouts throw on
 * duplicate keys. The backend's page boundaries are not stable — a live check
 * showed page 2 of `discover-note` re-serving one item from page 1 — so
 * de-duplicating on append is required, not just tidy.
 */
fun List<NoteItem>.appendUnique(more: List<NoteItem>): List<NoteItem> {
    if (more.isEmpty()) return this
    if (isEmpty()) return more.distinctBy { it.noteId }
    val seen = HashSet<Long>(size + more.size)
    forEach { seen.add(it.noteId) }
    return this + more.filter { seen.add(it.noteId) }
}