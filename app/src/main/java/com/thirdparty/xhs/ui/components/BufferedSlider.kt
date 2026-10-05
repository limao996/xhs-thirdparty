package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * The three steps of a seek track, **dimmest first**.
 *
 * Ordered by emphasis — that was the earlier bug: the empty track was drawn
 * brighter than the buffered span, so "downloaded" and "not downloaded" read the
 * same. Now empty < buffered < played.
 */
object SeekTrack {
    /** nothing downloaded here yet */
    val inactive: Color = Color.White.copy(alpha = 0.20f)
    /** downloaded and playable, but not reached yet */
    val buffered: Color = Color.White.copy(alpha = 0.32f)
    /** the thumb colour */
    val thumb: Color = Color.White
}

// The official track's own geometry, needed only to line the buffered band up with
// it. From `tokens/SliderTokens.kt`: the track leaves
// `handleWidth / 2 + handleLeadingSpace` on each side of the handle.
private val ThumbWidth = 4.dp
private val ThumbTrackGapSize = 6.dp
/** `SliderTokens.TrackInsideCornerSize` — the near-square end facing the gap */
private val TrackInsideCornerSize = 2.dp

/**
 * Seek bar that shows a buffered span on top of the **official** material3 track.
 *
 * The track itself is drawn entirely by `SliderDefaults.Track` — the earlier
 * hand-written copy of the drawing algorithm is gone, so none of the style is ours
 * to get wrong. The only addition is a slightly brighter band over the part of the
 * *inactive* track that the player has already downloaded.
 *
 * That band is the whole feature: with it "buffered ahead of the playhead" is
 * visible; without it the inactive track looks the same whether the next seconds
 * are already local or still on the network. It is deliberately subtle — it must
 * not compete with the played segment, and it must not change how the official
 * track looks.
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
    /** 拖动这条进度条时的触感反馈（拖动中按比例给"换挡"反馈，用户要求） */
    haptics: Haptics? = null,
    playedColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = SeekTrack.inactive,
    bufferedColor: Color = SeekTrack.buffered,
    thumbColor: Color = SeekTrack.thumb
) {
    // This material3 version's custom-track overload takes a SliderState rather
    // than a value, and SliderState no longer carries onValueChangeFinished (it is
    // a parameter of Slider). Both were established from compiler errors, not from
    // the cached sources, which belong to a different version.
    val state = remember { SliderState(value = value.coerceIn(0f, 1f)) }
    LaunchedEffect(value) {
        val v = value.coerceIn(0f, 1f)
        if (state.value != v) state.value = v
    }

    val colors = SliderDefaults.colors(
        thumbColor = thumbColor,
        activeTrackColor = playedColor,
        inactiveTrackColor = inactiveColor
    )
    // 拖动时的触感：按 5% 一档节流，不然一次拖动会连发几十次
    val lastNotch = remember { java.util.concurrent.atomic.AtomicInteger(-1) }
    LaunchedEffect(Unit) { lastNotch.set(-1) }

    Slider(
        state = state,
        onValueChange = {
            haptics?.let { h ->
                val notch = (it.coerceIn(0f, 1f) * 20).toInt()
                if (notch != lastNotch.getAndSet(notch)) h.segment()
            }
            onValueChange(it)
        },
        onValueChangeFinished = {
            lastNotch.set(-1)
            onValueChangeFinished()
        },
        modifier = modifier,
        colors = colors,
        track = { sliderState ->
            Box {
                // unmodified official track
                SliderDefaults.Track(sliderState = sliderState, colors = colors)
                val played = sliderState.value.coerceIn(0f, 1f)
                val bufferedEnd = buffered.coerceIn(played, 1f)
                if (bufferedEnd > played) {
                    Canvas(Modifier.matchParentSize()) {
                        drawBufferedBand(played = played, bufferedEnd = bufferedEnd, color = bufferedColor)
                    }
                }
            }
        }
    )
}

/**
 * Paints the buffered span over the official inactive track.
 *
 * Lines up with the official geometry: it starts one handle-gap past the playhead
 * (the official track leaves that gap either side of the handle) and ends at the
 * downloaded position. Corners follow the official inactive track — near-square
 * where it meets the handle gap, fully rounded at the far end — so the band reads
 * as part of that track rather than as a separate shape.
 */
private fun DrawScope.drawBufferedBand(played: Float, bufferedEnd: Float, color: Color) {
    val gap = ThumbWidth.toPx() / 2 + ThumbTrackGapSize.toPx()
    val start = size.width * played + gap
    val end = min(size.width * bufferedEnd, size.width)
    if (end <= start) return

    val outer = CornerRadius(size.height / 2f, size.height / 2f)
    val inside = CornerRadius(TrackInsideCornerSize.toPx(), TrackInsideCornerSize.toPx())
    val band =
        RoundRect(
            rect = Rect(Offset(start, 0f), Size(end - start, size.height)),
            topLeft = inside,
            bottomLeft = inside,
            topRight = outer,
            bottomRight = outer
        )
    val path = Path()
    path.addRoundRect(band)
    drawPath(path, color)
    path.rewind()
}
