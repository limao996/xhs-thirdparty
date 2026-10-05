package com.thirdparty.xhs.ui.components

import androidx.media3.exoplayer.ExoPlayer
import java.util.Collections
import java.util.WeakHashMap

/**
 * Carries playback across a navigation.
 *
 * Two things travel, and they are not the same thing:
 *
 *  - the **position** ([stash]/[take]), for when the next screen has to build its own
 *    player. It doubles as the fallback for the case below: if the player could not be
 *    handed over, the detail page still opens where the feed was.
 *  - the **player itself** ([givePlayer]/[takeForDetail]), which is what actually avoids
 *    the restart. A second ExoPlayer on the same stream means a fresh prepare, a fresh
 *    playlist fetch and a fresh buffer, so the video visibly restarted and re-buffered
 *    even though the position had been carried over.
 *
 * ## Ownership
 *
 * One player, two screens that can render it, and one screen (the feed) whose disposal
 * happens as part of the very navigation that hands the player over. The state is
 * therefore explicit rather than implied, because getting it wrong has two very
 * different failure modes:
 *
 *  - releasing too early — the feed's disposal tears down the player the detail page is
 *    about to render, and the video plays with a dead surface (a black screen with the
 *    progress bar still moving; that is not hypothetical, it is how the fullscreen bug
 *    of the previous round presented).
 *  - releasing too late — a player marked as handed over that nobody adopts is never
 *    released at all, and its codecs and buffers leak. That is why the hand-over is
 *    bounded to ONE player in flight, replaced or released on the next hand-over.
 *
 * So: absent from [owned] means "the feed's own" (release as usual). Present means a
 * hand-over is in progress and the feed's disposal must keep its hands off.
 *
 * The position store is keyed by note id and **consumed once** ([take] clears it): it
 * must apply only to the transition that stashed it, not to every later open of the
 * same note (e.g. coming back from a deep link or the saved list should start at the
 * beginning, not resume a stale position).
 */
object PlaybackHandoff {

    data class Pending(val positionMs: Long, val playIntent: Boolean)

    /**
     * A handed-over player, and the play/pause INTENT it was handed over with.
     *
     * The intent is `playWhenReady`, not `isPlaying`: `isPlaying` is false whenever the
     * player is momentarily buffering, so sampling it turned an ordinary mid-buffer tap
     * into "handed over paused" — and the detail page then dutifully paused a video the
     * user was watching. `playWhenReady` is the user's own play/pause choice and does not
     * flicker with the network.
     */
    class Held(val player: ExoPlayer, val playIntent: Boolean)

    private var noteId: Long = -1L
    private var pending: Pending? = null
    private var pendingAtMs: Long = 0L

    /**
     * 单槽通道的保鲜期。
     *
     * 这个槽是"一次性事务"：信息流点进详情页时写入，详情页 compose 时消费。但**关掉小窗**那一路上
     * 写入的进度（`PipController.closeAndRelease`）可能根本没有消费者 —— 用户关掉小窗后直接退出应用，
     * 下次从收藏/深链打开同一个作品就会"莫名其妙从中间开始并自动播放"（审计 F7）。
     * 加 TTL + `clearPending()`：过期的槽按不存在处理，且在 Activity 销毁时主动清掉。
     */
    private const val PENDING_TTL_MS = 60_000L

    private var heldNoteId: Long = -1L
    private var held: Held? = null

    /**
     * Players that are in another screen's hands.
     *
     * Weak keys on purpose: once no screen references a player any more the entry
     * disappears by itself, so a long session cannot accumulate them and this cannot
     * keep a released player's buffers alive.
     */
    private val owned = Collections.synchronizedMap(WeakHashMap<ExoPlayer, Boolean>())

