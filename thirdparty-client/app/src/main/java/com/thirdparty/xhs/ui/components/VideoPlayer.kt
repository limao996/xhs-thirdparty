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
import androidx.media3.exoplayer.SeekParameters
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import kotlin.math.roundToInt
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
    ExoPlayer.Builder(
        context.applicationContext,
        // Decoder fallback: after a seek every decoder is flushed, and some
        // decoders never resume. When the primary one does that the player sits in
        // BUFFERING forever. Fallback lets ExoPlayer move to another decoder
        // instead of retrying a dead one.
        androidx.media3.exoplayer.DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
    )
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
            // The feed loops (a swipe feed is consumed in seconds and swiping past a
            // stopped clip is a dead end). The detail page must NOT: a finished work
            // stops at its last frame and waits for the user to press 重播, so the
            // player reaches STATE_ENDED and stays there. Looping it made the clip
            // restart on its own, which reads as "it never ends".
            repeatMode =
                if (longForm) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            // Seeking: ExoPlayer's DEFAULT is EXACT, which makes the extractor start
            // at the preceding keyframe and decode forward. A 1s tolerance lets it
            // take any sync point that close instead, without a visible loss of
            // precision. Matters for progressive files; HLS already lands on the
            // segment boundary either way.
            setSeekParameters(if (longForm) SEEK_PARAMETERS_TOLERANT else SeekParameters.EXACT)
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = autoPlay
        }

private val SEEK_PARAMETERS_TOLERANT =
    androidx.media3.exoplayer.SeekParameters(SEEK_TOLERANCE_US, SEEK_TOLERANCE_US)
private const val SEEK_TOLERANCE_US = 1_000_000L

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
/** 5s to begin a cold start — ExoPlayer's default 2.5s was too eager for 弱网. */
private const val BUFFER_FOR_PLAYBACK_MS = 5_000

/**
 * After a seek the player counts as **rebuffering**, and ExoPlayer then requires
 * this much media before it resumes — a different threshold from the cold start.
 *
 * This used to be 15s. Measured against the real content (note 13689345): the
 * media is HLS, 142 segments of 5s, 707s total. So 15s meant **every single seek
 * had to download 3 full segments before a frame appeared**, while the first load
 * only needed 5s (one segment) — which is exactly the reported symptom: "刚进来
 * 加载很快，一跳转就缓冲特别久，跳回最前面也一样".
 *
 * 4s is one segment, so a seek resumes on the next segment boundary. Weak-network
 * resilience is not lost: [MIN_BUFFER_MS]/[MAX_BUFFER_MS] still build a deep
 * buffer *while playing*, which is what actually prevents mid-playback stalls.
 * Paying a 15s gate on every seek was protecting against the wrong thing.
 */
private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 4_000
/** 2 minutes of rewindable history, served from memory. */
private const val BACK_BUFFER_MS = 120_000
private const val SEEK_INCREMENT_MS = 10_000L

// ---- feed profile: start fast, do not hoard ----
private const val FEED_MIN_BUFFER_MS = 8_000
private const val FEED_MAX_BUFFER_MS = 25_000
private const val FEED_BUFFER_FOR_PLAYBACK_MS = 1_500
private const val FEED_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

/**
 * What the buffering overlay counts towards.
 *
 * A determined video resumes once the load control has this much buffered ahead
 * of the playhead, so "how soon will it play again" is `ahead / this`. Kept in
 * step with [BUFFER_FOR_PLAYBACK_MS] (5s) and
 * [BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS] (4s) — the thresholds the player
 * actually applies — so the bar filling up coincides with playback resuming.
 */
