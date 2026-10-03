@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import com.thirdparty.xhs.ui.theme.Corners
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Build an ExoPlayer configured for short- and long-form playback over an
 * unreliable connection.
 *
 * Uses movie/media audio attributes with audio-focus handling so the app
 * pauses itself when another app takes over audio (a call, a music player),
 * and holds a local wake lock while playing.
 *
 * ## Tuning, and why
 *
 * **Weak network.** Default ExoPlayer buffer targets are tuned for reliable
 * connections: playout starts after 2.5s of media and the buffer tops out at 50s.
 * On a slow link that produces a stutter/rebuffer loop, because the buffer never
 * outruns the playhead. Playback now waits for [BUFFER_FOR_PLAYBACK_MS] before
 * starting and fills up to [MAX_BUFFER_MS], which trades a slightly longer
 * initial wait for continuous playback. `prioritizeTimeOverSizeThresholds` keeps
 * the buffer filling even when that overruns the size limit — on a weak link the
 * bytes will be needed regardless, so dropping them early only causes a rebuffer.
 *
 * **Very long videos.** A back buffer of [BACK_BUFFER_MS] means a rewind of up to
 * that much is served from memory instead of re-fetching, so scrubbing backwards
 * through a long video does not re-download it. `setSeekBack/ForwardIncrementMs`
 * keep the ±10s step meaningful for long content when the player itself is asked
 * to seek.
 *
 * **Arbitrary seeking.** The scrub bar commits its seek only on release (see the
 * slider in `MediaPlayer`), so dragging cannot spam the network; seeks within the
 * back buffer are then instant.
 */
fun buildVideoPlayer(
    context: Context,
    url: String,
    autoPlay: Boolean = true,
    /**
     * true for the detail page (a work can be hours long), false for the feed.
     *
     * The two need different buffers, and using one setting for both is wrong in
     * both directions: the feed keeps several players alive at once (current page
     * plus neighbours), so a 90s buffer each is a lot of wasted memory and
     * bandwidth for clips that are seconds long — while a long work needs the
     * deep buffer or it rebuffers constantly on a slow link.
     */
    longForm: Boolean = false
): ExoPlayer =
    ExoPlayer.Builder(context.applicationContext)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)   // pause when headphones are unplugged
        .setLoadControl(if (longForm) longFormLoadControl() else feedLoadControl())
        .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = autoPlay
        }

/**
 * Deep buffer, for a work the user may watch end to end.
 *
 * `prioritizeTimeOverSizeThresholds` keeps the buffer filling past the size
 * limit: on a weak link those bytes will be needed anyway, so dropping them
 * early only buys another rebuffer.
 */
private fun longFormLoadControl() =
    androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        .setBackBuffer(BACK_BUFFER_MS, /* retainBackBufferFromKeyframe = */ true)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

/**
 * Shallow but quick buffer for the feed.
 *
 * Enough to survive a short stall, small enough that the neighbours which are
 * being pre-buffered do not each hold a large chunk. Starting sooner matters
 * more than buffering far ahead: the user swipes long before the deep buffer
 * would ever be used.
 */
private fun feedLoadControl() =
    androidx.media3.exoplayer.DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            FEED_MIN_BUFFER_MS,
            FEED_MAX_BUFFER_MS,
            FEED_BUFFER_FOR_PLAYBACK_MS,
            FEED_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        .setBackBuffer(0, false)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

/** 15s minimum before playback starts — long enough to survive a slow start. */
private const val MIN_BUFFER_MS = 15_000
/** 90s ceiling so a fast link still starts quickly. */
private const val MAX_BUFFER_MS = 90_000
/** don't begin until this much is buffered (default is 2.5s — too eager for 弱网). */
private const val BUFFER_FOR_PLAYBACK_MS = 5_000
/** after a rebuffer, wait for more before resuming, so it does not stutter again. */
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 15_000
/** 2 minutes of rewindable history, served from memory. */
private const val BACK_BUFFER_MS = 120_000
private const val SEEK_INCREMENT_MS = 10_000L

// ---- feed profile: start fast, do not hoard ----
private const val FEED_MIN_BUFFER_MS = 8_000
private const val FEED_MAX_BUFFER_MS = 25_000
private const val FEED_BUFFER_FOR_PLAYBACK_MS = 1_500
private const val FEED_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000

/**
 * Pause playback while the host lifecycle is not at least STARTED and resume
 * afterwards if it was playing before. Without this the feed keeps playing
 * (and keeps its audio) after the user presses Home.
 */
