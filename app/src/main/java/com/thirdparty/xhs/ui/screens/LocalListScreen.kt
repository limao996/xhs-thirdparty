@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thirdparty.xhs.data.SavedSort
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.XhsWaterfallGrid
import com.thirdparty.xhs.ui.components.rememberNoteActions
import com.thirdparty.xhs.ui.components.rememberNoteFlags
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.LocalListViewModel
import androidx.compose.material.icons.automirrored.filled.ArrowBack

/**
 * 收藏 / 最近浏览 —— 瀑布流（2 列 Masonry）+ 客户端「加载更多」。
 * 纯本地 Room 数据。
 *
 * 收藏支持**长按进入多选**批量取消（最近浏览不提供：它没有"取消浏览"的语义）。
 *
 * Selection is HOISTED to the caller (see [selected] / [onSelectionChange]) so the
 * hosting Scaffold can swap its own TopAppBar for a contextual selection bar —
 * that is the Material 3 pattern, and it avoids stacking two bars on top of each
 * other. This composable stays stateless about the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalListScreen(
    mode: LocalListViewModel.Mode,
    onOpenDetail: (Long) -> Unit,
    viewModel: LocalListViewModel,
    selected: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    // （多选栏由 AppNavHost 自己画，这里不再暴露 onRequestSelectAll / onRequestDelete：
    //  没人传、也没人用）
    /** false when the host renders its own app bar (then we render nothing) */
    showOwnAppBar: Boolean = false,
    title: String = "",
    onBack: () -> Unit = {}
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(mode) { viewModel.reload() }

    val selecting = selected.isNotEmpty()
    val canSelect = mode == LocalListViewModel.Mode.SAVED
    // 排序方式的选择框（用原生 AlertDialog，不用下拉菜单：项目硬约束 16）
    var showSortDialog by remember { mutableStateOf(false) }

    // back gesture leaves selection mode rather than the screen
    BackHandler(enabled = selecting) { onSelectionChange(emptySet()) }
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()

    Column(Modifier.fillMaxSize()) {
        if (showOwnAppBar) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = haptics.click(onBack)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }

        // 收藏页：搜索（标题 / 作者名）+ 排序。最近浏览不加（它是"看过什么"的时间流水，
        // 用户这次要的是收藏页；真要加也是同一套实现，复制下面这行即可）。
        // 一条收藏都没有时整行隐藏 —— 搜索/排序空的列表没有意义，留着反而把空状态挤下去。
        if (canSelect && state.all.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = Spacing.s, end = Spacing.s, top = Spacing.s
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SearchBar(
                    query = state.query,
                    onQueryChange = { viewModel.onQueryChange(it) },
                    onSearch = { haptics.tick() },
                    active = false,
                    onActiveChange = {},
                    placeholder = { Text("搜索收藏（标题 / 作者）") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "搜索") },
                    // 收藏页是本地数据，"搜索"随输入即时生效，所以这里只需要一个清除按钮
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = {
                                haptics.tick()
                                viewModel.onQueryChange("")
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { }
                IconButton(
                    onClick = { haptics.tick(); showSortDialog = true },
                    modifier = Modifier.padding(start = Spacing.xs)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                }
            }
        }

        when {
            state.loading && state.all.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }

            state.all.isEmpty() ->
                EmptyState(
                    title = if (mode == LocalListViewModel.Mode.SAVED) "还没有收藏" else "还没有浏览记录",
                    modifier = Modifier.fillMaxSize(),
                    description = if (mode == LocalListViewModel.Mode.SAVED)
                        "在详情页点击右上角的心形即可收藏；长按可批量取消"
                    else "看过的内容会自动出现在这里",
                    icon = if (mode == LocalListViewModel.Mode.SAVED) Icons.Filled.FavoriteBorder
                    else Icons.Filled.History
                )

            // 有收藏、但当前搜索词一条都没匹配上 —— 不能落回"还没有收藏"，
            // 否则用户会以为收藏被清空了（文案约定 27：说清发生了什么、能怎么办）
            state.resultCount == 0 ->
                EmptyState(
                    title = "没有匹配的收藏",
                    modifier = Modifier.fillMaxSize(),
                    description = "标题或作者名里包含「${state.query.trim()}」的收藏没有找到",
                    icon = Icons.Filled.SearchOff,
                    actionLabel = "清除搜索",
                    onAction = {
                        haptics.tick()
                        viewModel.onQueryChange("")
                    }
                )

            else ->
                // No pull-to-refresh: this list is local and reloads on entry, so a
                // drag could only misfire.
                XhsWaterfallGrid(
                    items = state.visible,
                    onOpenDetail = onOpenDetail,
                    flags = rememberNoteFlags(),
                    actions = rememberNoteActions(),
                    contentPadding = PaddingValues(
                        start = Spacing.s, end = Spacing.s, top = Spacing.s, bottom = Spacing.l
                    ),
                    hasMore = state.hasMore,
                    resetKey = state.refreshTick,
                    onLoadMore = { viewModel.loadMore() },
                    selectedIds = selected,
                    selectionMode = selecting,
                    // 长按出菜单（收藏/稍后观看/多选），菜单里的「多选」进多选；
                    // 多选模式下单击仍然是「选中/取消选中」。
                    onEnterSelection = if (canSelect) { item ->
                        onSelectionChange(
                            if (item.noteId in selected) selected - item.noteId
                            else selected + item.noteId
                        )
                    } else null,
                    onLongPress = if (canSelect) { item ->
                        onSelectionChange(
                            if (item.noteId in selected) selected - item.noteId
                            else selected + item.noteId
                        )
                    } else null
                )
        }
    }

    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = { Text("排序方式") },
            text = {
                Column {
                    SavedSort.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptics.tick()
                                    viewModel.onSortChange(option)
                                    showSortDialog = false
                                }
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            RadioButton(
                                selected = state.sort == option,
                                onClick = {
                                    haptics.tick()
                                    viewModel.onSortChange(option)
                                    showSortDialog = false
                                }
                            )
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { haptics.click { showSortDialog = false } }) {
                    Text("取消")
                }
            }
        )
    }
}