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

    /**
     * Bumped when the app-lock toggle changes, so the lock state can take effect
     * immediately: turning it ON locks right away (the user sees it work), OFF
     * unlocks.
     */
    val lockEpoch = MutableStateFlow(0)

    fun notifyLockChanged() { lockEpoch.value = lockEpoch.value + 1 }

    /**
     * True while the 推荐 tab is the visible content. It is full-bleed, so the
     * system bars need white icons there; every other destination has an opaque
     * themed surface and needs icons that follow the theme. Set by HomeScreen and
     * consumed by AppNavHost, which owns the actual bar configuration — keeping it
     * in one place is what stops a screen from being left with the feed's bars.
     */
    val feedImmersive = MutableStateFlow(false)

    /**
     * Bridge for the settings screen to toggle VIP auto-switch on the SAME
     * GuestViewModel instance HomeScreen owns — that instance drives the 5s poll,
     * so writing the pref directly would leave the running loop out of step.
     * HomeScreen registers the setter while it is composed.
     */
    var autoVipSetter: ((Boolean) -> Unit)? = null

    /**
     * True while one of our own system pickers (the file/folder chooser) is in
     * front. That chooser pauses this activity, and the app lock must not fire for
     * it — otherwise picking a backup file would demand an unlock on every attempt.
     * Only set around launches this app starts.
     */
    @Volatile
    var systemPickerActive: Boolean = false

    /**
     * True while any of this app's activities is started (i.e. the UI is on screen).
     *
     * Owned here because more than one thing needs it: the VIP poll asks it so it does
     * not do network work — or register a fresh account — with the screen off, and any
     * future background-capable work has the same question. Tracked with a counter
     * rather than a boolean because a configuration change or a second activity
     * overlaps start/stop.
     */
    val appForeground = MutableStateFlow(false)

    private var startedActivities = 0

    /**
     * Long-lived scope for work that must OUTLIVE a screen but still not be
     * unstructured: a settings write that should land even if the settings page is
     * popped immediately (see the 最近浏览 trim in AppNavHost). Cancelled only with the
     * process, which is what "app scope" has to mean here.
     */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                appForeground.value = true
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) appForeground.value = false
            }

            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
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