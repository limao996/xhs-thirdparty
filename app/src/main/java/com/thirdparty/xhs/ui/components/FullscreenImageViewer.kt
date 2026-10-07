package com.thirdparty.xhs.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.thirdparty.xhs.data.NoteImage
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding

/**
 * Full-screen image viewer.
 *
 * The windowed gallery scales images down to a share of the screen, so fine
 * detail is unreadable. Tapping an image opens this: paging across the whole set
 * from the tapped page, pinch-zoom up to 5x, and pan while zoomed. In the
 * examined client this is `BigPictureViewActivity`.
 *
 * Laid out as a **Column** — a fixed top bar plus the pager taking the rest —
 * rather than a Box with the pager filling it and the chrome positioned by an
 * alignment on top. As Box children the counter and close button never appeared
 * on screen; a real layout slot removes that whole class of uncertainty.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenImageViewer(
    images: List<NoteImage>,
    initialPage: Int,
    onDismiss: () -> Unit,
    /**
     * Applied to the root. Callers rendering this INSIDE a padded Scaffold content slot
     * must pass that padding: without it the viewer starts at y=0 and its top bar ends
     * up BEHIND the page's own app bar — which is exactly why the counter and close
     * button were invisible even though the viewer itself rendered fine.
     *
     * A caller that renders it in TRUE fullscreen passes nothing (edge to edge), so that
     * the picture runs under the transparent system bars; the chrome below insets
     * itself for that case.
     */
    modifier: Modifier = Modifier,
    /**
     * Reports the page being shown, so the caller can keep ONE index for this viewer
     * and the embedded gallery: swiping here and then closing lands back on the same
     * picture instead of the one the gallery was left at (the two counters used to
     * disagree).
     */
    onPageChange: ((Int) -> Unit)? = null
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, images.lastIndex)
    ) { images.size }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { onPageChange?.invoke(it) }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val haptics = rememberHaptics()
    // 双击缩放要"平滑"（用户要求），但捏合与拖动必须逐帧跟手：所以渲染用的值在
    // 「动画」与「立即」两套 spec 之间切换，只有双击和「恢复」按钮打开动画。
    var smooth by remember { mutableStateOf(false) }
    val zoomSpec = if (smooth) tween(ZOOM_ANIM_MS) else snap<Float>()
    val panSpec = if (smooth) tween(ZOOM_ANIM_MS) else snap<Offset>()
    val renderScale by animateFloatAsState(scale, animationSpec = zoomSpec, label = "zoom")
    val renderOffset by animateOffsetAsState(offset, animationSpec = panSpec, label = "pan")
    LaunchedEffect(smooth) {
        if (smooth) {
            delay(ZOOM_ANIM_MS.toLong() + 40)
            smooth = false
        }
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 双击放大/恢复：记住上一次「干净」的点击，两次够快、位置够近就算双击
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }
    // zoom is per-page: carrying it across a swipe would leave the next image
    // mysteriously cropped
    // 翻页触感与嵌入画廊共用同一个实现（用户要求两处一致）
    PagerPageHaptics(pagerState)
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    BackHandler { onDismiss() }

    // Volume keys page through the set while the viewer is up. Registered on
    // entry and cleared on exit, so the keys are untouched everywhere else.
    val keyScope = rememberCoroutineScope()
    DisposableEffect(pagerState, images.size) {
        ImageViewerKeys.register { step ->
            val target = (pagerState.currentPage + step).coerceIn(0, images.lastIndex)
            if (target != pagerState.currentPage) {
                keyScope.launch { pagerState.animateScrollToPage(target) }
            }
        }
        onDispose { ImageViewerKeys.unregister() }
    }

    // The chrome FLOATS over the picture (Box, pager drawn first, bar second and
    // explicitly aligned) so the picture can be centred on the SCREEN.
    //
    // It used to be a Column with a real top-bar slot, which cost the picture the bar's
    // height: it was centred in the space BELOW the bar, i.e. half the bar's height too
    // low. (That Column layout was itself a fix for the counter and close button never
    // appearing — the failure there was drawing the bar BEFORE the full-bleed pager, so
    // the picture covered it. Order and an explicit alignment is what keeps this one
    // safe.)
    Box(modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            // while zoomed the drag belongs to panning, not to paging
            userScrollEnabled = scale <= 1.01f,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val active = page == pagerState.currentPage
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .pointerInput(page) {
                    // NOT detectTransformGestures: that consumes every drag, so the
                    // pager never saw a swipe and horizontal paging was dead. Take
                    // the gesture only for a pinch, or to pan an image that is
                    // already zoomed; otherwise leave the drag to the pager.
                    //
                    // Taps ride along in the same handler on purpose: a second
                    // `pointerInput` for taps would compete with this one for the very
                    // same events, and the only tap action here is 双击放大/恢复.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downAt = System.currentTimeMillis()
                        var lastPos = down.position
                        var travelled = 0f
                        var pinched = false
                        do {
                            val event = awaitPointerEvent()
                            val pinch = event.calculateZoom()
                            val pan = event.calculatePan()
                            val multiTouch = event.changes.count { it.pressed } > 1
                            if (multiTouch) pinched = true
                            if (multiTouch || scale > 1f) {
                                scale = (scale * pinch).coerceIn(1f, MAX_ZOOM)
                                // Panning used to be unbounded, so a zoomed image
                                // could be pushed completely off the screen with no
                                // way back except leaving the viewer. Bound it to
                                // what the zoom level actually reveals.
                                offset = if (scale > 1f) {
                                    clampPan(offset + pan, scale, viewSize)
                                } else {
                                    Offset.Zero
                                }
                                event.changes.forEach { it.consume() }
                            }
                            travelled += pan.getDistance()
                            event.changes.firstOrNull { it.id == down.id }
                                ?.let { lastPos = it.position }
                        } while (event.changes.any { it.pressed })

                        val isTap = !pinched &&
                            System.currentTimeMillis() - downAt < TAP_MAX_MS &&
                            travelled < TAP_SLOP_PX
                        if (!isTap) {
                            // a drag/pinch ends the double-tap window
                            lastTapAt = 0L
                        } else {
                            val now = System.currentTimeMillis()
                            val doubled = now - lastTapAt < DOUBLE_TAP_MS &&
                                (lastPos - lastTapPos).getDistance() < DOUBLE_TAP_SLOP_PX
                            if (doubled) {
                                lastTapAt = 0L
                                // 双击是"跳到"另一个倍率，动画化（捏合/拖动必须跟手，见下面的
                                // smooth 开关：只有双击和「恢复」按钮会打开它）
                                haptics.tick()
                                smooth = true
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    // zoom towards the tapped point, so the spot the
                                    // user aimed at stays under their finger
                                    val centre = Offset(
                                        viewSize.width / 2f, viewSize.height / 2f
                                    )
                                    scale = DOUBLE_TAP_ZOOM
                                    offset = clampPan(
                                        (centre - lastPos) * (DOUBLE_TAP_ZOOM - 1f),
                                        DOUBLE_TAP_ZOOM, viewSize
                                    )
                                }
                            } else {
                                lastTapAt = now
                                lastTapPos = lastPos
                            }
                        }
                    }
                },
                contentAlignment = Alignment.Center
            ) {
                XhsAsyncImage(
                    url = images[page].url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = if (active) renderScale else 1f,
                        scaleY = if (active) renderScale else 1f,
                        translationX = if (active) renderOffset.x else 0f,
                        translationY = if (active) renderOffset.y else 0f
                    )
                )
            }
        }

        // Reset affordance, shown only while zoomed. Without it the only ways back
        // were pinching out again or closing the viewer, which is not discoverable
        // when the image has been panned off-screen.
        if (scale > 1.01f) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    // 查看器是边缘到边缘的（全屏时图片要跑到系统栏下面），所以这个
                    // "恢复"按钮必须自己让开导航栏，否则会被手势条压住/挡住（用户反馈）。
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = Spacing.l),
                contentAlignment = Alignment.Center
            ) {
                ResetZoomButton(onClick = {
                    haptics.tick()
                    smooth = true
                    scale = 1f
                    offset = Offset.Zero
                })
            }
        }

        // the bar goes LAST so it draws over the picture, and is aligned explicitly
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().background(Scrim.chrome)
                // the root can be edge to edge (fullscreen), so the bar's own chrome
                // must clear the status bar — the PICTURE is what belongs underneath it
                .statusBarsPadding()
                .padding(horizontal = Spacing.s, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${pagerState.currentPage + 1}/${images.size}",
                color = Scrim.onMedia,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).padding(start = Spacing.s)
            )
            // 屏幕上的关闭按钮要有触感（用户要求）：系统返回手势不用我们管，
            // 但这是我们自己画的按钮，按下去得有反馈。
            IconButton(onClick = haptics.click(onDismiss)) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Scrim.onMedia)
            }
        }
    }
}

