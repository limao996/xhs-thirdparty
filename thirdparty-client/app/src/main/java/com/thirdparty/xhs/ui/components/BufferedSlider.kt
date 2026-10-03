/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thirdparty.xhs.ui.components

// ============================================================================
// Adapted from androidx.compose.material3 `Slider.kt` (Apache-2.0, notice kept
// above) and `tokens/SliderTokens.kt`.
//
// Why vendored: material3's slider cannot show a buffered span. Its public
// `SliderDefaults.Track` has no `buffer` parameter, and the overload that does is
// `internal`. Earlier revisions approximated the M3 Expressive track by eye and
// got it wrong twice (16dp vs thin inactive, wrong corner treatment), so the
// drawing algorithm is copied here **line for line** instead and the buffered
// sub-track is inserted into it.
//
// What is verbatim from AOSP:
//   - `drawTrackPath` (unmodified)
//   - the body of `drawTrack` for the horizontal determinate case, including the
//     `sliderValueEnd` / `sliderValueStart` maths, `startGap` / `endGap`, the
//     per-end corner radii and both stop-indicator offsets
//   - the geometry tokens below
//
// What is ours:
//   - the `buffered` parameter and the buffered sub-track between the active and
//     the inactive track (marked `[buffer]` at each site)
//   - replacing the two internal accessors the original relies on
//     (`SliderColors.trackColor(...)` -> the public `activeTrackColor` /
//     `inactiveTrackColor`; `SliderState.tickFractions` -> dropped, this slider has
//     no steps) — those are listed at the point of substitution
// ============================================================================

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

// ---- geometry, verbatim from tokens/SliderTokens.kt -------------------------
/** SliderTokens.InactiveTrackHeight (== ActiveTrackHeight) */
private val TrackHeight = 16.dp
/** SliderTokens.ActiveHandleWidth / HandleWidth */
private val ThumbWidth = 4.dp
/** SliderTokens.ActiveHandleLeadingSpace — the gap between handle and track */
private val ThumbTrackGapSize = 6.dp
/** SliderTokens.TrackInsideCornerSize */
private val TrackInsideCornerSize = 2.dp
/** SliderTokens.TrackStopIndicatorSize */
private val TrackStopIndicatorSize = 4.dp

/**
 * The three steps of a seek track, **dimmest first**.
 *
 * Ordered by emphasis, which is the bug that was fixed here: the empty track used
 * to be drawn brighter than the buffered span, so "downloaded" and "not
 * downloaded" looked the same.
 */
object SeekTrack {
    /** nothing downloaded here yet */
    val inactive: Color = Color.White.copy(alpha = 0.20f)
    /** downloaded and playable, but not reached yet */
    val buffered: Color = Color.White.copy(alpha = 0.42f)
    /** the thumb colour */
    val thumb: Color = Color.White
}

/**
 * Seek bar with a **buffered sub-track**, built on the official [Slider].
 *
 * The component (thumb, drag/tap gestures, ripple, focus, accessibility) is
 * material3's own. Only the track is drawn here, by the vendored copy of the
 * official algorithm above plus the buffered segment.
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
    // This material3 version's custom-track overload takes a SliderState rather
    // than a value, and SliderState no longer carries onValueChangeFinished (it is
    // a parameter of Slider). Both confirmed from compiler errors rather than from
    // the sources cached locally, which are a different version.
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

    Slider(
        state = state,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier,
        colors = colors,
        track = { sliderState ->
            TrackImpl(
                sliderState = sliderState,
                buffered = buffered,
                trackCornerSize = Dp.Unspecified,
                colors = colors,
                bufferedTrackColor = bufferedColor
            )
        }
    )
}

/**
 * Verbatim `TrackImpl(sliderState, …)` from AOSP, narrowed to the horizontal
 * determinate case and carrying the buffered fraction through to [drawTrack].
 *
 * Removed relative to the original (each unused here, not simplified by accident):
 * the vertical branch, `LocalRippleThemeConfiguration` focus-ring padding and the
 * tick drawing — this slider is horizontal, has no steps and no inset focus ring.
 */
