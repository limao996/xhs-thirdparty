@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.thirdparty.xhs.data.CommentReply
import com.thirdparty.xhs.data.NoteImage
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.components.FeeBadge
import com.thirdparty.xhs.ui.components.ImageGallery
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import com.thirdparty.xhs.ui.components.buildVideoPlayer
import kotlinx.coroutines.delay
import com.thirdparty.xhs.ui.components.CommentRepliesDialog
import androidx.compose.ui.draw.clip

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
    // Hoisted here (not in DetailContent) so the app bar can open the image
    // viewer for image posts — see the 全屏 action below.
    var openImage by rememberSaveable { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val view = LocalView.current

    // In fullscreen the app bar is hidden, so the system back gesture must leave
    // fullscreen first instead of popping the whole detail screen — and while the
    // image viewer is up, back closes the viewer rather than just the chrome.
    androidx.activity.compose.BackHandler(enabled = fullscreen || openImage != null) {
        if (openImage != null) {
            openImage = null
            fullscreen = false
        } else {
            fullscreen = false
        }
    }
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
                        IconButton(onClick = { shareNote(context, state.item) }) {
                            Icon(Icons.Filled.Share, contentDescription = "分享")
                        }
                        IconButton(onClick = { viewModel.toggleSave() }) {
                            Icon(
                                if (state.saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                if (state.saved) "取消收藏" else "收藏",
                                tint = if (state.saved) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // The 全屏 action means different things per media type.
                        //
                        // It used to be unconditional and only ever set the VIDEO
                        // fullscreen flag, so on an image post it did nothing at all
                        // (the fullscreen branch also requires isVideo). It now opens
                        // the image viewer there instead.
                        //
                        // For video notes this is byte-for-byte the old behaviour:
                        // `isVideo` is itself `item.isVideo && mediaUrl.isNotEmpty()`.
                        val videoNote = state.item?.isVideo == true &&
                            !state.item?.mediaUrl.isNullOrEmpty()
                        IconButton(onClick = {
                            // `fullscreen` hides the app bar and the system bars; the
                            // image branch used to set only `openImage`, so the viewer
                            // came up with 内容详情 still sitting above it — not
                            // fullscreen at all.
                            fullscreen = true
                            if (!videoNote) openImage = 0
                        }) {
                            Icon(
                                if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                "全屏"
                            )
                        }
                    }
                )
            }
        }
    ) { pad ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            state.missing || state.item == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("内容加载失败（可能已下线或需付费）")
            }
            else -> {
                val item = state.item!!
                val isVideo = item.isVideo && item.mediaUrl.isNotEmpty()
                // Hoisted so the fullscreen branch below can pick the orientation
                // that matches the video instead of assuming portrait.
                var videoAspect by remember(item.noteId) { mutableFloatStateOf(9f / 16f) }
                // Fullscreen orientation follows the VIDEO's shape: a landscape clip
                // should fill a landscape screen, a portrait clip should stay
                // portrait. Restored to unspecified when leaving fullscreen/screen.
                val activity = context as? android.app.Activity
                DisposableEffect(fullscreen, videoAspect, activity) {
                    if (fullscreen && isVideo) {
                        activity?.requestedOrientation = if (videoAspect > 1f) {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        }
                    }
                    onDispose {
                        activity?.requestedOrientation =
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                // ONE player for both layouts. The windowed and fullscreen branches
                // are different composables, so if each built its own player then
                // toggling fullscreen would tear one down and start the other from
                // zero — the video appeared to restart every time.
                val sharedPlayer = if (isVideo) {
                    remember(item.mediaUrl) {
                        buildVideoPlayer(
                            context = context.applicationContext,
                            url = item.mediaUrl,
        longForm = true
                        )
                    }
                } else null
                // survive the Activity relaunch that an orientation change causes
                var resumeMs by rememberSaveable(item.mediaUrl) {
                    androidx.compose.runtime.mutableLongStateOf(0L)
                }
                LaunchedEffect(sharedPlayer) {
                    if (sharedPlayer == null) return@LaunchedEffect
                    // Continue from where 推荐 left off when the detail was opened
                    // by tapping that same video there. Consumed once — a later open
                    // of this note (deep link, saved list) must start at the start.
                    val handoff = com.thirdparty.xhs.ui.components.PlaybackHandoff.take(item.noteId)
                    if (handoff != null) {
                        if (handoff.positionMs > 0L) sharedPlayer.seekTo(handoff.positionMs)
                        // only resume if it was actually playing; a paused feed must
                        // not start talking on its own
                        if (handoff.playing) sharedPlayer.play()
                    } else if (resumeMs > 0L) {
                        sharedPlayer.seekTo(resumeMs)
                    }
                }
                LaunchedEffect(sharedPlayer) {
                    if (sharedPlayer == null) return@LaunchedEffect
                    while (true) {
                        if (sharedPlayer.isPlaying) resumeMs = sharedPlayer.currentPosition
                        delay(500)
                    }
                }
                DisposableEffect(sharedPlayer) {
                    onDispose {
                        sharedPlayer?.stop()
                        sharedPlayer?.clearMediaItems()
                        sharedPlayer?.release()
                    }
                }
                if (fullscreen && isVideo) {
                    // 真全屏：视频铺满整屏
                    Box(Modifier.fillMaxSize()) {
                        MediaPlayer(
                            url = item.mediaUrl,
                            externalPlayer = sharedPlayer,
                            fullscreen = true,
                            title = item.title,
                            onAspect = { r -> if (r > 0f) videoAspect = r },
                            onToggleFullscreen = { fullscreen = !fullscreen },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    DetailContent(state, viewModel, onOpenAuthor, pad, isVideo, onEnterFullscreen = { fullscreen = true }, openImage = openImage, onOpenImage = { page ->
                        openImage = page
                        // closing the viewer must also restore the app bar and the
                        // system bars, otherwise the detail screen stays chromeless
                        if (page == null) fullscreen = false
                    }, sharedPlayer = sharedPlayer, videoAspect = videoAspect, onAspect = { videoAspect = it })
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
    isVideo: Boolean,
    /**
     * Index of the image open in the full-screen viewer, hoisted to DetailScreen:
     * the app bar's 全屏 button drives it for image notes (which have no video to
     * go full screen), and the app bar lives outside this composable.
     */
    openImage: Int?,
    onOpenImage: (Int?) -> Unit,
    onEnterFullscreen: () -> Unit = {},
    sharedPlayer: androidx.media3.exoplayer.ExoPlayer? = null,
    videoAspect: Float = 9f / 16f,
    onAspect: (Float) -> Unit = {}
) {
    val item = state.item!!
    // which comment's reply thread is open in the dialog (null = none).
    // Declared here, not inside the scrolling Column, so the dialog below can see it.
    var openReplies by remember(item.noteId) {
        mutableStateOf<com.thirdparty.xhs.data.CommentItem?>(null)
    }
    // Single scrolling column: media on top, then all the content BELOW it.
    // (Previously media and text were siblings in a Box, so the text drew
    //  on top of the video — that was the broken layout.)
    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
        if (isVideo) {
            // Size the container to the video's real ratio (portrait default for
            // short video); avoids huge black bars from a fixed 16:9 box.
            // In landscape, sizing by WIDTH would compute a height far taller than
            // the window (a portrait ratio at 2400px wide is ~5200px tall), so the
            // video overflowed the screen with black on one side and cropped on the
            // other. Constrain by height instead and centre it, so the whole frame
            // fits — which is what 横屏 support has to mean.
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val landscape = config.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val maxVideoHeight = (config.screenHeightDp * 0.92f).dp
            // Windowed player must never take more than half the screen, otherwise
            // a portrait video pushes the title/author/actions off-screen and the
            // page reads as "just a video". Height is computed from the real width
            // and the video's own ratio, then clamped to that half-screen cap.
            val halfScreen = (config.screenHeightDp * 0.5f).dp
            val naturalHeight = (config.screenWidthDp / videoAspect).dp
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MediaPlayer(
                    url = item.mediaUrl,
                    externalPlayer = sharedPlayer,
                    fullscreen = false,
                    title = item.title,
                    // without this the in-player fullscreen button is inert:
                    // MediaPlayer defaults the callback to a no-op
                    onToggleFullscreen = onEnterFullscreen,
                    onAspect = onAspect,
                    // windowed playback starts with the bar hidden; a tap reveals it
                    controlsHiddenInitially = true,
                    modifier = if (landscape) {
                        Modifier.height(minOf(maxVideoHeight, halfScreen))
                            .aspectRatio(videoAspect)
                    } else {
                        Modifier.fillMaxWidth()
                            .height(if (naturalHeight > halfScreen) halfScreen else naturalHeight)
                    }
                )
            }
        } else {
            val images = item.images.ifEmpty {
                listOf(item.cover).filter { it.isNotEmpty() }.map { NoteImage(it) }
            }
            // Same half-screen cap the windowed video player uses. Without it a
            // tall portrait gallery filled most of the screen and pushed the
            // title / author / actions off the first screen.
            val galleryConfig = androidx.compose.ui.platform.LocalConfiguration.current
            ImageGallery(
                images = images,
                maxHeight = (galleryConfig.screenHeightDp * 0.5f).dp,
                onOpen = { onOpenImage(it) }
            )
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
            Spacer(Modifier.height(Spacing.s))
            HorizontalDivider()

            state.author?.let { author ->
                // The whole card opens the author page, so a separate 主页 button is
                // redundant — and having two big buttons side by side made both look
                // oversized. The 关注 button keeps its own onClick: a child's click
                // wins over the row's, so tapping it follows rather than navigating.
                Row(
                    Modifier.fillMaxWidth()
                        .clip(XhsShapes.small)
                        .clickable { onOpenAuthor(author.userId) }
                        .padding(vertical = Spacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XhsAvatar(url = author.headImg, contentDescription = author.userName,
                        modifier = Modifier.size(AvatarSize.list))
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(author.userName, style = MaterialTheme.typography.titleSmall)
                        if (author.signature.isNotBlank())
                            Text(author.signature, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(Spacing.s))
                    Surface(onClick = { viewModel.toggleFollow() }, shape = Corners.full,
                        color = if (state.followed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary) {
                        Text(if (state.followed) "已关注" else "+ 关注",
                            Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.followed) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary)
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
                Spacer(Modifier.height(Spacing.s))
                Surface(shape = Corners.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("#$topic", Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }

            Spacer(Modifier.height(Spacing.l))
            Text("评论 ${item.commentCount}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (state.commentsLoading && state.comments.isEmpty()) {
                LoadingIndicator(Modifier.size(28.dp))
            } else if (state.comments.isEmpty() && state.commentsError) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "评论加载失败",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.fetchComments(reset = true) }) { Text("重试") }
                }
            } else if (state.comments.isEmpty()) {
                Text("还没有评论", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                state.comments.forEach { c -> CommentRow(c) { openReplies = it } }
                if (state.commentsHasMore) {
                    TextButton(
                        onClick = { viewModel.loadMoreComments() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.commentsLoading) {
                            LoadingIndicator(Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.s))
                        }
                        Text("查看更多评论")
                    }
                }
            }
        }

    }
    openImage?.let { page ->
        com.thirdparty.xhs.ui.components.FullscreenImageViewer(
            images = item.images.ifEmpty {
                listOf(item.cover).filter { it.isNotEmpty() }.map { com.thirdparty.xhs.data.NoteImage(it) }
            },
            initialPage = page,
            modifier = Modifier.padding(pad),
            onDismiss = { onOpenImage(null) }
        )
    }

    openReplies?.let { oc ->
        CommentRepliesDialog(
            noteId = item.noteId,
            commentId = oc.commentId,
            commentUserName = oc.userName,
            totalCount = oc.replyCount,
            preview = oc.replies,
            onDismiss = { openReplies = null }
        )
    }
}

@Composable
private fun CommentRow(c: CommentItem, onOpenReplies: (CommentItem) -> Unit) {
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
                    Spacer(Modifier.width(Spacing.s))
                    Text("♥${c.likeCount}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // inline reply preview. The comment list only carries a FEW replies
            // (reply_data is a preview with a data_count); the full thread comes
            // from v2/note-comment/comment-reply-list. Tapping the preview or the
            // count opens the whole thread in a dialog.
            if (c.replies.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Surface(
                    shape = Corners.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().clickable { onOpenReplies(c) }
                ) {
                    Column(Modifier.padding(Spacing.s)) {
                        c.replies.forEach { r -> ReplyRow(r) }
                        if (c.replyCount > c.replies.size) {
                            Text(
                                "共 ${c.replyCount} 条回复，点击查看",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = Spacing.xs)
                            )
                        }
                    }
                }
            } else if (c.replyCount > 0) {
                // no preview came with the comment, but replies exist
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "共 ${c.replyCount} 条回复，点击查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(Corners.small)
                        .clickable { onOpenReplies(c) }
                        .padding(vertical = Spacing.xs)
                )
            }
        }
    }
}

@Composable
private fun ReplyRow(r: CommentReply) {
    Row(Modifier.padding(vertical = Spacing.xs)) {
        XhsAvatar(url = r.headImg, contentDescription = r.userName,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(Spacing.s))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.userName, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
                if (r.replyToName.isNotBlank()) {
                    Text(" 回复 ", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.replyToName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(r.content, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun timeStr(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/**
 * Share the note through the system sheet, falling back to copying the link
 * when the backend did not provide a `share_url`.
 */
private fun shareNote(context: android.content.Context, item: NoteItem?) {
    if (item == null) return
    // 口令式分享：链接只作为**文本**存在，不做成可点击的深链。
    // 复制后用户回到本应用，由 MainActivity 检测剪贴板并询问是否跳转。
    val link = com.thirdparty.xhs.DeepLink.noteUrl(item.noteId)
    val text = buildString {
        if (item.title.isNotBlank()) append(item.title).append('\n')
        append(link)
    }
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager
    val copied = runCatching {
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
        true
    }.getOrDefault(false)
    android.widget.Toast.makeText(
        context,
        if (copied) "口令已复制，回到应用可自动打开" else "复制失败",
        android.widget.Toast.LENGTH_SHORT
    ).show()
}

private fun com.thirdparty.xhs.data.NoteItem.detailTopic(): String =
    runCatching { org.json.JSONObject(rawJson).optString("topic_title") }.getOrDefault("")

private fun com.thirdparty.xhs.data.NoteItem.detail(): org.json.JSONObject =
    runCatching { org.json.JSONObject(rawJson) }.getOrElse { org.json.JSONObject() }