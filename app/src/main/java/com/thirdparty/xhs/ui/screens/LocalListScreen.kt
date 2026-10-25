@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thirdparty.xhs.data.LocalSortField
import com.thirdparty.xhs.data.LocalSortOrder
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
 * 两个页面都带**搜索 + 排序**（用户 2026-10-25 要求）：搜索匹配标题与作者名；
 * 排序维度是时间 / 标题 / 作者 / 点赞量，另有正序 / 倒序切换（逻辑见 `data/LocalListOrder.kt`）。
 *
 * 收藏支持**长按进入多选**批量取消；最近浏览同样支持多选，选中的记录会被删掉
 * （两个页面共用一套多选，动作与文案由宿主页按模式区分）。
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
    // 两个页面都支持多选（用户 2026-10-25 要求"最近浏览也加一套"）：
    // 收藏页的长按/多选删除 = 取消收藏；最近浏览的长按/多选删除 = 删掉这几条浏览记录。
    val canSelect = true
    // 排序维度的选择框（原生 AlertDialog，不用下拉菜单：项目硬约束 16）
    var showSortDialog by remember { mutableStateOf(false) }

    // back gesture leaves selection mode rather than the screen
    BackHandler(enabled = selecting) { onSelectionChange(emptySet()) }
    val haptics = com.thirdparty.xhs.ui.components.rememberHaptics()

    // 时间维度在收藏页叫「收藏时间」、在浏览页叫「浏览时间」；其余维度两个页面一样
    val timeLabel = if (mode == LocalListViewModel.Mode.SAVED) "收藏时间" else "浏览时间"
    val fieldLabel = if (state.sortField == LocalSortField.TIME) timeLabel else state.sortField.label

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

        // 搜索 + 排序工具条。原来用 M3 `SearchBar` 时它自带 56dp 高度与内边距，
        // 和旁边的排序按钮**重心对不齐**（图标看着飘在上面），所以这里换成自绘的紧凑搜索框：
        // 44dp 圆角面 + 单行输入 + 清除按钮，与右侧两个按钮严格垂直居中。
        // 一条数据都没有时整条工具条隐藏 —— 搜索/排序空列表没有意义。
        if (state.all.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.s, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.s),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Box(Modifier.weight(1f)) {
                            if (state.query.isEmpty()) {
                                Text(
                                    "搜索标题 / 作者",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            BasicTextField(
                                value = state.query,
                                onValueChange = { viewModel.onQueryChange(it) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (state.query.isNotEmpty()) {
                            IconButton(
                                onClick = { haptics.tick(); viewModel.onQueryChange("") },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "清除搜索",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                // 当前排序维度（点开选其它维度）。
                // 图标的 contentDescription 说明"这是个排序入口"，读屏会念成「排序方式，浏览时间」；
                // 光靠按钮文字的话，读屏用户不知道点它会发生什么。
                TextButton(onClick = { haptics.tick(); showSortDialog = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "排序方式",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(fieldLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
                // 正序 / 倒序
                IconButton(onClick = { haptics.tick(); viewModel.onToggleSortOrder() }) {
                    Icon(
                        if (state.sortOrder == LocalSortOrder.DESC) Icons.Filled.ArrowDownward
                        else Icons.Filled.ArrowUpward,
                        contentDescription = if (state.sortOrder == LocalSortOrder.DESC) "当前倒序，点击改成正序"
                        else "当前正序，点击改成倒序",
                        modifier = Modifier.size(20.dp)
                    )
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

            // 有数据、但当前搜索词一条都没匹配上 —— 不能落回「还没有收藏」，
            // 否则用户会以为数据被清空了（文案约定 27：说清发生了什么、能怎么办）
            state.resultCount == 0 ->
                EmptyState(
                    title = "没有匹配的内容",
                    modifier = Modifier.fillMaxSize(),
                    description = "标题或作者名里包含「${state.query.trim()}」的记录没有找到",
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
                        start = Spacing.s, end = Spacing.s, top = Spacing.xs, bottom = Spacing.l
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
                    LocalSortField.entries.forEach { option ->
                        val label = if (option == LocalSortField.TIME) timeLabel else option.label
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptics.tick()
                                    viewModel.onSortFieldChange(option)
                                    showSortDialog = false
                                }
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            RadioButton(
                                selected = state.sortField == option,
                                onClick = {
                                    haptics.tick()
                                    viewModel.onSortFieldChange(option)
                                    showSortDialog = false
                                }
                            )
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        "正序 / 倒序用列表右侧的箭头切换",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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
