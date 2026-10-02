package com.thirdparty.xhs.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.thirdparty.xhs.data.NoteImage
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Full-screen image viewer.
 *
 * The windowed gallery scales images down to a share of the screen, so fine
 * detail is unreadable. Tapping an image opens this: paging across the whole set
 * from the tapped page, pinch-zoom up to 5x, and pan while zoomed. In the
 * examined client this is `BigPictureViewActivity`.
 *
 * Laid out as a **Column** — a fixed top bar plus the pager taking the rest —
 * rather than a Box with the pager filling it and the chrome positioned by an
 * alignment on top. As Box children the counter and close button never appeared
 * on screen; a real layout slot removes that whole class of uncertainty.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenImageViewer(
    images: List<NoteImage>,
    initialPage: Int,
    onDismiss: () -> Unit,
    /**
     * Applied to the root. Callers rendering this inside a Scaffold must pass the
     * content padding: without it the viewer starts at y=0 and its top bar ends up
     * BEHIND the page's own app bar — which is exactly why the counter and close
     * button were invisible even though the viewer itself rendered fine.
     */
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, images.lastIndex)
    ) { images.size }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // zoom is per-page: carrying it across a swipe would leave the next image
    // mysteriously cropped
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    BackHandler { onDismiss() }

    // Volume keys page through the set while the viewer is up. Registered on
    // entry and cleared on exit, so the keys are untouched everywhere else.
    val keyScope = rememberCoroutineScope()
    DisposableEffect(pagerState, images.size) {
        ImageViewerKeys.register { step ->
            val target = (pagerState.currentPage + step).coerceIn(0, images.lastIndex)
            if (target != pagerState.currentPage) {
                keyScope.launch { pagerState.animateScrollToPage(target) }
            }
        }
        onDispose { ImageViewerKeys.unregister() }
    }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().background(Scrim.chrome)
                .padding(horizontal = Spacing.s, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${pagerState.currentPage + 1}/${images.size}",
                color = Scrim.onMedia,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(start = Spacing.s)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Scrim.onMedia)
            }
        }

        HorizontalPager(
            state = pagerState,
            // while zoomed the drag belongs to panning, not to paging
            userScrollEnabled = scale <= 1.01f,
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) { page ->
            val active = page == pagerState.currentPage
            Box(
                Modifier.fillMaxSize().pointerInput(page) {
                    // NOT detectTransformGestures: that consumes every drag, so the
                    // pager never saw a swipe and horizontal paging was dead. Take
                    // the gesture only for a pinch, or to pan an image that is
                    // already zoomed; otherwise leave the drag to the pager.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pinch = event.calculateZoom()
                            val pan = event.calculatePan()
                            val multiTouch = event.changes.count { it.pressed } > 1
                            if (multiTouch || scale > 1f) {
                                scale = (scale * pinch).coerceIn(1f, MAX_ZOOM)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                XhsAsyncImage(
                    url = images[page].url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = if (active) scale else 1f,
                        scaleY = if (active) scale else 1f,
                        translationX = if (active) offset.x else 0f,
                        translationY = if (active) offset.y else 0f
                    )
                )
            }
        }
    }
}

/** How far a pinch may zoom in. */
private const val MAX_ZOOM = 5f