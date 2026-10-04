package com.thirdparty.xhs

/**
 * Share-link contract.
 *
 * A shared note has to open THIS app on the right note, so the share sheet emits
 * a custom-scheme link rather than the backend's web URL — we cannot register an
 * App Link for a domain we do not own, and `assetlinks.json` is not an option
 * here. A custom scheme needs no verification and launches straight into the app.
 *
 * Format: `xhstp://note/<noteId>`
 */
object DeepLink {
    const val SCHEME = "xhstp"
    const val HOST = "note"

    fun noteUrl(noteId: Long): String = "$SCHEME://$HOST/$noteId"

    /**
     * Pull a note id out of arbitrary clipboard text.
     *
     * Users paste the whole share message ("标题" + newline + link, or the link
     * buried in other text), so this searches rather than matching the string
     * exactly. Returns null when there is no share link in the text.
     */
    private val LINK = Regex("""$SCHEME://$HOST/(\d+)""")

    fun parseNoteId(text: String?): Long? =
        text?.let { LINK.find(it)?.groupValues?.get(1)?.toLongOrNull() }
}
