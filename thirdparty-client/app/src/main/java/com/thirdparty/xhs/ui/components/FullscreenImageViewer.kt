package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * Full-screen image viewer.
 *
 * The windowed gallery scales images down to a share of the screen, so fine
 * detail is unreadable. Tapping an image opens this: black background, paging
 * across the whole set from the tapped page, pinch-zoom up to 5x, and pan while
 * zoomed. In the examined client this is `BigPictureViewActivity`.
 *
 * [NoteImage] carries the set's own ratio, so the windowed layout stays stable;
 * here everything is fit and letterboxed instead, which is what a viewer wants.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullscreenImageViewer(
    images: List<NoteImage>,
    initialPage: Int,
    onDismiss: () -> Unit
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

    androidx.activity.compose.BackHandler { onDismiss() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            // while zoomed the drag belongs to panning, not to paging
            userScrollEnabled = scale <= 1.01f,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val active = page == pagerState.currentPage
            Box(
                Modifier.fillMaxSize().pointerInput(page) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
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

        // n/N — top centre so it never fights the close button
        Surface(
            shape = Corners.small,
            color = Scrim.chrome,
            contentColor = Scrim.onMedia,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()
                .padding(top = Spacing.s)
        ) {
            Text(
                "${pagerState.currentPage + 1}/${images.size}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)
            )
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(Spacing.s)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Scrim.onMedia)
        }
    }
}

/** How far a pinch may zoom in. */
private const val MAX_ZOOM = 5f
