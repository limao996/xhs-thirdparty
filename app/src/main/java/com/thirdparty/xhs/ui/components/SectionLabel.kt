package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 小节标题（设置页 / 我的页共用的一份实现）。
 *
 * 以前两个页面各写了一份 private `SectionLabel`，只差 4dp 的下边距 —— 这类"看着一样、改一处忘一处"
 * 的重复会在换主题、调字号时露馅，所以收成一个组件，差异用参数表达。
 */
@Composable
fun SectionLabel(
    text: String,
    bottom: Dp = Spacing.s
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = bottom)
    )
}
