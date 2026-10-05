@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.thirdparty.xhs.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.thirdparty.xhs.common.RepoViewModelFactory
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.components.BufferingIndicator
import com.thirdparty.xhs.ui.components.EmptyState
import com.thirdparty.xhs.ui.components.FeeBadge
import com.thirdparty.xhs.ui.components.PauseWhenNotStarted
import com.thirdparty.xhs.ui.components.VideoSurface
import com.thirdparty.xhs.ui.components.PlaybackErrorOverlay
import com.thirdparty.xhs.ui.components.RecoverStuckPlayback
import com.thirdparty.xhs.ui.components.STUCK_PLAYBACK_EXCEPTION
import com.thirdparty.xhs.ui.components.PlayerView
import com.thirdparty.xhs.ui.components.XhsAsyncImage
import com.thirdparty.xhs.ui.components.VideoProgress
import com.thirdparty.xhs.ui.components.buildVideoPlayer
import com.thirdparty.xhs.ui.components.rememberIsPlaying
import com.thirdparty.xhs.ui.components.rememberPlaybackError
import com.thirdparty.xhs.ui.components.retryPlayback
import com.thirdparty.xhs.ui.components.NoteActionDialog
import com.thirdparty.xhs.ui.components.WatchLaterBar
import com.thirdparty.xhs.ui.components.rememberHaptics
import com.thirdparty.xhs.ui.components.rememberNoteActions
import com.thirdparty.xhs.ui.components.rememberNoteFlags
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.VideoFeedViewModel
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars

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
    /**
     * Whether this page's chrome (info bar, fee tag) is showing.
     *
     * Owned by the host shell: the header and the bottom navigation belong to it,
     * not to this screen, so a tap that only retires the caption leaves them sitting
     * over the video. The shell holds the single flag, hands it down, and renders
     * its own chrome from it.
     */
    infoVisible: Boolean = true,
    /** A tap flipped [infoVisible]; the shell owns the value. */
    onInfoVisibleChange: (Boolean) -> Unit = {},
    /** 稍后观看队列：信息条用的入口（推荐页用信息条而不是浮动按钮） */
    onOpenWatchLater: () -> Unit = {},
    viewModel: VideoFeedViewModel = viewModel(factory = RepoViewModelFactory())
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { state.items.size }
    // 换挡触感用在下面「落定页变了」的地方
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) {
        if (state.items.isEmpty() && !state.firstLoading) viewModel.loadMore()
    }
    // refresh when the 推荐 tab is re-tapped.
    //
    // Guarded on a CHANGE of refreshTick, not on `refreshTick > 0`: a
    // LaunchedEffect also re-runs whenever the composable re-enters the
    // composition, so after the user had refreshed once (`refreshTick == 1`)
    // every return from the detail page would reload the feed and jump back to
    // the first video — losing their place mid-session.
    var refreshTickSeen by remember { mutableStateOf(-1) }
    LaunchedEffect(refreshTick) {
        if (refreshTickSeen == refreshTick) return@LaunchedEffect
        val isFirst = refreshTickSeen == -1
        refreshTickSeen = refreshTick
        if (!isFirst) {
            // an actual refresh returns the viewer to the first video, otherwise
            // they stay parked on the old position
            pagerState.scrollToPage(0)
            viewModel.refresh()
        }
    }

    if (state.items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                state.firstLoading -> LoadingIndicator()
                state.error -> EmptyState(
                    title = "推荐加载失败",
                    modifier = Modifier.fillMaxSize(),
                    description = "请检查网络后重试",
                    actionLabel = "重试",
                    onAction = { viewModel.refresh() }
                )
                else -> EmptyState(
                    title = "暂无推荐内容",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "刷新",
                    onAction = { viewModel.refresh() }
                )
            }
        }
        return
    }

    // 长按弹出的作品菜单（收藏 / 稍后观看）。状态放在这一层：整屏只可能有一个菜单，
    // 而每一页各自去读「哪些作品已收藏」会把同一个查询做几十遍。
    var menuFor by remember { mutableStateOf<NoteItem?>(null) }
    val noteFlags = rememberNoteFlags()
    val noteActions = rememberNoteActions()

    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().background(Color.Black),
            // keep the immediate neighbours composed so their players can pre-buffer
            beyondViewportPageCount = 1
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
                infoVisible = infoVisible,
                onToggleInfo = { onInfoVisibleChange(!infoVisible) },
                onWatched = { viewModel.recordView(item) },
                onClickDetail = { onOpenDetail(item.noteId) },
                onLongPress = { menuFor = it },
                onOpenWatchLater = onOpenWatchLater
            )
        }

        // 视频是全屏的，「就地小面板」没有锚点可依附，所以用对话框
        menuFor?.let { note ->
            NoteActionDialog(
                title = note.title,
                saved = note.noteId in noteFlags.savedIds,
                inWatchLater = note.noteId in noteFlags.watchLaterIds,
                onToggleSave = { noteActions.toggleSave(note) },
                onToggleWatchLater = { noteActions.toggleWatchLater(note) },
                onDismiss = { menuFor = null }
            )
        }
    }

    // Every video starts with its chrome showing, the way the first one does.
    // Without this, retiring the overlays on one clip left every following clip
    // bare as well, and the only way back was to tap blind.
    // 换到下一个视频（滑动落定）给一次"换挡"触感
    LaunchedEffect(pagerState.settledPage) { onInfoVisibleChange(true) }
    var pipSegmentSeen by remember { mutableStateOf(-1) }
    LaunchedEffect(pagerState.settledPage) {
        if (pipSegmentSeen != -1 && pipSegmentSeen != pagerState.settledPage) haptics.segment()
        pipSegmentSeen = pagerState.settledPage
    }

    // pagination driven by the settled page (side-effect free, runs off composition)
    LaunchedEffect(pagerState.settledPage, state.items.size) {
        if (state.items.isNotEmpty() && pagerState.settledPage >= state.items.size - 3) {
            viewModel.loadMore()
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun VideoPage(
    item: NoteItem,
    active: Boolean,
    nearby: Boolean,
    /** owned by the host shell — see [VideoFeedScreen.infoVisible] */
    infoVisible: Boolean,
    onToggleInfo: () -> Unit,
    onWatched: () -> Unit,
    onClickDetail: () -> Unit,
    /** 长按：弹出作品菜单（收藏 / 稍后观看），由上层渲染 */
    onLongPress: (NoteItem) -> Unit,
    /** 信息条上的稍后观看入口 */
    onOpenWatchLater: () -> Unit
) {
    val haptics = rememberHaptics()
    // the video's real width/height ratio; used to size the surface so the
    // picture is never stretched (FILL would distort, ZOOM would crop).
    var videoAspect by remember(item.noteId) { mutableFloatStateOf(9f / 16f) }
    val player: ExoPlayer? = rememberPreparedPlayer(item.mediaUrl, nearby) { r ->
        if (r > 0f) videoAspect = r
    }
    // derive "paused" from the player itself, not from the last double-tap, so an
    // externally caused pause (audio focus loss, codec stall) is reflected too
    val playing = rememberIsPlaying(player)
    val paused = active && player != null && !playing &&
        player.playbackState == Player.STATE_READY
    var decoderStuck by remember(player) { mutableStateOf(false) }
    val playbackError =
        rememberPlaybackError(player) ?: if (decoderStuck) STUCK_PLAYBACK_EXCEPTION else null

    // only the current page plays; neighbours stay prepared (paused)
    LaunchedEffect(active, player) {
        val p = player ?: return@LaunchedEffect
        if (active) {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        } else {
            // **放过已经交出去的播放器**：从推荐页点进详情时，这一台被交给详情页
            // （再交给小窗）。信息流这一侧只是"在底下"而已，它一旦按 active=false
            // 去 pause，就会把小窗里正在播的画面按停（用户反馈：从推荐页进详情再开小窗，
            // 小窗依然自动暂停）。归属判断与详情页销毁、inPip 分支同一套规则。
            val handedOver = com.thirdparty.xhs.ui.components.PipController.isHandedOver(p)
            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                android.util.Log.i("XhsPip", "feed active=false handedOver=$handedOver")
            }
            if (!handedOver) p.pause()
        }
    }
    // recording the view is a separate effect so a player rebuild does not
    // re-stamp viewedAt and reshuffle 最近浏览
    LaunchedEffect(active) { if (active) onWatched() }
    // Do NOT use `pointerInput { detectTapGestures }` here.
    //
    // pointerInput compares its block by identity and rebuilds it on every
    // recomposition, which cancels the in-flight gesture. The first single tap
    // flips infoVisible -> recomposition -> the second tap of a double-tap was
    // swallowed, so onDoubleTap never fired (verified with logs: after one single
    // tap, a double tap produced only a single onTap). combinedClickable's node
    // instead UPDATES its callbacks in place, so recomposition never interrupts a
    // gesture in progress.
    //
    // `player` is wrapped in rememberUpdatedState so the callback the node holds
    // never points at a released player.
    val currentPlayer = androidx.compose.runtime.rememberUpdatedState(player)
    // 小窗接管播放时，信息流自己的播放器必须停：否则小窗在前面放着，
    // 后面的推荐流也在放，用户听到的是两条声音混在一起（用户实测反馈）。
    //
    // 但要**放过小窗那一台**：从推荐页点进详情时交给详情页、再由详情页交给小窗的
    // 就是同一台播放器，无差别 pause 会把小窗里的视频一起按停（实测：小窗里视频停住）。
    // 判断归属用 PipController.isHandedOver，和详情页销毁时是同一套规则。
    val inPip by com.thirdparty.xhs.ui.components.PipController.inPip
        .collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(inPip) {
        if (!inPip) return@LaunchedEffect
        val p = currentPlayer.value ?: return@LaunchedEffect
        val handedOver = com.thirdparty.xhs.ui.components.PipController.isHandedOver(p)
        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
            android.util.Log.i("XhsPip", "feed inPip 分支 handedOver=$handedOver")
        }
        if (!handedOver) {
            runCatching { p.pause() }
        }
    }
    val noRipple = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            // combinedClickable, NOT pointerInput { detectTapGestures }.
            //
            // pointerInput compares its block by identity and rebuilds it on every
            // recomposition, which cancels the in-flight gesture. The first single
            // tap flips infoVisible -> recomposition -> the second tap of a
            // double-tap was swallowed, so onDoubleTap never fired (verified with
            // logs: after one single tap, a double tap produced only a single
            // onTap). clickable's node instead UPDATES its callbacks in place, so
            // recomposition never interrupts a gesture in progress.
            .combinedClickable(
                interactionSource = noRipple,
                indication = null,
                onClick = {
                    haptics.tick()
                    onToggleInfo()
                },
                onDoubleClick = {
                    haptics.tick()
                    togglePlayback(currentPlayer.value)
                },
                onLongClick = {
                    haptics.longPress()
                    onLongPress(item)
                }
            )
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
        //
        // VideoSurface (TextureView), not PlayerView: when a feed item opens the
        // detail page, a SurfaceView-backed player would keep painting its last
        // frame over the outgoing screen for the whole transition.
        if (player != null) {
            VideoSurface(
                player = player,
                videoAspect = videoAspect,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center)
            )
            // feedback while the stream buffers / starts up — hidden once the
            // error panel is up, otherwise the two draw on top of each other
            RecoverStuckPlayback(player) { decoderStuck = true }
            if (playbackError == null) {
                BufferingIndicator(player, modifier = Modifier.fillMaxSize(), active = active)
            }
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

        // The system navigation bar is now VISIBLE (transparent) and the app's own
        // bottom NavigationBar grows by that inset, so a fixed clearance left the
        // overlays sitting underneath it. Read the real inset instead.
        val navBarInset = androidx.compose.foundation.layout.WindowInsets.navigationBars
            .asPaddingValues().calculateBottomPadding()

        // info bar (bottom, toggled by single tap) — tapping it opens detail.
        // Lifted above the bottom navigation bar so it stays tappable.
        if (infoVisible) {
            // 信息条 + 信息栏：**两段**，信息条在外（自己的背景、自己的留白），
            // 不要塞进信息栏的背景里 —— 塞进去就变成"信息栏的一部分"了（用户要求放外面）。
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .padding(bottom = BottomNavHeight + navBarInset)
            ) {
                WatchLaterBar(
                    onOpen = onOpenWatchLater,
                    modifier = Modifier.padding(horizontal = Spacing.l)
                )
                Spacer(Modifier.height(Spacing.s))
                Column(
                Modifier.fillMaxWidth()
                    .background(Scrim.strong)
                    // Single tap opens the detail; a DOUBLE tap must still reach the
                    // player. This strip sits over the video, so a plain `clickable`
                    // swallowed the gesture and a double-tap here opened the detail
                    // instead of pausing — which is why double-tap appeared broken
                    // whenever the finger landed on the lower part of the video.
                    // same reasoning as the video surface above
                    .combinedClickable(
                        // 信息条是"可点的"东西，波纹要用系统的 ripple（M3），并显式给它
                        // 媒体层上的浅色 —— 默认色是深色 onSurface，压在暗色信息栏上看不出来
                        // （用户反馈"波纹不明显"）。
                        //
                        // 这里**不接 onDoubleClick**：combinedClickable 带双击时，单击必须等
                        // 双击判定窗口（~300ms）过去才会触发，用户的感觉就是"点了半天才跳转"。
                        // 双击暂停交给上面那层视频区域（它才需要区分单击/双击）。
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(color = Scrim.onMedia),
                        onClick = {
                            haptics.tick()
                            // Hand the player ITSELF over, not just its position: the
                            // detail page would otherwise build a second ExoPlayer on
                            // the same stream and pay for a fresh prepare, a fresh
                            // playlist fetch and a fresh buffer — which is what the
                            // "restart with a re-buffer" actually was, position
                            // handoff or not. No pause here either: the detail picks
                            // the same player straight up, and pausing would show a
                            // frozen frame for the whole transition.
                            //
                            // The position is still stashed: it is the fallback for a
                            // detail page that could not adopt (nothing waiting), and
                            // it is what makes 重播/断点续播 land in the right place.
                            currentPlayer.value?.let {
                                // playWhenReady, not isPlaying: the latter is false while
                                // the player is momentarily buffering, and handing that
                                // over reads as "the user had it paused".
                                val playing = runCatching { it.playWhenReady }.getOrDefault(false)
                                val pos = runCatching { it.currentPosition }.getOrDefault(0L)
                                runCatching {
                                    com.thirdparty.xhs.ui.components.PlaybackHandoff.stash(
                                        item.noteId, pos, playing
                                    )
                                }
                                runCatching {
                                    com.thirdparty.xhs.ui.components.PlaybackHandoff
                                        .givePlayer(item.noteId, it)
                                }
                            }
                            onClickDetail()
                        }
                    )
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
            }
            // fee tag at top-right — pushed below the translucent header so it
            // never collides with the search icon.
            // statusBarsPadding() is required in addition to HeaderClearance: the
            // header gained a status-bar inset when that bar became visible, so the
            // old fixed clearance alone left the tag sitting on the search icon.
            FeeBadge(
                item,
                modifier = Modifier.align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = HeaderClearance, end = Spacing.l)
            )
        }

        // thin playback progress line at the bottom of the video
        VideoProgress(
            player = player,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(bottom = BottomNavHeight + navBarInset)
                .height(2.dp)
        )
    }
}

