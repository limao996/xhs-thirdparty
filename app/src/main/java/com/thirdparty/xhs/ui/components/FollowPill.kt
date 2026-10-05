package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 关注 / 已关注的小号按钮（全局唯一实现）。
 *
 * 原来是 M3 的 `Button` / `OutlinedButton`：在列表行里那几个按钮又高又宽，把作者名挤得很窄。
 * 粉丝圈 tab 里那个尺寸是对的样子，于是把它抽成组件，详情页、关注页、关注 tab、粉丝圈 tab 共用一套。
 *
 * 语义：未关注 = 实心主色（"去关注"是这一屏要做的动作），已关注 = 次级容器色（是状态，不是号召）。
 */
@Composable
fun FollowPill(
    followed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (followed) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (followed) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier
    ) {
        Text(
            if (followed) "已关注" else "关注",
            Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            style = MaterialTheme.typography.labelLarge
        )
    }
}
