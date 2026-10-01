package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Shared masonry/waterfall grid used by 发现 / 收藏 / 最近浏览 / 搜索结果 / 作者主页.
 *
 * Handles:
 *  - 2-column staggered layout with varied cell heights (natural masonry)
 *  - fee badge on the cover corner
 *  - endless pagination: watches the tail index and calls [onLoadMore]
 *  - a trailing loading row while the next page is in flight
 */
@Composable
fun XhsWaterfallGrid(
    items: List<NoteItem>,
    onOpenDetail: (Long) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 2,
    contentPadding: PaddingValues = PaddingValues(
        start = Spacing.s, end = Spacing.s, top = Spacing.xs, bottom = Spacing.s
    ),
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: (() -> Unit)? = null
) {
    val gridState = rememberLazyStaggeredGridState()

    // endless pagination — trigger when the tail becomes visible
    if (onLoadMore != null) {
        LaunchedEffect(gridState) {
            snapshotFlow {
                val info = gridState.layoutInfo
                if (info.totalItemsCount == 0) -1
                else info.visibleItemsInfo.lastOrNull()?.index ?: -1
            }
                .distinctUntilChanged()
                .collect { last ->
                    val total = gridState.layoutInfo.totalItemsCount
                    if (total > 0 && last >= total - 4) onLoadMore()
                }
        }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalItemSpacing = Spacing.s,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        items(
            count = items.size,
            key = { i -> items[i].noteId }
        ) { index ->
            WaterfallCard(items[index], onClick = { onOpenDetail(items[index].noteId) })
        }
        if (hasMore && items.isNotEmpty()) {
            item(key = "__loading__") {
                Box(
                    Modifier.fillMaxWidth().padding(Spacing.m),
                    contentAlignment = Alignment.Center
                ) {
                    if (loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * Masonry cell. Height varies by a stable hash of the id so columns stagger
 * naturally (avoids the rigid alternating look).
 */
@Composable
fun WaterfallCard(item: NoteItem, onClick: () -> Unit) {
    val heights = intArrayOf(150, 180, 210, 165, 195)
    val cellHeight = heights[(item.noteId.hashCode() and 0x7FFFFFFF) % heights.size]
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box {
                XhsAsyncImage(
                    url = item.cover.ifEmpty { item.thumbnail },
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth().height(cellHeight.dp)
                )
                FeeBadge(item, compact = true, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs))
            }
            Text(
                item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs)
            )
            Text(
                "@${item.userName} · ♥${item.likeCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.s, end = Spacing.s, bottom = Spacing.s)
            )
        }
    }
}