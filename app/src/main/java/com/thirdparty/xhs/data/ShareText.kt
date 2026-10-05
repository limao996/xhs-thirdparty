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
        append("\n② 打开「").append(APP_NAME).append("」App → 会自动弹出提示")
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

    /**
     * 自己刚分享出去的那条文案。
     *
     * 背景：分享面板里可以「复制」，用户复制完切回应用时，`MainActivity` 的焦点回调会读剪贴板、
     * 认出里面的 `xhstp://note/<id>`，然后弹出"要打开这条笔记吗"——而这个笔记**就是他刚刚分享的**。
     * 所以分享前先把 (noteId, 完整文案) 记在这里，回来看见一模一样的剪贴板内容就当作自己发的，
     * 直接标记成"已看过"，不再提示。
     *
     * 只在内存里存：进程被杀之后这份记录就没了，但那时的剪贴板内容也已经不是"刚刚分享"的语境。
     */
    private val selfShared = java.util.concurrent.atomic.AtomicReference<Pair<Long, String>?>(null)

    fun markSelfShared(noteId: Long, text: String) {
        selfShared.set(noteId to text)
    }

    /** 剪贴板里的这条内容是不是我们自己刚分享出去的 */
    fun isSelfShared(noteId: Long, clipboardText: String): Boolean {
        val cur = selfShared.get() ?: return false
        return cur.first == noteId && cur.second.trim() == clipboardText.trim()
    }

    private const val APP_NAME = "小黄书"
}
