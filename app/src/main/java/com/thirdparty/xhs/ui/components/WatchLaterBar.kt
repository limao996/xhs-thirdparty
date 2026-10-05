package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thirdparty.xhs.App
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 推荐页的信息条：贴在视频信息栏（标题/作者那一条）上方，点一下进队列。
 *
 * 推荐页是全屏视频，浮动按钮会压在画面上、也会跟着「收起 chrome」的手势忽隐忽现；
 * 一条和标题栏同材质的信息条反而更自然 —— 它跟着信息栏一起显示/隐藏（调用方把它放在
 * 信息栏那个 Column 里）。
 *
 * 队列为空、或者正处在画中画小窗里时什么都不画（小窗控制栏里已经有队列入口了）。
 */
@Composable
fun WatchLaterBar(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val version by App.repo.watchLaterVersion.collectAsStateWithLifecycle()
    val inPip by PipController.inPip.collectAsStateWithLifecycle()
    var count by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) {
        count = withContext(Dispatchers.IO) { App.repo.watchLaterCount() }
    }

    if (count <= 0 || inPip) return

    val haptics = rememberHaptics()
    Surface(
        color = Scrim.chrome,
        contentColor = Scrim.onMedia,
        shape = Corners.small,
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                haptics.tick()
                onOpen()
            }
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlaylistPlay,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                "稍后观看 $count 件 · 点这里查看",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}
