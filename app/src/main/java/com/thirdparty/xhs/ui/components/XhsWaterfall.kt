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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
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
 *  - scroll position saved against [resetKey]: a new key starts at the top,
 *    the same key keeps where the user was
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
     * 长按弹窗菜单的两个动作（收藏 / 稍后观看）。
     *
     * 给了它就长按弹菜单；没给就退回 [onLongPress] 的老行为（多选页面的入口）。
     * 两个都给的话：长按出菜单，菜单里的「多选」再进多选。
     */
    actions: NoteActions? = null,
    /** 菜单要显示「收藏」还是「取消收藏」等，取决于这两个 id 集合 */
    flags: NoteFlags = NoteFlags(),
    /** 菜单里的「多选」入口（收藏 / 最近浏览这类有多选模式的页面） */
    onEnterSelection: ((NoteItem) -> Unit)? = null,
    /**
     * Identity of the list this grid is showing. The scroll position is saved
     * against it: a changed key starts at the top, the same key keeps the user's
     * place (sub-tab switch, returning from a detail page). It must be unique per
     * distinct list — see `DiscoverUiState.feedEpoch` for why a category id alone
     * is not enough in the 发现 pager.
     */
    resetKey: Any? = Unit
) {
    // Scoping the state to [resetKey] is what makes "another list" mean "another
    // position" — `key(...)` moves the composite key hash the saveable registry
    // stores this state under, so a key that moved on cannot restore an old
    // offset, and an unchanged key restores one.
    //
    // Why not `LaunchedEffect(resetKey) { scrollToItem(0) }`: it fires only AFTER
    // the stale position has already been put on screen, and right after a category
    // switch the list is still empty, so the scroll had nothing to act on and the
    // old offset came back with the new data — 发现 pager: swipe to another chip
    // and back landed mid-list of content whose top was never shown. "Skip the
    // first run" flags could not fix that either: a re-entering composition has no
    // memory of the flag.
    val gridState = key(resetKey) { rememberLazyStaggeredGridState() }

    // Defensive: Lazy layouts throw when two items share a key, and the backend's
    // page boundaries are not stable. Callers already de-dup on append; this
    // guarantees the grid can never crash regardless.
    val safeItems = remember(items) { items.distinctBy { it.noteId } }

    // 当前展开了长按菜单的作品（同一时刻最多一个）
    var menuFor by remember { mutableStateOf<NoteItem?>(null) }

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
            // 长按 / 选中这类"模式切换"都给一次系统触感反馈（见 Haptics）
            val haptics = rememberHaptics()
            // 每张卡片外面套一个 Box：长按菜单是以它自己的锚点弹出的（DropdownMenu 用
            // 父节点的坐标定位），所以菜单必须和卡片在同一个 Box 里。
            Box {
                WaterfallCard(
                    item = note,
                    selected = selectionMode && note.noteId in selectedIds,
                    // In selection mode a plain tap toggles instead of opening, which is
                    // what every gallery-style multi-select does.
                    onClick = {
                        if (selectionMode) {
                            haptics.tick()
                            onLongPress?.invoke(note)
                        } else {
                            onOpenDetail(note.noteId)
                        }
                    },
                    onLongClick = {
                        haptics.longPress()
                        if (actions != null) menuFor = note else onLongPress?.let { cb -> cb(note) }
                    }
                )
                if (actions != null && menuFor?.noteId == note.noteId) {
                    NoteActionDialog(
                        title = note.title,
                        saved = note.noteId in flags.savedIds,
                        inWatchLater = note.noteId in flags.watchLaterIds,
                        onToggleSave = { actions.toggleSave(note) },
                        onToggleWatchLater = { actions.toggleWatchLater(note) },
                        onEnterSelection = onEnterSelection?.let { cb -> { cb(note) } },
                        onDismiss = { menuFor = null }
                    )
                }
            }
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