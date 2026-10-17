package com.thirdparty.xhs.download

import android.content.Context
import android.util.Log
import com.thirdparty.xhs.data.DownloadKind
import com.thirdparty.xhs.data.DownloadStage
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

    /** `schedule()` 的串行锁（见该函数注释：并发排期会突破并发上限）。 */
    private val scheduleMutex = Mutex()

    /** 给通知用的一份摘要。 */
    data class Snapshot(
        val running: Int = 0,
        val queued: Int = 0,
        val totalBytes: Long = 0,
        val doneBytes: Long = 0,
        val currentTitles: List<String> = emptyList(),
        /** 正在跑的任务所处阶段（`DownloadStage`），供通知显示"合并中 / 转换为 mp4 / 保存中"。 */
        val currentStage: String = ""
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
        // 网络恢复（开 Wi-Fi / 连上线路）后，把"等待 Wi-Fi"而暂停的任务自动接上。
        // 取 `App.INSTANCE` 用 runCatching 兜一下：它由 `App.onCreate` 里的 `INSTANCE = this` 赋值，
        // 而引擎可能在 Application 尚未走完初始化时就被初始化（这条协程立刻会读一次初值）。
        // 真正的修法是把 `DownloadEngine.init` 放在 `INSTANCE = this` 之后（见 App.kt），这里再防一手。
        scope.launch {
            val epochFlow = runCatching { com.thirdparty.xhs.App.INSTANCE.networkEpoch }.getOrNull()
                ?: return@launch
            epochFlow.collect { epoch ->
                if (epoch > 0) resumeWaitingWifi()
            }
        }
    }

    /** 「仅 Wi-Fi 下载」把任务挡下来时写的暂停原因（也是自动恢复的判据）。 */
    const val WAITING_WIFI_REASON = "等待 Wi-Fi（已开启仅 Wi-Fi 下载）"

    /**
     * 网络恢复后，把**因网络门禁而暂停**的任务重新排上（不碰用户手动暂停的）。
     *
     * 硬约束 25 的口径：网络恢复要自动重试。下载这一侧的对照点就是这里 ——
     * 否则用户开了"仅 Wi-Fi"之后回到 Wi-Fi 还得手动点一次「继续全部」。
     */
    private suspend fun resumeWaitingWifi() {
        val waiting = dao.all().filter {
            it.status == DownloadStatus.PAUSED && it.error == WAITING_WIFI_REASON
        }
        if (waiting.isEmpty()) return
        var resumed = 0
        waiting.forEach { task ->
            // 再确认一次状态：这中间它可能已经被别的路径（用户点继续、上一轮排期）跑起来了，
            // 无脑重置成 QUEUED 会让同一个任务被启动两次
            if (dao.byId(task.id)?.status == DownloadStatus.PAUSED) {
                dao.setStatus(task.id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
                resumed++
            }
        }
        if (resumed == 0) return
        Log.i(TAG, "网络恢复：继续 $resumed 个等待 Wi-Fi 的任务")
        DownloadService.start(appContext)
        schedule()
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

    /**
     * 按并发上限把 `QUEUED` 的任务排上。
     *
     * **必须串行**：容量 = 上限 − 在跑数，如果两次 `schedule` 同时读容量（例如"任务完成→排下一个"
     * 与"启动时恢复队列"撞在一起），两边都会看到空位、各排一个，于是并发上限被突破
     * （2026-10-17 实测：设成 1 时出现过 3 个任务同时开跑）。用 `scheduling` 门串起来。
     */
    private fun schedule() = scope.launch {
        // 用 Mutex 串行（不要用布尔门：`synchronized { if (busy) return@launch; busy = true }` 的
        // `finally` 在早退分支也会执行，会把别的进行中那次的标记清掉，等于没锁 —— 2026-10-17 实际踩到）。
        scheduleMutex.withLock {
            val concurrent = DownloadSettings.concurrentTasks(appContext)
            val queued = dao.unfinished().filter { it.status == DownloadStatus.QUEUED }
            val capacity = concurrent - synchronized(lock) { jobs.size }
            if (capacity <= 0) return@withLock
            queued.take(capacity).forEach { task -> startTask(task) }
            refreshState()
        }
    }

    private fun startTask(task: DownloadTaskEntity) {
        // 同一个任务只允许有一个在跑：多路径（排期 / 网络恢复 / 用户点继续）可能同时到达，
        // 重复启动会让同一个文件被下载两遍，并让并发名额算错
        val alreadyRunning = synchronized(lock) { jobs.containsKey(task.id) }
        if (alreadyRunning) {
            Log.i(TAG, "任务 ${task.id} 已在跑，跳过重复启动")
            return
        }
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
            maybeNotifyFinished()
        }
        synchronized(lock) { jobs[task.id] = job }
    }

    /** 记录上一批结束时的任务数，用来判断"这一批是不是刚收尾"。 */
    private var lastReportedFinished = -1

    /**
     * 一批任务收尾时发一条完成通知（受设置里的「下载完成通知」控制，见 [DownloadService.notifyFinished]）。
     *
     * 判据用**库**而不是内存计数：队列里没有未完成任务（含排队/暂停之外的全部结束）才叫收尾。
     * 只在"上一批已经报过"到"这一批又结束"之间发一次，避免每次 schedule 都重复通知。
     */
    private suspend fun maybeNotifyFinished() {
        if (!::appContext.isInitialized) return
        val all = dao.all()
        val unfinished = all.count { it.status in DownloadStatus.unfinished }
        if (unfinished > 0) return
        if (all.size == lastReportedFinished) return
        lastReportedFinished = all.size
        val ok = all.count { it.status == DownloadStatus.DONE }
        val failed = all.count { it.status == DownloadStatus.FAILED }
        if (ok + failed == 0) return
        Log.i(TAG, "一批下载结束：成功 $ok，失败 $failed")
        DownloadService.notifyFinished(appContext, ok, failed)
    }

    private suspend fun execute(task: DownloadTaskEntity) {
        if (!hasNetworkFor(task)) {
            dao.setStatus(
                task.id, DownloadStatus.PAUSED, WAITING_WIFI_REASON,
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
            workDir = work,
            limit = null,
            onProgress = { progress ->
                scope.launch {
                    dao.setProgress(
                        task.id, progress.done, progress.total, progress.bytes,
                        System.currentTimeMillis()
                    )
                    refreshState()
                }
            },
            // 阶段写库：界面与通知据此显示「合并中 / 转换为 mp4 / 保存中」，
            // 否则切片下完后（22/22）会有一段时间看起来完全没动静
            onStage = { stage ->
                scope.launch {
                    dao.setStage(task.id, stage, System.currentTimeMillis())
                    refreshState()
                }
            }
        )
        when (outcome) {
            is HlsDownloader.Outcome.Failed -> {
                dao.setStatus(task.id, DownloadStatus.FAILED, outcome.reason, System.currentTimeMillis())
            }

            is HlsDownloader.Outcome.Ok -> {
                dao.setStage(task.id, DownloadStage.SAVING, System.currentTimeMillis())
                refreshState()
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
        dao.setStage(task.id, DownloadStage.FETCHING, System.currentTimeMillis())
        refreshState()
        when (val outcome = downloader.saveToGallery(appContext, task.sourceUrl, relativeDir, name)) {
            is ImageDownloader.Outcome.Failed ->
                dao.setStatus(task.id, DownloadStatus.FAILED, outcome.reason, System.currentTimeMillis())

            is ImageDownloader.Outcome.Ok -> {
                // 单张图片的"保存"很短，但解码/解密大图时也会有一小段空窗，一并标出来
                dao.setStage(task.id, DownloadStage.SAVING, System.currentTimeMillis())
                // 存 MediaStore 的 uri：下载管理页要靠它预览/打开这张图（早期传 null 导致点开报"找不到文件记录"）
                dao.setDone(
                    task.id, outcome.displayPath, outcome.uri, outcome.bytes, System.currentTimeMillis()
                )
            }
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
        val active = all.filter { it.status == DownloadStatus.RUNNING }
        _state.value = Snapshot(
            running = running,
            queued = all.count { it.status == DownloadStatus.QUEUED },
            totalBytes = all.filter { it.status != DownloadStatus.DONE }.size.toLong(),
            doneBytes = all.count { it.status == DownloadStatus.DONE }.toLong(),
            currentTitles = active.map { it.title },
            // 多个任务同时在跑时取"处理阶段"优先的那个：合并/转封装/保存比拉切片更值得说
            currentStage = active.map { it.stage }
                .firstOrNull { DownloadStage.isProcessing(it) }
                ?: active.firstOrNull()?.stage.orEmpty()
        )
    }

    /** 失败原因给用户看：把底层英文异常收成人话。 */
    private fun friendlyReason(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接服务器，请检查网络"
        is java.net.SocketTimeoutException -> "连接超时，请重试"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "下载失败，请重试"
    }
}
