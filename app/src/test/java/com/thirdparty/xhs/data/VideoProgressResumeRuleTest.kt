package com.thirdparty.xhs.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [VideoProgressStore.ResumeRule] 的单测：什么情况该提示"继续观看"。 */
class VideoProgressResumeRuleTest {

    private val min = VideoProgressStore.ResumeRule.MIN_POSITION_MS
    private val guard = VideoProgressStore.ResumeRule.END_GUARD_MS

    @Test
    fun too_early_is_not_resumable() {
        // 点开就退（< 5s）不该提示"继续观看"
        assertFalse(VideoProgressStore.ResumeRule.shouldResume(0L, 60_000L))
        assertFalse(VideoProgressStore.ResumeRule.shouldResume(min - 1, 60_000L))
        assertTrue(VideoProgressStore.ResumeRule.shouldResume(min, 60_000L))
    }

    @Test
    fun finished_video_is_not_resumable() {
        val dur = 60_000L
        // 离结尾不足 10s：视为看完
        assertFalse(VideoProgressStore.ResumeRule.shouldResume(dur - guard + 1, dur))
        assertFalse(VideoProgressStore.ResumeRule.shouldResume(dur, dur))
        // 离结尾刚好 10s：还能继续看
        assertTrue(VideoProgressStore.ResumeRule.shouldResume(dur - guard, dur))
    }

    @Test
    fun unknown_duration_falls_back_to_position_only() {
        assertTrue(VideoProgressStore.ResumeRule.shouldResume(min, 0L))
        assertFalse(VideoProgressStore.ResumeRule.shouldResume(0L, 0L))
    }

    @Test
    fun short_video_never_resumes() {
        // 总长 8 秒的片子：5~10 秒的规则会互相矛盾，这里要求"不提示"（pos 已越过 dur-guard）
        assertFalse(VideoProgressStore.ResumeRule.shouldResume(5_000L, 8_000L))
    }
}
