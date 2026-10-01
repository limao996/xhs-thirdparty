package com.thirdparty.xhs.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Minimal pull-to-refresh for scrollable content.
 *
 * Only reacts to downward drags the child could not consume (i.e. it is already
 * at the top), so it never interferes with normal scrolling. Deliberately not
 * used for the vertical short-video feed, where a downward drag means "previous
 * video".
 *
 * Requires the active Material3 `material3` 1.2 API surface (no
 * `PullToRefreshBox`), hence the hand-rolled implementation.
 */
@Composable
fun PullToRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val threshold = with(LocalDensity.current) { PullThreshold.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }

    // smooth the raw drag distance
    val animated by animateFloatAsState(targetValue = pull, label = "pull")

    val connection = remember(enabled, threshold, refreshing) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // dragging back up retracts the indicator first
                if (available.y < 0f && pull > 0f) {
                    val consumed = available.y.coerceAtLeast(-pull)
                    pull += consumed
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!enabled || refreshing) return Offset.Zero
                // available.y > 0 means the list could not scroll any further up
                if (available.y > 0f) {
                    val next = (pull + available.y * DRAG_DAMPING).coerceAtMost(threshold * 1.4f)
                    pull = next
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (enabled && !refreshing && pull >= threshold) {
                    pull = threshold
                    onRefresh()
                } else {
                    pull = 0f
                }
                return Velocity.Zero
            }
        }
    }

    // collapse once the refresh finishes
    if (!refreshing && pull >= threshold) pull = 0f

    Box(modifier.nestedScroll(connection)) {
        // indicator sits at the top; the content below is pushed down to reveal it
        Box(
            Modifier.fillMaxWidth().height(PullThreshold),
            contentAlignment = Alignment.Center
        ) {
            if (animated > 1f) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, animated.roundToInt()) }
        ) {
            content()
        }
    }
}

/** Drag distance needed to trigger a refresh. */
private val PullThreshold = 64.dp

/** The indicator follows the finger at a fraction of the drag for a natural feel. */
private const val DRAG_DAMPING = 0.5f
