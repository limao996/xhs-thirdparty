package com.thirdparty.xhs.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Build an ExoPlayer configured for short-video playback.
 *
 * Uses movie/media audio attributes with audio-focus handling so the app
 * pauses itself when another app takes over audio (a call, a music player),
 * and holds a local wake lock while playing.
 */
fun buildVideoPlayer(context: Context, url: String): ExoPlayer =
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
            playWhenReady = true
        }

/**
 * Pause playback while the host lifecycle is not at least STARTED and resume
 * afterwards if it was playing before. Without this the feed keeps playing
 * (and keeps its audio) after the user presses Home.
 */
@Composable
fun PauseWhenNotStarted(player: Player?) {
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
            // leaving the screen entirely: never leave audio running
            current?.pause()
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