/**
 * Height of the translucent feed header's own content, so overlays clear it.
 * Excludes the status-bar inset — overlays must add `statusBarsPadding()` too,
 * otherwise they drift up by a full status bar whenever that bar is visible.
 */
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
    // Rotating recreates the Activity, which would rebuild this player and restart
    // the clip. rememberSaveable carries the position across the change.
    var resumeMs by androidx.compose.runtime.saveable.rememberSaveable(url) {
        androidx.compose.runtime.mutableLongStateOf(0L)
    }
    val player = remember(url, prepare) {
        if (url.isBlank() || !prepare) null
        else buildVideoPlayer(context, url, autoPlay = false)
    }
    LaunchedEffect(player) {
        if (player != null && resumeMs > 0L) player.seekTo(resumeMs)
    }
    LaunchedEffect(player) {
        val p = player ?: return@LaunchedEffect
        while (true) {
            if (p.isPlaying) resumeMs = p.currentPosition
            kotlinx.coroutines.delay(500)
        }
    }
    // stop playback/audio when the app leaves the foreground.
    //
    // NOT on dispose, though (`pauseOnDispose = false`): this disposal happens as part
    // of the navigation that HANDS THIS PLAYER OVER to the detail page, and pausing
    // here stopped the very video the detail page had just taken over — racing with
    // the detail's own resume, which is why the clip "sometimes" arrived paused. When
    // the page is disposed without a hand-over, the player is released below anyway
    // (stop + clear + release), which ends the audio just as well.
    PauseWhenNotStarted(player, pauseOnDispose = false)
    DisposableEffect(player) {
        val p = player
        val listener = if (p == null) null else object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // 与详情页/小窗共用同一个比例判据（含旋转修正与像素比），别再各算一套（审计 F9）
                val a = com.thirdparty.xhs.ui.components.PipController.videoAspectOf(videoSize)
                if (a > 0f) aspect(a)
            }
        }
        if (p != null && listener != null) p.addListener(listener)
        onDispose { if (p != null && listener != null) p.removeListener(listener) }
    }
    DisposableEffect(player, prepare) {
        onDispose {
            player?.let {
                // A player that was handed to the detail page on the way out is NOT
                // ours to release any more: this disposal runs as part of the very
                // navigation that transferred it, so releasing here would kill the
                // player the detail page is about to render. Once the feed has taken it
                // back the mark is cleared and this releases normally.
                if (com.thirdparty.xhs.ui.components.PlaybackHandoff.isHandedOver(it)) {
                    return@onDispose
                }
                it.stop()
                it.clearMediaItems()
                it.release()
            }
        }
    }
    return player
}

/** Flip play/pause on a feed player; shared by the video surface and the info bar. */
private fun togglePlayback(player: androidx.media3.common.Player?) {
    if (player == null) return
    if (player.isPlaying) player.pause() else player.play()
}