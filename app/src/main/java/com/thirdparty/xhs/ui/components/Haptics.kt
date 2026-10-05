package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 系统触感反馈的统一入口。
 *
 * 走 [LocalHapticFeedback]（Compose 对系统 `View.performHapticFeedback` 的封装），
 * **不用 `Vibrator`**：系统 API 会尊重用户的「触感反馈」总开关与强度设置，也不需要
 * `VIBRATE` 权限；自己造振动既要重复处理这些，还会在用户已经关掉触感的机器上照振。
 *
 * 语义与用法（每档都对应系统的触感常量，不要随意替换）：
 *  - [longPress]：长按 —— 弹作品菜单、开始拖动排序、进入多选
 *  - [tick]：**轻点** —— 按钮、列表项、切换开关这类"按下了"的反馈
 *    （用 `ContextClick`：它是系统给"轻点/点击"的反馈，比 `TextHandleMove`
 *     ——那是文本光标移动用的——合适得多）
 *  - [segment]：**翻页/换挡** —— 上下滑换视频、翻图片、切分类
 *  - [confirm]：确认 —— 收藏、加入队列、拖动落位、清空
 *  - [reject]：取消 / 移除 —— 取消收藏、移出队列
 */
class Haptics(private val feedback: HapticFeedback) {
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.ContextClick)
    fun segment() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

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

/** `Modifier.clickable` 的带触感版本：新的可点区域直接用它（需 import）。 */
fun Modifier.hapticClickable(
    haptics: Haptics,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = this.clickable(enabled = enabled) { haptics.tick(); onClick() }
