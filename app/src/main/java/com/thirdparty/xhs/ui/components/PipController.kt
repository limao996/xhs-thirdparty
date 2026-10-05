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
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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

    /**
     * 播放状态变了就 +1：MainActivity 据此重设窗口参数。
     *
     * 小窗上那三个按钮的图标必须跟着**真实状态**走 —— 播放中显示暂停、暂停中显示播放、
     * 播完显示重播；否则就会出现"播完了按钮还是暂停、点了也没反应"（用户实测）。
     */
    val paramsVersion = MutableStateFlow(0)

    /** 监听播放器状态，用于刷新小窗按钮（随会话建立/结束挂上/摘掉）。 */
    private var stateListener: Player.Listener? = null

    // ---- 生命周期 ----------------------------------------------------------

    /**
     * 详情页点「小窗播放」：把播放器交出去。
     *
     * [playIntent] 是交出去那一刻"是不是在播"，进小窗后按它把播放状态**接着**下去
     * （用户实测过：不显式接着，小窗里会停在暂停状态）。
     */
    fun start(player: ExoPlayer, noteId: Long, title: String, playIntent: Boolean) {
        // 同一个播放器第二次进来时，旧会话必须先收掉，否则没人 release 它（后台出声）
        if (hasSession() && _session.value?.player !== player) closeAndRelease()
        detachListener()
        _session.value = Session(player, noteId, title)
        inPip.value = true
        runCatching { if (playIntent) player.play() else player.pause() }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                paramsVersion.value++
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                paramsVersion.value++
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // 小窗比例是按视频尺寸算的：尺寸是**进小窗之后**才探到的（尤其是先全屏
                // 后切小窗的横屏片），所以这里必须再刷一次参数，否则小窗会沿用
                // Activity 的比例 —— 看起来就是"横屏视频的小窗变成竖屏、画面被拉伸"。
                paramsVersion.value++
            }
        }
        player.addListener(listener)
        stateListener = listener
        paramsVersion.value++
    }

    private fun detachListener() {
        stateListener?.let { l -> _session.value?.player?.let { p -> runCatching { p.removeListener(l) } } }
        stateListener = null
    }

    fun hasSession(): Boolean = _session.value != null

    /**
     * 屏幕关掉/锁屏时把小窗的播放**暂停**（会话照旧保留，小窗窗口也还在）。
     *
     * 为什么不 release：锁屏不是关小窗，窗口解锁后还要继续用（内容与进度都得留着）。
     * 为什么解锁后**不自动续播**：用户锁屏往往就是为了让它停下来 —— 自动续播会让人没法静音；
     * 要接着看，点小窗上的播放按钮或展开回详情页即可。
     */
    fun pauseForScreenOff() {
        _session.value?.player?.let { p ->
            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                android.util.Log.i("XhsPip", "pauseForScreenOff playing=${p.isPlaying}")
            }
            runCatching { p.pause() }
        }
    }

    /**
     * 播放器**正归小窗所有**（会话还在）。生命周期/销毁逻辑用它来判断"这台别动"。
     *
     * 注意**只算小窗会话**，不要把 `PlaybackHandoff` 的持有也算进来：那个标记是"曾经交给过别的
     * 屏幕"（信息流 → 详情页）用的，用来防止**误 release**。把它并进来会让详情页切后台/锁屏
     * 时也被"放过"——用户反馈"详情页切后台还在放"就是这么来的（不合并才有正确的暂停）。
     */
    fun isHandedOver(player: Player?): Boolean = _session.value?.player === player

    /**
     * 播放器**刚刚被交回详情页**（展开小窗那一刻）：这时 `handBackForDetail()` 已经清了会话，
     * 所以 [isHandedOver] 会返回 false —— 但那一瞬间的组合销毁/`ON_STOP` 不该把它暂停。
     * 只认 `givePlayer` 这一次交接（`held`），不认信息流那次"曾经交给过"的标记。
     */
    fun isReturningToDetail(player: Player?): Boolean =
        player is ExoPlayer && PlaybackHandoff.isHeldForHandBack(player)

    /** 展开回详情页：把播放器交回给详情页（详情页通过 PlaybackHandoff 认领）。 */
    fun handBackForDetail(): Session? {
        val s = _session.value ?: return null
        detachListener()
        _session.value = null
        inPip.value = false
        return s
    }

    /**
     * 进入小窗**失败**时的回滚（系统拒绝：画中画权限被关、多窗口策略不允许…）。
     *
     * `enterPictureInPictureMode()` 返回 false 时不会再有 `onPictureInPictureModeChanged`，
     * 而 [start] 已经把 `inPip` 置成 true —— 不收回就等于把导航内容永久藏起来（屏幕只剩视频、
     * 返回也回不去；审计 F3）。处理方式与"展开"一致：把播放器按交回详情页登记，再恢复导航内容。
     */
    fun abortStart(): Session? {
        val s = handBackForDetail() ?: return null
        PlaybackHandoff.givePlayer(s.noteId, s.player)
        return s
    }

    /** 用户关掉小窗（或 Activity 被销毁）：把进度交出去，然后停止并销毁播放器。 */
    fun closeAndRelease() {
        detachListener()
        _session.value?.let { s ->
            // **关掉小窗后详情页会重建播放器**（这一台马上要 release），如果不把进度交出去，
            // 回到详情页就会从 0:00 开始（用户反馈）。这里复用与"信息流 → 详情页"同一套续播通道：
            // `stash` 记下位置与播放意图，详情页 compose 时 `take(noteId)` 会 seek 回去。
            runCatching {
                PlaybackHandoff.stash(
                    s.noteId,
                    s.player.currentPosition,
                    s.player.playWhenReady
                )
            }
            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "XhsPip",
                    "closeAndRelease stash note=${s.noteId} pos=${s.player.currentPosition}" +
                        " playing=${s.player.playWhenReady}"
                )
            }
            runCatching {
                s.player.stop()
                s.player.clearMediaItems()
                s.player.release()
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
        player?.let { p ->
            val aspect = videoAspectOf(p.videoSize)
            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "XhsPip",
                    "buildParams videoSize=${p.videoSize.width}x${p.videoSize.height}" +
                        " rot=${p.videoSize.unappliedRotationDegrees} aspect=$aspect"
                )
            }
            if (aspect > 0f) {
                // PiP 允许的比例是 [1/2.39, 2.39]，越界会被系统忽略（窗口就退回 Activity 比例）
                val clamped = aspect.coerceIn(1f / MAX_PIP_RATIO, MAX_PIP_RATIO)
                builder.setAspectRatio(Rational((clamped * 1000f).toInt(), 1000))
            }
            builder.setActions(actions(activity, p))
        }
        return runCatching { builder.build() }.getOrNull()
    }

    /**
     * 视频的显示比例（宽/高），**含旋转修正**。
     *
     * 手机拍的横屏片常常是"1920×1080 的帧 + 旋转 90°"编码的，直接按 `width/height` 算，
     * 得到的是一个旋转过的比例 —— 小窗就会是竖的、画面被拉伸。已知尺寸为 0 时返回 0（调用方跳过设置）。
     */
    fun videoAspectOf(size: VideoSize?): Float {
        if (size == null) return 0f
        val rotate = size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270
        val w = if (rotate) size.height else size.width
        val h = if (rotate) size.width else size.height
        if (w <= 0 || h <= 0) return 0f
        return w.toFloat() / h.toFloat()
    }

    /** PiP 系统允许的最大比例（约 2.39:1）；超出的比例会被忽略 */
    private const val MAX_PIP_RATIO = 2.39f

    private fun actions(activity: Activity, player: ExoPlayer): List<RemoteAction> {
        // 三种状态给三种图标：播完给「重播」，播放中给「暂停」，其余给「播放」
        val ended = player.playbackState == Player.STATE_ENDED
        val playing = player.playWhenReady && !ended
        return listOf(
            remoteAction(activity, ACTION_REWIND, 1, R.drawable.ic_pip_rewind, "后退 10 秒"),
            remoteAction(
                activity,
                ACTION_PLAY_PAUSE,
                2,
                when {
                    ended -> R.drawable.ic_pip_replay
                    playing -> R.drawable.ic_pip_pause
                    else -> R.drawable.ic_pip_play
                },
                when {
                    ended -> "重播"
                    playing -> "暂停"
                    else -> "播放"
                }
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
                    // 播完之后再点，`playWhenReady` 仍是 true 而状态是 ENDED：
                    // 这时不能走 pause 分支（点了没反应），要从头重播
                    ACTION_PLAY_PAUSE -> if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0L)
                        player.play()
                    } else if (player.playWhenReady) {
                        player.pause()
                    } else {
                        player.play()
                    }
                    ACTION_REWIND -> player.seekTo(
                        (player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L)
                    )
                    ACTION_FORWARD -> {
                        // 时长还没探到时 media3 返回 C.TIME_UNSET（负值），`coerceAtLeast(0)` 会变成
                        // 0 → seek 到开头（审计 F8：刚进小窗点前进，画面跳回 0:00）。
                        val d = player.duration
                        val target = if (d <= 0L || d == androidx.media3.common.C.TIME_UNSET) {
                            player.currentPosition + SEEK_STEP_MS
                        } else {
                            (player.currentPosition + SEEK_STEP_MS).coerceAtMost(d)
                        }
                        player.seekTo(target)
                    }
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
