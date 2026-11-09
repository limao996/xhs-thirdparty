package com.thirdparty.xhs.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** [boostedSpeed] 的单测：长按后向右滑动的倍速换算（纯函数）。 */
class BoostSpeedTest {

    @Test
    fun starts_at_base() {
        // 按住不动 = 1.25x
        assertEquals(1.25f, boostedSpeed(0f), 0.0001f)
        assertEquals(1.25f, boostedSpeed(50f), 0.0001f)   // 不到一档
    }

    @Test
    fun right_drag_adds_steps_of_quarter() {
        assertEquals(1.5f, boostedSpeed(120f), 0.0001f)
        assertEquals(1.75f, boostedSpeed(240f), 0.0001f)
        assertEquals(2f, boostedSpeed(360f), 0.0001f)
    }

    @Test
    fun clamped_to_max() {
        assertEquals(3f, boostedSpeed(1_000f), 0.0001f)
        assertEquals(3f, boostedSpeed(10_000f), 0.0001f)
    }

    @Test
    fun left_drag_does_not_go_below_base() {
        // "向右滑动增加速率"：往左只是收回加的那部分，不会低于起始 1.25x
        assertEquals(1.25f, boostedSpeed(-200f), 0.0001f)
        assertEquals(1.25f, boostedSpeed(-2_000f), 0.0001f)
    }
}
