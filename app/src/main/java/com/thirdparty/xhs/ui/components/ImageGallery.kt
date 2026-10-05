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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /**
     * Page to show. Hoisted so the full-screen viewer and this gallery can share ONE
     * index: swiping in the viewer then closing it used to drop the user back on the
     * page the gallery was left at, and the two counters disagreed.
     */
    page: Int = 0,
    /** reports swipes here, so the caller can feed the viewer the same index */
    onPageChange: ((Int) -> Unit)? = null,
    /** tapping an image opens the full-screen viewer at that page */
    onOpen: ((Int) -> Unit)? = null
) {
    // 图文的触感都在这里给：翻页 = segment，点开大图 = tick。
    // 放在组件内部而不是调用方，是为了"任何地方用到这个画廊都一致"（用户反馈详情页图文没触感）。
    val haptics = rememberHaptics()
    // rememberPagerState must be called unconditionally: hoisting it above the
    // size checks keeps the slot count stable when the image list is swapped.
    val pagerState = rememberPagerState(
        initialPage = page.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
        pageCount = { images.size }
    )

    // follow the shared index when it changes from the outside (the viewer swiped, or
    // the page came back from a restore)
    LaunchedEffect(page, images.size) {
        val target = page.coerceIn(0, (images.size - 1).coerceAtLeast(0))
        if (images.isNotEmpty() && pagerState.currentPage != target) pagerState.scrollToPage(target)
    }
    // ...and report our own swipes back out
    LaunchedEffect(pagerState, images.size) {
        var first = true
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect {
                // 首帧不算"翻页"（那是恢复现场），只有真的换页才给反馈
                if (first) first = false else haptics.segment()
                onPageChange?.invoke(it)
            }
    }

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
                    detectTapGestures(onTap = {
                        haptics.tick()
                        onOpen?.invoke(0)
                    })
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
                        detectTapGestures(onTap = {
                            haptics.tick()
                            onOpen?.invoke(page)
                        })
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
