package com.thirdparty.xhs.ui.components

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.media3.exoplayer.ExoPlayer
import com.thirdparty.xhs.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 画中画（PiP）小窗的持有者。
 *
 * 只管三件事：
 *  1. **播放器交给谁**：详情页把 `ExoPlayer` 交给小窗之后，详情页的销毁流程就不能再
 *     release 它（和 [PlaybackHandoff] 同一类问题，同一个解法：[isHandedOver]）。
 *  2. **当前是不是在小窗里**：小窗宿主（MainActivity 的 Compose 侧）据此只画视频，
 *     稍后观看浮动按钮据此隐藏。
 *  3. **小窗控制栏**：画中画该有的三个动作 —— 后退 10 秒、播放/暂停、前进 10 秒，
 *     都是系统标准的 `RemoteAction`（窗口上的关闭 / 展开由系统提供）。画中画窗口最多
 *     显示 3 个自定义按钮，所以恰好放满这三个，不再往里塞别的（队列属于主界面）。
 */
object PipController {

    class Session(val player: ExoPlayer, val noteId: Long, val title: String)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** true = Activity 现在处于画中画模式（窗口被系统缩小显示） */
    val inPip = MutableStateFlow(false)

    /** 展开小窗后要跳回的详情页（MainActivity 消费一次） */
    val pendingDetailId = MutableStateFlow<Long?>(null)

    // ---- 生命周期 ----------------------------------------------------------

    /** 详情页点「小窗播放」：把播放器交出去。 */
    fun start(player: ExoPlayer, noteId: Long, title: String) {
        _session.value = Session(player, noteId, title)
        inPip.value = true
    }

    fun hasSession(): Boolean = _session.value != null

    /** 播放器已经交给小窗，详情页销毁时不许 release。 */
    fun isHandedOver(player: ExoPlayer): Boolean = _session.value?.player === player

    /** 展开回详情页：把播放器交回给详情页（详情页通过 PlaybackHandoff 认领）。 */
    fun handBackForDetail(): Session? {
        val s = _session.value ?: return null
        _session.value = null
        inPip.value = false
        return s
    }

    /** 用户关掉小窗（或 Activity 被销毁）：停止并销毁播放器。 */
    fun closeAndRelease() {
        _session.value?.player?.let { p ->
            runCatching {
                p.stop()
                p.clearMediaItems()
                p.release()
            }
        }
        _session.value = null
        inPip.value = false
    }

    // ---- 控制栏 ------------------------------------------------------------

    /** 小窗控制栏的动作：后退 / 播放暂停 / 前进。 */
    const val ACTION_REWIND = "com.thirdparty.xhs.pip.REWIND"
    const val ACTION_PLAY_PAUSE = "com.thirdparty.xhs.pip.PLAY_PAUSE"
    const val ACTION_FORWARD = "com.thirdparty.xhs.pip.FORWARD"

    /** 小窗里前进/后退的步长（秒）。 */
    private const val SEEK_STEP_MS = 10_000L

    /**
     * 小窗的窗口参数：画面比例 + 三个标准动作。
     *
     * 比例取自播放器的视频尺寸（横屏片 16:9、竖屏片 9:16 都能占满窗口，不留黑边）。
     */
    fun buildParams(activity: Activity, player: ExoPlayer?): PictureInPictureParams? {
        val builder = PictureInPictureParams.Builder()
        player?.videoSize?.let { size ->
            if (size.width > 0 && size.height > 0) {
                builder.setAspectRatio(Rational(size.width, size.height))
            }
        }
        if (player != null) builder.setActions(actions(activity, player))
        return runCatching { builder.build() }.getOrNull()
    }

    private fun actions(activity: Activity, player: ExoPlayer): List<RemoteAction> {
        val playing = player.playWhenReady
        return listOf(
            remoteAction(activity, ACTION_REWIND, 1, R.drawable.ic_pip_rewind, "后退 10 秒"),
            remoteAction(
                activity,
                ACTION_PLAY_PAUSE,
                2,
                if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play,
                if (playing) "暂停" else "播放"
            ),
            remoteAction(activity, ACTION_FORWARD, 3, R.drawable.ic_pip_forward, "前进 10 秒")
        )
    }

    private fun remoteAction(
        activity: Activity,
        action: String,
        requestCode: Int,
        iconRes: Int,
        title: String
    ): RemoteAction {
        val intent = Intent(action).setPackage(activity.packageName)
        val pending = PendingIntent.getBroadcast(
            activity,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(activity, iconRes), title, title, pending)
    }

    /**
     * 注册小窗按钮的接收者；返回的 receiver 要一直持有，否则会被 GC 掉而收不到广播。
     *
     * [onUpdateParams] 用来在状态变化（播放/暂停）之后刷新控制栏图标。
     */
    fun registerReceiver(activity: Activity, onUpdateParams: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val player = _session.value?.player ?: return
                when (intent?.action) {
                    ACTION_PLAY_PAUSE -> if (player.playWhenReady) player.pause() else player.play()
                    ACTION_REWIND -> player.seekTo(
                        (player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L)
                    )
                    ACTION_FORWARD -> player.seekTo(
                        (player.currentPosition + SEEK_STEP_MS).coerceAtMost(
                            player.duration.coerceAtLeast(0L)
                        )
                    )
                }
                onUpdateParams()
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_REWIND)
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_FORWARD)
        }
        // Android 13+ 要求显式声明是否导出；这是应用内广播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(receiver, filter)
        }
        return receiver
    }
}
