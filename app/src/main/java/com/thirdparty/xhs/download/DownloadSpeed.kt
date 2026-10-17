package com.thirdparty.xhs.download

/**
 * 下载速度的格式化（纯函数，带单测）。
 *
 * 只在内存里用：引擎按字节差采样出"每秒字节数"，界面/通知把它显示成人看的单位。
 * 不落库 —— 速度是瞬时观测，不是任务状态（硬约束 30 的状态真相仍然只有 `download_task`）。
 */
object DownloadSpeed {

    /** 小于 1 KB/s 视为"还没动起来"，不显示速度。 */
    fun format(bytesPerSecond: Long): String? {
        if (bytesPerSecond < 1024) return null
        val kb = bytesPerSecond / 1024.0
        return if (kb < 1024) {
            "%.0f KB/s".format(kb)
        } else {
            "%.1f MB/s".format(kb / 1024.0)
        }
    }
}
