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

        /** DEBUG 专用：把任务表打到日志里（验证用，见 DownloadEnqueueProbe；T7 后连同探针一起删）。 */
        const val ACTION_DUMP = "com.thirdparty.xhs.download.DUMP"
        const val EXTRA_TASK_ID = "taskId"

        private const val CHANNEL_ID = "downloads"
        private const val CHANNEL_NAME = "下载"
        private const val NOTIFICATION_ID = 4801

        /** 启动（或唤醒）服务。已在跑时不会重复建服务。 */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
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
            ACTION_DUMP -> scope.launch { dumpTasks() }
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
        val title = when {
            snapshot.currentTitles.isNotEmpty() -> snapshot.currentTitles.joinToString("、")
            snapshot.queued > 0 -> "准备下载 ${snapshot.queued} 项"
            else -> "下载完成"
        }
        val text = "已完成 $done / $total 项" +
            if (snapshot.running > 0) "（同时 ${snapshot.running} 个）" else ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, done, snapshot.running == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "暂停全部", pause)
            .addAction(0, "继续全部", resume)
            .build()
    }

    /** DEBUG 专用：打印任务表（不依赖 adb 拉 sqlite 文件）。 */
    private suspend fun dumpTasks() {
        if (!com.thirdparty.xhs.BuildConfig.DEBUG) return
        val dao = XhsDatabase.get(this).downloadTaskDao()
        val rows = dao.all()
        android.util.Log.i("XhsProbe", "任务表共 ${rows.size} 行")
        rows.forEach { t ->
            android.util.Log.i(
                "XhsProbe",
                "  行 id=${t.id} kind=${t.kind} status=${t.status} 切片=${t.doneSegments}/${t.totalSegments} " +
                    "bytes=${t.bytes} path=${t.savedPath} err=${t.error}"
            )
        }
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
