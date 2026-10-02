package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size

/**
 * Detail page player: media3 surface + custom controller.
 * - controller auto-hides after 3s, tap to show/hide ([controlsHiddenInitially]
 *   starts it hidden, which is what the windowed player wants)
 * - full controls: play/pause, seek, time, replay, fullscreen (横/竖 皆可)
 * - releases on dispose so no residual frame on exit — **unless** the player was
 *   passed in via [externalPlayer], in which case the caller owns its lifetime
 *
 * [externalPlayer] exists so the windowed and fullscreen layouts can render the
 * SAME ExoPlayer. They are separate composables in separate branches, so without
 * this each branch built and released its own player and entering/leaving
 * fullscreen restarted the video from zero.
 */
@Composable
fun MediaPlayer(
    url: String,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    /** Reports the video's real width/height ratio once known. */
    onAspect: ((Float) -> Unit)? = null,
    /** an externally owned player to render instead of building a new one */
    externalPlayer: ExoPlayer? = null,
    /** start with the control bar hidden; a tap reveals it */
    controlsHiddenInitially: Boolean = false,
    /** shown in the fullscreen top bar */
    title: String = ""
) {
    val context = LocalContext.current.applicationContext
    val ownsPlayer = externalPlayer == null
    // Rotating the device recreates the Activity (verified: WindowManager logs a
    // "relaunch"), which rebuilds this composition and therefore the player. The
    // playback position must survive that or the video jumps back to the start.
    // rememberSaveable is what carries it across the configuration change.
    var resumeMs by rememberSaveable(url) { androidx.compose.runtime.mutableLongStateOf(0L) }
    val player = externalPlayer ?: remember(url) { buildVideoPlayer(context, url) }

    // pick up where the previous instance left off (a no-op on first entry).
    // Skipped for a shared player: the owner keeps the position itself, so
    // seeking here would fight it.
    LaunchedEffect(player) {
        if (ownsPlayer && resumeMs > 0L) player.seekTo(resumeMs)
    }
    // keep the saved position fresh without touching composition state: the read
    // and write both happen in a coroutine, so nothing recomposes every tick
    LaunchedEffect(player) {
        if (!ownsPlayer) return@LaunchedEffect
        while (true) {
            if (player.isPlaying) resumeMs = player.currentPosition
            delay(500)
        }
    }
    // stop playback/audio when the app leaves the foreground. Only pause on
    // dispose when we own the player — a shared one is paused by its owner, and
    // pausing here would stop the video every time the layout switches.
    PauseWhenNotStarted(player, pauseOnDispose = ownsPlayer)
    // report the natural aspect ratio so callers can size the container
    DisposableEffect(player, onAspect) {
        val listener = if (onAspect == null) null else object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onAspect(videoSize.width.toFloat() / videoSize.height.toFloat())
                }
            }
        }
        listener?.let { player.addListener(it) }
        onDispose { listener?.let { player.removeListener(it) } }
    }
    DisposableEffect(player) {
        onDispose {
            if (!ownsPlayer) return@onDispose
            player.stop()
            player.clearMediaItems()
            player.release()
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        PlayerView(
            player = player,
            useController = false,
            // never stretch: FIT letterboxes; the caller sizes the container to
            // the video's own ratio so no bars appear in windowed mode
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
            modifier = Modifier.fillMaxSize()
        )
        AutoHideController(player, fullscreen, onToggleFullscreen, controlsHiddenInitially, title)
        // buffering feedback
        BufferingIndicator(player, modifier = Modifier.fillMaxSize())
        // a dead stream must not fail silently
        PlaybackErrorOverlay(
            error = rememberPlaybackError(player),
            onRetry = { retryPlayback(player) },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun AutoHideController(
    player: Player,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    startHidden: Boolean = false,
    title: String = ""
) {
    val scope = rememberCoroutineScope()
    var visible by remember(player) { mutableStateOf(!startHidden) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var duration by remember(player) { mutableFloatStateOf(0f) }
    var position by remember(player) { mutableFloatStateOf(0f) }
    var ended by remember(player) { mutableStateOf(false) }
    // While the user drags the slider we show a local value and only seek on
    // release. Otherwise the 250ms position poll fights the drag, and every
    // pixel of movement would issue a seek — expensive on an HLS stream.
    var dragging by remember(player) { mutableStateOf(false) }
    var dragFraction by remember(player) { mutableFloatStateOf(0f) }
    // Bumped on every user interaction so the auto-hide timer restarts — without
    // this the controls vanish immediately after a seek and the user never sees
    // where the video landed.
    var interaction by remember(player) { androidx.compose.runtime.mutableIntStateOf(0) }
    // touch lock: while on, every gesture except the unlock button is ignored, so
    // a stray palm cannot seek or pause the video
    var locked by remember(player) { mutableStateOf(false) }
    // playback speed, cycled through SPEEDS by the speed button
    var speedIdx by remember(player) { androidx.compose.runtime.mutableIntStateOf(DEFAULT_SPEED_IDX) }
    // fine-seek step: ±5s by default, toggled to ±1s for frame-ish nudging
    var fineStep by remember(player) { mutableStateOf(false) }
    // 更多菜单：微调步长、倍速、锁定都收在这里
    var menuOpen by remember(player) { mutableStateOf(false) }

    // show everything the moment the user unlocks, and keep the speed applied
    LaunchedEffect(speedIdx) { player.setPlaybackSpeed(SPEEDS[speedIdx]) }
    LaunchedEffect(locked) { if (locked) visible = true }

    fun seekBy(deltaMs: Long) {
        val d = player.duration
        if (d <= 0) return
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, d))
        position = player.currentPosition.toFloat()
        interaction++
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { playing = p }
            override fun onPlaybackStateChanged(state: Int) {
                playing = player.isPlaying
                ended = state == Player.STATE_ENDED
                duration = if (player.duration > 0) player.duration.toFloat() else 0f
                position = player.currentPosition.toFloat()
            }
            override fun onPositionDiscontinuity(a: Player.PositionInfo, b: Player.PositionInfo, reason: Int) {
                position = player.currentPosition.toFloat()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Auto-hide after 3s of no interaction — but NEVER while the user's finger
    // is on the slider (the controls would vanish mid-drag and cancel the
    // gesture) and NEVER while the overflow menu is open: hiding `visible`
    // removes the whole control block, DropdownMenu included, so the menu would
    // close itself out from under the user mid-choice.
    LaunchedEffect(visible, playing, interaction, dragging, menuOpen, locked) {
        if (visible && playing && !dragging && !menuOpen && !locked) {
            delay(3000)
            visible = false
        }
    }

    // Keep position/duration fresh while the controls are visible and playing.
    // Relying on Player.Listener alone leaves the slider and the time label
    // frozen during normal playback: those callbacks only fire on state changes,
    // seeks and media transitions — never per frame.
    LaunchedEffect(player, visible, playing, dragging) {
        if (!visible || !playing || dragging) return@LaunchedEffect
        while (true) {
            duration = if (player.duration > 0) player.duration.toFloat() else 0f
            position = player.currentPosition.toFloat()
            delay(PROGRESS_POLL_MS)
        }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(locked) {
            // pointerInput (not clickable) on purpose: no ripple, and it gives us
            // a double-tap for free. Ripples over video look like artifacts.
            detectTapGestures(
                onTap = {
                    if (locked) return@detectTapGestures
                    visible = !visible
                    if (visible) { scope.launch { delay(3000); visible = false } }
                },
                onDoubleTap = {
                    if (locked) return@detectTapGestures
                    if (player.isPlaying) player.pause() else player.play()
                    visible = true
                    interaction++
                }
            )
        }
    ) {
        // replay button when ended
        if (ended && !locked) {
            IconButton(onClick = { player.seekTo(0); player.play() },
                modifier = Modifier.align(Alignment.Center)) {
                Icon(Icons.Filled.Replay, "重播", tint = Scrim.onMedia)
            }
        }
        // The lock button stays reachable whenever the lock is on — it is the only
        // way out, so it must never auto-hide.
        if (locked) {
            Surface(
                shape = CircleShape,
                color = Scrim.strong,
                modifier = Modifier.align(Alignment.CenterEnd).padding(Spacing.s)
            ) {
                IconButton(onClick = { locked = false; visible = true; interaction++ }) {
                    Icon(Icons.Filled.LockOpen, "解除锁定", tint = Scrim.onMedia)
                }
            }
        }
        if (visible && !locked) {
            // ── 顶栏: 退出全屏 + 标题 + 更多菜单 ──────────────────────────
            // Everything that is not an everyday action lives in the menu, so the
            // bars stay uncrowded instead of stacking eight controls in one row.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Scrim.strong)
                    .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // the page's own app bar is hidden in fullscreen, so offer a way back
                if (fullscreen) {
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "退出全屏", tint = Scrim.onMedia
                        )
                    }
                }
                Text(
                    title.ifBlank { "播放中" },
                    color = Scrim.onMedia,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Spacing.xs)
                )
                Box {
                    IconButton(onClick = { menuOpen = true; interaction++ }) {
                        Icon(Icons.Filled.MoreVert, "更多", tint = Scrim.onMedia)
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (fineStep) "微调：±1 秒" else "微调：±5 秒") },
                            onClick = { fineStep = !fineStep; menuOpen = false; interaction++ }
                        )
                        HorizontalDivider()
                        SPEEDS.forEachIndexed { i, s ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (i == speedIdx) "$s x  ✓" else "$s x",
                                        color = if (i == speedIdx) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = { speedIdx = i; menuOpen = false; interaction++ }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("锁定屏幕") },
                            onClick = { menuOpen = false; locked = true }
                        )
                    }
                }
            }

            // ── 侧边按钮: 锁定（左缘中部，拇指够得到）────────────────────
            Surface(
                shape = CircleShape,
                color = Scrim.strong,
                modifier = Modifier.align(Alignment.CenterStart).padding(Spacing.xs)
            ) {
                IconButton(onClick = { locked = true; interaction++ }) {
                    Icon(Icons.Filled.Lock, "锁定", tint = Scrim.onMedia)
                }
            }

            // ── 底栏: 进度条 + 播放控制 ─────────────────────────────────
            //
            // Density depends on the container. Fullscreen has room to spare, but an
            // inline player does not: default 48dp buttons stacked under a
            // full-height slider made this bar ~110dp tall, which ate a large slice
            // of a landscape video and put a large dead zone right where the user
            // taps to reveal the controls. Inline size is reduced here; targets stay
            // comfortably tappable (40dp buttons, 28dp slider).
            val dense = !fullscreen
            val btnSize = if (dense) 40.dp else 48.dp
            val iconSize = if (dense) 21.dp else 24.dp
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Scrim.strong).padding(vertical = if (dense) 1.dp else Spacing.xs)
            ) {
                val fraction = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
                Slider(
                    value = if (dragging) dragFraction else fraction,
                    onValueChange = { f ->
                        dragging = true
                        dragFraction = f
                        interaction++
                    },
                    onValueChangeFinished = {
                        val d = player.duration
                        if (d > 0) player.seekTo((dragFraction * d).toLong())
                        dragging = false
                        interaction++
                    },
                    modifier = Modifier.height(if (dense) 28.dp else 44.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Scrim.onMedia,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Scrim.onMediaVariant
                    )
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = if (dense) 2.dp else Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val step = if (fineStep) 1000L else 5000L
                    IconButton(onClick = { seekBy(-step) }, modifier = Modifier.size(btnSize)) {
                        Icon(Icons.Filled.Replay5, "后退${step / 1000}秒", tint = Scrim.onMedia,
                            modifier = Modifier.size(iconSize))
                    }
                    IconButton(onClick = { if (playing) player.pause() else player.play() },
                        modifier = Modifier.size(btnSize)) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "暂停" else "播放", tint = Scrim.onMedia,
                            modifier = Modifier.size(iconSize))
                    }
                    IconButton(onClick = { seekBy(step) }, modifier = Modifier.size(btnSize)) {
                        Icon(Icons.Filled.Forward5, "前进${step / 1000}秒", tint = Scrim.onMedia,
                            modifier = Modifier.size(iconSize))
                    }
                    Text("${fmt(position.toLong())} / ${fmt(duration.toLong())}",
                        color = Scrim.onMedia, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = Spacing.xs))
                    Spacer(Modifier.weight(1f))
                    // 当前倍速直接显示，否则用户在底栏看不出视频被改过速
                    if (speedIdx != DEFAULT_SPEED_IDX) {
                        Text("${SPEEDS[speedIdx]}x",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = Spacing.xs))
                    }
                    IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(btnSize)) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            "全屏", tint = Scrim.onMedia, modifier = Modifier.size(iconSize))
                    }
                }
            }
        }
    }
}

/** Playback speed steps offered by the speed button. */
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private const val DEFAULT_SPEED_IDX = 2

private fun fmt(ms: Long): String {
    val s = ms / 1000; val m = s / 60
    return "%d:%02d".format(m, s % 60)
}

/** How often the visible controls re-read the playback position. */
private const val PROGRESS_POLL_MS = 250L