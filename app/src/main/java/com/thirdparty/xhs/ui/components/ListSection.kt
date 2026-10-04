package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 分组容器：一个小标题 + 一张圆角卡片，与设置页的分组同款
 * （M3 late-2025 的 contained list：行与行之间靠卡片和留白分隔，不靠分割线）。
 *
 * 「关于」与「检查更新」两页共用，避免两个页面各写一份分组长相不同的近似实现。
 */
@Composable
fun ColumnScope.ListSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.s)
    )
    Surface(
        shape = Corners.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m)
    ) {
        Column(content = content)
    }
    Spacer(Modifier.height(Spacing.l))
}
