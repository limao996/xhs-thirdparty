package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.ui.theme.Scrim

/**
 * Seek bar that also shows **how much is buffered**.
 *
 * Material's Slider draws one played segment and one inactive segment, so a
 * stream stalled on a slow link looks identical to a video that simply has not
 * been watched that far — the user cannot tell "waiting for network" from "not
 * watched yet", which is exactly what a buffering bar should answer.
 *
 * Drawn by hand rather than through `Slider(track = …)`: the custom-track
 * overload's parameter set varies between material3 versions, and this needs
 * three segments (inactive / buffered / played) with the geometry under our own
 * control. Tap-to-seek and horizontal drag are handled here.
 */
@Composable
fun BufferedSlider(
    /** 0..1 playhead */
    value: Float,
    /** 0..1 of the whole video the player already holds */
    buffered: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = Scrim.onMediaVariant
    // a distinct tone from both neighbours: lighter than the played segment, more
    // solid than the empty track, so it reads as "held, not played"
    val bufferedColor = Scrim.onMedia.copy(alpha = 0.55f)
    val thumbColor = Scrim.onMedia

    var widthPx by remember { mutableIntStateOf(1) }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onValueChange((offset.x / widthPx).coerceIn(0f, 1f))
                    onValueChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onValueChange((offset.x / widthPx).coerceIn(0f, 1f))
                    },
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() }
                ) { change, _ ->
                    onValueChange((change.position.x / widthPx).coerceIn(0f, 1f))
                    change.consume()
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val barHeight = 6.dp.toPx()
            val cy = size.height / 2f
            val top = cy - barHeight / 2f
            val radius = CornerRadius(barHeight / 2f, barHeight / 2f)
            val played = value.coerceIn(0f, 1f)
            val held = buffered.coerceIn(played, 1f)

            fun segment(fraction: Float, color: Color) {
                if (fraction <= 0f) return
                drawRoundRect(
                    color = color,
                    topLeft = Offset(0f, top),
                    // never shorter than the bar is tall, or a tiny segment renders
                    // as a squashed blob instead of a rounded end
                    size = Size(
                        width = (size.width * fraction).coerceAtLeast(barHeight),
                        height = barHeight
                    ),
                    cornerRadius = radius
                )
            }

            segment(1f, inactiveColor)
            segment(held, bufferedColor)
            segment(played, activeColor)

            // thumb
            val thumbR = 7.dp.toPx()
            drawCircle(
                color = thumbColor,
                radius = thumbR,
                center = Offset(
                    x = (size.width * played).coerceIn(thumbR, size.width - thumbR),
                    y = cy
                )
            )
        }
    }
}