@Composable
fun PauseWhenNotStarted(player: Player?, pauseOnDispose: Boolean = true) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(player)
    DisposableEffect(lifecycleOwner, player) {
        var resumeOnStart = false
        val observer = LifecycleEventObserver { _, event ->
            val p = current ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = p.playWhenReady
                    p.pause()
                }
                Lifecycle.Event.ON_START -> {
                    if (resumeOnStart) p.play()
                    resumeOnStart = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Never leave audio running when leaving the screen — but ONLY when
            // this component owns the player.
            //
            // This used to fire unconditionally, which broke things badly once the
            // windowed and fullscreen layouts started sharing one player: toggling
            // fullscreen disposed the outgoing layout, which paused the SHARED
            // player, so entering/leaving fullscreen stopped the video and left the
            // control bar showing a stale play/pause state. A shared player's
            // lifetime (including pausing) belongs to its owner.
            if (pauseOnDispose) current?.pause()
        }
    }
}

/**
 * Create a lifecycle-managed ExoPlayer for a single screen (e.g. the detail
 * page). The player is released on dispose.
 */
@Composable
fun rememberExoPlayer(
    autoPlay: Boolean = true,
    onError: (PlaybackException) -> Unit = {}
): ExoPlayer {
    val context = LocalContext.current.applicationContext
    val player = remember {
        ExoPlayer.Builder(context)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
    }
    DisposableEffect(player) {
        player.addListener(errorListener(onError))
        onDispose { player.release() }
    }
    return player
}

/**
 * Centered buffering feedback, shown over the video.
 *
 * Shows **how much is buffered**, not just a spinner: on a weak link the useful
 * question is "is it making progress or stuck?", and an indeterminate spinner
 * cannot answer that. The percentage comes from [Player.getBufferedPercentage]
 * (a whole-stream figure for progressive files, and per-window for HLS) and is
 * paired with a thin progress line so the state is readable at a glance.
 *
 * [active] gates the IDLE case. Every page in the feed keeps a prepared player
 * alive, and an unprepared neighbour sits in STATE_IDLE — treating that as
 * buffering would put a spinner on pages the user has not reached yet.
 */
@Composable
fun BufferingIndicator(
    player: Player?,
    modifier: Modifier = Modifier,
    /** true only for the page actually on screen */
    active: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White
) {
    var state by remember(player) {
        androidx.compose.runtime.mutableIntStateOf(player?.playbackState ?: Player.STATE_IDLE)
    }
    var percent by remember(player) { androidx.compose.runtime.mutableIntStateOf(0) }
    DisposableEffect(player) {
        val p = player
        val listener = if (p == null) null else object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state = playbackState
                percent = p.bufferedPercentage
            }

            override fun onIsLoadingChanged(isLoading: Boolean) {
                percent = p.bufferedPercentage
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    // keep the figure moving while it is visible: bufferedPercentage only changes
    // on load events, which are coarse and irregular
    LaunchedEffect(player, state, active) {
        if (!active) return@LaunchedEffect
        if (state != Player.STATE_IDLE && state != Player.STATE_BUFFERING) return@LaunchedEffect
        while (true) {
            player?.let { percent = it.bufferedPercentage }
            delay(400)
        }
    }

    val show = when {
        !active -> false
        player == null -> true
        // IDLE before prepare(): a spinner, but only for the page on screen
        state == Player.STATE_IDLE -> true
        state == Player.STATE_BUFFERING -> true
        else -> false
    }
    if (!show) return

    androidx.compose.foundation.layout.Box(
        modifier.background(Scrim.chrome),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.LoadingIndicator(
                modifier = Modifier.size(40.dp),
                color = tint,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.s))
            androidx.compose.material3.Text(
                if (percent > 0) "缓冲中 $percent%" else "缓冲中",
                color = Scrim.onMedia,
                style = MaterialTheme.typography.labelMedium
            )
            if (percent > 0) {
                androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .width(120.dp)
                        .height(3.dp)
                        .clip(Corners.full)
                        .background(Scrim.onMediaVariant)
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(percent / 100f)
                            .clip(Corners.full)
                            .background(tint)
                    )
                }
            }
        }
    }
}

/**
 * Thin playback progress line (0..1) for the immersive feed.
 *
 * Polls the player because media3 has no per-frame position callback — the
 * listener API only reports state changes and seeks.
 */
