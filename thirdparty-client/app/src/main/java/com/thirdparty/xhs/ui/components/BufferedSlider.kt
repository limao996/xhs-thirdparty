package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The three steps of a video seek track, **dimmest first**.
 *
 * The order is the whole point. Previously the empty track was drawn at
 * `onMediaVariant` (70% white) and the buffered span at 55% white, so the empty
 * part was *brighter* than the buffered part and the two were impossible to tell
 * apart — reported as "缓冲条太亮，分不清缓冲和播放进度".
 *
 * Emphasis now increases with progress: empty → buffered → played.
 */
object SeekTrack {
    /** nothing downloaded here yet */
    val inactive: Color = Color.White.copy(alpha = 0.20f)
    /** downloaded and playable, but not reached yet */
    val buffered: Color = Color.White.copy(alpha = 0.42f)
    /** the thumb colour */
    val thumb: Color = Color.White
}

// M3 Expressive slider geometry, from the official tokens
// (androidx.compose.material3.tokens.SliderTokens):
//   ActiveTrackHeight        = 16.dp
//   ActiveTrackShape         = CornerFull
//   ActiveHandleWidth        = 4.dp
//   ActiveHandleHeight       = 44.dp
//   ActiveHandleLeadingSpace = 6.dp   (the gap between handle and track)
private val TrackHeight = 16.dp
private val HandleWidth = 4.dp
private val HandleLeadingSpace = 6.dp
private val StopIndicatorSize = 4.dp

/**
 * Seek bar that also shows **how much is buffered**.
 *
 * The component itself is the official [Slider]: it brings the M3 Expressive
 * thumb (a 4×44dp bar, not the old circular knob), the drag/tap gestures, the
 * ripple, the focus and the accessibility semantics — none of which are worth
 * reimplementing.
 *
 * Only the **track** is drawn here, because material3's track cannot show a
 * buffered span: `SliderDefaults.Track` has no `buffer` parameter (checked in the
 * 1.5.0-alpha sources — the overload that takes one is internal). The custom track
 * follows the same token geometry as the official one, so the result matches what
 * the official component would draw, plus the buffered segment.
 *
 * Three segments, dimmest to brightest: inactive → buffered → played.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BufferedSlider(
    /** 0..1 playhead */
    value: Float,
    /** 0..1 of the whole video the player already holds */
    buffered: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = SeekTrack.inactive,
    bufferedColor: Color = SeekTrack.buffered,
    thumbColor: Color = SeekTrack.thumb
) {
    // The custom-track overload of Slider takes a SliderState, not a value
    // (verified from the compiler: `Slider(value, …, track = …)` does not exist in
    // this material3 version — only `Slider(state: SliderState, …, track = …)`).
    // In this material3 version SliderState carries no completion callback — it is
    // a parameter of Slider itself, so it is passed below.
    val state = remember { SliderState(value = value.coerceIn(0f, 1f)) }
    // keep the handle following the player while it plays; during a drag the
    // caller already feeds back the dragged fraction, so this stays consistent
    LaunchedEffect(value) {
        val v = value.coerceIn(0f, 1f)
        if (state.value != v) state.value = v
    }

    Slider(
        state = state,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = SliderDefaults.colors(
            thumbColor = thumbColor,
            activeTrackColor = playedColor,
            inactiveTrackColor = inactiveColor
        ),
        track = { sliderState ->
            val played = sliderState.value.coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(TrackHeight)) {
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                val gap = HandleLeadingSpace.toPx()
                val handle = HandleWidth.toPx()

                fun segment(widthPx: Float, color: Color) {
                    if (widthPx <= 0f) return
                    drawRoundRect(
                        color = color,
                        topLeft = Offset.Zero,
                        // never shorter than the bar is tall, or a small value
                        // renders as a squashed blob instead of a rounded end
                        size = Size(
                            width = widthPx.coerceIn(size.height, size.width),
                            height = size.height
                        ),
                        cornerRadius = radius
                    )
                }

                // 1. the whole track, dimmest
                segment(size.width, inactiveColor)
                // 2. downloaded, one step brighter (never less than the playhead)
                segment(size.width * buffered.coerceAtLeast(played), bufferedColor)
                // 3. played, brightest. Shortened by the gap plus half the handle
                //    so the handle sits in its own space instead of on the track —
                //    the same separation the official track makes.
                segment(size.width * played - gap - handle / 2f, playedColor)
                // 4. stop indicator at the end of the track (part of the M3 track)
                drawCircle(
                    color = inactiveColor,
                    radius = StopIndicatorSize.toPx() / 2f,
                    center = Offset(size.width - StopIndicatorSize.toPx() / 2f, size.height / 2f)
                )
            }
        }
    )
}
