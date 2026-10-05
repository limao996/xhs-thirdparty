package com.thirdparty.xhs.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.NoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 长按菜单要显示「收藏」还是「取消收藏」、「稍后观看」还是「移出稍后观看」——
 * 所以菜单弹出之前必须知道这件作品的两种状态。整屏瀑布流一次读两个 id 集合。
 */
@Immutable
data class NoteFlags(
    val savedIds: Set<Long> = emptySet(),
    val watchLaterIds: Set<Long> = emptySet()
)

/** 长按菜单里的两个动作（都写本地库，跑在 appScope 上）。 */
class NoteActions(
    val toggleSave: (NoteItem) -> Unit,
    val toggleWatchLater: (NoteItem) -> Unit
)

@Composable
fun rememberNoteFlags(): NoteFlags {
    val savedVersion by App.repo.savedVersion.collectAsStateWithLifecycle()
    val watchLaterVersion by App.repo.watchLaterVersion.collectAsStateWithLifecycle()
    var flags by remember { mutableStateOf(NoteFlags()) }
    LaunchedEffect(savedVersion, watchLaterVersion) {
        flags = withContext(Dispatchers.IO) {
            NoteFlags(App.repo.savedIds(), App.repo.watchLaterIds())
        }
    }
    return flags
}

/**
 * 长按菜单的动作。
 *
 * 写库是 IO（加入队列时可能要补一份完整快照），提示要回主线程；两者都在这里收口，
 * 免得每个瀑布流页面各写一遍。
 */
@Composable
fun rememberNoteActions(): NoteActions {
    val context = LocalContext.current
    return remember(context) {
        NoteActions(
            toggleSave = { item ->
                App.INSTANCE.appScope.launch {
                    val saved = App.repo.toggleSaveLocal(item)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (saved) "已收藏" else "已取消收藏",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            toggleWatchLater = { item ->
                App.INSTANCE.appScope.launch {
                    val added = App.repo.toggleWatchLater(item)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            if (added) "已加入稍后观看" else "已移出稍后观看",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}
