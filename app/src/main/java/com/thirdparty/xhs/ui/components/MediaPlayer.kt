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
import com.thirdparty.xhs.BuildConfig
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    title: String = "",
    /**
     * 「小窗播放」（画中画）入口；为 null 时不显示这一项。
     *
     * 入口放在**播放器自己的菜单**里（菜单本身就是对话框），因为它是"对当前这段视频"的操作，
     * 不属于页面级动作；页面右上角那个「更多」曾经放过它，用户要求挪到这里。
     */
    onEnterPip: (() -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val ownsPlayer = externalPlayer == null
    // Rotating the device recreates the Activity (verified: WindowManager logs a
    // "relaunch"), which rebuilds this composition and therefore the player. The
    // playback position must survive that or the video jumps back to the start.
    // rememberSaveable is what carries it across the configuration change.
    var resumeMs by rememberSaveable(url) { androidx.compose.runtime.mutableLongStateOf(0L) }
    val player = externalPlayer ?: remember(url) { buildVideoPlayer(context, url, longForm = true) }

    // 摄像头挖孔的安全区。这是"避开摄像头"的正牌 API，而且**全屏时依然有效** ——
    // 挖孔不是系统栏，隐藏状态栏不会让它变成 0（statusBars 会变 0，所以不能用它）。
    // 现场直接读即可，不需要记住任何东西。
    val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()

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
    // mirrored locally: the surface needs it to letterbox inside a clamped box.
    //
    // Seeded from the player's CURRENT video size, not from 0. The windowed and
    // fullscreen layouts are different compositions, so entering fullscreen builds
    // a fresh MediaPlayer around the same player: `onVideoSizeChanged` is a
    // *change* notification and does not fire again for a size the player already
    // has, so starting at 0 meant the fullscreen surface never learned the ratio
    // and fell back to fillMaxSize() — the full-screen picture would have been
    // stretched (and, before the surface fix, simply black).
    var videoAspect by remember(player) {
        mutableStateOf(
            player.videoSize.let {
                if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height.toFloat() else 0f
            }
        )
    }
    // report the natural aspect ratio so callers can size the container
    DisposableEffect(player, onAspect) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val a = videoSize.width.toFloat() / videoSize.height.toFloat()
                    videoAspect = a
                    onAspect?.invoke(a)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // and report what is already known to the host, so a layout that never sees a
    // change event still sizes itself correctly
    LaunchedEffect(player) {
        val vs = player.videoSize
        if (vs.width > 0 && vs.height > 0) {
            onAspect?.invoke(vs.width.toFloat() / vs.height.toFloat())
        }
    }
    DisposableEffect(player) {
        onDispose {
            if (!ownsPlayer) return@onDispose
            player.stop()
            player.clearMediaItems()
            player.release()
        }
    }

    // Pinch-zoom, fullscreen only.
    //
    // In windowed mode the player is a small in-page box and zooming it would
    // fight the page's own scrolling, so the gesture is left alone there.
    var scale by remember(player) { mutableFloatStateOf(1f) }
    var offset by remember(player) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var viewSize by remember(player) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // leaving fullscreen or changing video must not carry the zoom over
    LaunchedEffect(fullscreen, url) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }

    Box(
        modifier = modifier.background(Color.Black)
            .then(
                if (!fullscreen) Modifier else Modifier
                    .onSizeChanged { viewSize = it }
                    .pointerInput(url) {
                        // Same shape as the image viewer's handler: take the gesture
                        // only for a pinch, or to pan while already zoomed. Consuming
                        // every drag here would kill the tap/double-tap controls.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pinch = event.calculateZoom()
                                val pan = event.calculatePan()
                                val multiTouch = event.changes.count { it.pressed } > 1
                                if (multiTouch || scale > 1f) {
                                    scale = (scale * pinch).coerceIn(1f, MAX_PLAYER_ZOOM)
                                    offset = clampPlayerPan(offset + pan, scale, viewSize)
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            )
    ) {
        // TextureView-backed, not PlayerView: a SurfaceView would not fade with the
        // screen during a navigation transition and the last frame would stay
        // painted over the screen being returned to. See [VideoSurface].
        VideoSurface(
            player = player,
            videoAspect = videoAspect,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
        )
        AutoHideController(
            player, fullscreen, onToggleFullscreen, controlsHiddenInitially, title,
            topClearance = cutoutTop,
            onEnterPip = onEnterPip
        )
        // Buffering feedback — but never together with the error panel: the
        // player keeps retrying in BUFFERING while the panel is up, so both
        // used to draw on top of each other and neither was readable.
        var decoderStuck by remember(player) { mutableStateOf(false) }
        RecoverStuckPlayback(player) { decoderStuck = true }
        val playbackError =
            rememberPlaybackError(player) ?: if (decoderStuck) STUCK_PLAYBACK_EXCEPTION else null
        if (playbackError == null) {
            BufferingIndicator(player, modifier = Modifier.fillMaxSize())
        }
        // a dead stream must not fail silently
        PlaybackErrorOverlay(
            error = playbackError,
            onRetry = { retryPlayback(player) },
            modifier = Modifier.fillMaxSize()
        )

        // Reset affordance while zoomed, same one the image viewer shows. Only in
        // fullscreen: that is the only mode where zooming is possible.
        if (fullscreen && scale > 1.01f) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = FULLSCREEN_RESET_INSET),
                contentAlignment = Alignment.Center
            ) {
                ResetZoomButton(onClick = {
                    scale = 1f
                    offset = androidx.compose.ui.geometry.Offset.Zero
                })
            }
        }
    }
}

