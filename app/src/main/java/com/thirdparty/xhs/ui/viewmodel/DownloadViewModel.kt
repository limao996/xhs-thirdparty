package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.DownloadGroup
import com.thirdparty.xhs.data.DownloadStatus
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.data.groupDownloads
import com.thirdparty.xhs.download.DownloadEngine
import com.thirdparty.xhs.download.DuplicateCleaner
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
    /** 整组完成（没有失败项） */
    val finished: List<DownloadGroup> = emptyList(),
    /** 整组结束但有失败项：单独成组，方便一眼看到并重试 */
    val failed: List<DownloadGroup> = emptyList(),
    val loading: Boolean = true
) {
    val hasActive: Boolean get() = active.isNotEmpty()
    val runningCount: Int get() = active.count { it.queuedOrRunning > 0 }
    val pausedCount: Int get() = active.count { it.queuedOrRunning == 0 && it.pausedCount > 0 }
    /** 所有已结束的作品（清空用） */
    val settled: List<DownloadGroup> get() = finished + failed
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
                        finished = groups.filter { it.settled && !it.failed },
                        failed = groups.filter { it.failed },
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

    /**
     * 删除一条记录；[alsoDeleteFile] 为真时连公共目录里的源文件一起删。
     * （用户要的是"能选"：默认只删记录，避免误删相册里的文件。）
     */
    fun delete(id: Long, alsoDeleteFile: Boolean) = DownloadEngine.delete(id, alsoDeleteFile)

    // ---- 全局 ---------------------------------------------------------------

    fun pauseAll() = DownloadEngine.pauseAll()

    fun resumeAll() = DownloadEngine.resumeAll()

    /**
     * 清空已完成 / 失败的记录；[alsoDeleteFiles] 为真时连文件一起删。
     *
     * @param onResult 回调实际删掉的文件数（界面提示用）
     */
    fun clearFinished(alsoDeleteFiles: Boolean, onResult: (Int) -> Unit = {}) {
        viewModelScope.launch { onResult(DownloadEngine.clearFinished(alsoDeleteFiles)) }
    }

    // ---- 重复 / 孤儿文件检查 ------------------------------------------------

    /**
     * 检查结果：待删的重复文件与孤儿文件。
     *
     * @param duplicates 同一内容存了多份时多出来的那些
     * @param orphans 记录已被删、文件还留在相册 / 视频库里的那些
     */
    data class DupReport(
        val duplicates: List<DuplicateCleaner.MediaFile>,
        val orphans: List<DuplicateCleaner.MediaFile>
    ) {
        val all: List<DuplicateCleaner.MediaFile> get() = duplicates + orphans
        val bytes: Long get() = all.sumOf { it.sizeBytes }
        val isEmpty: Boolean get() = all.isEmpty()
    }

    /** 最近一次扫描结果（入口角标用；null = 还没扫过）。 */
    private val _report = MutableStateFlow<DupReport?>(null)
    val report: StateFlow<DupReport?> = _report.asStateFlow()

    /** 是否把清单对话框摆出来。 */
    private val _showDuplicates = MutableStateFlow(false)
    val showDuplicates: StateFlow<Boolean> = _showDuplicates.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 防重入用**独立字段**，不能拿 [scanning] 当锁（硬约束 29：显示态只用于渲染）。 */
    private var scanInFlight = false

    /**
     * 扫公共目录，算出重复与孤儿文件。
     *
     * @param silent 真 = 只更新入口上的角标（进页面时自动跑一次）；假 = 同时把清单对话框摆出来
     */
    fun scanDuplicates(context: android.content.Context, silent: Boolean) {
        if (scanInFlight) return
        scanInFlight = true
        _scanning.value = true
        viewModelScope.launch {
            val files = DuplicateCleaner.scan(context)
            val referenced = runCatching { repo.downloadSavedPaths() }.getOrDefault(emptySet())
            val dups = DuplicateCleaner.duplicates(files, referenced)
            val orphans = DuplicateCleaner.orphans(files, referenced)
                .filter { o -> dups.none { it.uri == o.uri } }
            _report.value = DupReport(dups, orphans)
            _scanning.value = false
            scanInFlight = false
            if (!silent) _showDuplicates.value = true
        }
    }

    /** 点「检查重复文件 / 孤儿文件」：已经有结果就直接摆清单，否则先扫一遍。 */
    fun openDuplicates(context: android.content.Context) {
        if (_report.value != null) {
            _showDuplicates.value = true
        } else {
            scanDuplicates(context, silent = false)
        }
    }

    /** 删除清单里的文件（不动数据库记录），删完重新扫一次更新角标。 */
    fun deleteDuplicates(context: android.content.Context, onResult: (Int) -> Unit = {}) {
        val files = _report.value?.all.orEmpty()
        _showDuplicates.value = false
        viewModelScope.launch {
            onResult(DuplicateCleaner.delete(context, files))
            scanInFlight = false
            scanDuplicates(context, silent = true)
        }
    }

    fun dismissDuplicates() {
        _showDuplicates.value = false
    }
}
