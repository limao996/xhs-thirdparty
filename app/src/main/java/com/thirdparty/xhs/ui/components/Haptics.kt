package com.thirdparty.xhs.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 系统触感反馈的统一入口。
 *
 * 走 [LocalHapticFeedback]（Compose 对系统 `View.performHapticFeedback` 的封装），
 * **不用 `Vibrator`**：系统 API 会尊重用户的「触感反馈」总开关与强度设置，也不需要
 * `VIBRATE` 权限；自己造振动既要重复处理这些，还会在用户已经关掉触感的机器上照振。
 *
 * 语义与用法（每档都对应系统的触感常量，不要随意替换）：
 *  - [longPress]：长按 —— 弹作品菜单、开始拖动排序、进入多选
 *  - [tick]：**轻点** —— 按钮、列表项、开关、**返回 / 关闭 / 取消**这类屏幕上的控件
 *    （用 `ContextClick`：它是系统给"轻点/点击"的反馈，比 `TextHandleMove`
 *     ——那是文本光标移动用的——合适得多）
 *  - [segment]：**翻页/换挡** —— 上下滑换视频、翻图片、切分类
 *  - [confirm]：确认 —— 收藏、加入队列、落位、清空
 *  - [reject]：取消 / 移除 —— 取消收藏、移出队列
 *
 * **版本门控**：后三档用的是 API 30/34 才有的常量，低版本上会被系统静默忽略
 * （用户看到的症状就是"点了没感觉"）。所以这里按 `Build.VERSION.SDK_INT` 降级到
 * 低版本就有的常量，保证**任何支持版本都有反馈**。
 *
 * **不加触感的是什么**：不是"返回/关闭按钮"，而是**系统的返回手势/按键** ——
 * 那一下系统自己会给反馈，我们再抖一次就是重复。屏幕上我们自己画的按钮一律要有。
 */
class Haptics(private val feedback: HapticFeedback) {

    /**
     * 系统触感常量各自的**最低可用 API**。
     *
     * 这三档在低版本上不存在（`HapticFeedbackConstants` 是普通 int 常量，用了不报错，
     * 系统只是**静默忽略**——表现就是"点了没感觉"）。所以按版本降级到低版本就有的常量，
     * 而不是直接把调用丢掉：宁可给一个略粗糙的反馈，也不要什么都不给。
     *
     * - `SEGMENT_TICK` = API 30
     * - `CONFIRM`      = API 30
     * - `REJECT`       = API 34
     */
    private val sdk: Int get() = android.os.Build.VERSION.SDK_INT

    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)

    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.ContextClick)

    /** 翻页/换挡：低版本没有 SegmentTick，用 ContextClick 代替。 */
    fun segment() = feedback.performHapticFeedback(
        if (sdk >= android.os.Build.VERSION_CODES.R) HapticFeedbackType.SegmentTick
        else HapticFeedbackType.ContextClick
    )

    /** 确认：低版本没有 Confirm，用 LongPress（"落实了"的观感最接近）。 */
    fun confirm() = feedback.performHapticFeedback(
        if (sdk >= android.os.Build.VERSION_CODES.R) HapticFeedbackType.Confirm
        else HapticFeedbackType.LongPress
    )

    /** 取消/移除：低版本没有 Reject，用 ContextClick（轻点一下，不抢注意力）。 */
    fun reject() = feedback.performHapticFeedback(
        if (sdk >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackType.Reject
        else HapticFeedbackType.ContextClick
    )

    /**
     * 包一层回调，省掉"每处 onClick 都手写 haptics.xxx()"的重复 —— 也正因为容易漏写，
     * 用户反复反馈过"很多交互没有触感"。新代码一律用它：
     *
     * ```
     * onClick = haptics.click { onOpenDetail(id) }        // 轻点
     * onClick = haptics.confirmClick { viewModel.save() } // 确认
     * ```
     *
     * 写成**成员函数**而不是扩展函数：这样调用方只要有 `haptics` 就能用，不必再 import。
     */
    fun click(block: () -> Unit): () -> Unit = { tick(); block() }

    fun confirmClick(block: () -> Unit): () -> Unit = { confirm(); block() }

    fun rejectClick(block: () -> Unit): () -> Unit = { reject(); block() }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}

/**
 * **翻页触感的唯一实现**：页面**落定**时给一次 `segment()`。
 *
 * 之前图文有两处各自实现（嵌入画廊与全屏查看器），触发时机略有差别，用户反馈"两处手感不一样"。
 * 现在两边都调这个函数 —— 要改一起改。
 *
 * 用 `settledPage` 而不是 `currentPage`：后者在拖动过程中跨过半页就会变，反馈会"提前"响；
 * `settledPage` 是真正停下来那一页，手感更实。首帧不算翻页（那是恢复现场）。
 *
 * **只对"用户真的拖了这一个分页器"给反馈**：图文里嵌入画廊与全屏查看器是同一个页码的两处视图，
 * 在查看器里滑一页会回调上层把画廊**程序化**滚到同一页 —— 那个也走 `settledPage`，
 * 于是用户听到/感到两次（"切换图片触感触发两次"）。程序化滚动不会产生 `DragInteraction`，
 * 所以这里是判据。
 */
@Composable
fun PagerPageHaptics(state: androidx.compose.foundation.pager.PagerState) {
    val haptics = rememberHaptics()
    // 由本分页器自己的拖动事件置位，落定时消费掉；程序化滚动不会置位
    val fromUserDrag = remember(state) { java.util.concurrent.atomic.AtomicBoolean(false) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                fromUserDrag.set(true)
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(state) {
        var first = true
        androidx.compose.runtime.snapshotFlow { state.settledPage }
            .distinctUntilChanged()
            .collect {
                val user = fromUserDrag.getAndSet(false)
                if (first) first = false else if (user) haptics.segment()
            }
    }
}

/**
 * **按下即触感**：手指一碰到就反馈一次，和拖动过程无关。
 *
 * 拖动类控件（进度条 / 倍速条）本该如此 —— 之前是"拖动中每 5% 给一次 segment"，
 * 用户反馈不对：他要的是"按下去那一下就抖一下"。用 `PointerEventPass.Initial` 观察但不消费事件，
 * 所以不影响控件自己的手势。
 */
fun Modifier.pressHaptic(haptics: Haptics?): Modifier =
    if (haptics == null) this
    else this.pointerInput(haptics) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                    haptics.tick()
                }
            }
        }
    }
