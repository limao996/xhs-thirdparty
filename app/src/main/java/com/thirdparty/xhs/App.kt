package com.thirdparty.xhs

import android.app.Application
import android.content.Context
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.net.UpdateChecker
import com.thirdparty.xhs.ui.theme.ThemeMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import com.thirdparty.xhs.common.runCatchingCancellable
import androidx.core.content.edit

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

    fun notifyDataRestored() {
        dataEpoch.value = dataEpoch.value + 1
        // 恢复是直接写 Room，不经过仓库的增删方法，所以这里显式通知三个本地版本号，
        // 让收藏/关注/队列的标签与计数跟着刷新（审计 P1）。
        runCatching { repository.bumpLocalVersions() }
    }

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
     * True while the 详情 page is in TRUE fullscreen — i.e. a VIDEO filling the screen.
     * The system bars are hidden for it.
     *
     * Set by DetailScreen, consumed by AppNavHost, like [feedImmersive]: the bars have
     * exactly one owner, and screens only declare what they want.
     */
    val detailImmersive = MutableStateFlow(false)

    /**
     * True while the 图文 fullscreen viewer is up.
     *
     * A still image is not a video: the user asked for the bars to STAY there, made
     * transparent, with light icons, so the picture runs underneath them. That is a
     * different treatment from [detailImmersive] (which hides them), so the shell needs
     * to know which one is in force.
     */
    val imageViewerShown = MutableStateFlow(false)

    /**
     * Bridge for the settings screen to toggle VIP auto-switch on the SAME
     * GuestViewModel instance HomeScreen owns — 那个实例负责把开关真写进仓库，
     * so writing the pref directly would leave the running loop out of step.
     * HomeScreen registers the setter while it is composed.
     */
    var autoVipSetter: ((Boolean) -> Unit)? = null // 无定时轮询：开关变化时由这个回调落库

    /**
     * True while one of our own system pickers (the file/folder chooser) is in
     * front. That chooser pauses this activity, and the app lock must not fire for
     * it — otherwise picking a backup file would demand an unlock on every attempt.
     * Only set around launches this app starts.
     */
    @Volatile
    var systemPickerActive: Boolean = false

    /**
     * A newer release found by the startup check, waiting to be shown as a dialog.
     *
     * Only ever set when there really IS a newer release: no network, a rate-limit,
     * or no published release stays silent (see [checkUpdateOnLaunch]).
     */
    val pendingUpdate = MutableStateFlow<UpdateChecker.Result.Newer?>(null)

    /** The startup check runs once per process, not once per Activity. */
    private val autoUpdateTried = AtomicBoolean(false)

    /**
     * Set when the startup check could not answer (offline, GitHub down, rate
     * limited), so the next moment the device gets a network we try once more.
     * Without it a launch in a tunnel would mean "no update check this session".
     */
    @Volatile
    private var autoUpdateWantsRetry = false

    /** 上一次「真的发出请求」的时刻（含失败的重试），用来给失败重试退避。 */
    private var lastUpdateAttemptAt = 0L

    /**
     * Ask GitHub for the latest release and, if it is newer, surface it.
     *
     * Called from [onCreate] (i.e. every cold start) and again from [bump] after a
     * failed attempt. It uses the standalone client inside UpdateChecker — no
     * account, no AES envelope — so it works before any guest identity exists.
     *
     * At most once per [UPDATE_CHECK_INTERVAL_MS] (12h): the check is a courtesy, and
     * GitHub's anonymous quota is 60 requests/hour/IP, so checking on every single
     * launch both annoys the user and gets us rate-limited (HTTP 403) out of real
     * checks. Only a check that actually reached GitHub counts as done — a failed one
     * leaves the window open, so the retry in [bump] still works.
     */
    fun checkUpdateOnLaunch(force: Boolean = false) {
        if (!force && !autoUpdateTried.compareAndSet(false, true)) return
        // force 只用来「回到前台补一次」，不是「每次回前台都补」：失败重试也要退避，
        // 否则断网时每次切前台都会打一次 GitHub（匿名 60 次/小时/IP，实测会 403）。
        val now = System.currentTimeMillis()
        if (force && now - lastUpdateAttemptAt < UPDATE_RETRY_MIN_INTERVAL_MS) return
        lastUpdateAttemptAt = now
        appScope.launch {
            if (!updateCheckDue()) return@launch
            val result = runCatchingCancellable { UpdateChecker.check() }.getOrNull()
            autoUpdateWantsRetry = result == null || result is UpdateChecker.Result.Failed
            if (result != null && result !is UpdateChecker.Result.Failed) {
                markUpdateChecked()
            }
            if (result is UpdateChecker.Result.Newer && result.version != ignoredUpdateVersion()) {
                pendingUpdate.value = result
            }
        }
    }

    /** True when the last completed update check is outside the 12h window. */
    private fun updateCheckDue(): Boolean =
        System.currentTimeMillis() - settingsPrefs().getLong(KEY_UPDATE_CHECKED_AT, 0L) >=
            UPDATE_CHECK_INTERVAL_MS

    private fun markUpdateChecked() {
        settingsPrefs().edit { putLong(KEY_UPDATE_CHECKED_AT, System.currentTimeMillis()) }
    }

    /** User closed the update dialog for now (it will be offered again next launch). */
    fun dismissUpdate() {
        pendingUpdate.value = null
    }

    /** User pressed 跳过这个版本: never offer [version] again. */
    fun ignoreUpdateVersion(version: String) {
        pendingUpdate.value = null
        settingsPrefs().edit { putString(KEY_IGNORED_UPDATE, version) }
    }

    fun ignoredUpdateVersion(): String? = settingsPrefs().getString(KEY_IGNORED_UPDATE, null)

    private fun settingsPrefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

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
        watchNetwork()
        // 每次冷启动查一次有没有新版本：查不到就什么都不发生（见 checkUpdateOnLaunch）
        checkUpdateOnLaunch()
    }

    /**
     * Bumped whenever the device gains a usable default network — including the moment a
     * VPN comes up (a VPN is a new default network, and it fires again when it finishes
     * validating).
     *
     * This exists because of a real sequence: without the VPN the API host is unreachable,
     * so the app fails its first requests and parks itself in an error state; turning the
     * VPN on afterwards retries nothing by itself, so the app sat on 「内容加载失败」 until
     * the user hit 重试 — the "开一下 VPN 又无法及时加载" report. Screens observe this and
     * retry whatever they failed to load.
     */
    val networkEpoch = MutableStateFlow(0)

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = bump()

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    if (caps.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                        )
                    ) bump()
                }
            })
        }
    }

    private fun bump() {
        networkEpoch.value = networkEpoch.value + 1
        // a startup update check that failed offline gets one more chance now
        if (autoUpdateWantsRetry) checkUpdateOnLaunch(force = true)
    }

    private fun loadThemeMode(): ThemeMode {
        val key = settingsPrefs().getString("theme_mode", ThemeMode.SYSTEM.key)
            ?: ThemeMode.SYSTEM.key
        return ThemeMode.entries.firstOrNull { it.key == key } ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(mode: ThemeMode) {
        themeState.value = mode
        settingsPrefs().edit { putString("theme_mode", mode.key) }
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
        /** release version the user pressed 跳过这个版本 on */
        private const val KEY_IGNORED_UPDATE = "ignored_update_version"
        /** when the last COMPLETED update check happened (see checkUpdateOnLaunch) */
        private const val KEY_UPDATE_CHECKED_AT = "update_checked_at"
        /** startup update checks are throttled to this interval */
        private const val UPDATE_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

    /** 失败后允许再试的最小间隔（30 分钟）：失败不算「查过了」，但也不能立刻再试。 */
    private const val UPDATE_RETRY_MIN_INTERVAL_MS = 30L * 60L * 1000L
    }
}