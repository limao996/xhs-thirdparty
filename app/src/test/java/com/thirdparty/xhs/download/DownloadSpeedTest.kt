package com.thirdparty.xhs.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 速度格式化单测。 */
class DownloadSpeedTest {

    @Test
    fun below_one_kb_is_not_shown() {
        // 刚开始还没动起来（或已经停下）时不显示速度，免得闪一个 0 KB/s
        assertNull(DownloadSpeed.format(0))
        assertNull(DownloadSpeed.format(512))
        assertNull(DownloadSpeed.format(1023))
    }

    @Test
    fun kb_and_mb_scale_are_readable() {
        assertEquals("1 KB/s", DownloadSpeed.format(1024))
        assertEquals("512 KB/s", DownloadSpeed.format(512 * 1024))
        assertEquals("1.0 MB/s", DownloadSpeed.format(1024L * 1024))
        assertEquals("2.5 MB/s", DownloadSpeed.format((2.5 * 1024 * 1024).toLong()))
        assertEquals("12.0 MB/s", DownloadSpeed.format(12L * 1024 * 1024))
    }

    @Test
    fun negative_input_is_treated_as_idle() {
        // 采样异常（字节回退）不应显示负数
        assertNull(DownloadSpeed.format(-1))
    }
}
