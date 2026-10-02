package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.data.NoteImage
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * Swipeable multi-image viewer for photo posts.
 *
 * The container takes the first image's aspect ratio (from the backend's
 * `image_size`) so the pager height stays stable; every page renders with
 * [ContentScale.Fit] so nothing is ever stretched or cropped. A "n/N" counter
 * appears when there is more than one image.
 *
 * [maxHeight] caps how tall the gallery may become. Without it a tall portrait
 * image (9:16) filled ~80% of the screen and pushed the title, author and actions
 * off the first screen — the same problem the windowed video player already
 * solved by clamping to half the screen. Height is computed from the real width
 * and the image's own ratio, then clamped, so the box is exactly as tall as the
 * image needs until that cap kicks in.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ImageGallery(
    images: List<NoteImage>,
    modifier: Modifier = Modifier,
    /** null = no cap (grids and other callers that manage their own size) */
    maxHeight: Dp? = null,
    /** tapping an image opens the full-screen viewer at that page */
    onOpen: ((Int) -> Unit)? = null
) {
    // rememberPagerState must be called unconditionally: hoisting it above the
    // size checks keeps the slot count stable when the image list is swapped.
    val pagerState = rememberPagerState(pageCount = { images.size })

    if (images.isEmpty()) return

    val containerRatio = images.first().ratio.takeIf { it > 0f } ?: NoteImage.DEFAULT_RATIO

    // Resolve the cap once; `null` means "use the natural ratio height".
    val config = LocalConfiguration.current
    val cappedHeight: Dp? = maxHeight?.let { cap ->
        val natural = (config.screenWidthDp / containerRatio).dp
        if (natural > cap) cap else natural
    }

    if (images.size == 1) {
        XhsAsyncImage(
            url = images[0].url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // tapping opens the full-screen viewer; without it the image stays at
            // this reduced size with no way to see the detail
            modifier = modifier.fillMaxWidth()
                .then(if (cappedHeight != null) Modifier.height(cappedHeight) else Modifier.aspectRatio(containerRatio))
                .pointerInput(images[0].url) {
                    detectTapGestures(onTap = { onOpen?.invoke(0) })
                }
        )
        return
    }

    Box(
        modifier.fillMaxWidth()
            .then(if (cappedHeight != null) Modifier.height(cappedHeight) else Modifier.aspectRatio(containerRatio))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            XhsAsyncImage(
                url = images[page].url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
                    .then(if (cappedHeight != null) Modifier.height(cappedHeight) else Modifier.aspectRatio(containerRatio))
                    .pointerInput(images[page].url) {
                        detectTapGestures(onTap = { onOpen?.invoke(page) })
                    }
            )
        }
        // n/N indicator
        Surface(
            shape = Corners.small,
            color = Scrim.chrome,
            contentColor = Scrim.onMedia,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = Spacing.m, bottom = Spacing.m)
        ) {
            Text(
                "${pagerState.currentPage + 1}/${images.size}",
                Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
