package com.thirdparty.xhs.download

import android.content.Context
import android.util.Log
import com.thirdparty.xhs.data.DownloadKind
import com.thirdparty.xhs.data.DownloadStatus
import com.thirdparty.xhs.data.DownloadTaskDao
import com.thirdparty.xhs.data.DownloadTaskEntity
import com.thirdparty.xhs.data.XhsDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient

/**
 * 下载引擎：把 [DownloadTaskEntity] 的队列跑起来。
 *
 * 分工：**服务**负责前台化与通知，**引擎**负责任务调度与落盘，**DAO** 是唯一的状态真相
 * （UI 与通知都从库里读，避免三处各存一份状态对不上）。
 *
 * 并发模型（用户拍定"两者都要"）：
 * - `concurrentTasks`：同时跑几个任务（Rollup：`Semaphore` 卡住任务级并发）；
 * - `parallelSegments`：单个视频内部并行拉几个切片（交给 [HlsDownloader]）。
 *
 * 暂停/取消的语义：暂停 = 取消当前协程并把状态写回 `PAUSED`（可继续，切片级断点）；
 * 取消 = 取消 + 删任务行 + 删临时目录（不可恢复）。
 */
object DownloadEngine {

    private const val TAG = "XhsDownload"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Long, Job>()
    private val lock = Any()

    /** 给通知用的一份摘要。 */
    data class Snapshot(
        val running: Int = 0,
        val queued: Int = 0,
        val totalBytes: Long = 0,
        val doneBytes: Long = 0,
        val currentTitles: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var dao: DownloadTaskDao
    private var client: OkHttpClient = OkHttpClient()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        dao = XhsDatabase.get(appContext).downloadTaskDao()
        client = OkHttpClient.Builder().build()
        // 应用启动时把上次没跑完的任务接着跑（用户强停/系统杀进程后）
        scope.launch { resumeUnfinished() }
    }

    /** 入队（由下载对话框调用）并启动前台服务。 */
    suspend fun enqueue(tasks: List<DownloadTaskEntity>) {
        tasks.forEach { dao.insert(it) }
        Log.i(TAG, "入队 ${tasks.size} 个任务")
        DownloadService.start(appContext)
        schedule()
    }

    /**
     * 把未完成的任务重新排进队列。
     *
     * **不要在这里再次启动服务**：服务启动后会调它，它再启动服务就形成了
     * "启动 → 恢复 → 启动" 的自激循环（2026-10-17 实测日志 300ms 内刷了 13 次）。启动服务是
     * [enqueue] / 用户动作的职责，恢复只管排队；同时用一个标记把每个进程里的恢复动作收敛成一次。
     */
    private var resumedOnce = false

    suspend fun resumeUnfinished(force: Boolean = false) {
        if (resumedOnce && !force) return
        val requeued = dao.requeueRunning(System.currentTimeMillis())
        if (requeued > 0) Log.i(TAG, "把 $requeued 个上次中断的任务重新排队")
        val pending = dao.unfinished()
        if (pending.isNotEmpty()) Log.i(TAG, "恢复未完成任务 ${pending.size} 个")
        resumedOnce = true
        schedule()
    }

    fun pauseAll() = scope.launch {
        val ids = synchronized(lock) { jobs.keys.toList() }
        Log.i(TAG, "暂停全部：${ids.size} 个在跑的任务")
        ids.forEach { id ->
            jobs[id]?.cancel()
            jobs.remove(id)
            dao.setStatus(id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
        }
        refreshState()
    }

    fun resumeAll() = scope.launch {
        val paused = dao.all().filter { it.status == DownloadStatus.PAUSED }
        Log.i(TAG, "继续全部：${paused.size} 个已暂停的任务")
        paused
            .forEach { dao.setStatus(it.id, DownloadStatus.QUEUED, null, System.currentTimeMillis()) }
        DownloadService.start(appContext)
        schedule()
    }

    fun pause(id: Long) = scope.launch {
        jobs[id]?.cancel()
        jobs.remove(id)
        dao.setStatus(id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
        refreshState()
    }

    fun retry(id: Long) = scope.launch {
        dao.setStatus(id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        DownloadService.start(appContext)
        schedule()
    }

    fun cancel(id: Long) = scope.launch {
        jobs[id]?.cancel()
        jobs.remove(id)
        dao.byId(id)?.let { deleteTemp(it.id) }
        dao.delete(id)
        refreshState()
    }

    fun clearFinished() = scope.launch {
        dao.finished().forEach { deleteTemp(it.id) }
        dao.clearFinished()
        refreshState()
    }

    /** 按并发上限把 `QUEUED` 的任务排上。 */
    private fun schedule() = scope.launch {
        val concurrent = DownloadSettings.concurrentTasks(appContext)
        val queued = dao.unfinished().filter { it.status != DownloadStatus.PAUSED }
        val capacity = concurrent - synchronized(lock) { jobs.size }
        if (capacity <= 0) return@launch
        queued.filter { it.status == DownloadStatus.QUEUED }
            .take(capacity)
            .forEach { task -> startTask(task) }
        refreshState()
    }

    private fun startTask(task: DownloadTaskEntity) {
        val job = scope.launch {
            dao.setStatus(task.id, DownloadStatus.RUNNING, null, System.currentTimeMillis())
            Log.i(TAG, "开始任务 ${task.id}（${task.kind} ${task.title}）")
            refreshState()
            runCatching { execute(task) }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "任务 ${task.id} 已取消/暂停")
                    throw e
                }
                    Log.e(TAG, "任务 ${task.id} 失败：${e.message}", e)
                    dao.setStatus(
                        task.id, DownloadStatus.FAILED,
                        friendlyReason(e), System.currentTimeMillis()
                    )
                }
            synchronized(lock) { jobs.remove(task.id) }
            refreshState()
            // 空出名额后继续排下一个
            schedule()
        }
        synchronized(lock) { jobs[task.id] = job }
    }

