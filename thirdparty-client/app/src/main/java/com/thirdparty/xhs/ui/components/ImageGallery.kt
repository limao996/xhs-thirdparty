package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * Swipeable multi-image viewer for photo posts.
 *
 * Images are shown with [ContentScale.Fit] inside a fixed-ratio box so they are
 * never stretched or cropped; a "n/N" counter appears when there is more than
 * one image.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ImageGallery(
    images: List<String>,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 3f / 4f
) {
    if (images.isEmpty()) return

    if (images.size == 1) {
        XhsAsyncImage(
            url = images[0],
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxWidth().aspectRatio(aspectRatio)
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { images.size })
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            XhsAsyncImage(
                url = images[page],
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)
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