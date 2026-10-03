package com.thirdparty.xhs.ui.components

import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player

/**
 * Where a player's picture is drawn.
 *
 * A `TextureView`, not the `SurfaceView` that `PlayerView` uses by default.
 *
 * A SurfaceView is composited by the system in its own layer, outside this app's
 * window, so it takes no part in the view's transform or alpha. When the detail
 * page is popped, its exit animation slides and fades the screen — while the video
 * keeps being drawn at full opacity for the whole transition, so the picture stays
 * pasted over the screen the user just returned to. Reported as
 * "视频播放器画面残留到上级界面", and reproduced: the outgoing player's overlay was
 * still drawn on top of the feed after pressing back.
 *
 * A TextureView is an ordinary view rendered through the normal view pipeline, so
 * it fades and slides with the rest of the screen. That is the entire reason for
 * not using `PlayerView` here.
 *
 * `PlayerView` also provided letterboxing (`resizeMode`). That is handled here:
 * pass [videoAspect] and the picture keeps its own proportions inside whatever box
 * the caller allocated — which matters because the caller may have clamped that box
 * to a minimum or maximum height, leaving more room than the video fills.
 */
@Composable
fun VideoSurface(
    player: Player?,
    modifier: Modifier = Modifier,
    /** width / height of the video; null means fill the box */
    videoAspect: Float? = null
) {
    // `update` runs on every recomposition, and re-attaching a surface each time
    // would flicker, so the binding happens once per view via factory and only
    // again if the player instance actually changes.
    val currentPlayer by rememberUpdatedState(player)

    Box(
        modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    runCatching { player?.setVideoTextureView(this) }
                }
            },
            update = { view ->
                // a no-op unless the player changed since the view was built
                runCatching { currentPlayer?.setVideoTextureView(view) }
            },
            modifier = if (videoAspect != null && videoAspect > 0f) {
                Modifier.fillMaxSize().aspectRatio(videoAspect)
            } else {
                Modifier.fillMaxSize()
            }
        )
    }

    DisposableEffect(player) {
        onDispose {
            // Detach so nothing is drawn to a surface that is on its way out; this
            // is what stops the last frame surviving the transition.
            runCatching { player?.clearVideoSurface() }
        }
    }
}
