package com.thirdparty.xhs.data

import com.thirdparty.xhs.DeepLink

/**
 * The message produced by the share action.
 *
 * The point of the message is the **mechanism**. A shared note is not a link:
 * `xhstp://note/…` does nothing if the recipient taps it. It only works once it
 * is on the clipboard and the app is brought to the foreground, and nothing about
 * a bare custom-scheme string communicates that — without an instruction the
 * share just looks like a broken link. So the message states the two steps
 * plainly, and gives the token on its own line so "复制下面这行" has an obvious
 * referent.
 *
 * Lines above it answer the other questions a share has to answer:
 *   1. **what is it** — 视频 / 图文, plus the title (the hook)
 *   2. **is it any good** — the author and the like/collect counts
 *
 * Deliberately plain: no invented hype. A share that claims the work is "顶" or
 * "值得一看" says nothing about the work, reads as advertising, and is the same
 * sentence on every share.
 *
 * The token travels as **text**, never as a clickable link, and is found by regex
 * rather than an exact match (see [DeepLink.parseNoteId]), so the surrounding
 * lines cannot break the return-to-app flow.
 */
object ShareText {

    fun of(item: NoteItem): String = buildString {
        append("【").append(kindLabel(item)).append("】").append(item.title.trim())

        val meta = metaLine(item)
        if (meta.isNotEmpty()) {
            append('\n').append(meta)
        }

        append("\n\n① 复制本条消息\n")
        append(DeepLink.noteUrl(item.noteId))
        append("\n② 打开（或切回）「").append(APP_NAME).append("」App → 会自动弹出打开提示")
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