    /** Called by the feed right before navigating to the detail page. */
    @Synchronized
    fun stash(noteId: Long, positionMs: Long, playIntent: Boolean) {
        this.noteId = noteId
        this.pending = Pending(positionMs.coerceAtLeast(0L), playIntent)
        this.pendingAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /** Called by the detail page once its own player exists. Clears the store. */
    @Synchronized
    fun take(noteId: Long): Pending? {
        val p = pending
        val fresh = p != null &&
            android.os.SystemClock.elapsedRealtime() - pendingAtMs <= PENDING_TTL_MS
        if (this.noteId != noteId || !fresh) {
            // 过期/不匹配的槽不再留着：它是"一次性事务"，留着只会在很久以后被别的打开动作误消费
            if (p != null && !fresh) clearPendingLocked()
            return null
        }
        clearPendingLocked()
        return p
    }

    /** 主动丢弃还没被消费的进度（Activity 销毁时调，见审计 F7）。 */
    @Synchronized
    fun clearPending() {
        clearPendingLocked()
    }

    private fun clearPendingLocked() {
        pending = null
        this.noteId = -1L
        pendingAtMs = 0L
    }

    // ---- the player itself --------------------------------------------------

    /**
     * The feed hands its current player over to whoever comes next.
     *
     * Called on the TAP, i.e. before the navigation starts: the feed's composition is
     * disposed as part of navigating, and by then the player must already be marked,
     * or that disposal releases it.
     */
    @Synchronized
    fun givePlayer(noteId: Long, player: ExoPlayer) {
        // At most one in flight. A second hand-over means whoever was going to adopt the
        // previous one never will (its screen is gone), so that one is released here
        // rather than waiting for a give-back that cannot come.
        held?.player?.takeIf { it !== player }?.let { stale ->
            if (owned.remove(stale) != null) {
                runCatching {
                    stale.stop()
                    stale.clearMediaItems()
                    stale.release()
                }
            }
        }
        owned[player] = true
        heldNoteId = noteId
        held = Held(player, player.playWhenReady)
    }

    /**
     * The detail page takes the handed-over player for [noteId], or null when there is
     * none waiting (opened from 搜索/收藏/作者页, a deep link, …).
     *
     * The caller owns the player from here — it renders it and it releases it — and it
     * has to RE-CONFIGURE it for its own screen first: a feed player loops, and the
     * detail page must stop at the end. See `applyLongFormPlayerSettings`.
     *
     * The mark deliberately STAYS set: the feed's own disposal may run after this, and
     * that disposal must keep its hands off a player another screen is rendering.
     *
     * There is no way back for the player, by the way — on the way out of the detail
     * page the feed has already been recomposed (probed: its pages compose before the
     * popped detail's effects are disposed), so nothing is in a position to take the
     * player back, and leaving it "in flight" for an adopter that never comes is how a
     * player leaks. The feed therefore rebuilds its own on return, exactly as it did
     * before this hand-over existed, and resumes from the saved position.
     */
    @Synchronized
    fun takeForDetail(noteId: Long): Held? {
        if (heldNoteId != noteId) return null
        val h = held ?: return null
        held = null
        heldNoteId = -1L
        return h
    }

    /**
     * True while this player has been handed over, so it is not the feed's to release.
     *
     * The feed's page disposal asks this: that disposal runs as part of the navigation
     * that transferred the player, so releasing there would kill the player the detail
     * page is about to render.
     */
    @Synchronized
    fun isHandedOver(player: ExoPlayer): Boolean = owned.containsKey(player)

    /**
     * 这台播放器是**刚刚由小窗交回详情页**的那一台（`givePlayer` 之后、详情页认领之前）。
     *
     * 与 [isHandedOver] 的区别：那个是"曾经交给过别的屏幕"（信息流 → 详情页，标记会一直留着，
     * 用来防误 release）；这个只覆盖"展开小窗"那一瞬间，用来防 `ON_STOP` 误暂停 ——
     * 详情页切后台/锁屏该暂停时它必须是 false。
     */
    @Synchronized
    fun isHeldForHandBack(player: ExoPlayer): Boolean = held?.player === player
}
