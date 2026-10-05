package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 右下角的浮动按钮组：**刷新**（小按钮）+ **稍后观看**（扩展按钮）竖着叠。
 *
 * 这两个按钮曾经各画各的、又都停在同一个角上，于是后画的那个把前一个盖住了
 * （用户看到的是"稍后观看把刷新挤掉了"）。它们的职责不一样，本来就该共存：
 *  - 刷新是"这个页面的动作"，用小号 FAB、次要色，放在上面；
 *  - 稍后观看是"队列的入口"，用扩展 FAB 带件数，放在下面（拇指更容易够到）。
 *
 * 队列为空时 [WatchLaterFab] 自己不画，于是只剩刷新按钮；画中画时整组隐藏
 * （[WatchLaterFab] 里也会判一次，这里再判是为了连刷新按钮一起让位给小窗）。
 */
@Composable
fun CornerFabStack(
    onOpenWatchLater: () -> Unit,
    modifier: Modifier = Modifier,
    /** 为 null 表示这个页面没有"刷新"这个动作（例如关注列表） */
    onRefresh: (() -> Unit)? = null
) {
    val inPip by PipController.inPip.collectAsStateWithLifecycle()
    if (inPip) return
    val haptics = rememberHaptics()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        if (onRefresh != null) {
            SmallFloatingActionButton(
                onClick = {
                    haptics.tick()
                    onRefresh()
                },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        WatchLaterFab(onOpen = onOpenWatchLater)
    }
}
