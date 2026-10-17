package com.thirdparty.xhs.download

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.thirdparty.xhs.data.DownloadDedupe
import com.thirdparty.xhs.data.DownloadGroups
import com.thirdparty.xhs.data.DownloadUnit
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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
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
    /** 正在跑的**执行单元**：键 = [DownloadUnit.key]（一个视频 / 一个图集各一份）。 */
    private val jobs = mutableMapOf<String, Job>()
    private val lock = Any()

    /** `schedule()` 的串行锁（见该函数注释：并发排期会突破并发上限）。 */
    private val scheduleMutex = Mutex()

    /**
     * 每个在跑任务的实时速度（字节/秒），**只在内存里**。
     *
     * 速度是瞬时观测量，不是任务状态 —— 状态真相仍然只有 `download_task`（硬约束 30），
     * 所以不落库、进程重启后重新采样。界面与通知把它显示成 `1.2 MB/s`。
     */
    private val speeds = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, Long>>(emptyMap())
    val currentSpeeds: kotlinx.coroutines.flow.StateFlow<Map<Long, Long>> = speeds

    /** 速度采样点（上一次的字节数与时间戳）。 */
    private data class Sample(val bytes: Long, val at: Long, val rate: Long)

    private val samples = java.util.concurrent.ConcurrentHashMap<Long, Sample>()

    /** 记录一次进度采样，算出瞬时速度并用指数滑动平均压抖动。 */
    private fun sampleSpeed(taskId: Long, bytes: Long) {
        val now = System.currentTimeMillis()
        val last = samples[taskId]
        if (last == null) {
            samples[taskId] = Sample(bytes, now, 0)
            return
        }
        val dt = now - last.at
        if (dt < 400) return
        val instant = ((bytes - last.bytes) * 1000L / dt).coerceAtLeast(0)
        val smoothed = if (last.rate <= 0) instant else (last.rate * 2 + instant) / 3
        samples[taskId] = Sample(bytes, now, smoothed)
        speeds.value = speeds.value + (taskId to smoothed)
    }

    /** 任务结束 / 暂停 / 失败时清掉速度。 */
    private fun forgetSpeed(taskId: Long) {
        samples.remove(taskId)
        if (speeds.value.containsKey(taskId)) speeds.value = speeds.value - taskId
    }

    /** 给通知用的一份摘要。 */
    data class Snapshot(
        val running: Int = 0,
        val queued: Int = 0,
        val totalBytes: Long = 0,
        val doneBytes: Long = 0,
        val currentTitles: List<String> = emptyList(),
        /** 正在跑的任务所处阶段（`DownloadStage`），供通知显示"合并中 / 转换为 mp4 / 保存中"。 */
        val currentStage: String = "",
        /** 当前作品的进度行（与下载管理页同一套 [DownloadGroups] 口径）。 */
        val currentDetail: String = "",
        /** 当前作品的标题（通知标题用）。 */
        val currentTitle: String = "",
        /** 作品总数 / 已完成作品数（通知的「已完成 x / y 件作品」）。 */
        val worksTotal: Int = 0,
        val worksDone: Int = 0,
        /**
         * 当前单元的**真实进度**（视频看分片数、图集看图片张数）。
         *
         * 原来的 `totalBytes/doneBytes` 名字骗人：它们其实是"未完成条目数 / 已完成条目数"，
         * 单个视频时就是 1 和 0 —— 进度条永远停在 0%（用户 2026-10-17：「进度条没效果」）。
         */
        val progressDone: Int = 0,
        val progressTotal: Int = 0,
        /** 处理阶段（合并 / 转封装 / 保存）已经过去多少秒；非处理阶段为 0。 */
        val stageSeconds: Int = 0,
        /**
         * **每个未结束的作品一条通知**用的数据（用户 2026-10-17：不要挤在一条通知里）。
         *
         * 顺序 = 作品在库里的先后（新到的排在前面由 DAO 决定），服务端按这个顺序分配通知 id。
         */
        val works: List<WorkNotice> = emptyList()
    )

    /**
     * 一件作品在通知里的全部信息。**只讲这一件作品**，不带全局计数
     * （全局进度放在前台那条的副标题里，见 [DownloadService]）。
     */
    data class WorkNotice(
        val noteId: Long,
        val title: String,
        /** 这件作品的进度行，如 `图片 26/43 张` / `视频 5/14 片` / `转换为 mp4…` */
        val detail: String,
        val done: Int,
        val total: Int,
        val running: Boolean,
        val paused: Boolean,
        val failed: Boolean,
        val processing: Boolean,
        val stageSeconds: Int
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var dao: DownloadTaskDao
    private var client: OkHttpClient = OkHttpClient()

    /** 仓库引用：下载要像观看一样过账号/VIP 闸门（[XhsRepository.ensureAccountForRequest]）。 */
    private var repo: com.thirdparty.xhs.data.XhsRepository? = null

    /** 下载前确保账号带 VIP：与观看同一条链路（不足 60 秒就换新游客号）。 */
    private suspend fun ensureAccount() {
        runCatching {
            repo?.ensureAccountForRequest()
            Log.i(TAG, "下载前已检查 VIP（账号有效，必要时已换新游客号）")
        }.onFailure { Log.w(TAG, "下载前检查 VIP 失败：${it.message}") }
    }

    fun init(context: Context, repository: com.thirdparty.xhs.data.XhsRepository) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        dao = XhsDatabase.get(appContext).downloadTaskDao()
        repo = repository
        // 用仓库的下载客户端：超时放宽 + 与主客户端共用连接池与拦截器（原来是自己 new 一个裸的，
        // OkHttp 默认 read 10s，大分片必超时 —— 用户 2026-10-17 反馈）
        client = repository.downloadHttp
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
    const val WAITING_WIFI_REASON = "等待 Wi-Fi（仅 Wi-Fi 下载已开启）"

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

    /**
     * 入队（由下载对话框调用）并启动前台服务。
     *
     * **顺手去重**：用户反复点「下载」是常见操作，同一条内容（作品 + 类型 + 序号）库里同时只留一条
     * 有效任务 —— 已下载/在下载中的直接跳过（返回给调用方去提示），旧的失败行先删再建
     * （失败重试是正当需求，但同一内容不留两条）。判定逻辑在 `data/DownloadDedupe.kt`（带单测）。
     *
     * @return 入队计划（新建了哪些、跳过了哪些），供界面提示"已跳过 N 项"
     */
    suspend fun enqueue(tasks: List<DownloadTaskEntity>): DownloadDedupe.EnqueuePlan {
        val noteIds = tasks.map { it.noteId }.distinct()
        val existing = noteIds.flatMap { dao.byNote(it) }
        val plan = DownloadDedupe.planEnqueue(tasks, existing)

        plan.superseded.forEach { dao.delete(it.id) }
        plan.added.forEach { dao.insert(it) }
        Log.i(
            TAG,
            "入队 ${plan.added.size} 个任务" +
                if (plan.skippedCount > 0) "（跳过已下载/在下载中的 ${plan.skippedCount} 项）" else ""
        )
        if (plan.added.isEmpty()) return plan
        DownloadService.start(appContext)
        schedule()
        return plan
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

    /** 暂停**一件作品**（通知上的「暂停」按钮）。图集是整单元停，单元内其它在跑的也一起落状态。 */
    fun pauseWork(noteId: Long) = scope.launch {
        val tasks = dao.unfinished().filter { it.noteId == noteId }
        val keys = tasks.map { DownloadUnit.keyOf(it) }.distinct()
        keys.forEach { key -> synchronized(lock) { jobs.remove(key) }?.cancel() }
        tasks.filter { it.status == DownloadStatus.RUNNING }.forEach {
            dao.setStatus(it.id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
            forgetSpeed(it.id)
        }
        tasks.forEach {
            if (dao.byId(it.id)?.status == DownloadStatus.QUEUED) {
                dao.setStatus(it.id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
            }
        }
        refreshState()
    }

    /** 继续**一件作品**（通知上的「继续」按钮）。 */
    fun resumeWork(noteId: Long) = scope.launch {
        dao.unfinished()
            .filter { it.noteId == noteId && it.status == DownloadStatus.PAUSED }
            .forEach { dao.setStatus(it.id, DownloadStatus.QUEUED, null, System.currentTimeMillis()) }
        DownloadService.start(appContext)
        schedule()
    }

    fun pauseAll() = scope.launch {
        val keys = synchronized(lock) { jobs.keys.toList() }
        Log.i(TAG, "暂停全部：${keys.size} 个在跑的执行单元")
        keys.forEach { key -> synchronized(lock) { jobs.remove(key) }?.cancel() }
        // 单元被取消后，把库里所有还在 RUNNING 的都落成 PAUSED（单元内并行的那几张也要落）
        dao.unfinished()
            .filter { it.status == DownloadStatus.RUNNING }
            .forEach {
                dao.setStatus(it.id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
                forgetSpeed(it.id)
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
        // 一个图集是一个单元：暂停任意一张都要把整个单元停下来，并把单元里其它在跑的任务一起标 PAUSED
        // （否则它们会以 RUNNING 卡住 —— 协程已经随单元被取消，不会再有人写它们的收尾状态）
        val task = dao.byId(id)
        val key = task?.let { DownloadUnit.keyOf(it) }
        if (key != null) {
            synchronized(lock) { jobs[key] }?.cancel()
            synchronized(lock) { jobs.remove(key) }
            dao.unfinished()
                .filter { DownloadUnit.keyOf(it) == key && it.status == DownloadStatus.RUNNING }
                .forEach {
                    dao.setStatus(it.id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
                    forgetSpeed(it.id)
                }
        }
        dao.setStatus(id, DownloadStatus.PAUSED, null, System.currentTimeMillis())
        forgetSpeed(id)
        refreshState()
    }

    fun retry(id: Long) = scope.launch {
        dao.setStatus(id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
        DownloadService.start(appContext)
        schedule()
    }

    fun cancel(id: Long) = scope.launch {
        val task = dao.byId(id)
        val key = task?.let { DownloadUnit.keyOf(it) }
        if (key != null) {
            // 取消后**等协程收尾**再动磁盘：否则正在写的那一片会把刚删掉的目录又建回来（孤儿文件）
            val job = synchronized(lock) { jobs.remove(key) }
            job?.cancel()
            job?.join()
            // 用户取消的是**这一张**：单元里其它还在跑的图回到排队，等下次排期继续
            dao.unfinished()
                .filter { DownloadUnit.keyOf(it) == key && it.id != id }
                .forEach {
                    if (it.status == DownloadStatus.RUNNING) {
                        dao.setStatus(it.id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
                    }
                }
        }
        task?.let { deleteTemp(it.id) }
        dao.delete(id)
        refreshState()
        schedule()
    }

    /**
     * 删除一条记录；[alsoDeleteFile] 为真时**连公共目录里的源文件一起删**。
     *
     * 默认只删记录（用户可能只是想把列表清干净，文件还要留在相册里看）；
     * 要删文件时通过 `ContentResolver` 删 MediaStore 的行 —— 那同时会把文件删掉，
     * 本应用是这些文件的创建者（`MediaStoreWriter`），29+ 不需要额外权限，26–28 有 `WRITE_EXTERNAL_STORAGE`。
     */
    fun delete(id: Long, alsoDeleteFile: Boolean) = scope.launch {
        val running = dao.byId(id)?.let { DownloadUnit.keyOf(it) }
        if (running != null) {
            // 用户手动停止（点删除）时：先停协程并**等它收尾**，再删记录与临时文件 ——
            // 不等的话，正在写盘的那一片会在清理之后把目录重建出来（缓存里留孤儿文件）；
            // 另外被取消的单元不会执行 `startUnit` 结尾的 schedule，所以这组里其它还在跑的图
            // 必须回到排队，并在本方法末尾补一次 schedule，否则它们会以 RUNNING 卡住。
            val job = synchronized(lock) { jobs.remove(running) }
            job?.cancel()
            job?.join()
            dao.unfinished()
                .filter { DownloadUnit.keyOf(it) == running && it.id != id }
                .forEach {
                    if (it.status == DownloadStatus.RUNNING) {
                        dao.setStatus(it.id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
                    }
                }
        }
        val task = dao.byId(id)
        task?.let { deleteTemp(it.id) }
        if (alsoDeleteFile) task?.let { deleteSavedFile(it) }
        dao.delete(id)
        refreshState()
        schedule()
    }

    /**
     * 清空已完成 / 失败的记录；[alsoDeleteFiles] 为真时连文件一起删。
     *
     * @return 实际删掉的文件数（给界面提示用）
     */
    suspend fun clearFinished(alsoDeleteFiles: Boolean): Int {
        val rows = dao.finished()
        var deletedFiles = 0
        rows.forEach { task ->
            deleteTemp(task.id)
            if (alsoDeleteFiles && deleteSavedFile(task)) deletedFiles++
        }
        dao.clearFinished()
        refreshState()
        return deletedFiles
    }

    /** 删掉公共目录里的源文件（按 MediaStore 的 uri）。返回是否真的删了。 */
    private fun deleteSavedFile(task: DownloadTaskEntity): Boolean {
        val uri = task.savedUri?.takeIf { it.isNotBlank() } ?: return false
        return runCatching {
            val removed = appContext.contentResolver.delete(uri.toUri(), null, null)
            Log.i(TAG, "删除源文件 ${task.savedPath ?: uri}（结果=$removed）")
            removed > 0
        }.getOrElse { e ->
            Log.w(TAG, "删除源文件失败：${task.savedPath ?: uri}（${e.message}）")
            false
        }
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
            // 名额按**执行单元**算：一个视频一个、一个图集一个（一张图不算一个名额）
            val units = DownloadUnit.of(queued)
            val capacity = concurrent - synchronized(lock) { jobs.size }
            if (capacity <= 0) return@withLock
            units.take(capacity).forEach { unit -> startUnit(unit) }
            refreshState()
        }
    }

    /**
     * 排一个**执行单元**（见 [DownloadUnit]）。
     *
     * 视频 = 一个单元（内部按"并发数"并行拉切片）；**一个作品的全部图片 = 一个单元**
     * （内部同样按"并发数"并行拉图）—— 用户 2026-10-17 要求"一张图片不能当成一个任务"。
     * 单元是并发名额的计量单位，`jobs` 也只按单元记一份。
     */
    private fun startUnit(unit: DownloadUnit) {
        // 同一个单元只允许有一个在跑：多路径（排期 / 网络恢复 / 用户点继续）可能同时到达，
        // 重复启动会让同一个文件被下载两遍，并让并发名额算错
        val alreadyRunning = synchronized(lock) { jobs.containsKey(unit.key) }
        if (alreadyRunning) {
            Log.i(TAG, "单元 ${unit.key} 已在跑，跳过重复启动")
            return
        }
        val job = scope.launch {
            // 与观看一致：动手前先确认账号带 VIP（不足就换新游客号）
            ensureAccount()
            if (unit.isImageSet) {
                Log.i(TAG, "开始图集 ${unit.key}（${unit.images.size} 张，并发=$unitParallel）")
                val semaphore = Semaphore(unitParallel.coerceAtLeast(1))
                // 单元内部并行：每张图仍是一条任务行（进度/失败/断点各自独立）
                unit.images.map { image ->
                    launch {
                        semaphore.withPermit {
                            if (dao.byId(image.id)?.status == DownloadStatus.QUEUED) {
                                runTask(image)
                            }
                        }
                    }
                }.joinAll()
            } else {
                unit.video?.let { video ->
                    Log.i(TAG, "开始任务 ${video.id}（${video.kind} ${video.title}）")
                    runTask(video)
                }
            }
            synchronized(lock) { jobs.remove(unit.key) }
            refreshState()
            // 空出名额后继续排下一个
            schedule()
            maybeNotifyFinished()
        }
        synchronized(lock) { jobs[unit.key] = job }
    }

    /** 单个任务的一次执行：置 RUNNING → 跑 → 收尾（失败/取消都要留下正确的状态）。 */
    private suspend fun runTask(task: DownloadTaskEntity) {
        dao.setStatus(task.id, DownloadStatus.RUNNING, null, System.currentTimeMillis())
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
    }

    /** 单元内的并行度：与视频分片用同一档设置（用户要求"图集按视频分片的标准来"）。 */
    private val unitParallel: Int
        get() = if (::appContext.isInitialized) DownloadSettings.parallelSegments(appContext) else 4

    /**
     * **这一轮**（本进程内）完成与失败的任务数，只用于完成通知的文案。
     *
     * 不要拿"库里 DONE 的总数"来报：那会把历史所有下载都算进去，用户只下了 1 个作品却看到
     * 「完成 17 项」（2026-10-17 实测发现）。报完清零；"这一轮什么都没跑完"时不发通知。
     */
    /** 网络类失败自动重排队的计数（taskId -> 次数）；成功/最终失败时清掉。 */
    private val fetchRetries = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    /** 网络类失败最多自动重排 2 次（每次间隔 20 秒，且复用已下好的分片）。 */
    private const val FETCH_RETRY_LIMIT = 2

    private val runOkVideo = java.util.concurrent.atomic.AtomicInteger(0)
    private val runOkImage = java.util.concurrent.atomic.AtomicInteger(0)
    private val runFailedVideo = java.util.concurrent.atomic.AtomicInteger(0)
    private val runFailedImage = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 一批任务收尾时发一条完成通知（受设置里的「下载完成通知」控制，见 [DownloadService.notifyFinished]）。
     *
     * 判据用**库**判"是否收尾"（没有未完成任务），数量用本进程的轮次计数
     * （[runOk] / [runFailed]），与"历史一共完成了多少"无关。
     */
    private suspend fun maybeNotifyFinished() {
        if (!::appContext.isInitialized) return
        val unfinished = dao.unfinished().size
        if (unfinished > 0) return
        val okVideo = runOkVideo.getAndSet(0)
        val okImage = runOkImage.getAndSet(0)
        val failedVideo = runFailedVideo.getAndSet(0)
        val failedImage = runFailedImage.getAndSet(0)
        if (okVideo + okImage + failedVideo + failedImage == 0) return
        Log.i(
            TAG,
            "一批下载结束：成功 ${okVideo} 个视频 / ${okImage} 张图片，失败 ${failedVideo} / ${failedImage}"
        )
        DownloadService.notifyFinished(appContext, okVideo, okImage, failedVideo, failedImage)
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
        val downloader = HlsDownloader(client, parallel = parallel, ensureAccount = { ensureAccount() })
        val outcome = downloader.remuxToMp4(
            context = appContext,
            playlistUrl = task.sourceUrl,
            workDir = work,
            limit = null,
            onProgress = { progress ->
                // 速度是内存观测值：这里采样，界面/通知据此显示 "1.2 MB/s"
                sampleSpeed(task.id, progress.bytes)
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
                // 网络类失败自动重排队：重来时**已下好的分片会被复用**（切片级断点），
                // 所以重试成本只是没下完的那部分。用户报的"大视频卡进度 / 连接超时"里，
                // 真正的卡点常是某几片反复超时 —— 让它自己歇 20 秒再来，比直接把任务判死有用。
                val tries = fetchRetries.merge(task.id, 1) { a, _ -> a + 1 } ?: 1
                if (outcome.retryable && tries <= FETCH_RETRY_LIMIT) {
                    Log.i(
                        TAG,
                        "网络类失败，20 秒后自动重排队（第 $tries/$FETCH_RETRY_LIMIT 次）：${outcome.reason}"
                    )
                    dao.setStatus(task.id, DownloadStatus.QUEUED, null, System.currentTimeMillis())
                    forgetSpeed(task.id)
                    scope.launch {
                        delay(20_000)
                        schedule()
                    }
                } else {
                    fetchRetries.remove(task.id)
                    dao.setStatus(
                        task.id, DownloadStatus.FAILED, outcome.reason, System.currentTimeMillis()
                    )
                    runFailedVideo.incrementAndGet()
                    forgetSpeed(task.id)
                }
            }

            is HlsDownloader.Outcome.Ok -> {
                fetchRetries.remove(task.id)
                dao.setStage(task.id, DownloadStage.SAVING, System.currentTimeMillis())
                refreshState()
                val name = DownloadNames.uniqueName(
                    DownloadNames.baseName(task.title, task.noteId), "mp4"
                ) { candidate -> existsInVideoDir(candidate) }
                when (val saved = MediaStoreWriter.saveVideo(appContext, outcome.mp4, name)) {
                    is MediaStoreWriter.Result.Failed -> {
                        dao.setStatus(task.id, DownloadStatus.FAILED, saved.reason, System.currentTimeMillis())
                        runFailedVideo.incrementAndGet()
                        forgetSpeed(task.id)
                    }

                    is MediaStoreWriter.Result.Ok -> {
                        dao.setDone(task.id, saved.displayPath, saved.uri.toString(), saved.bytes, System.currentTimeMillis())
                        forgetSpeed(task.id)
                        runOkVideo.incrementAndGet()
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
        // MIME 按扩展名给：写死 image/jpeg 会让 .png 的图落成 `01.png.jpg`（实测）
        when (
            val outcome = downloader.saveToGallery(
                appContext, task.sourceUrl, relativeDir, name, DownloadNames.imageMimeType(ext)
            )
        ) {
            is ImageDownloader.Outcome.Failed -> {
                dao.setStatus(task.id, DownloadStatus.FAILED, outcome.reason, System.currentTimeMillis())
                runFailedImage.incrementAndGet()
                forgetSpeed(task.id)
            }

            is ImageDownloader.Outcome.Ok -> {
                // 单张图片的"保存"很短，但解码/解密大图时也会有一小段空窗，一并标出来
                dao.setStage(task.id, DownloadStage.SAVING, System.currentTimeMillis())
                // 存 MediaStore 的 uri：下载管理页要靠它预览/打开这张图（早期传 null 导致点开报"找不到文件记录"）
                dao.setDone(
                    task.id, outcome.displayPath, outcome.uri, outcome.bytes, System.currentTimeMillis()
                )
                forgetSpeed(task.id)
                runOkImage.incrementAndGet()
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
        // 名额按执行单元算（一个视频 / 一个图集各一个），所以 running 用 jobs.size 就是"在跑的作品数"
        val running = synchronized(lock) { jobs.size }
        val active = all.filter { it.status == DownloadStatus.RUNNING }
        // 作品维度：通知文案统一用"作品"，不再混着数图片张数（用户 2026-10-17：通知文案太乱）
        val groups = DownloadGroups.of(all)
        val activeGroup = groups.firstOrNull { !it.settled }
        _state.value = Snapshot(
            running = running,
            queued = all.count { it.status == DownloadStatus.QUEUED },
            totalBytes = all.filter { it.status != DownloadStatus.DONE }.size.toLong(),
            doneBytes = all.count { it.status == DownloadStatus.DONE }.toLong(),
            currentTitles = active.map { it.title },
            // 多个任务同时在跑时取"处理阶段"优先的那个：合并/转封装/保存比拉切片更值得说
            currentStage = active.map { it.stage }
                .firstOrNull { DownloadStage.isProcessing(it) }
                ?: active.firstOrNull()?.stage.orEmpty(),
            // 当前这件作品的进度行（与下载管理页同一套口径，通知不再另写一份）
            currentDetail = activeGroup?.statusSummary().orEmpty(),
            currentTitle = activeGroup?.title.orEmpty(),
            worksTotal = groups.size,
            worksDone = groups.count { it.settled },
            progressDone = activeGroup?.doneProgressUnits() ?: 0,
            progressTotal = activeGroup?.progressUnits() ?: 0,
            stageSeconds = active.map { it.updatedAt }.minOrNull()
                ?.let { ((System.currentTimeMillis() - it) / 1000).toInt().coerceAtLeast(0) }
                ?.takeIf { active.any { t -> DownloadStage.isProcessing(t.stage) } } ?: 0,
            works = groups.filter { !it.settled }.map { g ->
                val running = g.items.any { it.status == DownloadStatus.RUNNING }
                val stageSeconds = g.items.filter { it.status == DownloadStatus.RUNNING }
                    .map { it.updatedAt }.minOrNull()
                    ?.let { ((System.currentTimeMillis() - it) / 1000).toInt().coerceAtLeast(0) }
                    ?: 0
                WorkNotice(
                    noteId = g.noteId,
                    title = g.title,
                    detail = g.statusSummary(),
                    done = g.doneProgressUnits(),
                    total = g.progressUnits(),
                    running = running,
                    paused = !running && g.pausedCount > 0,
                    failed = g.failedCount > 0,
                    processing = g.processing,
                    stageSeconds = stageSeconds
                )
            }
        )
    }

    /** 失败原因给用户看：把底层英文异常收成人话。 */
    private fun friendlyReason(e: Throwable): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "无法连接服务器，请检查网络"
        is java.net.SocketTimeoutException -> "连接超时，请重试"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "下载失败，请重试"
    }
}
