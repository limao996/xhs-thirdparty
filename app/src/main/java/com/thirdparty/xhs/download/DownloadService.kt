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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.thirdparty.xhs.MainActivity
import com.thirdparty.xhs.R
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
        fun notifyFinished(context: Context, okCount: Int, failedCount: Int) {
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
            val text = when {
                failedCount > 0 && okCount > 0 -> "完成 $okCount 项，失败 $failedCount 项"
                failedCount > 0 -> "失败 $failedCount 项，点开可重试"
                else -> "完成 $okCount 项，点开查看"
            }
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
         * 确保"下载完成"渠道存在且为高优先级。
         *
         * 渠道创建后重要性改不了，所以这里只在不存在时创建；老版本装过的用户原来只有低优先级渠道，
         * 新渠道会被单独创建出来（`download_done` 这个名字之前没用过）。
         */
        private fun ensureDoneChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(DONE_CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    DONE_CHANNEL_ID,
                    DONE_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "下载完成后提醒（可单独关闭，不影响下载进度通知）"
                    enableVibration(true)
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
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        val title = when {
            snapshot.currentTitles.isNotEmpty() && stageLabel != null ->
                // 有阶段时优先说"现在在干什么"：切片下完之后的 合并 / 转封装 / 保存
                // 都没有可数进度，只显示"22/22 片"会让人以为卡住
                "$stageLabel · ${snapshot.currentTitles.first()}"

            snapshot.currentTitles.isNotEmpty() -> snapshot.currentTitles.joinToString("、")
            snapshot.queued > 0 -> "准备下载 ${snapshot.queued} 项"
            else -> "下载任务已结束"
        }
        val text = buildString {
            append("已完成 $done / $total 项")
            if (snapshot.running > 0) append("（同时下载 ${snapshot.running} 个）")
            if (DownloadStage.isProcessing(snapshot.currentStage)) {
                // 处理阶段没有进度，明确说明"完成后会继续"，避免看起来停住
                append(" · 正在处理，完成后自动保存")
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            // 处理阶段（没有可数进度）用不确定进度条：动着的条本身就是"还活着"的信号
            .setProgress(
                total,
                done,
                snapshot.running == 0 || DownloadStage.isProcessing(snapshot.currentStage)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
