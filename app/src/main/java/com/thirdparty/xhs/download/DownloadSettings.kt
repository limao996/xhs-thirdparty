package com.thirdparty.xhs.download

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 下载相关的设置（设置页在 T8 提供 UI，这里只负责读写与取值约束）。
 *
 * 用项目现有的 `settings` SharedPreferences（与主题、浏览上限等同一个文件），不引入 DataStore。
 */
object DownloadSettings {

    private const val PREFS = "settings"
    private const val KEY_CONCURRENT = "download_concurrent_tasks"
    private const val KEY_SEGMENTS = "download_parallel_segments"
    private const val KEY_WIFI_ONLY = "download_wifi_only"
    private const val KEY_NOTIFY = "download_notify_on_finish"

    const val DEFAULT_CONCURRENT = 2
    const val DEFAULT_SEGMENTS = 4

    /** 同时下载的任务数（1–5）。上限压住是为了别把带宽/IO 打满。 */
    fun concurrentTasks(context: Context): Int =
        prefs(context).getInt(KEY_CONCURRENT, DEFAULT_CONCURRENT).coerceIn(1, 5)

    fun setConcurrentTasks(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_CONCURRENT, value.coerceIn(1, 5)) }

    /** 单个视频内部并行拉几个切片（1–8）。 */
    fun parallelSegments(context: Context): Int =
        prefs(context).getInt(KEY_SEGMENTS, DEFAULT_SEGMENTS).coerceIn(1, 8)

    fun setParallelSegments(context: Context, value: Int) =
        prefs(context).edit { putInt(KEY_SEGMENTS, value.coerceIn(1, 8)) }

    /** 仅 Wi-Fi 下载：非 Wi-Fi 时任务自动暂停（不报错）。 */
    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, false)

    fun setWifiOnly(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_WIFI_ONLY, value) }

    /** 下载完成时是否发一条完成通知。 */
    fun notifyOnFinish(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY, true)

    fun setNotifyOnFinish(context: Context, value: Boolean) =
        prefs(context).edit { putBoolean(KEY_NOTIFY, value) }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