    private suspend fun execute(task: DownloadTaskEntity) {
        if (!hasNetworkFor(task)) {
            dao.setStatus(
                task.id, DownloadStatus.PAUSED, "等待 Wi-Fi（已开启仅 Wi-Fi 下载）",
                System.currentTimeMillis()
            )
            return
        }
        when (task.kind) {
            DownloadKind.VIDEO -> executeVideo(task)
            DownloadKind.IMAGE -> executeImage(task)
            else -> dao.setStatus(
                task.id, DownloadStatus.FAILED, "未知的任务类型", System.currentTimeMillis()
            )
        }
    }

    private suspend fun executeVideo(task: DownloadTaskEntity) {
        val work = tempDir(task.id)
        val parallel = DownloadSettings.parallelSegments(appContext)
        val downloader = HlsDownloader(client, parallel = parallel)
        val outcome = downloader.remuxToMp4(
            context = appContext,
            playlistUrl = task.sourceUrl,
            workDir = work
        ) { progress ->
            scope.launch {
                dao.setProgress(
                    task.id, progress.done, progress.total, progress.bytes,
                    System.currentTimeMillis()
                )
                refreshState()
            }
        }
        when (outcome) {
            is HlsDownloader.Outcome.Failed -> {
                dao.setStatus(task.id, DownloadStatus.FAILED, outcome.reason, System.currentTimeMillis())
            }

            is HlsDownloader.Outcome.Ok -> {
                val name = DownloadNames.uniqueName(
                    DownloadNames.baseName(task.title, task.noteId), "mp4"
                ) { candidate -> existsInVideoDir(candidate) }
                when (val saved = MediaStoreWriter.saveVideo(appContext, outcome.mp4, name)) {
                    is MediaStoreWriter.Result.Failed ->
                        dao.setStatus(task.id, DownloadStatus.FAILED, saved.reason, System.currentTimeMillis())

                    is MediaStoreWriter.Result.Ok -> {
                        dao.setDone(task.id, saved.displayPath, saved.uri.toString(), saved.bytes, System.currentTimeMillis())
                        Log.i(TAG, "视频完成：${saved.displayPath}（${saved.bytes} 字节，${outcome.durationMs}ms）")
                    }
                }
            }
        }
        work.deleteRecursively()
    }

    private suspend fun executeImage(task: DownloadTaskEntity) {
        val relativeDir = DownloadNames.imageRelativeDir(task.title, task.noteId)
        val ext = DownloadNames.imageExtension(task.sourceUrl)
        val name = DownloadNames.imageFileName(task.imageIndex, ext)
        val downloader = ImageDownloader(client)
        when (val outcome = downloader.saveToGallery(appContext, task.sourceUrl, relativeDir, name)) {
            is ImageDownloader.Outcome.Failed ->
                dao.setStatus(task.id, DownloadStatus.FAILED, outcome.reason, System.currentTimeMillis())

            is ImageDownloader.Outcome.Ok ->
                dao.setDone(
                    task.id, outcome.displayPath, null, outcome.bytes, System.currentTimeMillis()
                )
        }
    }

    private fun hasNetworkFor(task: DownloadTaskEntity): Boolean {
        if (!com.thirdparty.xhs.App.INSTANCE.hasValidatedNetwork()) return false
        if (!DownloadSettings.wifiOnly(appContext)) return true
        return isOnWifi()
    }

    private fun isOnWifi(): Boolean {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun existsInVideoDir(name: String): Boolean {
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_MOVIES
        ), DownloadNames.DIR)
        return File(dir, name).exists()
    }

    private fun tempDir(id: Long): File =
        File(appContext.cacheDir, "download/$id").apply { mkdirs() }

    private fun deleteTemp(id: Long) {
        runCatching { File(appContext.cacheDir, "download/$id").deleteRecursively() }
    }

    private suspend fun refreshState() {
        val all = dao.all()
        val running = synchronized(lock) { jobs.size }
        _state.value = Snapshot(
            running = running,
            queued = all.count { it.status == DownloadStatus.QUEUED },
            totalBytes = all.filter { it.status != DownloadStatus.DONE }.size.toLong(),
            doneBytes = all.count { it.status == DownloadStatus.DONE }.toLong(),
            currentTitles = all.filter { it.status == DownloadStatus.RUNNING }.map { it.title }
        )
    }

    /** 失败原因给用户看：把底层英文异常收成人话。 */
    private fun friendlyReason(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接服务器，请检查网络"
        is java.net.SocketTimeoutException -> "连接超时，请重试"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "下载失败，请重试"
    }
}
