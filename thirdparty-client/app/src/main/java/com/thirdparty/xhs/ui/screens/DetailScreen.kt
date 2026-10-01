package com.thirdparty.xhs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.CommentItem
import com.thirdparty.xhs.ui.components.FeeBadge
import com.thirdparty.xhs.ui.components.MediaPlayer
import com.thirdparty.xhs.ui.components.XhsAsyncImage
import com.thirdparty.xhs.ui.components.XhsAvatar
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.theme.XhsShapes
import com.thirdparty.xhs.ui.viewmodel.DetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 详情页：视频播放器 + 标题 + 作者 + 介绍 + 标签 + 评论区。
 * 收藏按钮在标题栏右侧；播放器支持真全屏（隐藏系统栏，横/竖皆可）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    noteId: Long,
    onBack: () -> Unit,
    onOpenAuthor: (Int) -> Unit,
    viewModel: DetailViewModel = viewModel(
        key = "detail-$noteId",
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(noteId, App.repo) as T
        }
    )
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    // 真全屏：隐藏状态/导航栏（不强制方向，横竖都行）
    val window = (LocalContext.current as? android.app.Activity)?.window
    DisposableEffect(fullscreen, window) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (fullscreen) {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.let { WindowCompat.getInsetsController(it, view).show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        }
    }

    Scaffold(
        topBar = {
            // hide the app bar entirely in fullscreen for true immersion
            if (!fullscreen) {
                TopAppBar(
                    title = { Text("内容详情") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.toggleSave() }) {
                            Icon(
                                if (state.saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                if (state.saved) "取消收藏" else "收藏",
                                tint = if (state.saved) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { fullscreen = true }) {
                            Icon(Icons.Filled.Fullscreen, "全屏")
                        }
                    }
                )
            }
        }
    ) { pad ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.missing || state.item == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("内容加载失败（可能已下线或需付费）")
            }
            else -> {
                val item = state.item!!
                val isVideo = item.isVideo && item.mediaUrl.isNotEmpty()
                if (fullscreen && isVideo) {
                    // 真全屏：视频铺满整屏
                    Box(Modifier.fillMaxSize()) {
                        MediaPlayer(
                            url = item.mediaUrl,
                            fullscreen = true,
                            onToggleFullscreen = { fullscreen = !fullscreen },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    DetailContent(state, viewModel, onOpenAuthor, pad, isVideo)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DetailContent(
    state: com.thirdparty.xhs.ui.viewmodel.DetailUiState,
    viewModel: DetailViewModel,
    onOpenAuthor: (Int) -> Unit,
    pad: androidx.compose.foundation.layout.PaddingValues,
    isVideo: Boolean
) {
    val item = state.item!!
    // Single scrolling column: media on top, then all the content BELOW it.
    // (Previously media and text were siblings in a Box, so the text drew
    //  on top of the video — that was the broken layout.)
    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
        if (isVideo) {
            // size the container to the video's real ratio (portrait default for
            // short video); avoids huge black bars from a fixed 16:9 box
            var videoAspect by remember(item.noteId) { mutableFloatStateOf(9f / 16f) }
            MediaPlayer(
                url = item.mediaUrl,
                fullscreen = false,
                onAspect = { r -> if (r > 0f) videoAspect = r },
                modifier = Modifier.fillMaxWidth().aspectRatio(videoAspect)
            )
        } else {
            val images = item.images.ifEmpty { listOf(item.cover).filter { it.isNotEmpty() } }
            if (images.isNotEmpty()) {
                XhsAsyncImage(
                    url = images[0], contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                )
            }
        }

        Column(Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)) {
            val ctx = LocalContext.current
            Text(
                item.title.ifEmpty { "(无标题)" },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.combinedClickable(
                    onClick = {}, onLongClick = {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("title", item.title))
                        android.widget.Toast.makeText(ctx.applicationContext, "已复制标题", android.widget.Toast.LENGTH_SHORT).show()
                    })
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeeBadge(item, modifier = Modifier.padding(end = 8.dp))
                Text("♥${item.likeCount}  收藏 ${item.collectCount}  评论 ${item.commentCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()

            state.author?.let { author ->
                Row(Modifier.padding(vertical = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                    XhsAvatar(url = author.headImg, contentDescription = author.userName,
                        modifier = Modifier.size(AvatarSize.list))
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f).clickable { onOpenAuthor(author.userId) }) {
                        Text(author.userName, style = MaterialTheme.typography.titleSmall)
                        if (author.signature.isNotBlank())
                            Text(author.signature, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(onClick = { viewModel.toggleFollow() }, shape = XhsShapes.small,
                        color = if (state.followed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary) {
                        Text(if (state.followed) "已关注" else "+ 关注",
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (state.followed) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(onClick = { onOpenAuthor(author.userId) }, shape = XhsShapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("主页", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                HorizontalDivider()
            }

            if (item.content.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("介绍", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(item.content, style = MaterialTheme.typography.bodyMedium)
            }

            val topic = item.detailTopic()
            if (topic.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = Corners.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("#$topic", Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("评论 ${state.comments.size}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (state.commentsLoading && state.comments.isEmpty()) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else if (state.comments.isEmpty()) {
                Text("还没有评论", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                state.comments.forEach { c -> CommentRow(c) }
            }
        }
    }
}

@Composable
private fun CommentRow(c: CommentItem) {
    Row(Modifier.padding(vertical = Spacing.s)) {
        XhsAvatar(url = c.headImg, contentDescription = c.userName,
            modifier = Modifier.size(AvatarSize.comment))
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Text(c.userName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(c.content, style = MaterialTheme.typography.bodyMedium)
            Row {
                Text(timeStr(c.createdAt), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.likeCount > 0) {
                    Spacer(Modifier.width(10.dp))
                    Text("♥${c.likeCount}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun timeStr(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun com.thirdparty.xhs.data.NoteItem.detailTopic(): String =
    runCatching { org.json.JSONObject(rawJson).optString("topic_title") }.getOrDefault("")

private fun com.thirdparty.xhs.data.NoteItem.detail(): org.json.JSONObject =
    runCatching { org.json.JSONObject(rawJson) }.getOrElse { org.json.JSONObject() }