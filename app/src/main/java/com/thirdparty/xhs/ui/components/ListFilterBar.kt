package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.data.LocalSortOrder
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 本地列表的「搜索 + 排序 + 正倒序」工具条（收藏 / 最近浏览 / 我关注的作者共用）。
 *
 * 为什么要抽成组件：这三处如果各写一份，搜索框的高度、内边距、排序按钮位置迟早会走偏 ——
 * 2026-10-25 第一版用 M3 `SearchBar` 时就和旁边的排序按钮**重心对不齐**（它自带 56dp 高度与内边距），
 * 换成自绘 44dp 圆角面之后才一致。共用一份实现，就没有"改了一处忘了另一处"的机会。
 *
 * 内边距：横向 [Spacing.m]（12dp）。用户 2026-10-25 反馈 8dp「有点小」，两侧各加宽了一档，
 * 放大镜与文字之间的距离也同步加到 [Spacing.s]，文字不会顶着图标。
 *
 * @param fieldLabel 当前排序维度（如「收藏时间」/「昵称」），直接显示在排序按钮上
 * @param onOpenSort 点排序按钮：由调用方弹出原生 AlertDialog（项目硬约束 16，不用下拉菜单）
 */
@Composable
fun ListFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    fieldLabel: String,
    onOpenSort: () -> Unit,
    order: LocalSortOrder,
    onToggleOrder: () -> Unit,
    modifier: Modifier = Modifier,
    haptics: Haptics = rememberHaptics()
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.s, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.weight(1f).height(44.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            // 横向内边距 12dp：太小时文字像贴着圆角边缘（用户 2026-10-25 反馈）
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.m),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.s))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { haptics.tick(); onQueryChange("") },
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
        // 当前排序维度（点开选其它维度）。图标语义说明"这是排序入口"，
        // 读屏会念成「排序方式，昵称」；只靠按钮文字读屏用户不知道点它会排序。
        TextButton(onClick = { haptics.tick(); onOpenSort() }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "排序方式",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(fieldLabel, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        // 正序 / 倒序
        IconButton(onClick = { haptics.tick(); onToggleOrder() }) {
            Icon(
                if (order == LocalSortOrder.DESC) Icons.Filled.ArrowDownward
                else Icons.Filled.ArrowUpward,
                contentDescription =
                    if (order == LocalSortOrder.DESC) "当前倒序，点击改成正序" else "当前正序，点击改成倒序",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