@Composable
private fun TrackImpl(
    sliderState: SliderState,
    buffered: Float,
    trackCornerSize: Dp,
    colors: SliderColors,
    bufferedTrackColor: Color
) {
    // substitution: the original calls the internal
    // `colors.trackColor(enabled, active)`; these are the same values exposed
    // publicly.
    val inactiveTrackColor = colors.inactiveTrackColor
    val activeTrackColor = colors.activeTrackColor

    Canvas(
        Modifier.fillMaxWidth().height(TrackHeight).let { it }
    ) {
        val cornerSize =
            if (trackCornerSize == Dp.Unspecified) {
                size.height / 2
            } else {
                trackCornerSize.toPx()
            }
        drawTrack(
            // substitution: `sliderState.tickFractions` — dropped, steps == 0
            tickFractions = EMPTY_TICKS,
            activeRangeStart = 0f,
            activeRangeEnd = sliderState.value.coerceIn(0f, 1f),
            bufferedRangeEnd = buffered.coerceIn(0f, 1f),
            inactiveTrackColor = inactiveTrackColor,
            activeTrackColor = activeTrackColor,
            bufferedTrackColor = bufferedTrackColor,
            startThumbWidth = sliderState.thumbWidth,
            endThumbWidth = sliderState.thumbWidth,
            thumbTrackGapSize = ThumbTrackGapSize,
            trackInsideCornerSize = TrackInsideCornerSize,
            trackCornerSize = cornerSize.toDp(),
            drawStopIndicator = { offset ->
                drawStopIndicator(
                    offset = offset,
                    color = activeTrackColor,
                    size = TrackStopIndicatorSize
                )
            },
            isRangeSlider = false,
            enableCornerShrinking = true,
            isCentered = false
        )
    }
}

/** `SliderState.tickFractions` for a slider without steps. */
private val EMPTY_TICKS = FloatArray(0)

/** `SliderState.thumbWidth` (4dp in the default token set). */
private val SliderState.thumbWidth: Dp get() = ThumbWidth

/**
 * Verbatim `DrawScope.drawTrack` from AOSP for the horizontal determinate case.
 *
 * Lines marked `[buffer]` are the addition. Everything else — including the
 * `sliderValueEnd` maths, `startGap`/`endGap`, the per-end corner radii and both
 * stop-indicator offsets — is the original.
 */
