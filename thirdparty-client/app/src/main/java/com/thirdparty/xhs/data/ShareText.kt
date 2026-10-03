package com.thirdparty.xhs.data

import com.thirdparty.xhs.DeepLink

/**
 * The message produced by the share action.
 *
 * Previously this was just `title\nlink`, which left the recipient with no way to
 * judge whether the work was worth opening and no idea which app the token
 * belonged to. The shape below answers the three questions a share has to answer,
 * in reading order:
 *
 *   1. **what is it** — 视频 / 图文, plus the title (the hook)
 *   2. **is it any good** — author and the like/collect counts
 *   3. **how do I open it** — a web link for anyone, and the in-app token, which
 *      is what the clipboard 回流 flow in MainActivity looks for
 *
 * Deliberately plain: no invented hype and no filler sentences. A share that
 * claims the work is "顶" or "值得一看" says nothing about the work, reads as
 * advertising, and is the same text every time.
 *
 * The token travels as **text**, never as a clickable link, and is found by
 * regex rather than an exact match (see [DeepLink.parseNoteId]), so the
 * surrounding lines cannot break the return-to-app flow.
 */
object ShareText {

    fun of(item: NoteItem): String = buildString {
        append("【").append(kindLabel(item)).append("】").append(item.title.trim())

        val meta = metaLine(item)
        if (meta.isNotEmpty()) {
            append('\n').append(meta)
        }

        append("\n\n")
        // 口令 first: that is the flow this app actually implements (copy, return,
        // it offers to open). The web link is the fallback for a recipient who does
        // not have the app.
        append("口令 ").append(DeepLink.noteUrl(item.noteId))
        val web = readableShareUrl(item.shareUrl)
        if (web.isNotEmpty()) {
            append('\n').append("网页 ").append(web)
        }

        // tells the recipient which app the 口令 belongs to
        append("\n\n来自「").append(APP_NAME).append("」")
    }

    /**
     * Unwraps the backend's share URL.
     *
     * It arrives as a redirect wrapper whose payload is URL-encoded:
     * ```
     * https://host//v2/shareInvite/redirect?shareTraceId=…&redirectUrl=<encoded>
     * ```
     * That is ~250 characters of query string, which dominates the message and
     * reads like spam. The `redirectUrl` parameter is the link the recipient
     * actually wants, and it still carries the trace id, so unwrapping shortens
     * the message without dropping the referral.
     *
     * Falls back to the raw value when there is nothing to unwrap, so an
     * unfamiliar shape is passed through rather than mangled.
     */
    private fun readableShareUrl(raw: String): String {
        if (raw.isBlank()) return ""
        val marker = "redirectUrl="
        val at = raw.indexOf(marker)
        val candidate = if (at >= 0) {
            val encoded = raw.substring(at + marker.length).substringBefore('&')
            runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
        } else {
            raw
        }
        // the backend emits a doubled slash after the host
        return (candidate ?: raw).trim().replace("//note", "/note")
    }

    /** `图文` / `视频`, from the same `note_type` mapping the badges use. */
    private fun kindLabel(item: NoteItem): String =
        if (item.noteType == 1) "图文" else "视频"

    /**
     * Author and heat, joined with `·`. A count of zero is left out rather than
     * printed as "0", which would read as "nobody liked this".
     */
    private fun metaLine(item: NoteItem): String {
        val parts = mutableListOf<String>()
        if (item.userName.isNotBlank()) parts += item.userName.trim()
        val heat = buildList {
            if (item.likeCount > 0) add("❤️${item.likeCount}")
            if (item.collectCount > 0) add("⭐${item.collectCount}")
        }
        if (heat.isNotEmpty()) parts += heat.joinToString(" ")
        return parts.joinToString(" · ")
    }

    private const val APP_NAME = "小黄书"
}
