package com.thirdparty.xhs.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Build an ExoPlayer configured for short-video playback.
 *
 * Uses movie/media audio attributes with audio-focus handling so the app
 * pauses itself when another app takes over audio (a call, a music player),
 * and holds a local wake lock while playing.
 */
fun buildVideoPlayer(context: Context, url: String, autoPlay: Boolean = true): ExoPlayer =
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
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = autoPlay
        }

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
 * Centered spinner shown while the player is buffering, so switching to the
 * next video never looks like a frozen black screen.
 */
@Composable
fun BufferingIndicator(
    player: Player?,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White
) {
    var buffering by remember(player) {
        androidx.compose.runtime.mutableStateOf(
            // IDLE counts too: before prepare() (and while the first frame loads)
            // there is otherwise no spinner at all, which looked like a frozen video.
            player == null || player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_BUFFERING
        )
    }
    DisposableEffect(player) {
        val p = player
        val listener = if (p == null) null else object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_IDLE ||
                    playbackState == Player.STATE_BUFFERING
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    if (buffering) {
        androidx.compose.foundation.layout.Box(
            modifier,
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = tint,
                strokeWidth = 3.dp
            )
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
    fillColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White
) {
    var fraction by remember(player) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            val d = p.duration
            fraction = if (d > 0) (p.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(250)
        }
    }
    Box(modifier.background(trackColor)) {
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
    var autoRetried by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val p = player
        val listener = if (p == null) null else object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // Transient failures (a DNS blip, a 5xx from the CDN) are worth
                // one silent retry; only surface the error if that also fails.
                if (!autoRetried) {
                    autoRetried = true
                    retryPlayback(p)
                } else {
                    error = e
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // a retry moves us back through BUFFERING -> clear the error
                if (playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_READY
                ) {
                    error = null
                }
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    return error
}

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