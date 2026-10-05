package com.thirdparty.xhs.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thirdparty.xhs.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「稍后观看」浮动按钮：队列不为空时出现，点开队列页。
 *
 * 两个隐藏条件，都是刻意的：
 *  - 队列为空 → 没什么可看的，按钮只会挡内容；
 *  - 已经在画中画小窗里 → 小窗本身就是队列的入口（控制栏里那个按钮），再叠一个浮动按钮
 *    只会挡住画面。
 */
@Composable
fun WatchLaterFab(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val version by App.repo.watchLaterVersion.collectAsStateWithLifecycle()
    val inPip by PipController.inPip.collectAsStateWithLifecycle()
    var count by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) {
        count = withContext(Dispatchers.IO) { App.repo.watchLaterCount() }
    }

    if (count <= 0 || inPip) return

    ExtendedFloatingActionButton(
        onClick = onOpen,
        modifier = modifier
    ) {
        Icon(Icons.Filled.PlaylistPlay, contentDescription = null)
        Text("稍后观看 $count")
    }
}
