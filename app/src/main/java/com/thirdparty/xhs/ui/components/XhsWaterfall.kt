@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons

/**
 * Shared masonry/waterfall grid used by 发现 / 收藏 / 最近浏览 / 搜索结果 / 作者主页.
 *
 * Handles:
 *  - 2-column staggered layout with varied cell heights (natural masonry)
 *  - fee badge on the cover corner
 *  - endless pagination: watches the tail index and calls [onLoadMore]
 *  - a trailing loading row while the next page is in flight
 *  - scroll-to-top when [resetKey] changes (refresh)
 */

/** How close to the tail (in items) triggers the next page. */
private const val LOAD_MORE_THRESHOLD = 4

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
    onLoadMore: (() -> Unit)? = null,
    /** ids currently ticked in multi-select mode */
    selectedIds: Set<Long> = emptySet(),
    /** when true a tap toggles selection instead of opening the note */
    selectionMode: Boolean = false,
    /** long-press handler; enabling it turns on multi-select (null = disabled) */
    onLongPress: ((NoteItem) -> Unit)? = null,
    /**
     * Changing this value scrolls the grid back to the top. Callers pass a
     * counter that increments on refresh — reloading data alone leaves the
     * LazyGrid at its old scroll offset, so the user stays parked mid-list.
     */
    resetKey: Any? = Unit
) {
    val gridState = rememberLazyStaggeredGridState()

    // Defensive: Lazy layouts throw when two items share a key, and the backend's
    // page boundaries are not stable. Callers already de-dup on append; this
    // guarantees the grid can never crash regardless.
    val safeItems = remember(items) { items.distinctBy { it.noteId } }

    // Back to the top when the caller signals a refresh — but ONLY when [resetKey]
    // really moved on since the position now on screen was placed.
    //
    // A LaunchedEffect also re-runs when the composable RE-ENTERS the composition
    // (returning from a detail page) or is REACTIVATED inside a
    // SaveableStateHolder (switching sub-tabs, …). "Skip the very first run" looked
    // like it covered that, but it does not: after a reactivation the flag is still
    // true, so the SECOND run already scrolled the restored grid back to the top —
    // the 发现/粉丝圈 "it forgot where I was" report. Remembering WHICH key the
    // current position belongs to fixes both halves: a reactivation with an
    // unchanged key keeps the position, while a key that changed while the tab was
    // away (a background refresh) still resets — which is exactly what a refresh is
    // supposed to do.
    var positionKey by remember { mutableStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (resetKey != positionKey) {
            positionKey = resetKey
            if (safeItems.isNotEmpty()) gridState.scrollToItem(0)
        }
    }

    // Endless pagination — trigger when the tail becomes visible.
    //
    // The effect is keyed on the item count and the in-flight flag so it is
    // re-evaluated after every load. Relying on the visible index alone meant
    // that a page which added nothing new (the backend re-serves overlapping
    // ids) left the condition unchanged, so no further snapshot emission ever
    // happened and pagination stalled permanently.
    if (onLoadMore != null && hasMore) {
        LaunchedEffect(gridState, safeItems.size, loadingMore) {
            snapshotFlow {
                val info = gridState.layoutInfo
                val total = info.totalItemsCount
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                total > 0 && last >= total - LOAD_MORE_THRESHOLD
            }
                .distinctUntilChanged()
                .collect { nearEnd -> if (nearEnd) onLoadMore() }
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
            count = safeItems.size,
            key = { i -> safeItems[i].noteId }
        ) { index ->
            val note = safeItems[index]
            WaterfallCard(
                item = note,
                selected = selectionMode && note.noteId in selectedIds,
                // In selection mode a plain tap toggles instead of opening, which is
                // what every gallery-style multi-select does.
                onClick = {
                    if (selectionMode) onLongPress?.invoke(note) else onOpenDetail(note.noteId)
                },
                onLongClick = onLongPress?.let { cb -> { cb(note) } }
            )
        }
        if (hasMore && safeItems.isNotEmpty()) {
            item(key = "__loading__") {
                Box(
                    Modifier.fillMaxWidth().padding(Spacing.m),
                    contentAlignment = Alignment.Center
                ) {
                    if (loadingMore) LoadingIndicator(Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * Masonry cell.
 *
 * The cell is sized from the cover's real aspect ratio (`note_cover_size`) so
 * the columns stagger authentically and images are not oddly cropped. The ratio
 * is clamped because a few covers are extreme (e.g. "375*210").
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WaterfallCard(
    item: NoteItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false
) {
    val ratio = item.coverRatio.coerceIn(0.55f, 1.6f)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    ) {
        Column {
            Box {
                XhsAsyncImage(
                    url = item.cover.ifEmpty { item.thumbnail },
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio)
                )
                if (selected) {
                    // Dim + tick, the conventional multi-select affordance.
                    Box(
                        Modifier.matchParentSize()
                            .background(Color.Black.copy(alpha = 0.38f))
                    )
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "已选择",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopStart)
                            .padding(Spacing.xs).size(24.dp)
                    )
                }
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