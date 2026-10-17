package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.DownloadGroup
import com.thirdparty.xhs.data.DownloadStatus
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.data.groupDownloads
import com.thirdparty.xhs.download.DownloadEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 下载管理页状态：**按作品分组**的进行中 / 已完成两组。
 *
 * 为什么按作品分组而不是一行一个文件：一个作品可能有 1 个视频 + 几十张图片，
 * 一行一个文件会让同一个作品的图片刷屏、用户反而找不到作品（2026-10-17 用户反馈）。
 * 分组逻辑在纯函数 [groupDownloads] 里，带单测。
 *
 * 状态只有一份真相（Room 的 `download_task`）；这个 ViewModel 只做分组与转发，
 * 真正干活的是 [DownloadEngine]（前台服务那一侧）。
 */
data class DownloadUiState(
    val active: List<DownloadGroup> = emptyList(),
    val finished: List<DownloadGroup> = emptyList(),
    val loading: Boolean = true
) {
    val hasActive: Boolean get() = active.isNotEmpty()
    val runningCount: Int get() = active.count { it.queuedOrRunning > 0 }
    val pausedCount: Int get() = active.count { it.queuedOrRunning == 0 && it.pausedCount > 0 }
}

class DownloadViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(DownloadUiState())
    val ui: StateFlow<DownloadUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.downloadTasks()
                .catch { _ui.value = DownloadUiState(loading = false) }
                .collect { tasks ->
                    val groups = groupDownloads(tasks)
                    _ui.value = DownloadUiState(
                        active = groups.filter { !it.settled },
                        finished = groups.filter { it.settled },
                        loading = false
                    )
                }
        }
    }

    // ---- 组级操作（一次作用在这件作品所有未完成的条目上）----------------------

    fun pauseGroup(group: DownloadGroup) {
        group.items.filter { it.status in DownloadStatus.unfinished }.forEach {
            DownloadEngine.pause(it.id)
        }
    }

    fun resumeGroup(group: DownloadGroup) {
        group.items.filter { it.status in DownloadStatus.unfinished }.forEach {
            DownloadEngine.retry(it.id)
        }
    }

    fun retryGroup(group: DownloadGroup) {
        group.items.filter { it.status == DownloadStatus.FAILED || it.status in DownloadStatus.unfinished }
            .forEach { DownloadEngine.retry(it.id) }
    }

    // ---- 单条操作（展开后逐条用）--------------------------------------------

    fun pause(id: Long) = DownloadEngine.pause(id)

    fun resume(id: Long) = DownloadEngine.retry(id)

    fun cancel(id: Long) = DownloadEngine.cancel(id)

    fun retry(id: Long) = DownloadEngine.retry(id)

    // ---- 全局 ---------------------------------------------------------------

    fun pauseAll() = DownloadEngine.pauseAll()

    fun resumeAll() = DownloadEngine.resumeAll()

    fun clearFinished() = DownloadEngine.clearFinished()
}
