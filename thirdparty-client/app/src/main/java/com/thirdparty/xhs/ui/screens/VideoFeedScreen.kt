package com.thirdparty.xhs.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.thirdparty.xhs.App
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.components.BufferingIndicator
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.FeeBadge
import com.thirdparty.xhs.ui.components.PauseWhenNotStarted
import com.thirdparty.xhs.ui.components.PlaybackErrorOverlay
import com.thirdparty.xhs.ui.components.PlayerView
import com.thirdparty.xhs.ui.components.XhsAsyncImage
import com.thirdparty.xhs.ui.components.VideoProgress
import com.thirdparty.xhs.ui.components.buildVideoPlayer
import com.thirdparty.xhs.ui.components.rememberPlaybackError
import com.thirdparty.xhs.ui.components.retryPlayback
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.VideoFeedViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 推荐短视频（沉浸式）：
 * - 全屏沉浸上下滑，自动播放 HLS
 * - 单击：切换信息栏与控制栏显隐；双击：播放/暂停
 * - 费用(tag) 置于右上角（跟随信息栏）；点信息栏(标题区)进详情
 * - 滑到底自动加载下一页
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoFeedScreen(
    onOpenDetail: (Long) -> Unit,
    refreshTick: Int = 0,
    viewModel: VideoFeedViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { state.items.size }

    LaunchedEffect(Unit) {
        if (state.items.isEmpty() && !state.firstLoading) viewModel.loadMore()
    }
    // refresh when the 推荐 tab is re-tapped
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0) viewModel.refresh()
    }

    if (state.items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                state.firstLoading -> CircularProgressIndicator()
                state.error -> EmptyState(
                    title = "推荐加载失败",
                    description = "请检查网络后重试",
                    actionLabel = "重试",
                    onAction = { viewModel.refresh() }
                )
                else -> EmptyState(
                    title = "暂无推荐内容",
                    actionLabel = "刷新",
                    onAction = { viewModel.refresh() }
                )
            }
        }
        return
    }

    VerticalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().background(Color.Black),
        // keep the immediate neighbours composed so their players can pre-buffer
        beyondBoundsPageCount = 1
    ) { index ->
        val item = state.items[index]
        val isCurrent = pagerState.currentPage == index
        // Preload the neighbours, keyed on the *settled* page: `currentPage`
        // changes continuously while dragging, which would create and destroy
        // neighbour players repeatedly and cause jank.
        val nearby = kotlin.math.abs(index - pagerState.settledPage) <= 1
        VideoPage(
            item = item,
            active = isCurrent,
            nearby = nearby,
            onWatched = { viewModel.recordView(item) },
            onClickDetail = { onOpenDetail(item.noteId) }
        )
    }

    // pagination driven by the settled page (side-effect free, runs off composition)
    LaunchedEffect(pagerState.settledPage, state.items.size) {
        if (state.items.isNotEmpty() && pagerState.settledPage >= state.items.size - 3) {
            viewModel.loadMore()
        }
    }
}

