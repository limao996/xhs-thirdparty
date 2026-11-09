package com.thirdparty.xhs.ui.components

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** 长按多久触发加速（用户要求 1.5 秒）。 */
const val BOOST_HOLD_MS = 1_500L

/** 起始倍速：一触发就是它。 */
const val BOOST_BASE_SPEED = 1.25f

/** 触发热区：画面正中这个比例的一半（0.20 → 居中 40%×40%）。 */
private const val HOT_ZONE_HALF = 0.20f

/** 按住期间允许的手抖幅度（占画面宽/高的比例）；超了就当拖动/缩放，不触发。 */
private const val HOLD_SLOP_FRACTION = 0.03f

/**
 * 全屏播放器的「长按加速」触摸观察者 —— **原生 View 路径**（2026-11-09 重做）。
 *
 * ## 为什么不用 Compose 手势
 *
 * Compose 的手势解析器（`detectTapGestures` / `awaitEachGesture`）会和播放器既有的单击、双击、
 * 捏合缩放抢同一条事件流。试过四版：有时不触发、卡在加速态、最严重的一次直接**触摸卡死 UI**。
 *
 * ## 现在的做法
 *
 * 挂在画面 TextureView 的 `setOnTouchListener` 上，**永远 `return false`** ——
 * 只旁听、不消费。事件照旧流向播放器自身与上层 Compose，所以点击（显示/隐藏控制栏）、
 * 双击（暂停）、捏合缩放、拖动进度条**一律不受影响**。
 *
 * - 按下：落在画面正中 40%×40%（按视图实测尺寸的百分比，横竖屏都成立）才起计时；
 * - 1.5 秒内没松手、移动不超过画面 3% → 触发 1.25x（振动一下）；
 * - 按住期间横向移动 → 按「位移 ÷ 画面宽度」实时换算倍速（每 10% 宽度 +0.25x，上限 3x）；
 * - 抬手/取消 → 立即结束（不走任何等待循环，所以不会卡住）。
 *
 * @param isFullscreen 只有全屏才生效（内嵌播放器太小，中间区域会和点按冲突）
 * @param onHaptic 触发时的触感（由调用方传入 Compose 的 Haptics，保证遵守系统触感开关）
 * @param onSpeed 正在加速：回调当前倍速；回调 null 表示加速结束
 */
class SpeedBoostTouch(
    private val isFullscreen: () -> Boolean,
    private val onHaptic: () -> Unit,
    private val onBoostSpeed: (Float?) -> Unit
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var viewWidth = 0
    private var viewHeight = 0
    private var boosting = false
    private var timer: Runnable? = null

    /** 每次都重新挂，避免 view 复用后监听器指向旧实例。 */
    fun attachTo(view: View) {
        view.setOnTouchListener(this)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!isFullscreen()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                viewWidth = v.width
                viewHeight = v.height
                downX = event.x
                downY = event.y
                cancelTimer()
                if (!inHotZone(event.x, event.y)) return false
                val runnable = Runnable {
                    boosting = true
                    onHaptic()
                    if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                        android.util.Log.i("XhsBoost", "start " + BOOST_BASE_SPEED + "x")
                    }
                    onBoostSpeed(BOOST_BASE_SPEED)
                }
                timer = runnable
                handler.postDelayed(runnable, BOOST_HOLD_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                if (boosting) {
                    if (viewWidth > 0) {
                        val target = boostedSpeed((event.x - downX) / viewWidth)
                        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                            android.util.Log.i("XhsBoost", "speed " + target + "x")
                        }
                        onBoostSpeed(target)
                    }
                } else {
                    // 还没触发就移动 → 用户是在拖动/缩放，取消这次长按
                    if (viewWidth > 0 && abs(event.x - downX) > viewWidth * HOLD_SLOP_FRACTION) {
                        cancelTimer()
                    }
                    if (viewHeight > 0 && abs(event.y - downY) > viewHeight * HOLD_SLOP_FRACTION) {
                        cancelTimer()
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelTimer()
                if (boosting) {
                    boosting = false
                    if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                        android.util.Log.i("XhsBoost", "end -> 1x")
                    }
                    onBoostSpeed(null)
                }
            }
        }
        // 只旁听：不消费，播放器与上层 Compose 的手势全部照旧
        return false
    }

    /** 播放器离开/重建时调用，避免回调打到已释放的界面。 */
    fun release() {
        cancelTimer()
        if (boosting) {
            boosting = false
            onBoostSpeed(null)
        }
    }

    private fun cancelTimer() {
        timer?.let { handler.removeCallbacks(it) }
        timer = null
    }

    private fun inHotZone(x: Float, y: Float): Boolean {
        if (viewWidth <= 0 || viewHeight <= 0) return false
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        return x >= cx - viewWidth * HOT_ZONE_HALF && x <= cx + viewWidth * HOT_ZONE_HALF &&
            y >= cy - viewHeight * HOT_ZONE_HALF && y <= cy + viewHeight * HOT_ZONE_HALF
    }
}
