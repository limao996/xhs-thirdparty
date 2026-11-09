package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import com.thirdparty.xhs.BuildConfig
import com.thirdparty.xhs.data.ResumeRule
import com.thirdparty.xhs.data.VideoProgressStore
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.delay

/** 横条停留时长（用户要求 5 秒）。 */
private const val BANNER_MS = 5_000L

/**
 * 「继续观看（上次看到 x:xx）」横条 + 观看进度记录（2026-11-09 深度审查后重写）。
 *
 * ## 记录部分的设计要点（每条都是修过的缺陷）
 *
 * 1. **循环只以 `noteId` 为 key**，播放器用 [rememberUpdatedState] 取 —— 之前把 `player`
 *    也放进 key，播放器实例一变（全屏切换、信息流交接）循环就重启，`lastSaved` 被重置，
 *    中间留出空档 → 用户看到的"上次看到"比实际返回位置**早一大截**。
 * 2. **`lastSaved` 放在 `remember` 里**，跨重启保留，避免重复写同一个位置。
 * 3. **三种时机都落盘**：每 500ms 轮询（位置变化 ≥0.5s）、`ON_PAUSE`（真正离开页面）、
 *    `onDispose`。只靠轮询不行 —— 组合被拆掉时循环先死，最后一段就丢了。
 * 4. **只在前台写**：详情页返回后这份组合可能还活着（信息流在下面继续播），
 *    不限前台会把进度写到更后面 —— 那是另一个方向的偏差。
 * 5. **一律 `runCatching`**：离开时播放器可能已释放。
 * 6. 记录值一律取 **`player.currentPosition`**（播放器真实进度），不读任何 UI 滑块状态。
 *
 * ## 显示部分
 *
 * - 触发信号是"又进入本页"（[entryKey] 递增）或播放器实例变化，不用"是否提示过"的布尔标记；
 * - 横条 `zIndex(2f)` 压在控制栏之上（否则会被控制栏深色底整个盖住）；
 * - 5 秒自动消失用**独立 effect** 计时（写在判断 effect 里会被重启取消，横条就再也不消失）；
 * - 点击 → `seekTo` 记录的进度并立刻隐藏；✕ → 只隐藏。
 *
 * @param noteId 作品 id；null（信息流里的连续滑动视频）时整个组件什么都不做
 * @param entryKey 每次真正回到页面时由调用方递增
 * @param barHeight 控制栏实测高度，横条底边距 = 它 + 4dp
 */
@Composable
fun BoxScope.ResumePlaybackBanner(
    player: Player,
    noteId: Long?,
    entryKey: Int,
    barHeight: Dp
) {
    val appContext = LocalContext.current.applicationContext
    val haptics = rememberHaptics()

    var targetMs by remember(noteId) { mutableLongStateOf(0L) }
    var showing by remember(noteId) { mutableStateOf(false) }

    /** 统一的一次落盘（读播放器真实进度）。 */
    fun saveNow(reason: String) {
        val id = noteId ?: return
        val pos = runCatching { player.currentPosition }.getOrDefault(0L)
        val dur = runCatching { player.duration }.getOrDefault(0L)
        if (pos <= 0L) return
        VideoProgressStore.save(appContext, id, pos, dur)
        if (BuildConfig.DEBUG) {
            android.util.Log.i("XhsProgress", reason + " note=" + id + " pos=" + pos + " dur=" + dur)
        }
    }

    // ── 生命周期：离开前落一次，且离开后不再写 ─────────────────────────
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> saveNow("pause save")
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 不做周期轮询 ──────────────────────────────────────────────────
    // 用户要求「推荐页短视频不要记录，只记录详情页」。轮询存活的边界是"组合在不在"，
    // 而详情页返回推荐页时 Activity 并不会 pause（只有导航变化），轮询会继续把
    // 推荐页在播的短视频位置写进来。所以改成**只在离开详情页时落一次真实位置**：
    // ON_PAUSE（切后台/锁屏）与 onDispose（返回上一页/页面销毁）。

    // 组合被拆掉时最后落一次
    DisposableEffect(noteId) {
        onDispose { saveNow("dispose save") }
    }

    // ── 进入页面判断一次 ────────────────────────────────────────────────
    LaunchedEffect(noteId, entryKey, player) {
        val id = noteId ?: return@LaunchedEffect
        if (showing) return@LaunchedEffect
        val saved = VideoProgressStore.load(appContext, id) ?: return@LaunchedEffect
        val ok = ResumeRule.shouldResume(saved.positionMs, saved.durationMs)
        if (BuildConfig.DEBUG) {
            android.util.Log.i(
                "XhsResume",
                "check note=" + id + " at=" + saved.positionMs + " dur=" + saved.durationMs +
                    " resumable=" + ok
            )
        }
        if (!ok) return@LaunchedEffect
        targetMs = saved.positionMs
        showing = true
    }

    // 5 秒自动消失：独立 effect，showing 变化就重新计时
    LaunchedEffect(showing) {
        if (!showing) return@LaunchedEffect
        delay(BANNER_MS)
        showing = false
    }

    if (!showing) return

    Row(
        Modifier
            .zIndex(2f)
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(start = Spacing.m, end = Spacing.s, bottom = barHeight + Spacing.xs)
            .clip(MaterialTheme.shapes.small)
            .background(Scrim.strong)
            .clickable {
                haptics.confirm()
                runCatching { player.seekTo(targetMs) }
                showing = false
                if (BuildConfig.DEBUG) {
                    android.util.Log.i("XhsResume", "tap -> seek " + targetMs)
                }
            }
            .padding(start = Spacing.m, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "继续观看（上次看到 " + formatPosition(targetMs) + "）",
            color = Scrim.onMedia,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = { haptics.tick(); showing = false },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "关闭",
                tint = Scrim.onMedia,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 毫秒 → `m:ss`。 */
private fun formatPosition(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return (total / 60).toString() + ":" + (total % 60).toString().padStart(2, '0')
}
