package com.thirdparty.xhs.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/** 长按多久触发加速（用户要求 1.5 秒）。 */
const val BOOST_HOLD_MS = 1_500L

/** 起始倍速：一触发就是它。 */
const val BOOST_BASE_SPEED = 1.25f

/** 每向右滑过画面宽度的这个比例，加一档（0.10 = 10% 宽度）。 */
const val BOOST_STEP_FRACTION = 0.10f

/** 一档的大小。 */
const val BOOST_STEP = 0.25f

/** 上限。 */
const val BOOST_MAX_SPEED = 3f

/** 触发热区：画面正中的这个比例（居中 40%×40%）。 */
private const val HOT_ZONE_HALF = 0.20f

/** 按住 1.5 秒期间允许的手抖幅度（占画面宽度的比例）；超了就当作拖动/缩放，不触发加速。 */
private const val HOLD_SLOP_FRACTION = 0.02f

/**
 * 长按加速的目标倍速（纯函数，带单测）。
 *
 * @param dxFraction 横向位移 ÷ 画面宽度。用**比例**而不是像素：横屏竖屏、不同分辨率表现一致。
 *   起始 [BOOST_BASE_SPEED]，每 [BOOST_STEP_FRACTION] 加 [BOOST_STEP] 一档，上限 [BOOST_MAX_SPEED]；
 *   向左滑只是收回加的那部分，不会低于起点（用户要的是"向右滑动增加"）。
 */
fun boostedSpeed(dxFraction: Float): Float {
    val steps = (dxFraction / BOOST_STEP_FRACTION).toInt().coerceAtLeast(0)
    return (BOOST_BASE_SPEED + steps * BOOST_STEP).coerceAtMost(BOOST_MAX_SPEED)
}

/**
 * 全屏播放器的「长按加速」手势（2026-11-09 重写）。
 *
 * ## 为什么重写
 *
 * 前几版都用 `awaitEachGesture` / `detectTapGestures.onPress` **参与**手势竞争，结果反复出问题：
 * 两个解析器在同一节点上互相干扰（有时不触发）；抬手事件丢失时循环等不到"松开"，卡在加速态
 * （表现为"叠加层又冒出来、速度一直不正常"）。
 *
 * ## 现在的实现
 *
 * **只观察、不消费**：在 `PointerEventPass.Initial` 上旁听指针事件，绝不 `consume()`，
 * 所以播放器原有的单击（显示/隐藏控制栏）、双击（暂停）、捏合缩放**完全不受影响**。
 *
 * - 按下 → 记时间与位置，判断是否落在**画面正中 40%×40%**的热区里；
 * - 1.5 秒内没松手、也没移动超过 [HOLD_SLOP_FRACTION] → 触发加速，回调 [onBoostChange] 收到 1.25x；
 * - 按住期间横向移动 → 按"位移 ÷ 画面宽度"实时换算倍速；
 * - **抬手（或取消）→ 立刻回调 null**，加速态结束（不依赖任何等待循环，所以不会卡住）；
 * - 不在热区、或按下后很快就动 → 什么都不做，普通点击/拖动照旧。
 *
 * @param onBoostChange 非空 = 正在以该倍速播放；null = 加速结束。调用方据此显示叠加层与恢复倍速。
 */
@Composable
fun rememberSpeedBoostModifier(
    player: Player,
    enabled: Boolean,
    haptics: Haptics,
    onBoostChange: (Float?) -> Unit
): Modifier {
    val emit = rememberUpdatedState(onBoostChange)

    // 按住状态（全部由观察者写入；时间轴由它们驱动）
    var downAtMs by remember { mutableLongStateOf(0L) }
    var downX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    var holdingInZone by remember { mutableStateOf(false) }
    var boosting by remember { mutableStateOf(false) }
    var lastSent by remember { mutableFloatStateOf(0f) }

    // 1.5 秒计时。key 是按下时间：松手会把它清 0，effect 重来/取消；
    // 热区外按下则 holdingInZone 为 false，直接不启动。
    LaunchedEffect(downAtMs) {
        if (downAtMs == 0L || !holdingInZone) return@LaunchedEffect
        delay(BOOST_HOLD_MS)
        if (downAtMs != 0L && holdingInZone && !boosting) {
            boosting = true
            lastSent = BOOST_BASE_SPEED
            haptics.longPress()
            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                android.util.Log.i("XhsBoost", "start 1.25x")
            }
            emit.value(BOOST_BASE_SPEED)
        }
    }

    if (!enabled) return Modifier

    return Modifier.pointerInput(player) {
        awaitPointerEventScope {
            while (true) {
                // Initial 阶段：在子节点处理之前旁听；**不消费**，所以不影响任何既有手势
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                widthPx = size.width.toFloat()
                val hPx = size.height.toFloat()

                if (change.changedToDownIgnoreConsumed()) {
                    downAtMs = System.currentTimeMillis()
                    downX = change.position.x
                    holdingInZone = widthPx > 0f && hPx > 0f &&
                        change.position.x >= widthPx * (0.5f - HOT_ZONE_HALF) &&
                        change.position.x <= widthPx * (0.5f + HOT_ZONE_HALF) &&
                        change.position.y >= hPx * (0.5f - HOT_ZONE_HALF) &&
                        change.position.y <= hPx * (0.5f + HOT_ZONE_HALF)
                    continue
                }

                if (!change.pressed) {
                    // 抬手或取消：不管之前在干什么，加速态一律立刻结束。
                    // 这是"松手就彻底隐藏并恢复速度"的落点，也保证不会卡住。
                    downAtMs = 0L
                    holdingInZone = false
                    if (boosting) {
                        boosting = false
                        lastSent = 0f
                        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                            android.util.Log.i("XhsBoost", "end -> 1x")
                        }
                        emit.value(null)
                    }
                    continue
                }

                // 按住期间的移动
                if (!boosting) {
                    // 还没触发：移动太多就取消这次长按（那是拖动进度/缩放）
                    if (widthPx > 0f &&
                        kotlin.math.abs(change.position.x - downX) > widthPx * HOLD_SLOP_FRACTION
                    ) {
                        downAtMs = 0L
                        holdingInZone = false
                    }
                } else {
                    // 已触发：横向位移换算倍速（按画面宽度百分比，横竖屏一致）
                    if (widthPx > 0f) {
                        val target = boostedSpeed((change.position.x - downX) / widthPx)
                        if (target != lastSent) {
                            lastSent = target
                            haptics.segment()
                            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                                android.util.Log.i("XhsBoost", "speed " + target + "x")
                            }
                            emit.value(target)
                        }
                    }
                }
            }
        }
    }
}
