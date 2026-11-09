package com.thirdparty.xhs.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ResumeRule.shouldResume] 的单测：什么情况下该提示"继续观看"。 */
class VideoProgressResumeRuleTest {

    @Test
    fun too_early_is_not_resumable() {
        // 只看了 1 秒（< 2 秒）→ 不提示
        assertFalse(ResumeRule.shouldResume(1_000L, 120_000L))
        // 看了 2 秒 → 提示
        assertTrue(ResumeRule.shouldResume(2_000L, 120_000L))
    }

    @Test
    fun finished_video_is_not_resumable() {
        // 停在最后一秒以内 → 视为看完，不提示
        assertFalse(ResumeRule.shouldResume(119_500L, 120_000L))
        assertFalse(ResumeRule.shouldResume(120_000L, 120_000L))
        // 还差 1 秒以上 → 提示（短片段中途退出也算）
        assertTrue(ResumeRule.shouldResume(115_000L, 120_000L))
    }

    @Test
    fun unknown_duration_falls_back_to_position_only() {
        // 时长未知（<=0）→ 只看位置是否够长
        assertTrue(ResumeRule.shouldResume(30_000L, 0L))
        assertFalse(ResumeRule.shouldResume(1_000L, 0L))
    }

    @Test
    fun short_video_also_resumes() {
        // 8 秒的短视频，看到 5 秒 → 仍要提示（用户要求：进入详情页只要有进度就检查）
        assertTrue(ResumeRule.shouldResume(5_000L, 8_000L))
        // 但停在倒数第二秒就不再提示
        assertFalse(ResumeRule.shouldResume(7_000L, 8_000L))
    }
}