/** Padding kept around a zoomed image so an edge is never flush with the screen. */
private const val PAN_SLACK_FRACTION = 0.12f

/**
 * Limits panning to the area the current zoom actually reveals.
 *
 * A fitted image scaled by `s` overflows the view by `size * (s - 1) / 2` on each
 * side; past that there is only empty background. Panning used to be unbounded, so
 * a zoomed image could be pushed completely off-screen with no way back, and the
 * cap is computed from the container size so it scales with the zoom.
 */
private fun clampPan(offset: Offset, scale: Float, view: IntSize): Offset {
    if (scale <= 1f || view.width == 0 || view.height == 0) return Offset.Zero
    val slack = 1f + PAN_SLACK_FRACTION
    val maxX = view.width * (scale - 1f) / 2f * slack
    val maxY = view.height * (scale - 1f) / 2f * slack
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY)
    )
}

/** How far a pinch may zoom in. */
private const val MAX_ZOOM = 5f

/** 双击放大的倍率（再双击一次回到 1x）。 */
private const val DOUBLE_TAP_ZOOM = 2.5f
/** 双击缩放/「恢复」的动画时长（捏合与拖动不用动画，必须跟手）。 */
private const val ZOOM_ANIM_MS = 240
/** 识别双击用的窗口：单击最长时长、两次点击最大间隔、允许的位移。 */
private const val TAP_MAX_MS = 260L
private const val DOUBLE_TAP_MS = 300L
private const val TAP_SLOP_PX = 24f
private const val DOUBLE_TAP_SLOP_PX = 140f