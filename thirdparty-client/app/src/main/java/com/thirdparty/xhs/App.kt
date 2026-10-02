package com.thirdparty.xhs

import android.app.Application
import android.content.Context
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.ui.theme.ThemeMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

/**
 * Application holder: lightweight manual DI for the repository + a shared
 * OkHttp client + persisted theme preference.
 */
class App : Application() {

    lateinit var repository: XhsRepository
        private set

    lateinit var httpClient: OkHttpClient
        private set

    /** Day/Night/System theme preference (default follow system). */
    val themeState = MutableStateFlow(ThemeMode.SYSTEM)

    /**
     * Bumped whenever stored data is replaced wholesale (e.g. a backup restore).
     * Screens that cache account state observe this and reload — without it the
     * top bar kept showing the guest id from before the restore.
     */
    val dataEpoch = MutableStateFlow(0)

    fun notifyDataRestored() { dataEpoch.value = dataEpoch.value + 1 }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        // One shared client for the whole app: API calls and image loads use the
        // same connection pool. A disk cache is attached because the image CDN
        // serves `Cache-Control: max-age=31536000`, so covers and avatars are
        // served from disk on later launches. (OkHttp never caches POSTs, so the
        // encrypted API traffic is unaffected.)
        httpClient = OkHttpClient.Builder()
            .cache(okhttp3.Cache(java.io.File(cacheDir, "http_cache"), HTTP_CACHE_BYTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        repository = XhsRepository(this, httpClient)
        themeState.value = loadThemeMode()
    }

    private fun loadThemeMode(): ThemeMode {
        val key = getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("theme_mode", ThemeMode.SYSTEM.key) ?: ThemeMode.SYSTEM.key
        return ThemeMode.entries.firstOrNull { it.key == key } ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(mode: ThemeMode) {
        themeState.value = mode
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("theme_mode", mode.key).apply()
    }

    /**
     * Release decoded bitmaps when the system is under memory pressure.
     * They are re-fetched (from the OkHttp disk cache when possible), so
     * dropping them costs nothing but a re-decode.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.thirdparty.xhs.ui.components.clearImageMemoryCache()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.thirdparty.xhs.ui.components.clearImageMemoryCache()
    }

    companion object {
        lateinit var INSTANCE: App
            private set
        val repo: XhsRepository get() = INSTANCE.repository
        val http: OkHttpClient get() = INSTANCE.httpClient

        private const val HTTP_CACHE_BYTES = 64L * 1024 * 1024
    }
}