@Composable
private fun VideoPage(
    item: NoteItem,
    active: Boolean,
    nearby: Boolean,
    onWatched: () -> Unit,
    onClickDetail: () -> Unit
) {
    var infoVisible by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    // the video's real width/height ratio; used to size the surface so the
    // picture is never stretched (FILL would distort, ZOOM would crop).
    var videoAspect by remember(item.noteId) { mutableFloatStateOf(9f / 16f) }
    val player: ExoPlayer? = rememberPreparedPlayer(item.mediaUrl, nearby) { r ->
        if (r > 0f) videoAspect = r
    }
    val playbackError = rememberPlaybackError(player)

    // only the current page plays; neighbours stay prepared (paused)
    LaunchedEffect(active, player) {
        val p = player ?: return@LaunchedEffect
        if (active) {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        } else {
            p.pause()
        }
    }
    // recording the view is a separate effect so a player rebuild does not
    // re-stamp viewedAt and reshuffle 最近浏览
    LaunchedEffect(active) { if (active) onWatched() }
    LaunchedEffect(active) { if (!active) paused = false }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(item.noteId) {
                detectTapGestures(
                    onTap = { infoVisible = !infoVisible },
                    onDoubleTap = {
                        val p = player
                        if (p != null) { if (p.isPlaying) { p.pause(); paused = true } else { p.play(); paused = false } }
                    }
                )
            }
    ) {
        // poster cover behind the player so the page is never a black void.
        // ContentScale.Fit keeps the poster undistorted as well.
        val poster = item.cover.ifEmpty { item.thumbnail }
        if (poster.isNotEmpty()) {
            XhsAsyncImage(
                url = poster,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // video surface: container matches the video's own ratio, fills the
        // width and is vertically centred — original proportions, no crop.
        if (player != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PlayerView(
                    player = player,
                    useController = false,
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    modifier = Modifier.fillMaxWidth().aspectRatio(videoAspect)
                )
            }
            // feedback while the stream buffers / starts up
            BufferingIndicator(player, modifier = Modifier.fillMaxSize())
            // a dead stream must not fail silently
            PlaybackErrorOverlay(
                error = playbackError,
                onRetry = { retryPlayback(player) },
                modifier = Modifier.fillMaxSize()
            )
        }

        // center play/pause flash icon on double-tap pause
        if (paused) {
            Icon(
                Icons.Filled.Pause,
                contentDescription = "已暂停",
                tint = Scrim.onMediaVariant,
                modifier = Modifier.size(72.dp).align(Alignment.Center)
            )
        }

        // info bar (bottom, toggled by single tap) — tapping it opens detail.
        // Lifted above the bottom navigation bar so it stays tappable.
        if (infoVisible) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(bottom = BottomNavHeight)
                    .background(Scrim.strong)
                    .clickable { onClickDetail() }
                    .padding(Spacing.l)
            ) {
                Text(
                    item.title.ifEmpty { "(无标题)" },
                    color = Scrim.onMedia,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "@${item.userName} · ♥${item.likeCount}",
                    color = Scrim.onMediaVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // fee tag at top-right — pushed below the translucent header so it
            // never collides with the search icon
            FeeBadge(
                item,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = HeaderClearance, end = Spacing.l)
            )
        }

        // thin playback progress line at the bottom of the video
        VideoProgress(
            player = player,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = BottomNavHeight)
                .height(2.dp)
        )
    }
}

/** Height reserved for the translucent feed header so overlays clear it. */
private val HeaderClearance = 76.dp

/** Height reserved for the bottom NavigationBar so overlays clear it. */
private val BottomNavHeight = 80.dp

/**
 * Player for one feed page.
 *
 * [prepare] keeps the player alive (buffering, paused) for the pages adjacent to
 * the current one, so swiping starts playback instantly instead of paying a
 * fresh prepare + network round-trip each time. Players outside that window are
 * released.
 */
@Composable
private fun rememberPreparedPlayer(
    url: String,
    prepare: Boolean,
    onAspect: (Float) -> Unit = {}
): ExoPlayer? {
    val context: Context = LocalContext.current.applicationContext
    val aspect by rememberUpdatedState(onAspect)
    val player = remember(url, prepare) {
        if (url.isBlank() || !prepare) null
        else buildVideoPlayer(context, url, autoPlay = false)
    }
    // stop playback/audio when the app leaves the foreground
    PauseWhenNotStarted(player)
    DisposableEffect(player) {
        val p = player
        val listener = if (p == null) null else object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    aspect(videoSize.width.toFloat() / videoSize.height.toFloat())
                }
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    DisposableEffect(player, prepare) {
        onDispose {
            player?.let {
                it.stop()
                it.clearMediaItems()
                it.release()
            }
        }
    }
    return player
}