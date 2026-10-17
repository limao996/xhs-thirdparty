package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.DownloadStatus
import com.thirdparty.xhs.data.DownloadTaskEntity
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.download.DownloadEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * 下载管理页状态：进行中 / 已完成两组。
 *
 * 状态**只有一份真相**（Room 里的 `download_task`），这个 ViewModel 只做分组与转发；
 * 真正干活的是 [DownloadEngine]（前台服务那一侧）。
 */
data class DownloadUiState(
    val active: List<DownloadTaskEntity> = emptyList(),
    val finished: List<DownloadTaskEntity> = emptyList(),
    val loading: Boolean = true
) {
    val activeCount: Int get() = active.count { it.status != DownloadStatus.PAUSED }
    val pausedCount: Int get() = active.count { it.status == DownloadStatus.PAUSED }
    val hasActive: Boolean get() = active.isNotEmpty()
}

class DownloadViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(DownloadUiState())
    val ui: StateFlow<DownloadUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repo.downloadTasks()
                .catch { _ui.value = DownloadUiState(loading = false) }
                .collect { tasks ->
                    _ui.value = DownloadUiState(
                        active = tasks.filter { it.status in DownloadStatus.unfinished },
                        finished = tasks.filter { it.status == DownloadStatus.DONE || it.status == DownloadStatus.FAILED },
                        loading = false
                    )
                }
        }
    }

    fun pause(id: Long) = DownloadEngine.pause(id)

    fun resume(id: Long) = DownloadEngine.retry(id)

    fun cancel(id: Long) = DownloadEngine.cancel(id)

    fun retry(id: Long) = DownloadEngine.retry(id)

    fun pauseAll() = DownloadEngine.pauseAll()

    fun resumeAll() = DownloadEngine.resumeAll()

    fun clearFinished() = DownloadEngine.clearFinished()
}
