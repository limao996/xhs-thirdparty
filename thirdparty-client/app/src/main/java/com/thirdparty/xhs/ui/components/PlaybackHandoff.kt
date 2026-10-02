package com.thirdparty.xhs.ui.components

/**
 * Hands the feed's playback position over to the detail page.
 *
 * Tapping a short video in 推荐 opens the detail page, which builds its own
 * ExoPlayer. Without this the new player started from zero, so the video visibly
 * restarted even though the user had just been watching it.
 *
 * The store is keyed by note id and **consumed once** ([take] clears it): it must
 * apply only to the transition that stashed it, not to every later open of the
 * same note (e.g. coming back from a deep link or the saved list should start at
 * the beginning, not resume a stale position).
 */
object PlaybackHandoff {

    data class Pending(val positionMs: Long, val playing: Boolean)

    private var noteId: Long = -1L
    private var pending: Pending? = null

    /** Called by the feed right before navigating to the detail page. */
    @Synchronized
    fun stash(noteId: Long, positionMs: Long, playing: Boolean) {
        this.noteId = noteId
        this.pending = Pending(positionMs.coerceAtLeast(0L), playing)
    }

    /** Called by the detail page once its own player exists. Clears the store. */
    @Synchronized
    fun take(noteId: Long): Pending? {
        if (this.noteId != noteId) return null
        val p = pending
        pending = null
        this.noteId = -1L
        return p
    }
}