/** Highest magnification the fullscreen player allows. */
private const val MAX_PLAYER_ZOOM = 4f
/** Keeps the reset pill clear of the seek bar. */
private val FULLSCREEN_RESET_INSET = 96.dp

/**
 * Bounds panning to what the zoom reveals, in the same way the image viewer does.
 *
 * Unbounded panning let a zoomed frame be dragged entirely off-screen, leaving
 * only black with no hint of how to get back.
 */
private fun clampPlayerPan(
    offset: androidx.compose.ui.geometry.Offset,
    scale: Float,
    view: androidx.compose.ui.unit.IntSize
): androidx.compose.ui.geometry.Offset {
    if (scale <= 1f || view.width == 0 || view.height == 0) {
        return androidx.compose.ui.geometry.Offset.Zero
    }
    val maxX = view.width * (scale - 1f) / 2f
    val maxY = view.height * (scale - 1f) / 2f
    return androidx.compose.ui.geometry.Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

@Composable
private fun AutoHideController(
    player: Player,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    startHidden: Boolean = false,
    title: String = "",
    /** status-bar height remembered while the bars were visible; see MediaPlayer */
    topClearance: androidx.compose.ui.unit.Dp = 0.dp,
    /** 「小窗播放」入口；为 null 时菜单里没有这一项 */
    onEnterPip: (() -> Unit)? = null
) {
    var visible by remember(player) { mutableStateOf(!startHidden) }
    // 播放器控件也给系统触感反馈：它和页面其他按钮是同一层交互
    val haptics = rememberHaptics()    // Seeded FROM the player, not from zero/false.
    //
    // This controller can be built around a player that is already in the middle of
    // something — the one handed over by the feed, or one whose layout just changed. It
    // only learns about later changes through listener callbacks, and no callback arrives
    // for the state a player already had, so starting from defaults read "0:00 / 0:00"
    // with no play/pause state and, after the video had ENDED, no 重播 affordance at all
    // (which is why "播完之后切全屏" could not even be replayed).
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var duration by remember(player) { mutableFloatStateOf(effectiveDurationMs(player).toFloat()) }
    var position by remember(player) { mutableFloatStateOf(player.currentPosition.toFloat()) }
    // how much of the video the player already holds (0..1). Drives the buffered
    // segment on the seek bar, so "waiting for network" is distinguishable from
    // "not watched yet".
    var bufferedFraction by remember(player) { mutableFloatStateOf(bufferedOf(player)) }
    var ended by remember(player) {
        mutableStateOf(player.playbackState == Player.STATE_ENDED)
    }
    // While the user drags the slider we show a local value and only seek on
    // release. Otherwise the 250ms position poll fights the drag, and every
    // pixel of movement would issue a seek — expensive on an HLS stream.
    var dragging by remember(player) { mutableStateOf(false) }
    var dragFraction by remember(player) { mutableFloatStateOf(0f) }
    // Bumped on every user interaction so the auto-hide timer restarts — without
    // this the controls vanish immediately after a seek and the user never sees
    // where the video landed.
    var interaction by remember(player) { androidx.compose.runtime.mutableIntStateOf(0) }
    // 播放倍速：拖动条给连续值（0.25x ~ 3x，步进 0.25），不再是固定档位列表
    var speed by remember(player) { androidx.compose.runtime.mutableFloatStateOf(DEFAULT_SPEED) }
    // fine-seek step: ±5s by default, toggled to ±1s for frame-ish nudging
    var fineStep by remember(player) { mutableStateOf(false) }
    // 更多菜单：微调步长、倍速都收在这里
    var menuOpen by remember(player) { mutableStateOf(false) }

    // keep the speed applied
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

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
                duration = effectiveDurationMs(player).toFloat()
                position = player.currentPosition.toFloat()
                bufferedFraction = bufferedOf(player)
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "XhsSeek",
                        "state=${stateName(state)} pos=${player.currentPosition} " +
                            "buffered=${player.bufferedPosition} " +
                            "ahead=${player.bufferedPosition - player.currentPosition} " +
                            "loading=${player.isLoading} playing=${player.isPlaying}"
                    )
                }
            }
            override fun onPositionDiscontinuity(a: Player.PositionInfo, b: Player.PositionInfo, reason: Int) {
                position = player.currentPosition.toFloat()
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "XhsSeek",
                        "discontinuity reason=$reason -> pos=${player.currentPosition} " +
                            "buffered=${player.bufferedPosition}"
                    )
                }
            }
            // bufferedPosition moves independently of the playhead
            override fun onIsLoadingChanged(isLoading: Boolean) {
                bufferedFraction = bufferedOf(player)
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        "XhsSeek",
                        "loading=$isLoading pos=${player.currentPosition} " +
                            "buffered=${player.bufferedPosition} state=${stateName(player.playbackState)}"
                    )
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (BuildConfig.DEBUG) {
                    android.util.Log.w("XhsSeek", "error=${error.errorCodeName} ${error.message}")
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // While buffering, log how far ahead the buffer is every 500ms. If the number
    // keeps growing the player IS downloading and simply will not start; if it is
    // stuck, the loader is blocked.
    if (BuildConfig.DEBUG) {
        LaunchedEffect(player) {
            while (true) {
                if (player.playbackState == Player.STATE_BUFFERING) {
                    android.util.Log.i(
                        "XhsSeek",
                        "TICK pwr=${player.playWhenReady} suppress=${player.playbackSuppressionReason} " +
                            "ahead=${player.bufferedPosition - player.currentPosition} " +
                            "buffered=${player.bufferedPosition} loading=${player.isLoading}"
                    )
                }
                delay(500)
            }
        }
    }

    // "the user is waiting on the network, or it has not started yet" — the bar
    // should stay put so the buffering state is readable.
    val waiting = player.playbackState == Player.STATE_BUFFERING ||
        player.playbackState == Player.STATE_IDLE

    // Single auto-hide timer, restarted by every interaction.
    //
    // It used to be worse than this in two ways:
    //  1. the tap handler ALSO started its own `scope.launch { delay(3000) }`.
    //     Only this effect restarts on interaction, so after tapping to reveal the
    //     controls and then adjusting the slider, that second timer still fired
    //     three seconds after the tap and pulled the bar away mid-use.
    //  2. the timeout was 3s, and it ran while paused and while buffering — i.e.
    //     exactly when the user is looking at the controls or waiting on the
    //     network. Reported as "自动隐藏特别反人类".
    //
    // Now: one timer, 5s, and it never runs while paused, buffering, ended, mid
    // drag or with the overflow menu open. Hiding `visible` removes the whole
    // control block including the DropdownMenu, so the menu would otherwise close
    // itself under the user's finger.
    LaunchedEffect(visible, playing, interaction, dragging, menuOpen, waiting) {
        if (visible && playing && !dragging && !menuOpen && !waiting && !ended) {
            delay(AUTO_HIDE_MS)
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
            duration = effectiveDurationMs(player).toFloat()
            position = player.currentPosition.toFloat()
            bufferedFraction = bufferedOf(player)
            delay(PROGRESS_POLL_MS)
        }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // pointerInput (not clickable) on purpose: no ripple, and it gives us
            // a double-tap for free. Ripples over video look like artifacts.
            detectTapGestures(
                onTap = {
                    // No timer here: the LaunchedEffect above owns auto-hide and
                    // restarts with every interaction. A timer started here could
                    // not be reset by later touches.
                    visible = !visible
                    if (visible) interaction++
                },
                onDoubleTap = {
                    if (player.isPlaying) player.pause() else player.play()
                    visible = true
                    interaction++
                }
            )
        }
    ) {
        // Replay button once the work has finished.
        //
        // The detail player does not loop, so this is the only way back to the
        // start — it must not be missable. A finished clip otherwise shows its last
        // frame with no controls and reads as a frozen player.
        LaunchedEffect(ended) { if (ended) visible = true }
        if (ended) {
            IconButton(onClick = {
                haptics.tick()
                player.seekTo(0)
                player.play()
            },
                modifier = Modifier.align(Alignment.Center)) {
                Icon(Icons.Filled.Replay, "重播", tint = Scrim.onMedia)
            }
        }
        // The touch-lock button used to live here (and in the overflow menu). It
        // was removed: it sat in the middle of the left edge where it was easy to
        // hit by accident, and a locked player with no visible way out reads as a
        // frozen app.
        if (visible) {
            // ── 顶栏: 退出全屏 + 标题 + 更多菜单 ──────────────────────────
            // Everything that is not an everyday action lives in the menu, so the
            // bars stay uncrowded instead of stacking eight controls in one row.
            //
            // 竖屏全屏时给顶栏留出上边距：
            //  - 有挖孔 → 让开摄像头（用挖孔安全区的高度）
            //  - 没有挖孔 → 只留一点点呼吸边距
            //
            // 非挖孔屏**不能**按状态栏高度塞：全屏时两条栏本来就隐藏着，非挖孔屏上
            // 更没有任何东西需要避让，按状态栏高度留白只会白占一条 ~48dp 的黑带。
            // 横屏不加（挖孔在侧边，顶上多一条边距只会挤掉画面）。
            val portrait = LocalConfiguration.current.orientation !=
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val topInset = when {
                !fullscreen || !portrait -> 0.dp
                topClearance > 0.dp -> topClearance
                else -> Spacing.s
            }
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Scrim.strong)
                    .padding(
                        start = Spacing.xs, end = Spacing.xs,
                        top = Spacing.xs + topInset, bottom = Spacing.xs
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // the page's own app bar is hidden in fullscreen, so offer a way back
                if (fullscreen) {
                    IconButton(onClick = {
                        haptics.tick()
                        onToggleFullscreen()
                    }) {
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
                    IconButton(onClick = { haptics.tick(); menuOpen = true; interaction++ }) {
                        Icon(Icons.Filled.MoreVert, "更多", tint = Scrim.onMedia)
                    }
                    // 菜单用**对话框**而不是 DropdownMenu：下拉面板没有半透明遮罩、也没有
                    // 入场动画，铺在全屏视频上时还容易被边缘裁掉（用户明确要求）
                    if (menuOpen) {
                        AlertDialog(
                            onDismissRequest = { menuOpen = false },
                            title = {
                                Text(
                                    title.ifBlank { "播放设置" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            text = {
                                // 倍速 + 微调 + 小窗播放一共 8 行，小屏上会顶出对话框：
                                // 列表本身要能滚（用户实测反馈）
                                Column(
                                    Modifier
                                        .heightIn(max = 360.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    PlayerMenuRow(
                                        label = if (fineStep) "微调：±1 秒 ✓" else "微调：±1 秒"
                                    ) {
                                        fineStep = !fineStep
                                        menuOpen = false
                                        interaction++
                                    }
                                    HorizontalDivider()
                                    // 倍速：拖动条（0.25x ~ 3x）。原来是 6 个固定档位的列表，
                                    // 用户要求改成可拖的连续值
                                    PlayerMenuRow(
                                        label = "倍速 ${formatSpeed(speed)}x",
                                        highlighted = speed != DEFAULT_SPEED
                                    ) {
                                        speed = DEFAULT_SPEED
                                        interaction++
                                    }
                                    Slider(
                                        value = speed,
                                        onValueChange = {
                                            speed = (Math.round(it * 20f) / 20f)
                                                .coerceIn(MIN_SPEED, MAX_SPEED)
                                            interaction++
                                        },
                                        valueRange = MIN_SPEED..MAX_SPEED,
                                        steps = 10,
                                        modifier = Modifier.padding(horizontal = Spacing.xs)
                                    )
                                    if (onEnterPip != null) {
                                        HorizontalDivider()
                                        PlayerMenuRow(label = "小窗播放") {
                                            menuOpen = false
                                            interaction++
                                            onEnterPip()
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { menuOpen = false }) { Text("关闭") }
                            }
                        )
                    }
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
            val iconSize = if (dense) 20.dp else 24.dp
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Scrim.strong).padding(vertical = if (dense) 1.dp else Spacing.xs)
            ) {
                val fraction = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
                // BufferedSlider, not Slider: it draws the already-buffered span as
                // a third segment, so a stalled stream (buffer ahead of the
                // playhead) is visibly different from an unwatched one. The
                // component is the official M3 Expressive Slider — the 16dp track
                // and 4×44dp bar handle come from its own tokens.
                BufferedSlider(
                    value = if (dragging) dragFraction else fraction,
                    buffered = bufferedFraction,
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
                    playedColor = MaterialTheme.colorScheme.primary,
                    // The official Slider insets its own track by only a few dp, which
                    // at full screen left the progress bar looking pinned to the screen
                    // edges — reported as "进度条缺少水平边距". Give it the app's page
                    // margin; the bar's ends then line up with the transport buttons
                    // below it, whose icons already sit ~16dp in.
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = if (dense) 2.dp else Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val step = if (fineStep) 1000L else 5000L
                    IconButton(onClick = {
                        haptics.tick()
                        seekBy(-step)
                    }, modifier = Modifier.size(btnSize)) {
                        Icon(Icons.Filled.Replay5, "后退${step / 1000}秒", tint = Scrim.onMedia,
                            modifier = Modifier.size(iconSize))
                    }
                    IconButton(onClick = {
                        haptics.tick()
                        if (playing) player.pause() else player.play()
                    },
                        modifier = Modifier.size(btnSize)) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "暂停" else "播放", tint = Scrim.onMedia,
                            modifier = Modifier.size(iconSize))
                    }
                    IconButton(onClick = {
                        haptics.tick()
                        seekBy(step)
                    }, modifier = Modifier.size(btnSize)) {
                        Icon(Icons.Filled.Forward5, "前进${step / 1000}秒", tint = Scrim.onMedia,
                            modifier = Modifier.size(iconSize))
                    }
                    Text("${fmt(position.toLong())} / ${fmt(duration.toLong())}",
                        color = Scrim.onMedia, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = Spacing.xs))
                    Spacer(Modifier.weight(1f))
                    // 当前倍速直接显示，否则用户在底栏看不出视频被改过速
                    if (speed != DEFAULT_SPEED) {
                        Text("${formatSpeed(speed)}x",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = Spacing.xs))
                    }
                    IconButton(onClick = {
                        haptics.tick()
                        onToggleFullscreen()
                    }, modifier = Modifier.size(btnSize)) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            "全屏", tint = Scrim.onMedia, modifier = Modifier.size(iconSize))
                    }
                }
            }
        }
    }
}

