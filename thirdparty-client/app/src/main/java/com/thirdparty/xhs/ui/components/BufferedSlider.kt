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

// Geometry copied from the official M3 Expressive slider
// (androidx.compose.material3.tokens.SliderTokens and the private drawTrack in
// Slider.kt), because a screenshot comparison showed the track did not match:
//
//   ActiveTrackHeight / InactiveTrackHeight = 16.dp   (both full height)
//   ActiveTrackShape   = CornerFull                   (outer ends only)
//   ActiveHandleWidth  = 4.dp,  ActiveHandleHeight = 44.dp
//   ActiveHandleLeadingSpace = 6.dp
//   TrackInsideCornerSize    = 2.dp   <-- the end that faces the thumb gap is
//                                          almost square, NOT a full pill
//
// The gap on each side of the handle is `handleWidth / 2 + 6.dp` = 8.dp, and the
// track is split there rather than drawn underneath the handle.
private val TrackHeight = 16.dp
private val HandleWidth = 4.dp
private val HandleLeadingSpace = 6.dp
/** the nearly-square corner the official track uses next to the handle gap */
private val InsideCornerSize = 2.dp
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
                val outer = CornerRadius(size.height / 2f, size.height / 2f)
                val inside = InsideCornerSize.toPx()
                // the official split: value*width, then a gap of
                // handleWidth/2 + leadingSpace on each side of the handle
                val gap = HandleWidth.toPx() / 2f + HandleLeadingSpace.toPx()

                /**
                 * Draws one segment with **per-end** corner radii, as the official
                 * track does: the outer end of the track is fully rounded, while the
                 * end facing the handle gap is nearly square ([InsideCornerSize]).
                 * `drawRoundRect` takes a single radius, so this goes through a Path.
                 */
                fun bar(x: Float, widthPx: Float, color: Color, startR: Float, endR: Float) {
                    if (widthPx <= 0f) return
                    val w = widthPx.coerceIn(size.height, size.width)
                    val roundRect = androidx.compose.ui.geometry.RoundRect(
                        rect = androidx.compose.ui.geometry.Rect(Offset(x, 0f), Size(w, size.height)),
                        topLeft = CornerRadius(startR),
                        bottomLeft = CornerRadius(startR),
                        topRight = CornerRadius(endR),
                        bottomRight = CornerRadius(endR)
                    )
                    val path = androidx.compose.ui.graphics.Path().apply { addRoundRect(roundRect) }
                    drawPath(path, color)
                }

                val valueEnd = size.width * played

                // 1. the whole track, dimmest — covers the area the handle cuts into,
                //    so no seam shows through the gaps
                bar(0f, size.width, inactiveColor, outer.x, outer.x)
                // 2. downloaded, one step brighter. Runs from just past the handle to
                //    the downloaded end, so "buffered ahead" is the visible band
                //    between the playhead and the undownloaded remainder.
                val bufferedEnd = size.width * buffered.coerceAtLeast(played)
                bar(valueEnd + gap, bufferedEnd - valueEnd - gap, bufferedColor, inside, outer.x)
                // 3. played, brightest, ending one gap short of the handle with the
                //    near-square corner that faces it
                bar(0f, valueEnd - gap, playedColor, outer.x, inside)

                // 4. stop indicators, both ends (the official track draws both).
                //    The left one uses the played colour so it stays visible once the
                //    playhead has passed it.
                val r = StopIndicatorSize.toPx() / 2f
                drawCircle(playedColor, r, Offset(r, size.height / 2f))
                drawCircle(inactiveColor, r, Offset(size.width - r, size.height / 2f))
            }
        }
    )
}
