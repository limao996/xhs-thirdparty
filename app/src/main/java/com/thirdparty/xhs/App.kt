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

    /** 同时只允许一个检查在飞（进前台可能连着触发，别叠请求）。 */
    @Volatile
    private var updateCheckInFlight = false

    /** 上一次「真的发出请求」的时刻：用来去重（同一秒内的多次进前台）与失败退避。 */
    @Volatile
    private var lastUpdateAttemptAt = 0L

    /** 上次检查是不是失败；失败才需要退避，成功不需要（下次进前台照查）。 */
    @Volatile
    private var lastUpdateFailed = false

    /**
     * Ask GitHub for the latest release and, if it is newer, surface it.
     *
     * **每次进入应用（冷启动 + 每次回到前台）都查一次**：[onActivityStarted] 里在
     * "从后台回到前台"的那一次调用它。之所以敢这么查，是因为 `UpdateChecker` 走的是
     * `releases.atom`（网页 feed，**不占 REST API 那 60 次/小时/IP 的额度**，也不需要 token）；
     * 只有 feed 失败时才回落到 api.github.com，那时才可能碰到 403。
     *
     * 两道保护（都不是"节流"）：
     * - **去重**：[UPDATE_CHECK_DEDUPE_MS] 内的重复触发只发一次请求（onStart/onResume 连着来、
     *   快速切前后台），真正的判据始终是"是否进入前台"；
     * - **失败退避**：失败（断网/限流）后 [UPDATE_RETRY_MIN_INTERVAL_MS] 内不再重试，
     *   免得没网时每次切前台都白打一次；成功则不受限。
     */
    fun checkUpdateOnLaunch() {
        val now = System.currentTimeMillis()
        val gap = now - lastUpdateAttemptAt
        if (updateCheckInFlight) return
        if (lastUpdateAttemptAt != 0L && gap < UPDATE_CHECK_DEDUPE_MS) return
        if (lastUpdateFailed && gap < UPDATE_RETRY_MIN_INTERVAL_MS) return
        updateCheckInFlight = true
        lastUpdateAttemptAt = now
        appScope.launch {
            val result = runCatchingCancellable { UpdateChecker.check() }.getOrNull()
            lastUpdateFailed = result == null || result is UpdateChecker.Result.Failed
            updateCheckInFlight = false
            if (result is UpdateChecker.Result.Newer && result.version != ignoredUpdateVersion()) {
                pendingUpdate.value = result
            }
            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "XhsUpdate",
                    "check=" + (result?.javaClass?.simpleName ?: "null") + " failed=" + lastUpdateFailed
                )
            }
        }
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
                val wasBackground = startedActivities == 0
                startedActivities++
                appForeground.value = true
                // 每次"从后台回到前台"都查一次更新（atom feed 不吃 API 额度，见 checkUpdateOnLaunch）
                if (wasBackground) checkUpdateOnLaunch()
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
            // 线路在国内、接口在海外：没有 VPN 时 SYN 会被丢，connect 会一直等到超时。
            // 所以这里把 connect 收到 10s、read 收到 15s，并加 callTimeout —— 单次请求最多 20s
            // 就有结论（原来是 30s×2 次重试 ≈ 一分钟的转圈）。配合 XhsApi 的"没有可用网络就直接失败"，
            // 无网时几乎是立刻给出错误态而不是转圈。
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        repository = XhsRepository(this, httpClient)
        themeState.value = loadThemeMode()
        watchNetwork()
        // 冷启动那一次由 onActivityStarted（0→1）触发，这里不再单独查
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

    /**
     * 上一次 bump 时的"网络身份"与验证状态。
     *
     * `onCapabilitiesChanged` 不只在换网时触发：信号强度、带宽估算一变它就回调，
     * 有的是十几秒一次。以前每次回调都 bump，于是"网络能力微调"会带动所有页面重新加载 ——
     * 详情页看起来就是在频繁重建（用户 2026-10-09 报的现象）。
     * 现在只有**换了网络**或**验证状态翻转**才算一次真正的变化。
     */
    @Volatile
    private var lastBumpNetworkHandle: Long = 0L

    @Volatile
    private var lastBumpValidated: Boolean = false

    private fun watchNetwork() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    if (network.networkHandle != lastBumpNetworkHandle) {
                        lastBumpNetworkHandle = network.networkHandle
                        // 刚可用时还没验证过；等 onCapabilitiesChanged 报 VALIDATED 再补一次
                        lastBumpValidated = false
                        bump()
                    }
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities
                ) {
                    val validated = caps.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    val changed = network.networkHandle != lastBumpNetworkHandle ||
                        validated != lastBumpValidated
                    if (changed) {
                        lastBumpNetworkHandle = network.networkHandle
                        lastBumpValidated = validated
                    }
                    // 只在"变成已验证"时补一次；掉成未验证不用再打一轮请求（会立刻失败）
                    if (changed && validated) bump()
                }

                override fun onLost(network: android.net.Network) {
                    // 网络没了：清掉记录，这样它回来（或换成另一个）还会再 bump 一次
                    if (network.networkHandle == lastBumpNetworkHandle) {
                        lastBumpNetworkHandle = 0L
                        lastBumpValidated = false
                    }
                }
            })
        }
    }

    /**
     * 当前是否有"可用（已验证）的网络"。
     *
     * 用来做**快速失败**：国内线路直连海外接口时，没有 VPN 的请求会一直等到超时。既然
     * 系统已经告诉我们"现在没有可用网络"，就不要去等那 10~20 秒 —— 直接返回失败，
     * 让界面立刻显示错误态，等网络真的可用时（例如开了 VPN）再自动重试。
     *
     * 校验过（`NET_CAPABILITY_VALIDATED`）才算可用：只连着 Wi-Fi 但还没通外网、
     * 或者被强制门户拦住的情况，都应当算"不可用"。
     */
    fun hasValidatedNetwork(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun bump() {
        networkEpoch.value = networkEpoch.value + 1
        // 网络换了/刚恢复（典型：用户开了 VPN，这是一个新的默认网络）：**把更新检查的失败退避清掉**，
        // 立刻再查一次。否则"进应用时没网 → 检查失败 → 退避 5 分钟"会把 VPN 起来后的这次机会也挡掉，
        // 用户就会觉得"开了 VPN 也不查更新"。
        lastUpdateFailed = false
        lastUpdateAttemptAt = 0L
        checkUpdateOnLaunch()
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
        /**
     * 重复触发去重窗口：onStart/onResume 连着来、快速切前后台时只发一次请求。
     * 这不是"节流" —— 决定要不要查的是"是否进入前台"（见 checkUpdateOnLaunch）。
     */
    private const val UPDATE_CHECK_DEDUPE_MS = 3_000L

    /** 失败后允许再试的最小间隔（5 分钟）：失败不算「查过了」，但也不能立刻再试。 */
    private const val UPDATE_RETRY_MIN_INTERVAL_MS = 5L * 60L * 1000L
    }
}