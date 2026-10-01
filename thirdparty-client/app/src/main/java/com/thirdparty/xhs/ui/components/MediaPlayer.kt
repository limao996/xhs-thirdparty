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

/**
 * Detail page player: media3 surface + custom controller.
 * - controller auto-hides after 3s, tap to show/hide
 * - full controls: play/pause, seek, time, replay, fullscreen (横/竖 皆可)
 * - releases on dispose so no residual frame on exit
 */
@Composable
fun MediaPlayer(
    url: String,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    /** Reports the video's real width/height ratio once known. */
    onAspect: ((Float) -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val player = remember(url) { buildVideoPlayer(context, url) }
    // stop playback/audio when the app leaves the foreground
    PauseWhenNotStarted(player)
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
        AutoHideController(player, fullscreen, onToggleFullscreen)
    }
}

@Composable
private fun AutoHideController(
    player: Player,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var visible by remember(player) { mutableStateOf(true) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var duration by remember(player) { mutableFloatStateOf(0f) }
    var position by remember(player) { mutableFloatStateOf(0f) }
    var ended by remember(player) { mutableStateOf(false) }

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

    // auto-hide after 3s of no interaction
    LaunchedEffect(visible, playing) {
        if (visible && playing) { delay(3000); visible = false }
    }

    Box(
        Modifier.fillMaxSize().clickable {
            visible = !visible
            if (visible) { scope.launch { delay(3000); visible = false } }
        }
    ) {
        // replay button when ended
        if (ended) {
            IconButton(onClick = { player.seekTo(0); player.play() },
                modifier = Modifier.align(Alignment.Center)) {
                Icon(Icons.Filled.Replay, "重播", tint = Scrim.onMedia)
            }
        }
        if (visible) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Scrim.strong).padding(vertical = Spacing.xs)
            ) {
                val fraction = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
                Slider(
                    value = fraction,
                    onValueChange = { f -> player.seekTo((f * player.duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = Scrim.onMedia,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Scrim.onMediaVariant
                    )
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "暂停" else "播放", tint = Scrim.onMedia)
                    }
                    Text("${fmt(position.toLong())} / ${fmt(duration.toLong())}",
                        color = Scrim.onMedia, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f).padding(start = Spacing.xs))
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            "全屏", tint = Scrim.onMedia)
                    }
                }
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000; val m = s / 60
    return "%d:%02d".format(m, s % 60)
}