@Composable
fun VideoProgress(
    player: Player?,
    modifier: Modifier = Modifier,
    trackColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0x33FFFFFF),
    /** the already-buffered span, drawn between played and empty */
    bufferedColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(0x80FFFFFF),
    fillColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White
) {
    var fraction by remember(player) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var buffered by remember(player) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            val d = p.duration
            fraction = if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            // how far ahead playback can continue without waiting — the same
            // signal the detail page's seek bar shows, so the feed is not the one
            // surface where a stalled stream looks the same as a fresh one
            buffered = if (d > 0) (p.bufferedPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(250)
        }
    }
    Box(modifier.background(trackColor)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(buffered.coerceAtLeast(fraction))
                .background(bufferedColor)
        )
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(fillColor)
        )
    }
}

/**
 * Whether [player] is actually playing right now.
 *
 * The feed used to track paused/playing with a local flag flipped by double-tap
 * only, so an externally caused pause (another app taking audio focus, a codec
 * stall) left the UI claiming the video was running.
 */
@Composable
fun rememberIsPlaying(player: Player?): Boolean {
    var playing by remember(player) { mutableStateOf(player?.isPlaying == true) }
    DisposableEffect(player) {
        val p = player
        val listener = if (p == null) null else object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playing = p.isPlaying
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    return playing
}

/**
 * Tracks the most recent playback failure for [player] (null while healthy).
 *
 * Without this a dead stream (bad URL, unsupported codec, CDN error) is a
 * completely silent failure — the user just sees a poster or a stuck spinner.
 */
@Composable
fun rememberPlaybackError(player: Player?): PlaybackException? {
    var error by remember(player) { mutableStateOf<PlaybackException?>(null) }
    // Attempts spent on the CURRENT run of failures.
    //
    // This used to be a single immediate retry. On a weak link that is not enough:
    // the player gives up again the instant the same stall recurs, so the user saw
    // the error panel after one blip. It now retries up to [MAX_AUTO_RETRIES] times
    // with an exponential backoff, which gives the network a chance to recover
    // before anything is shown.
    var attempts by remember(player) { androidx.compose.runtime.mutableIntStateOf(0) }
    val retryScope = rememberCoroutineScope()
    DisposableEffect(player) {
        val p = player
        val listener = if (p == null) null else object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (attempts < MAX_AUTO_RETRIES) {
                    attempts++
                    // 1s, 2s, 4s … — still fast enough that a recovered link resumes
                    // without the user noticing a stall.
                    val waitMs = RETRY_BASE_MS shl (attempts - 1)
                    retryScope.launch {
                        delay(waitMs)
                        retryPlayback(p)
                    }
                } else {
                    error = e
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // back to buffering/ready means the retry took: clear the panel and
                // let the next failure start from a fresh budget
                if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                    error = null
                }
                if (playbackState == Player.STATE_READY) attempts = 0
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    return error
}

/** how many silent retries a failing stream gets before the error panel appears */
private const val MAX_AUTO_RETRIES = 3
private const val RETRY_BASE_MS = 1_000L

/** Restart playback after a failure. */
fun retryPlayback(player: Player?) {
    val p = player ?: return
    runCatching {
        p.prepare()
        p.play()
    }
}

/**
 * "播放失败 + 重试" overlay shown over a video that could not be played.
 */
@Composable
fun PlaybackErrorOverlay(
    error: PlaybackException?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (error == null) return
    Box(
        modifier.background(Scrim.strong),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = Scrim.onMedia
            )
            Spacer(Modifier.size(Spacing.s))
            Text(
                "视频播放失败",
                color = Scrim.onMedia,
                style = MaterialTheme.typography.bodyMedium
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/**
 * media3 [androidx.media3.ui.PlayerView] wrapper.
 *
 * [resizeMode] should stay [androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT]
 * and callers should size the container to the video's own ratio — FILL
 * stretches, ZOOM crops, neither is wanted here.
 */
@Composable
fun PlayerView(
    player: Player?,
    modifier: Modifier = Modifier,
    useController: Boolean = true,
    resizeMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
) {
    AndroidView(
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                this.useController = useController
                this.resizeMode = resizeMode
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(false)
            }
        },
        modifier = modifier,
        update = { view ->
            view.player = player
            if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
        },
        onRelease = { view ->
            // detach the player so the surface is cleared -> no residual frame
            view.player = null
            view.setShutterBackgroundColor(android.graphics.Color.BLACK)
            view.visibility = android.view.View.GONE
        }
    )
}

private fun errorListener(onError: (PlaybackException) -> Unit) = object : Player.Listener {
    override fun onPlayerError(error: PlaybackException) = onError(error)
}