private fun DrawScope.drawTrack(
    tickFractions: FloatArray,
    activeRangeStart: Float,
    activeRangeEnd: Float,
    /** [buffer] how much of the work the player already holds, 0..1 */
    bufferedRangeEnd: Float,
    inactiveTrackColor: Color,
    activeTrackColor: Color,
    /** [buffer] colour of the buffered sub-track */
    bufferedTrackColor: Color,
    startThumbWidth: Dp,
    endThumbWidth: Dp,
    thumbTrackGapSize: Dp,
    trackInsideCornerSize: Dp,
    trackCornerSize: Dp,
    drawStopIndicator: (DrawScope.(Offset) -> Unit)?,
    isRangeSlider: Boolean,
    enableCornerShrinking: Boolean = false,
    isCentered: Boolean = false
) {
    // ---- verbatim ----
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val isRtlHorizontal = isRtl
    val cornerSize = trackCornerSize.toPx()
    val sliderStart = 0f
    val sliderEnd = size.width

    val isStartOnFirstOrLastStep =
        activeRangeStart == tickFractions.firstOrNull() ||
            activeRangeStart == tickFractions.lastOrNull()
    val isEndOnFirstOrLastStep =
        activeRangeEnd == tickFractions.firstOrNull() ||
            activeRangeEnd == tickFractions.lastOrNull()
    val sliderValueEnd =
        if (tickFractions.isNotEmpty() && !isEndOnFirstOrLastStep) {
            sliderStart + (sliderEnd - sliderStart - cornerSize * 2) * activeRangeEnd + cornerSize
        } else {
            sliderStart + (sliderEnd - sliderStart) * activeRangeEnd
        }
    val sliderValueStart =
        if (tickFractions.isNotEmpty() && !isStartOnFirstOrLastStep) {
            sliderStart + (sliderEnd - sliderStart - cornerSize * 2) * activeRangeStart + cornerSize
        } else {
            sliderStart + (sliderEnd - sliderStart) * activeRangeStart
        }

    val insideCornerSize = trackInsideCornerSize.toPx()
    var startGap = 0f
    var endGap = 0f
    if (thumbTrackGapSize > 0.dp) {
        startGap = startThumbWidth.toPx() / 2 + thumbTrackGapSize.toPx()
        endGap = endThumbWidth.toPx() / 2 + thumbTrackGapSize.toPx()
    }
    val centerAxis = center.x

    // ---- active track: verbatim ----
    val activeStartCornerRadius = if (isRtlHorizontal) insideCornerSize else cornerSize
    val activeEndCornerRadius = if (isRtlHorizontal) cornerSize else insideCornerSize
    val activeStart = sliderValueStart
    val activeEnd = sliderValueEnd - startGap
    if (activeEnd > activeStart) {
        val trackOffset =
            if (isRtlHorizontal) {
                Offset(size.width - activeEnd, 0f)
            } else {
                Offset(0f, 0f)
            }
        val trackSize = Size(activeEnd - activeStart, size.height)
        drawTrackPath(
            trackOffset,
            trackSize,
            activeTrackColor,
            activeStartCornerRadius,
            activeEndCornerRadius
        )
        val stopIndicatorOffset =
            if (isRtl) {
                Offset(size.width - activeStart - cornerSize, center.y)
            } else {
                Offset(activeStart + cornerSize, center.y)
            }
        drawStopIndicator?.invoke(this, stopIndicatorOffset)
    }

    // ---- [buffer] buffered sub-track ------------------------------
    // In the original, the whole span from `sliderValueEnd + endGap` to
    // `sliderEnd` is the inactive track. It is split here: the part the player
    // already holds is drawn in `bufferedTrackColor`, and only the remainder keeps
    // the inactive colour. Corner radii follow the original's inactive track —
    // near-square against the handle gap, fully rounded at the far end.
    val bufferedEnd = sliderStart + (sliderEnd - sliderStart) * bufferedRangeEnd
    val bufferedStart = sliderValueEnd + endGap
    val bufferedVisibleEnd = min(bufferedEnd, sliderEnd)
    if (bufferedVisibleEnd > bufferedStart) {
        drawTrackPath(
            Offset(bufferedStart, 0f),
            Size(bufferedVisibleEnd - bufferedStart, size.height),
            bufferedTrackColor,
            insideCornerSize,
            cornerSize
        )
    }

    // ---- inactive track: verbatim, but starting after the buffered span ----
    val inactiveStart = max(bufferedVisibleEnd, bufferedStart)
    val inactiveEnd = sliderEnd
    if (inactiveEnd > inactiveStart) {
        drawTrackPath(
            Offset(inactiveStart, 0f),
            Size(inactiveEnd - inactiveStart, size.height),
            inactiveTrackColor,
            insideCornerSize,
            cornerSize
        )
        val stopIndicatorOffset =
            if (isRtl) {
                Offset(cornerSize, center.y)
            } else {
                Offset(inactiveEnd - cornerSize, center.y)
            }
        drawStopIndicator?.invoke(this, stopIndicatorOffset)
    }
}

/**
 * Verbatim `DrawScope.drawTrackPath` from AOSP, with the original's `Orientation`
 * parameter and vertical branch dropped: this slider is always horizontal.
 *
 * The `trackPath` scratch field is a plain local here instead of a reused field —
 * one allocation per frame is cheaper than the state the original threads around,
 * and the drawing is identical.
 */
private fun DrawScope.drawTrackPath(
    offset: Offset,
    size: Size,
    color: Color,
    startCornerRadius: Float,
    endCornerRadius: Float
) {
    val startCorner = CornerRadius(startCornerRadius, startCornerRadius)
    val endCorner = CornerRadius(endCornerRadius, endCornerRadius)
    val track =
        RoundRect(
            rect = Rect(offset, size = Size(size.width, size.height)),
            topLeft = startCorner,
            topRight = endCorner,
            bottomRight = endCorner,
            bottomLeft = startCorner
        )
    val trackPath = Path()
    trackPath.addRoundRect(track)
    drawPath(trackPath, color)
    trackPath.rewind()
}

/**
 * Verbatim default stop indicator from AOSP `SliderDefaults`.
 *
 * @param offset the coordinate where the indicator is to be drawn.
 * @param size the size of the indicator.
 * @param color the color of the indicator.
 */
private fun DrawScope.drawStopIndicator(offset: Offset, color: Color, size: Dp) {
    val radius = size.toPx() / 2
    drawCircle(color = color, radius = radius, center = offset)
}