private const val WAIT_START_MS = 5_000L
private const val WAIT_RESUME_MS = 4_000L

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
 * Beyond the spinner it shows **progress towards resuming**, which is the one
 * figure that answers "how much longer". What it must NOT show is how much of the
 * whole work has been downloaded: while the player is stalled
 * `bufferedPosition == currentPosition`, so that number is the playback position
 * wearing a percentage sign and it advances at exactly the playback rate. That is
 * what got reported as "缓冲中进度怎么是播放进度".
 *
 * So the bar counts `bufferedPosition - currentPosition` against the load
 * control's resume threshold ([WAIT_START_MS] for a cold start, [WAIT_RESUME_MS]
 * after a stall). It therefore fills while the playhead is frozen, and reaching
 * the end coincides with playback resuming.
 *
 * When that figure is unavailable (no duration yet, e.g. a live or not-yet-parsed
 * stream) **no bar is drawn** — a fabricated progress figure is worse than none.
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
    // How far along the CURRENT wait is, 0..1 — never how much of the whole work
    // has been downloaded.
    //
    // `bufferedPercentage` was the wrong figure: while the player is stalled,
    // `bufferedPosition == currentPosition`, so it reprints the playhead as a
    // share of the total and moves at exactly the playback rate. Reported as
    // "缓冲中进度怎么是播放进度".
    //
    // The honest question is "how soon will it start again", which is how much of
    // the resume threshold has arrived: `bufferedPosition - currentPosition` over
    // the amount the load control needs. `null` when that cannot be computed, and
    // then no bar is drawn at all — a made-up figure is worse than none.
    var waitFraction by remember(player) {
        androidx.compose.runtime.mutableStateOf<Float?>(null)
    }
    // a cold start needs less than a resume after a stall
    var hasPlayed by remember(player) { androidx.compose.runtime.mutableStateOf(false) }
    DisposableEffect(player) {
        val p = player
        val listener = if (p == null) null else object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state = playbackState
                if (playbackState == Player.STATE_READY) hasPlayed = true
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    // Poll: the listener API only fires on state changes, so the bar would sit
    // still through a long wait.
    LaunchedEffect(player, state, active) {
        if (!active) return@LaunchedEffect
        if (state != Player.STATE_IDLE && state != Player.STATE_BUFFERING) {
            waitFraction = null
            return@LaunchedEffect
        }
        while (true) {
            val pl = player
            waitFraction = if (pl == null || pl.duration <= 0L) null else {
                val ahead = (pl.bufferedPosition - pl.currentPosition).coerceAtLeast(0L)
                val needMs = if (hasPlayed) WAIT_RESUME_MS else WAIT_START_MS
                (ahead.toFloat() / needMs).coerceIn(0f, 1f)
            }
            delay(250)
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
            // The percentage here is progress towards RESUMING, not how much of the
            // whole work is downloaded — see the note on waitFraction. Showing the
            // whole-file figure is what previously made this read as the playback
            // position. When no real figure exists the label drops the number
            // rather than inventing one.
            androidx.compose.material3.Text(
                waitFraction?.let { "缓冲中 ${(it * 100).roundToInt()}%" } ?: "缓冲中",
                color = Scrim.onMedia,
                style = MaterialTheme.typography.labelMedium
            )
            waitFraction?.let { fraction ->
                androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.xs))
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .width(150.dp)
                        .height(4.dp)
                        .clip(Corners.full)
                        .background(SeekTrack.inactive)
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
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
    trackColor: androidx.compose.ui.graphics.Color = SeekTrack.inactive,
    /** the already-buffered span, drawn between played and empty */
    bufferedColor: androidx.compose.ui.graphics.Color = SeekTrack.buffered,
    fillColor: androidx.compose.ui.graphics.Color = SeekTrack.thumb
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

/**
 * Rebuilds playback when the player is stuck buffering although the data is
 * already there.
 *
 * Every seek flushes the decoder, and a decoder that does not resume leaves the
 * player in BUFFERING indefinitely: referenced measurements on the emulator
 * (`c2.goldfish.h264.decoder`) show the buffer growing past 70s ahead with
 * `playWhenReady=true`, no suppression, and `playing=false` forever — the user
 * just sees "buffering" and nothing plays. Download speed is not the issue there
 * (a 5s HLS segment takes ~1.5s).
 *
 * ExoPlayer has no timeout for this, so it is detected here: the player is
 * BUFFERING, enough media is already buffered, and it has been that way for
 * [STUCK_BUFFERING_MS]. Recovery is a full stop/prepare/seek, which rebuilds the
 * codecs — the one action that clears a poisoned decoder.
 *
 * The thresholds matter: a genuinely slow link also sits in BUFFERING, but its
 * `ahead` stays small, so it never trips the check.
 */
@Composable
fun RecoverStuckPlayback(player: Player?, onGaveUp: () -> Unit = {}) {
    val gaveUp = rememberUpdatedState(onGaveUp)
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        var since = 0L
        var attempts = 0
        while (true) {
            if (p.playbackState == Player.STATE_BUFFERING) {
                if (since == 0L) since = android.os.SystemClock.elapsedRealtime()
                val stallMs = android.os.SystemClock.elapsedRealtime() - since
                val ahead = p.bufferedPosition - p.currentPosition
                // Two ways to be stuck, and both are needed:
                //  - the data is already here and it still will not play
                //  - it is BUFFERING but not even fetching any more
                // Requiring only the first misses the case observed after a
                // rebuild, where the buffer never refills: the check never fires
                // again and the user is left with a spinner that never ends.
                val dataReady = ahead >= STUCK_ENOUGH_AHEAD_MS
                val notFetching = !p.isLoading
                // Once a rebuild has happened we know this is a real stall, not a
                // slow link, so a plain time budget is then enough to conclude it
                // will not recover. A slow link never reaches here at all: its
                // `ahead` stays small, so no rebuild is ever attempted.
                val stalledTooLong = attempts > 0 && stallMs > STUCK_GIVE_UP_MS
                if (stallMs > STUCK_BUFFERING_MS &&
                    (dataReady || notFetching || stalledTooLong)
                ) {
                    if (attempts >= MAX_STUCK_RECOVERIES) {
                        // The decoder is not coming back. Rebuilding forever would
                        // leave the user watching an endless spinner with no way
                        // out, so stop and let the caller show its error panel —
                        // which has a 重试 button.
                        gaveUp.value()
                        return@LaunchedEffect
                    }
                    attempts++
                    val pos = p.currentPosition
                    val wasPlaying = p.playWhenReady
                    p.stop()
                    p.seekTo(pos)
                    p.prepare()
                    p.playWhenReady = wasPlaying
                    since = 0L
                }
            } else {
                // a healthy state clears the budget, so a later unrelated stall
                // still gets its own full set of attempts
                since = 0L
                if (p.playbackState == Player.STATE_READY && p.isPlaying) attempts = 0
            }
            delay(STUCK_POLL_MS)
        }
    }
}

/** how long the player may sit in BUFFERING with data available before we rebuild */
private const val STUCK_BUFFERING_MS = 6_000L
/** "the data is already there": this much buffered ahead */
private const val STUCK_ENOUGH_AHEAD_MS = 5_000L
private const val STUCK_POLL_MS = 500L
/** after a rebuild, how long to keep waiting before concluding it will not recover */
private const val STUCK_GIVE_UP_MS = 20_000L
/** rebuilds before giving up and handing over to the error panel */
private const val MAX_STUCK_RECOVERIES = 2

/**
 * Surfaced when the stuck-playback watchdog has exhausted its rebuilds.
 *
 * It is not from the player — it is our conclusion that the decoder stopped
 * responding after a seek. Reusing [PlaybackErrorOverlay] rather than inventing a
 * second error UI keeps one place that explains a failure and offers 重试.
 */
val STUCK_PLAYBACK_EXCEPTION: androidx.media3.common.PlaybackException =
    androidx.media3.common.PlaybackException(
        "seek-following decoder stall",
        null,
        androidx.media3.common.PlaybackException.ERROR_CODE_UNSPECIFIED
    )