/** 播放器菜单对话框里的一行（样式与作品长按对话框保持一致）。 */
@Composable
private fun PlayerMenuRow(label: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 倍速范围与默认值（拖动条用；步进 0.25 由 `steps = 10` 表达）。 */
private const val MIN_SPEED = 0.25f
private const val MAX_SPEED = 3f
private const val DEFAULT_SPEED = 1f

/** 倍速显示：1.0 显示成「1」，1.25 显示成「1.25」，不留多余的零。 */
private fun formatSpeed(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else ((v * 100).toInt() / 100f).toString()

private fun fmt(ms: Long): String {
    // Round to the nearest second rather than truncating: truncation made a clip
    // whose real length is 10.9s read as "0:10", and the clock is what the user
    // compares the bar against.
    val s = (ms + 500) / 1000
    val m = s / 60
    return "%d:%02d".format(m, s % 60)
}

/**
 * Duration to lay the seek bar out against.
 *
 * The container can under-report it — seen on 粉丝团 clips, where the real media is
 * longer than the duration the extractor reports. The bar then reached 100% while
 * the video kept playing, sat pinned there for the remaining seconds, and only
 * reset when the item looped; reported as "播放完毕后进度会卡回5秒左右".
 *
 * Once the playhead is past the reported duration, that report is the thing that
 * is wrong, so the observed position becomes the reference and the bar keeps
 * tracking instead of pinning. `bufferedFraction` uses the same figure so both
 * agree.
 */
private fun effectiveDurationMs(player: Player): Long {
    val reported = player.duration
    val position = player.currentPosition
    return when {
        reported <= 0L -> 0L
        position > reported -> position
        else -> reported
    }
}

/** How often the visible controls re-read the playback position. */
private const val PROGRESS_POLL_MS = 250L

/**
 * How long the controls stay up after the last interaction.
 *
 * 5s, up from 3s: at 3s the user had to lunge for the slider before the bar
 * disappeared, which is what made the player feel hostile to use.
 */
private const val AUTO_HIDE_MS = 5_000L

/**
 * Fraction of the video the player already holds, 0..1.
 *
 * `bufferedPosition` is the END of the buffered span, so this is "how far ahead
 * playback can go without waiting". 0 while the duration is still unknown
 * (live / HLS before the manifest settles), which simply hides the segment.
 */
private fun bufferedOf(player: Player): Float {
    val d = player.duration
    if (d <= 0L) return 0f
    return (player.bufferedPosition.toFloat() / d).coerceIn(0f, 1f)
}

/** ExoPlayer state as a readable name, for the seek diagnostics. */
private fun stateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    Player.STATE_ENDED -> "ENDED"
    else -> "?$state"
}