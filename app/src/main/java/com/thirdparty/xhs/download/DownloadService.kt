package com.thirdparty.xhs.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.thirdparty.xhs.MainActivity
import com.thirdparty.xhs.R
import com.thirdparty.xhs.data.DownloadCopy
import com.thirdparty.xhs.data.DownloadStage
import com.thirdparty.xhs.data.XhsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载前台服务：让下载在切后台/锁屏后继续（用户拍定的形态），并给一条常驻进度通知。
 *
 * 权限与版本：
 * - `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`（API 34+ 必须声明类型）；
 * - `POST_NOTIFICATIONS`（API 33+ 运行时申请）：**被拒也不影响下载**，只是通知不可见，
 *   所以这里不弹权限、也不因缺权限而崩（申请放在下载对话框里做，见 T7）。
 *
 * 服务本身不保存任务状态：状态真相在 Room（见 [DownloadEngine]），通知只画引擎给的摘要。
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.thirdparty.xhs.download.START"
        const val ACTION_PAUSE_ALL = "com.thirdparty.xhs.download.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.thirdparty.xhs.download.RESUME_ALL"
        const val ACTION_CANCEL = "com.thirdparty.xhs.download.CANCEL"
        const val EXTRA_TASK_ID = "taskId"

        /** 常驻**进度**通知的渠道：低优先级（不响不弹，避免下载过程中反复打扰）。 */
        private const val CHANNEL_ID = "downloads"
        private const val CHANNEL_NAME = "下载进度"

        /**
         * **下载完成**通知的渠道：高优先级。
         *
         * 为什么必须单独一个渠道：Android 8+ 的通知渠道**创建后重要性不可修改**，
         * 而低优先级（`IMPORTANCE_LOW`）的表现就是"不弹横幅、不出声、折叠进列表"——
         * 用户反映"下载完成通知总是不弹出来"就是这个原因（2026-10-17）。
         * 完成是用户想立刻知道的事，单独给它 `IMPORTANCE_HIGH`（弹横幅 + 声音 + 抬到最前）。
         */
        private const val DONE_CHANNEL_ID = "download_done"
        private const val DONE_CHANNEL_NAME = "下载完成"

        private const val NOTIFICATION_ID = 4801

        /** 下载完成的一次性通知（与常驻进度通知分开 id，避免互相覆盖）。 */
        private const val FINISHED_NOTIFICATION_ID = 4802

        /**
         * 通知点击时带的 extra：为真则进应用后直接打开下载管理页。
         *
         * 两个通知（进度 / 完成）都带它 —— 通知本来就是"下载"这件事的回执，点开落到下载页才对。
         */
        const val EXTRA_OPEN_DOWNLOADS = "com.thirdparty.xhs.download.OPEN_DOWNLOADS"

        /** 启动（或唤醒）服务。已在跑时不会重复建服务。 */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /**
         * 一批下载结束后发一条完成通知。
         *
         * 由 [DownloadEngine] 在"队列彻底没有未完成任务"时调用（= 一批任务收尾）。
         * 走**高优先级**渠道 [DONE_CHANNEL_ID]（弹横幅 + 声音），所以用户能立刻看到；
         * 受设置里的「下载完成通知」控制；通知权限被拒时静默跳过（下载本身已经完成，不该因此报错）。
         */
        fun notifyFinished(
            context: Context,
            okVideo: Int,
            okImage: Int,
            failedVideo: Int,
            failedImage: Int
        ) {
            if (!DownloadSettings.notifyOnFinish(context)) return
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                android.util.Log.i(
                    "XhsDownload",
                    "下载完成通知被跳过：没有 POST_NOTIFICATIONS 权限"
                )
                return
            }
            ensureDoneChannel(context)
            val manager = NotificationManagerCompat.from(context)
            val okCount = okVideo + okImage
            val failedCount = failedVideo + failedImage
            // 按类型说清楚（"完成 1 个视频、20 张图片"），不要再含糊地写"N 项"（用户要求优化文案）
            val text = DownloadCopy.finishedText(okVideo, okImage, failedVideo, failedImage)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).putExtra(
                    EXTRA_OPEN_DOWNLOADS, true
                ),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            runCatching {
                manager.notify(
                    FINISHED_NOTIFICATION_ID,
                    NotificationCompat.Builder(context, DONE_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_download)
                        .setContentTitle(
                            if (failedCount > 0 && okCount == 0) "下载失败" else "下载完成"
                        )
                        .setContentText(text)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setAutoCancel(true)
                        .setContentIntent(open)
                        .build()
                )
            }
            android.util.Log.i("XhsDownload", "已发下载完成通知（$text，渠道=$DONE_CHANNEL_ID）")
        }

        /**
         * 确保"下载完成"渠道存在且为**高重要性** —— 横幅与振动都交给系统默认，自己不要另设。
         *
         * 用户 2026-10-17 的要求："弹出的通知默认开启系统横幅和振动，这是系统的配置，应该和通知渠道有关"。
         * 确实如此：`IMPORTANCE_HIGH` 的含义就是"发出声音并**以横幅形式**显示"，
         * 同时系统会给它套用**默认的提示音与振动**（用户还能在系统设置里逐项改）。
         * 所以这里只创建渠道并设重要性，**不写** `vibrationPattern` 之类的自定义值 ——
         * 自己写模式等于覆盖系统默认，也违背用户的本意。
         *
         * 重要性**创建后改不了**，所以发现现有渠道不是 HIGH（且不是用户主动关掉的 NONE）就删掉重建，
         * 让老装机也拿到"横幅 + 振动"的默认配置。
         */
        private fun ensureDoneChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val existing = manager.getNotificationChannel(DONE_CHANNEL_ID)
            if (existing != null) {
                val userDisabled = existing.importance == NotificationManager.IMPORTANCE_NONE
                if (!userDisabled && existing.importance == NotificationManager.IMPORTANCE_HIGH) return
                if (userDisabled) {
                    // 用户在系统设置里关掉了它：尊重用户选择，不重建
                    android.util.Log.i("XhsDownload", "下载完成渠道已被用户关闭，不再重建")
                    return
                }
                android.util.Log.i(
                    "XhsDownload",
                    "下载完成渠道重要性偏低（${existing.importance}），删掉重建为 HIGH"
                )
                manager.deleteNotificationChannel(DONE_CHANNEL_ID)
            }
            manager.createNotificationChannel(
                NotificationChannel(
                    DONE_CHANNEL_ID,
                    DONE_CHANNEL_NAME,
                    // HIGH = 系统横幅（heads-up）+ 系统提示音 + 系统振动，全部按系统默认
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "下载完成后弹出横幅提醒（可单独关闭，不影响下载进度通知）"
                }
            )
        }

        /** 前台服务不再有活干时调用（例如全部任务都结束了）。 */
        fun stopIfIdle(context: Context) {
            val dao = XhsDatabase.get(context).downloadTaskDao()
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                if (dao.unfinished().isEmpty()) {
                    runCatching { context.stopService(Intent(context, DownloadService::class.java)) }
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 下载期间持有的 `PARTIAL_WAKE_LOCK`。
     *
     * 为什么必须有：大视频 media3 会落到**软件重编码**（实测 115 MB / 163 片的视频走
     * `c2.android.avc.encoder`，耗时以分钟计）。此时若应用在后台、屏幕又灭了，CPU 会被挂起 ——
     * 编码停滞或直接抛 muxer/codec 异常（用户 2026-10-17 报的"切后台合并 mp4 就 muxer error"）。
     * 前台服务只保证进程不被杀，**不保证 CPU 不休眠**，所以还要一个唤醒锁。
     * 锁随服务生命周期（`onDestroy` 释放），不做超时 —— 服务本来只在下完/全暂停时才退出。
     */
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    /**
     * 故意**不设超时**：下载本身可能持续几十分钟（大视频还要软件重编码），超时到点被系统释放会让
     * 编码在后台再次被挂起 —— 这正是要修的 bug。锁的边界交给服务生命周期：`onDestroy` 释放，
     * 而服务只在"没有未完成任务"时才退出。
     */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "xhs:download"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
            android.util.Log.i("XhsDownload", "已持有下载唤醒锁（避免后台重编码被挂起）")
        }.onFailure { android.util.Log.w("XhsDownload", "获取唤醒锁失败：${it.message}") }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 唤醒锁：前台服务只保证进程不被杀，**不保证 CPU 不休眠** ——
        // 大视频的软件重编码在后台/息屏时会被挂起，表现为 muxer / codec 报错（用户 2026-10-17 反馈）
        acquireWakeLock()
        createChannel()
        DownloadEngine.init(applicationContext)
        // **立刻进前台**：`startForegroundService` 之后系统只给 ~5 秒窗口，等状态收集器再提升会来不及
        // （2026-10-17 实测：收集器先看到"没活干"就 stopSelf，随后系统抛
        // `ForegroundServiceDidNotStartInTimeException` 把进程干掉）。
        promote(buildNotification(DownloadEngine.state.value))
        // 之后通知跟着引擎摘要走
        scope.launch {
            DownloadEngine.state.collectLatest { snapshot ->
                if (snapshot.running + snapshot.queued == 0 && noUnfinishedWork()) {
                    stopSelf()
                    return@collectLatest
                }
                val notification = buildNotification(snapshot)
                if (!foregroundStarted) {
                    promote(notification)
                } else {
                    // API 33+ 需要 POST_NOTIFICATIONS；用户拒绝时**不能崩**，只是通知不显示
                    // （下载照常进行，所以这里既不弹权限也不抛错，见清单里的注释）。
                    // 权限判断写成内联形式：lint 只认调用点附近的 checkSelfPermission
                    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            this@DownloadService,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        runCatching {
                            NotificationManagerCompat.from(this@DownloadService)
                                .notify(NOTIFICATION_ID, notification)
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须尽快进前台，否则系统会因 startForegroundService 超时而杀服务
        promote(buildNotification(DownloadEngine.state.value))
        when (intent?.action) {
            ACTION_PAUSE_ALL -> DownloadEngine.pauseAll()
            ACTION_RESUME_ALL -> DownloadEngine.resumeAll()
            ACTION_CANCEL -> intent.getLongExtra(EXTRA_TASK_ID, -1L)
                .takeIf { it > 0 }?.let { DownloadEngine.cancel(it) }
            // 只有显式 START 动作才排一次队（onStartCommand 可能被系统重放，不能每次都排）
            ACTION_START -> scope.launch { DownloadEngine.resumeUnfinished() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun promote(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        if (!foregroundStarted) {
            android.util.Log.i("XhsDownload", "服务已进前台（id=$NOTIFICATION_ID type=$type）")
        }
        foregroundStarted = true
    }

    private fun buildNotification(snapshot: DownloadEngine.Snapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val resume = PendingIntent.getService(
            this,
            2,
            Intent(this, DownloadService::class.java).setAction(ACTION_RESUME_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val done = snapshot.doneBytes.toInt()
        val total = maxOf(snapshot.totalBytes.toInt(), 1)
        val stageLabel = DownloadStage.label(snapshot.currentStage)
        val processing = DownloadStage.isProcessing(snapshot.currentStage)
        val running = snapshot.running > 0 || snapshot.queued > 0

        // 文案统一成"作品"口径（用户 2026-10-17：原来的通知太乱）：
        //   标题 = 状态 + 在做哪件作品；正文 = 这件作品的进度行（与下载管理页同一套口径）；副标题 = 作品数
        val title = when {
            running -> DownloadCopy.runningTitle(snapshot.currentTitle, snapshot.running)
            snapshot.worksTotal > 0 -> "下载已暂停"
            else -> "下载任务已结束"
        }
        val text = if (running) {
            // 处理阶段没有可数进度：用"已用 N 秒"和"视频较大"提示，而不是干杵着
            DownloadCopy.runningText(
                currentDetail = snapshot.currentDetail,
                // 只在"没有可数进度"的处理阶段才贴阶段标签：拉切片/拉图时进度行本身已经说明在干什么，
                // 再补一个「· 下载中」只是噪音（标题里已经有「下载中」了）
                stageLabel = stageLabel?.takeIf { processing },
                slowHint = DownloadStage.slowHint(snapshot.currentStage, 0)
            )
        } else {
            "点开可继续未完成的作品"
        }
        val subText = DownloadCopy.worksSubText(snapshot.worksDone, snapshot.worksTotal)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            // 处理阶段（没有可数进度）用不确定进度条：动着的条本身就是"还活着"的信号
            .setProgress(
                total,
                done,
                !running || processing
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        subText?.let { builder.setSubText(it) }
        // 只放"当前点得动"的那个动作：全都在暂停时不该出现「暂停全部」，反之亦然
        if (snapshot.running > 0 || snapshot.queued > 0) {
            builder.addAction(0, "暂停全部", pause)
        } else {
            builder.addAction(0, "继续全部", resume)
        }
        return builder.build()
    }

    /** 库里还有没有未完成的任务（停止服务的判据以库为准，避免内存摘要滞后导致误停）。 */
    private suspend fun noUnfinishedWork(): Boolean =
        XhsDatabase.get(this).downloadTaskDao().unfinished().isEmpty()

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "下载进度" }
        )
    }
}
