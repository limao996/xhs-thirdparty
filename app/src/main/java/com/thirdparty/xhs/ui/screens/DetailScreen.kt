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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ModeComment
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.CommentItem
import com.thirdparty.xhs.data.CommentReply
import com.thirdparty.xhs.data.NoteImage
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.components.ConfirmActionDialog
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
import androidx.compose.ui.text.style.TextOverflow

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
    // 图文: the page the gallery is on. Hoisted here because BOTH the inline gallery and
    // the full-screen viewer read it — one index, so the "N/M" counters agree and
    // closing the viewer leaves the gallery on the picture the user swiped to.
    var imagePage by rememberSaveable(noteId) { mutableIntStateOf(0) }
    val context = LocalContext.current

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
    // 真全屏：状态栏/导航栏的处置权在 AppNavHost（它是这两条栏唯一的 owner），
    // 这里只声明意图：
    //  - 视频全屏 → 隐藏两条栏（沉浸观看）
    //  - 图文全屏（图片查看器占满屏幕）→ **不隐藏**，两条栏保持显示、做成透明、
    //    图标白色，让图片从其下方穿过
    val viewerFullscreen = fullscreen && openImage != null

    // 取消收藏 / 取消关注 都要先确认：两个动作都是一次点击生效且没有「撤销」，
    // 误触后不会有任何提示（收藏没了就是没了、作者从关注里消失）。加关注/收藏
    // 本身不弹窗——再点一下就能恢复。
    // 状态放在这里（而不是 DetailContent 里）是因为收藏按钮住在顶栏，而顶栏属于
    // 这个 composable。
    var confirmUnsave by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }

    // ---- media plumbing, hoisted OUT of the metadata branch -------------------
    //
    // All of this used to live inside `else -> { val item = state.item!! ... }`, i.e.
    // behind this page's own JSON request. That is wrong for video: when the page was
    // opened by tapping a clip in 推荐 the player is ALREADY PLAYING, so waiting for a
    // separate note request before drawing it meant the picture showed up seconds late
    // — the user watching a spinner with the video running right behind it.
    //
    // Adopting, sizing and releasing the player is therefore keyed on the NOTE, and the
    // metadata only decides what is drawn BELOW the media.
    val inherited = remember(noteId) {
        com.thirdparty.xhs.ui.components.PlaybackHandoff.takeForDetail(noteId)
    }
    // 0 while the video's shape is unknown (see the sizing below). Seeded from an
    // inherited player, which already knows its video size — no second
    // `onVideoSizeChanged` is ever coming for it.
    var videoAspect by remember(noteId) {
        mutableFloatStateOf(
            inherited?.player?.videoSize?.let {
                if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height.toFloat() else 0f
            } ?: 0f
        )
    }
    val itemMediaUrl = state.item?.mediaUrl.orEmpty()
    // the media to draw: a handed-over player is already proof that this is video
    val isVideoNote = inherited != null ||
        (state.item?.isVideo == true && itemMediaUrl.isNotEmpty())
    val sharedPlayer: androidx.media3.exoplayer.ExoPlayer? = if (!isVideoNote) null
    else inherited?.player ?: remember(itemMediaUrl) {
        if (itemMediaUrl.isEmpty()) null
        else buildVideoPlayer(context.applicationContext, itemMediaUrl, longForm = true)
    }
    // A handed-over player arrives wearing the FEED's settings — it loops there, and
    // this screen must stop at the end (that is the 播完显示「重播」behaviour). Done
    // once, in an effect.
    LaunchedEffect(inherited) {
        val a = inherited ?: return@LaunchedEffect
        com.thirdparty.xhs.ui.components.applyLongFormPlayerSettings(a.player)
        // Resume exactly the intent the feed handed over: playing (even if it was mid
        // buffer when the user tapped) stays playing, a deliberate pause stays paused.
        if (a.playIntent) runCatching { a.player.play() } else runCatching { a.player.pause() }
    }
    // 状态栏/导航栏的处置权在 AppNavHost（它是这两条栏唯一的 owner），这里只声明意图：
    //  - 视频全屏 → 隐藏两条栏（沉浸观看）
    //  - 图文全屏（图片查看器占满屏幕）→ **不隐藏**：两条栏保持显示、做成透明、
    //    图标白色，图片从其下方穿过
    DisposableEffect(fullscreen, isVideoNote, viewerFullscreen) {
        App.INSTANCE.detailImmersive.value = fullscreen && isVideoNote && !viewerFullscreen
        App.INSTANCE.imageViewerShown.value = viewerFullscreen
        onDispose {
            App.INSTANCE.detailImmersive.value = false
            App.INSTANCE.imageViewerShown.value = false
        }
    }
    // Fullscreen orientation follows the VIDEO's shape: a landscape clip should fill a
    // landscape screen, a portrait clip should stay portrait. Restored to unspecified
    // when leaving fullscreen/screen.
    val activity = context as? android.app.Activity
    DisposableEffect(fullscreen, videoAspect, activity) {
        if (fullscreen && isVideoNote) {
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
    // survive the Activity relaunch that an orientation change causes
    var resumeMs by rememberSaveable(noteId) {
        androidx.compose.runtime.mutableLongStateOf(0L)
    }
    LaunchedEffect(sharedPlayer) {
        if (sharedPlayer == null) return@LaunchedEffect
        // Continue from where 推荐 left off when the detail was opened by tapping that
        // same video there. Consumed once — a later open of this note (deep link, saved
        // list) must start at the start. Consumed even when the player itself was handed
        // over: leaving it behind would make the NEXT unrelated open of this note resume
        // a position from this transition.
        val handoff = com.thirdparty.xhs.ui.components.PlaybackHandoff.take(noteId)
        // Nothing to seek when the feed's own player was adopted: it never stopped, so
        // it is already at the position the user was watching. Seeking here as well
        // could only move it backwards (the stored position is up to half a second old).
        if (inherited != null) return@LaunchedEffect

        val target = handoff?.positionMs?.takeIf { it > 0L } ?: resumeMs
        if (handoff?.playIntent == true) sharedPlayer.play()

        if (target > 0L) {
            // Seek immediately, so a player that is already prepared moves at once with
            // no extra frame of the opening seconds...
            runCatching { sharedPlayer.seekTo(target) }
            // ...and again once the media is ready. This is a second player built from
            // scratch, and on an HLS stream a seek issued before the playlist has
            // settled can be clamped or dropped — which showed up as the detail page
            // starting from zero even though the position had been handed over.
            var applied = false
            sharedPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_READY && !applied) {
                        applied = true
                        runCatching { sharedPlayer.seekTo(target) }
                    }
                }
            })
        }
    }
    LaunchedEffect(sharedPlayer) {
        if (sharedPlayer == null) return@LaunchedEffect
        // Save the position so a recreate (orientation change) does not restart a long
        // work from zero.
        //
        // Two rules, both fixing the same report — "播放完毕后回到 5~7 秒 而不是重新播放":
        //  1. save while PAUSED too. Only recording while playing meant the stored value
        //     was always some earlier playing position, so a restore could jump to a spot
        //     the user had already left.
        //  2. clear it once the playhead reaches the end. A finished (or looping) item
        //     otherwise leaves a mid-video value behind, and the next recreate resumes
        //     from it instead of starting over.
        while (true) {
            val p = sharedPlayer
            val duration = p.duration
            val position = p.currentPosition
            resumeMs = when {
                p.playbackState == androidx.media3.common.Player.STATE_ENDED -> 0L
                duration > 0L && position >= duration - END_OF_MEDIA_MARGIN_MS -> 0L
                else -> position
            }
            delay(500)
        }
    }
    // A looping item must not leave a resume position behind: the loop is the video
    // restarting, and if the player is recreated while it plays round again, restoring
    // the pre-loop position looks like the player jumping to the middle of the clip
    // instead of replaying.
    DisposableEffect(sharedPlayer) {
        val p = sharedPlayer
        val listener = if (p == null) null else object :
            androidx.media3.common.Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    resumeMs = 0L
                }
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    DisposableEffect(sharedPlayer) {
        onDispose {
            // The detail page owns its player, adopted or built: releasing it here is
            // what stops audio continuing after 返回. The feed cannot take an adopted one
            // back — by the time this runs the feed has already recomposed and built its
            // own, so the two would just swap players mid-playback.
            sharedPlayer?.stop()
            sharedPlayer?.clearMediaItems()
            sharedPlayer?.release()
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
                        IconButton(onClick = {
                            // only the removal asks first; 收藏 stays one tap
                            if (state.saved) confirmUnsave = true else viewModel.toggleSave()
                        }) {
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
                        // For video notes this is byte-for-byte the old behaviour.
                        // `isVideoNote` rather than `state.item.isVideo`: a handed-over
                        // player proves this is video even before the note's own request
                        // answers, and without that the 全屏 button would open the IMAGE
                        // viewer on a video for those first seconds.
                        val videoNote = isVideoNote
                        IconButton(onClick = {
                            // `fullscreen` hides the app bar and the system bars; the
                            // image branch used to set only `openImage`, so the viewer
                            // came up with 内容详情 still sitting above it — not
                            // fullscreen at all.
                            fullscreen = true
                            // open the viewer on the page the gallery is showing, not on
                            // the first one (that was the other half of the two
                            // counters disagreeing)
                            if (!videoNote) openImage = imagePage
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
            // `inherited == null` on both: with a player in hand there is something to
            // show already, so the page must not be blanked by its own metadata request
            // (that is the case this whole hoist exists for).
            state.loading && inherited == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            state.missing && inherited == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("内容加载失败（可能已下线或需付费）")
            }
            else -> {
                // NOTE: fullscreen is a LAYOUT of this same content, not a separate
                // branch. It used to render a second MediaPlayer composition, which
                // meant toggling re-parented the player's AndroidView and threw away its
                // TextureView — and a fresh TextureView is black, because a finished
                // video has no further frames to draw into it. That is the reported
                // "播完之后一切全屏就黑屏、也重播不了". Now the media keeps its place in
                // the composition and only its size changes, so the surface — and the
                // last frame on it — survives the toggle.
                DetailContent(
                    state = state,
                    viewModel = viewModel,
                    onOpenAuthor = onOpenAuthor,
                    pad = pad,
                    isVideo = isVideoNote,
                    onEnterFullscreen = { fullscreen = true },
                    openImage = openImage,
                    imagePage = imagePage,
                    onImagePage = { imagePage = it },
                    onOpenImage = { page ->
                        openImage = page
                        // Tapping a picture in the embedded gallery must go FULL screen,
                        // not open the viewer inside the page's content slot: there the
                        // app bar is still above it and the picture is inset by the
                        // scaffold padding, which is exactly "并没有完整全屏".
                        fullscreen = page != null
                    },
                    sharedPlayer = sharedPlayer,
                    videoAspect = videoAspect,
                    onAspect = { if (it > 0f) videoAspect = it },
                    fullscreen = fullscreen,
                    onToggleFullscreen = { fullscreen = !fullscreen },
                    onUnfollowRequest = { confirmUnfollow = true }
                )
            }
        }
    }

    if (confirmUnsave) {
        ConfirmActionDialog(
            title = "取消收藏？",
            text = "这条内容会从「我的收藏」里移除。",
            confirmText = "移除",
            onConfirm = {
                confirmUnsave = false
                viewModel.toggleSave()
            },
            onDismiss = { confirmUnsave = false }
        )
    }
    if (confirmUnfollow) {
        val name = state.author?.userName.orEmpty()
        ConfirmActionDialog(
            title = "取消关注？",
            text = if (name.isBlank()) "将不再关注这位作者。" else "将不再关注「$name」。",
            confirmText = "取消关注",
            onConfirm = {
                confirmUnfollow = false
                viewModel.toggleFollow()
            },
            onDismiss = { confirmUnfollow = false }
        )
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
     * Index of the image open in the full-screen viewer (null = closed), hoisted to
     * DetailScreen: the app bar's 全屏 button drives it for image notes (which have no
     * video to go full screen), and the app bar lives outside this composable.
     */
    openImage: Int?,
    onOpenImage: (Int?) -> Unit,
    /**
     * Page the 图文 gallery is showing, shared with the full-screen viewer so the two
     * cannot drift apart (see [ImageGallery] and [FullscreenImageViewer]).
     */
    imagePage: Int,
    onImagePage: (Int) -> Unit,
    onEnterFullscreen: () -> Unit = {},
    sharedPlayer: androidx.media3.exoplayer.ExoPlayer? = null,
    /** width/height of the video, or 0 while it is not known yet */
    videoAspect: Float = 0f,
    onAspect: (Float) -> Unit = {},
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
    /**
     * The author row's 已关注 button asks the caller to confirm first (the dialog's
     * state lives in DetailScreen — see the note there). 关注 is still direct.
     */
    onUnfollowRequest: () -> Unit = {}
) {
    // NULLABLE, deliberately. When the player was inherited from 推荐 it is already
    // playing, so the media is drawn while this note's own request is still in flight;
    // only the sections below the media wait for `state.item`.
    val item = state.item
    // which comment's reply thread is open in the dialog (null = none).
    // Declared here, not inside the scrolling Column, so the dialog below can see it.
    var openReplies by remember(state.item?.noteId) {
        mutableStateOf<com.thirdparty.xhs.data.CommentItem?>(null)
    }
    // Single scrolling column: media on top, then all the content BELOW it.
    // (Previously media and text were siblings in a Box, so the text drew
    //  on top of the video — that was the broken layout.)
    //
    // Fullscreen changes only how this column is laid out — no scroll, no padding, the
    // media fills it — so the media's place in the composition (and with it its view and
    // surface) is identical in both modes.
    // The page's scroll position is read below as well: the comments auto-load when it
    // reaches the end (see the comments section).
    val scrollState = rememberScrollState()
    Column(
        Modifier.fillMaxSize()
            .then(if (fullscreen) Modifier else Modifier.padding(pad))
            .then(if (fullscreen) Modifier else Modifier.verticalScroll(scrollState))
    ) {
        if (isVideo) {
            // Windowed player: an inset media card, sized inside a RANGE.
            //
            // Fitting the video's own shape and then clamping it was the original
            // behaviour, but the clamp used `screenWidthDp` (ignoring the card's own
            // inset) and pinned every portrait clip to the ceiling. The floor-only
            // version that followed made every clip the same height instead — "尺寸怎么
            // 固定住了". So: fit the shape, clamp into [floor, ceiling], and use the
            // FLOOR while the shape is still unknown, which is what keeps the title,
            // author and actions on the first screenful the moment the page opens.
            //
            // In landscape, sizing by WIDTH would compute a height far taller than the
            // window (a portrait ratio at 2400px wide is ~5200px tall), so there the box
            // is constrained by height and the ratio keeps the whole frame visible —
            // which is what 横屏 support has to mean.
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            val landscape = config.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            // measured against the player's OWN width (the page inset is 2×Spacing.m),
            // so 16:10 stays 16:10 now that the card no longer runs edge to edge
            val playerWidth = config.screenWidthDp - Spacing.m.value * 2f
            val floorHeight = (playerWidth * 10f / 16f).dp
            val ceilingHeight = maxOf((config.screenHeightDp * 0.5f).dp, floorHeight)
            val windowedHeight = if (videoAspect <= 0f) floorHeight
            else (playerWidth / videoAspect).dp.coerceIn(floorHeight, ceilingHeight)
            // M3 Expressive hero media: inset from the page edges and clipped to the
            // large shape token. Full-bleed made the player read as a hole in the
            // page rather than as the page's media element.
            val mediaShape = MaterialTheme.shapes.large
            Box(
                Modifier.then(
                    if (fullscreen) Modifier.fillMaxSize()
                    else Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.s)
                ),
                contentAlignment = Alignment.Center
            ) {
                MediaPlayer(
                    // the note may still be loading when a player was inherited; the
                    // url is only a key then, because externalPlayer is always set here
                    url = item?.mediaUrl.orEmpty(),
                    externalPlayer = sharedPlayer,
                    fullscreen = fullscreen,
                    title = item?.title.orEmpty(),
                    // without this the in-player fullscreen button is inert:
                    // MediaPlayer defaults the callback to a no-op
                    onToggleFullscreen = onToggleFullscreen,
                    onAspect = onAspect,
                    // windowed playback starts with the bar hidden; a tap reveals it
                    controlsHiddenInitially = true,
                    modifier = when {
                        fullscreen -> Modifier.fillMaxSize()
                        // aspectRatio(0) throws, and 0 means "not known yet"
                        landscape && videoAspect > 0f ->
                            Modifier.height(windowedHeight).aspectRatio(videoAspect).clip(mediaShape)
                        else -> Modifier.fillMaxWidth().height(windowedHeight).clip(mediaShape)
                    }
                )
            }
        } else if (item != null) {
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
                // one shared index with the full-screen viewer (see [ImageGallery])
                page = imagePage,
                onPageChange = onImagePage,
                onOpen = { onOpenImage(it) }
            )
        }

        // true fullscreen shows the media and nothing else
        if (fullscreen) return@Column

        // Everything below the media needs the note, so while an INHERITED player is
        // already on screen and this request is still in flight, the page shows the
        // media plus this indicator rather than a blank column (or, worse, blanking the
        // whole screen the way the old `state.loading` branch did).
        if (item == null) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = Spacing.xxl),
                contentAlignment = Alignment.Center
            ) {
                // A request that FAILED (with a player already up) must say so rather
                // than spin forever — the media plays on either way.
                if (state.missing) {
                    Text(
                        "内容加载失败（可能已下线或需付费）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LoadingIndicator()
                }
            }
            return@Column
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
                FeeBadge(item, modifier = Modifier.padding(end = Spacing.s))
                // One label per figure rather than a single run-together string:
                // with icons the eye can find the number it wants instead of
                // reading "♥105  收藏 98  评论 0" as one blob, and each figure keeps
                // its own weight when a value is missing.
                StatItem(Icons.Filled.Favorite, item.likeCount)
                StatItem(Icons.Filled.Star, item.collectCount)
                StatItem(Icons.Filled.ModeComment, item.commentCount)
            }
            Spacer(Modifier.height(Spacing.s))
            HorizontalDivider()

            state.author?.let { author ->
                // MD3 list row, and MD3 buttons.
                //
                // The row was a hand-rolled Row (its own padding and metrics, and a
                // raw `Surface` for the button), which is how it ended up looking
                // unlike every other author row in the app. `ListItem` brings the
                // standard metrics, and it is the same shape as FollowedAuthorRow.
                //
                // The whole row still opens the author page; the button is a child
                // and consumes its own taps, so 关注 follows instead of navigating.
                // M3 buttons put an icon and a label side by side, which also gives
                // the "+" that used to be smuggled into the string a real place.
                ListItem(
                    headlineContent = {
                        Text(author.userName, style = MaterialTheme.typography.titleSmall)
                    },
                    supportingContent = {
                        if (author.signature.isNotBlank()) {
                            Text(
                                author.signature,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    leadingContent = {
                        XhsAvatar(
                            url = author.headImg,
                            contentDescription = author.userName,
                            modifier = Modifier.size(AvatarSize.list)
                        )
                    },
                    trailingContent = {
                        if (state.followed) {
                            // unfollowing asks for confirmation; following does not
                            OutlinedButton(onClick = onUnfollowRequest) {
                                Icon(Icons.Filled.Check, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.xs + 2.dp))
                                Text("已关注", style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            Button(onClick = { viewModel.toggleFollow() }) {
                                Icon(Icons.Filled.Add, contentDescription = null,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.xs + 2.dp))
                                Text("关注", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clip(Corners.small)
                        .clickable { onOpenAuthor(author.userId) }
                )
                HorizontalDivider()
            }

            if (item.content.isNotBlank()) {
                // section header in the M3 title role rather than a variant-coloured
                // label: 介绍 is a section, not a caption
                Spacer(Modifier.height(Spacing.l))
                Text("介绍", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(Spacing.s))
                Text(item.content, style = MaterialTheme.typography.bodyMedium)
            }

            val topic = item.detailTopic()
            if (topic.isNotBlank()) {
                Spacer(Modifier.height(Spacing.m))
                Surface(shape = Corners.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("#$topic", Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }

            // 评论 gets the same divider-led section treatment as 介绍 — it used to
            // run straight on from the description with no visual break.
            Spacer(Modifier.height(Spacing.l))
            HorizontalDivider()
            Spacer(Modifier.height(Spacing.l))
            Text("评论 ${item.commentCount}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
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
                // Auto-load instead of a 「查看更多评论」 button: the list is paged and a
                // tap per page is pure friction. Keyed on the SCROLL POSITION rather
                // than on the state, so a failed page is not retried in a loop — it
                // tries again when the user scrolls again.
                if (state.commentsHasMore) {
                    LaunchedEffect(scrollState, item.noteId) {
                        snapshotFlow { scrollState.value to scrollState.maxValue }
                            .distinctUntilChanged()
                            .collect { (value, max) ->
                                val atEnd = max == 0 || value >= max - 600
                                if (atEnd && !state.commentsLoading) viewModel.loadMoreComments()
                            }
                    }
                }
                if (state.commentsLoading) {
                    Box(Modifier.fillMaxWidth().padding(Spacing.s), contentAlignment = Alignment.Center) {
                        LoadingIndicator(Modifier.size(20.dp))
                    }
                }
            }
        }

    }
    // Both dialogs need the note (images, comment ids), and the function returns above
    // when it has not arrived yet.
    item?.let { note ->
        openImage?.let { page ->
            com.thirdparty.xhs.ui.components.FullscreenImageViewer(
                images = note.images.ifEmpty {
                    listOf(note.cover).filter { it.isNotEmpty() }.map { com.thirdparty.xhs.data.NoteImage(it) }
                },
                initialPage = page,
                // report swipes back, so the inline gallery is on the same picture when
                // the viewer closes
                onPageChange = onImagePage,
                // FULLSCREEN: edge to edge (no content padding) so the picture runs
                // under the transparent status/navigation bars — the whole point of
                // asking for transparent bars instead of hidden ones. The viewer's own
                // chrome insets itself.
                modifier = if (fullscreen) Modifier else Modifier.padding(pad),
                onDismiss = { onOpenImage(null) }
            )
        }

        openReplies?.let { oc ->
            CommentRepliesDialog(
                noteId = note.noteId,
                commentId = oc.commentId,
                commentUserName = oc.userName,
                totalCount = oc.replyCount,
                preview = oc.replies,
                onDismiss = { openReplies = null }
            )
        }
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
 * Share the note as a clipboard 口令.
 *
 * Copies a full description rather than just `title\nlink` — see [ShareText] for
 * what it contains and why. The token is buried in that text on purpose: the
 * return-to-app flow finds it by regex, so the extra lines do not break it, and
 * the recipient gets something worth reading even if they never paste it back.
 */
private fun shareNote(context: android.content.Context, item: NoteItem?) {
    if (item == null) return
    val text = com.thirdparty.xhs.data.ShareText.of(item)
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager
    val copied = runCatching {
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("link", text))
        true
    }.getOrDefault(false)
    android.widget.Toast.makeText(
        context,
        if (copied) "分享文案已复制，回到应用可自动打开" else "复制失败",
        android.widget.Toast.LENGTH_SHORT
    ).show()
}

private fun com.thirdparty.xhs.data.NoteItem.detailTopic(): String =
    runCatching { org.json.JSONObject(rawJson).optString("topic_title") }.getOrDefault("")

private fun com.thirdparty.xhs.data.NoteItem.detail(): org.json.JSONObject =
    runCatching { org.json.JSONObject(rawJson) }.getOrElse { org.json.JSONObject() }

/**
 * How close to the end counts as "finished" for the resume position.
 *
 * A looping item parks its position at the very end just before it restarts, and
 * a position that close to the end is not somewhere worth resuming to — the user
 * would rather see it play from the beginning.
 */
private const val END_OF_MEDIA_MARGIN_MS = 1_500L

/**
 * One engagement figure: a small icon plus its count.
 *
 * Replaces a single string that read "♥105  收藏 98  评论 0" — three measurements
 * of different things welded into one run of text, where the labels carry the same
 * weight as the numbers.
 */
@Composable
private fun StatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int) {
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp)
    )
    Text(
        "$count",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 3.dp, end = Spacing.m)
    )
}