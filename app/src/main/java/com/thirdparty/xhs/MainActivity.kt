package com.thirdparty.xhs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.thirdparty.xhs.navigation.AppNavHost
import com.thirdparty.xhs.ui.components.UpdateAvailableDialog
import com.thirdparty.xhs.ui.components.openUrl
import com.thirdparty.xhs.ui.theme.XhsWindowBackground
import com.thirdparty.xhs.ui.theme.XhsTheme
import com.thirdparty.xhs.ui.theme.isDark

/**
 * Single-Activity Compose app. Theme follows the persisted mode (default system).
 *
 * Also the entry point for shared notes: the share sheet emits a
 * [DeepLink.SCHEME] link, and opening it lands here (see [shareNote]).
 */
open class MainActivity : androidx.fragment.app.FragmentActivity() {

    /**
     * Note id from an incoming share link, consumed once by the nav host.
     * Kept in a plain field because it is read from `onCreate`/`onNewIntent` and
     * only handed to Compose.
     */
    /**
     * Note id to open, set ONLY by the clipboard prompt's 打开 button.
     *
     * There is deliberately no intent-filter for the share link: the flow is a
     * clipboard 口令, not a clickable deep link. The link only ever travels as
     * text, so nothing has to resolve it as a URI.
     */
    private val pendingNote = androidx.compose.runtime.mutableStateOf<Long?>(null)

    /**
     * Note id found in the clipboard when the app came back to the foreground,
     * awaiting the user's yes/no. This is the "复制口令回流" flow: the copy above
     * puts the link on the clipboard, and returning to the app offers to open it.
     */
    private val clipboardNote = androidx.compose.runtime.mutableStateOf<Long?>(null)

    /** last link already offered, so the same clipboard does not prompt every resume */
    private val prefs by lazy { getSharedPreferences("xhs_guest", MODE_PRIVATE) }

    /**
     * Look for a share link on the clipboard.
     *
     * Checked in [onResume] on purpose: from Android 10 reading the clipboard is
     * only allowed while the app has focus, which is exactly this moment.
     * The result is de-duplicated against the last link already offered, otherwise
     * every resume would re-prompt until the user cleared the clipboard.
     */
    private fun checkClipboardForShareLink() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) return
        val text = (0 until clip.itemCount)
            .mapNotNull { clip.getItemAt(it).coerceToText(this)?.toString() }
            .joinToString("\n")
        val noteId = DeepLink.parseNoteId(text) ?: return
        // 自己刚分享出去的那条：分享面板里的「复制」会把它放进剪贴板，用它去触发"打开这条笔记"
        // 纯属绕圈。标记成已看过，之后也不会再提示。
        if (com.thirdparty.xhs.data.ShareText.isSelfShared(noteId, text)) {
            prefs.edit().putString(KEY_LAST_CLIP, noteId.toString()).apply()
            return
        }
        if (prefs.getString(KEY_LAST_CLIP, null) == noteId.toString()) return
        clipboardNote.value = noteId
    }

    /**
     * Clipboard is read here, NOT in onResume.
     *
     * Android 10+ denies clipboard access to an app that is not focused and logs
     * `ClipboardService: Denying clipboard access ... application is not in focus`;
     * onResume runs BEFORE the window gains focus, so a read there is silently
     * refused. onWindowFocusChanged(hasFocus = true) is the documented point at
     * which the read is allowed and actually succeeds.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboardForShareLink()
    }

    /**
     * Open a note from a share link that arrived as an Intent.
     *
     * The share action copies an `xhstp://note/<id>` token, and the manifest also
     * declares that scheme, so tapping a link in a browser (or any other app) lands
     * here instead of on a dead end. `launchMode` is standard, so a cold start goes
     * through onCreate and an already-running app through onNewIntent — both paths
     * funnel into [pendingNote], which the nav host consumes once.
     */
    private fun consumeDeepLink(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == DeepLink.SCHEME && data.host == DeepLink.HOST) {
            data.lastPathSegment?.toLongOrNull()?.let { pendingNote.value = it }
        }
    }

    /**
     * Volume keys page the full-screen image viewer while it is open.
     *
     * Handled here rather than in the composable because volume keys are
     * delivered to the Activity before any composable sees them. When no viewer
     * is registered the event is passed on, so the keys keep their normal meaning
     * everywhere else — which is exactly the "only in full screen" requirement.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
            // Volume-DOWN goes FORWARD, volume-up goes back: "down" reads as
            // advancing through the gallery, the same way scrolling a feed does.
            // (This was the other way round and felt backwards in use.)
        val step = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> -1
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            else -> 0
        }
        if (step != 0 && com.thirdparty.xhs.ui.components.ImageViewerKeys.handle(step)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    // ---- 画中画（小窗） ----------------------------------------------------

    /** 小窗控制栏的广播接收者；必须持有引用，否则会被回收掉而收不到按钮点击。 */
    private var pipReceiver: android.content.BroadcastReceiver? = null

    /** 退出小窗后的"到底是展开还是关闭"判定任务，展开（onResume）时取消 */
    private var pipExitCheck: kotlinx.coroutines.Job? = null

    /** 进小窗的时刻：`onStop` 里用它跳过"刚进小窗那一下的瞬停" */
    private var pipEnteredAtMs = 0L

    /** 收到过"退出小窗"回调（还没判定展开/关闭）；`onResume` 用它确认是展开 */
    @Volatile
    private var pipExitPending = false

    /** 当前导航目的地的路由（展开时用来判断"是不是已经在这个作品的详情页"） */
    @Volatile
    private var currentRoute: String? = null

    /** 判断 onStop 是"锁屏/息屏"还是"小窗真的没了" */
    private val powerManager by lazy {
        getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    }
    private val keyguardManager by lazy {
        getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
    }

    override fun onStart() {
        super.onStart()
        pipReceiver = com.thirdparty.xhs.ui.components.PipController.registerReceiver(
            activity = this,
            onUpdateParams = { refreshPipParams() }
        )
    }

    override fun onStop() {
        pipReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        pipReceiver = null
        // 小窗被「关闭」时系统**不保证**销毁 Activity（实测只走 onStop），于是那个看不见的
        // ExoPlayer 会继续出声（用户反馈过两次）。这里用**系统的** `isInPictureInPictureMode`
        // 判断，不再看我们自己维护的 `inPip`：关闭小窗时那个回调有可能压根不来，`inPip` 会一直
        // 停在 true，判据就永远不成立（这正是上一版"修了但没生效"的原因）。
        val pip = com.thirdparty.xhs.ui.components.PipController
        val justEntered =
            android.os.SystemClock.elapsedRealtime() - pipEnteredAtMs < PIP_ENTRY_GRACE_MS
        // **锁屏不是关小窗**：息屏/锁屏时 Activity 同样会 stop，但小窗窗口是活着的；把它当成
        // "小窗没了"就会把会话收掉，解锁后那个窗口只剩下（重新组合出来的）详情页 UI —— 用户看到
        // 的"小窗里是视频外面套着详情页"就是这个（实测日志：`onStop pip=true session=true`）。
        val screenOff = !powerManager.isInteractive || keyguardManager.isKeyguardLocked
        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
            android.util.Log.i(
                "XhsPip",
                "onStop pip=${isInPictureInPictureMode} session=${pip.hasSession()} " +
                    "justEntered=$justEntered exitPending=$pipExitPending " +
                    "exitCheck=${pipExitCheck?.isActive} screenOff=$screenOff"
            )
        }
        // 「有会话 + 走到 onStop + 屏幕是亮的」= 小窗已经没了：小窗里的 Activity 是**可见**的、
        // 不会 stop（锁屏那一类例外已由 screenOff 排除）。
        //
        // 不再用 `isInPictureInPictureMode` 作判据：关闭小窗时这个值可能还停在 true，
        // 于是旧判据永远不成立、播放器永远不释放 —— 这正是用户两次反馈"关闭后还在后台放"的原因。
        //
        // 三种情况：
        //   ① 配置变更 / 刚进小窗的过渡期 → 不动；
        //   ② **已经收到退出回调又走到 onStop** → 几乎肯定是被关掉了，但为了不和"展开过程中
        //      系统先给一次 onStop"打架，这里只是把判定窗口缩短（onResume 一到就取消）；
        //   ③ 其余（亮屏、没有退出回调，直接 stop）= 小窗没了 → 立刻收尾。
        //
        // 锁屏/息屏单独处理：**停播但保留会话**（用户反馈锁屏后还在放；窗口解锁后还要继续用，
        // 所以不能 release）。解锁后不自动续播 —— 锁屏本来就是要让它停下来。
        if (screenOff && pip.hasSession()) {
            pip.pauseForScreenOff()
        } else if (!isChangingConfigurations && !justEntered && pip.hasSession()) {
            if (pipExitPending) {
                schedulePipExitCheck(PIP_STOP_CONFIRM_MS)
            } else if (pipExitCheck?.isActive != true) {
                // 顺带把"待打开的作品"清掉：小窗是被关掉的，不该在下次回到前台时把人拽进详情页
                pip.pendingDetailId.value = null
                pip.closeAndRelease()
            }
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 回来时如果系统说"还在小窗里"、而且我们手里确实有会话，就把导航内容重新藏起来：
        // 锁屏/内存回收可能导致 Activity 被重建，`inPip` 这个内存标记会丢，于是小窗窗口会显示
        // 整页详情 UI（用户反馈"小窗里是视频外面套着详情页"）。这条是幂等的兜底。
        if (isInPictureInPictureMode &&
            com.thirdparty.xhs.ui.components.PipController.hasSession()
        ) {
            com.thirdparty.xhs.ui.components.PipController.inPip.value = true
        }
        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
            android.util.Log.i(
                "XhsPip",
                "onResume pending=$pipExitPending session=" +
                    com.thirdparty.xhs.ui.components.PipController.hasSession()
            )
        }
        // 回到前台 = 展开（不是关闭）：先把播放器交回详情页，再取消收尾任务
        if (pipExitPending) returnFromPipToDetail()
        pipExitCheck?.cancel()
        pipExitCheck = null
    }

    /**
     * 刷新小窗的窗口参数（画面比例 + 后退/播放暂停/前进）。
     *
     * 播放状态一变（小窗里点了播放/暂停），图标要跟着换，所以注册的接收者会回调到这里。
     */
    private fun refreshPipParams() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (!isInPictureInPictureMode) return
        val params = com.thirdparty.xhs.ui.components.PipController.buildParams(
            this,
            com.thirdparty.xhs.ui.components.PipController.session.value?.player
        ) ?: return
        runCatching { setPictureInPictureParams(params) }
    }

    /**
     * 小窗状态切换。
     *
     * 进入小窗：详情页已经退出（点「小窗播放」时就退了），画面由 Compose 侧的小窗宿主
     * 接手（见 setContent 里的 VideoSurface）。
     *
     * 离开小窗：用户按了系统自带的展开按钮 → 把播放器交回给详情页并跳回那个作品。
     * 「关闭小窗」不会走这里，而是直接把 Activity 销毁掉（见 [onDestroy]）。
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.thirdparty.xhs.ui.components.PipController.inPip.value = isInPictureInPictureMode
        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
            android.util.Log.i("XhsPip", "pipModeChanged=$isInPictureInPictureMode")
        }
        if (isInPictureInPictureMode) {
            pipEnteredAtMs = android.os.SystemClock.elapsedRealtime()
            // 进小窗时把比例刷一遍：视频尺寸可能是在进小窗之后才探到的
            refreshPipParams()
        } else {
            // 退出小窗的**同一个回调**既可能是"展开"、也可能是"关闭"，这里不能立刻下结论：
            //   展开 → 同一 Activity 回到前台，会走 onResume；
            //   关闭 → 不回来（随后 onStop/onDestroy，或者什么都不来）。
            // 所以先挂上"待定"标记 + 宽限任务，等 onResume 或超时再决定。
            //
            // 注意**不在这里**就把 `inPip` 置 false / 排 pendingDetailId：那会让导航内容提前
            // 重新组合，而播放器还没交回去 —— 详情页会新建一个播放器，小窗那个就变成没人管的
            // 后台音频。交回顺序必须是：先 handBack + givePlayer，再让导航内容回来。
            pipExitPending = true
            schedulePipExitCheck(PIP_EXIT_GRACE_MS)
        }
    }

    /**
     * 退出小窗后的"展开还是关闭"判定：等一会儿，看 Activity 回到前台没有。
     *
     * 窗口不能太短：实测（模拟器 API 34）从 `onPictureInPictureModeChanged(false)` 到
     * `onResume` 要 **1.15 秒**；500ms 的窗口会把展开误判成关闭 —— 播放器被释放，详情页只剩
     * 重建（用户反馈"按全屏回到详情页，详情页被重新加载"）。
     */
    private fun schedulePipExitCheck(delayMs: Long) {
        pipExitCheck?.cancel()
        pipExitCheck = lifecycleScope.launch {
            kotlinx.coroutines.delay(delayMs)
            val pip = com.thirdparty.xhs.ui.components.PipController
            val resumed = lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED
            )
            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "XhsPip",
                    "宽限任务到期(${delayMs}ms) resumed=$resumed session=${pip.hasSession()}"
                )
            }
            if (resumed) {
                // 展开：回到详情页（同一导航记录，状态不丢）
                returnFromPipToDetail()
                return@launch
            }
            if (pip.hasSession()) {
                if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                    android.util.Log.i("XhsPip", "判定为关闭 → closeAndRelease")
                }
                pip.pendingDetailId.value = null
                pip.closeAndRelease()
            }
            pipExitPending = false
        }
    }

    /**
     * 从「展开」回到详情页。
     *
     * 必须在导航内容重新组合**之前**完成播放器交接，否则详情页会自建播放器、小窗那个就成了
     * 没人管的背景音。所以顺序是：`handBackForDetail()` → `PlaybackHandoff.givePlayer()` →
     * 最后才把 `inPip` 置 false（导航内容这时才回来，并从 handoff 认领同一个播放器）。
     *
     * 只有在"当前不在这个作品的详情页"时才需要导航 —— 进小窗时不再退出详情页，所以正常路径下
     * 那条记录还在（重新 navigate 会新建一条记录 = 整页重新加载，用户反馈的正是这个）。
     */
    private fun returnFromPipToDetail() {
        val pip = com.thirdparty.xhs.ui.components.PipController
        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
            android.util.Log.i("XhsPip", "returnFromPipToDetail 入口 session=${pip.hasSession()}")
        }
        pipExitPending = false
        pipExitCheck?.cancel()
        pipExitCheck = null
        val s = pip.handBackForDetail() ?: run {
            pip.inPip.value = false
            return
        }
        com.thirdparty.xhs.ui.components.PlaybackHandoff.givePlayer(s.noteId, s.player)
        val route = currentRoute
        if (route != com.thirdparty.xhs.navigation.Routes.detail(s.noteId)) {
            pip.pendingDetailId.value = s.noteId
        }
        pip.inPip.value = false
        if (com.thirdparty.xhs.BuildConfig.DEBUG) {
            android.util.Log.i("XhsPip", "returnFromPipToDetail route=$route note=${s.noteId}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 关掉小窗 = 没有界面了：播放器必须销毁，否则会留下一个看不见的 ExoPlayer
        // 继续放声音。展开回详情页时 session 已经交回去了（pendingDetailId 被消费），
        // 所以这里不会误伤。
        if (!isChangingConfigurations &&
            com.thirdparty.xhs.ui.components.PipController.hasSession()
        ) {
            com.thirdparty.xhs.ui.components.PipController.closeAndRelease()
        }
    }

    /** Whether the user turned on the app lock. */
    private fun biometricLockEnabled(): Boolean = App.INSTANCE.repository.biometricLock

    /**
     * Ask for the device credential and report success.
     *
     * Falls through to success when the device cannot authenticate at all: a user
     * who enabled the lock on a device that later lost its enrolled fingerprint
     * must not be permanently locked out of their own app.
     */
    private fun requestUnlock(onUnlocked: () -> Unit) {
        if (!com.thirdparty.xhs.ui.components.biometricAvailable(this)) {
            onUnlocked()
            return
        }
        com.thirdparty.xhs.ui.components.promptBiometric(
            activity = this,
            onSuccess = { onUnlocked() },
            onFail = { /* stays locked; the cover offers a retry */ }
        )
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeDeepLink(intent)
        enableEdgeToEdge()
        // Paint the launch window with the colour the user will actually land on,
        // so an in-app 深色/浅色 override does not flash the system default.
        // Black regardless of theme: the app opens on the black 推荐 feed, so the
        // window colour must be black before Compose draws or every cold start
        // flashes the theme colour first. See XhsWindowBackground.
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(XhsWindowBackground)
        )
        setContent {
            val themeMode by App.INSTANCE.themeState.collectAsState()
            // The window stays black as the theme changes — it is only ever visible
            // behind the feed, so it must not follow the theme (see
            // XhsWindowBackground).
            XhsTheme(mode = themeMode) {
                Surface(Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    // App lock. The normal content stays composed underneath so nav
                    // position and every screen's state survive an unlock; only the
                    // cover is swapped out. Replacing the content would bounce the
                    // user back to 推荐 every time the app was reopened.
                    var locked by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(biometricLockEnabled())
                    }
                    val owner = LocalLifecycleOwner.current
                    androidx.compose.runtime.DisposableEffect(owner) {
                        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE &&
                                !App.INSTANCE.systemPickerActive &&
                                biometricLockEnabled()
                            ) {
                                locked = true
                            }
                        }
                        owner.lifecycle.addObserver(observer)
                        onDispose { owner.lifecycle.removeObserver(observer) }
                    }
                    androidx.compose.runtime.LaunchedEffect(locked) {
                        if (locked) requestUnlock { locked = false }
                    }
                    // the toggle takes effect at once: ON locks now, OFF unlocks
                    val lockEpoch by App.INSTANCE.lockEpoch.collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(lockEpoch) {
                        if (lockEpoch > 0) locked = biometricLockEnabled()
                    }
                    // 小窗里只画视频：系统把整个 Activity 缩成小窗，其余 chrome 一律不要。
                    // 注意这里是**跳过**导航内容的组合（不是盖在上面）——所以详情页的
                    // ViewModel 留在返回栈里、组合被销毁，展开时重建但状态还在（不再重新加载）。
                    val pipActive by
                        com.thirdparty.xhs.ui.components.PipController.inPip
                            .collectAsStateWithLifecycle()
                    if (!pipActive) {
                        AppNavHost(navController, deepLinkNoteId = pendingNote.value) {
                            pendingNote.value = null
                        }
                    }
                    // 记住当前路由：展开小窗时要判断"是不是已经在这个作品的详情页"，
                    // 是就不导航（导航会新建记录 → 整页重新加载）。
                    // 注意 `destination.route` 是**模式串**（`detail/{noteId}`），必须把实参拼回去
                    // 才能和 `Routes.detail(id)` 比 —— 只比模式串会永远不相等。
                    androidx.compose.runtime.LaunchedEffect(navController) {
                        navController.currentBackStackEntryFlow.collect { entry ->
                            val pattern = entry.destination.route
                            val arg = entry.arguments?.getString("noteId")?.toLongOrNull()
                            currentRoute = if (pattern == com.thirdparty.xhs.navigation.Routes.DETAIL &&
                                arg != null
                            ) {
                                com.thirdparty.xhs.navigation.Routes.detail(arg)
                            } else {
                                pattern
                            }
                        }
                    }
                    if (locked) {
                        // swallow the back gesture: the cover must not be dismissible
                        androidx.activity.compose.BackHandler { }
                        com.thirdparty.xhs.ui.components.BiometricLockCover(
                            onUnlock = { requestUnlock { locked = false } }
                        )
                    }
                    // 复制口令回流：回到应用时发现剪贴板里有分享链接就询问是否跳转
                    clipboardNote.value?.let { noteId ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { clipboardNote.value = null },
                            title = { Text("检测到分享内容") },
                            text = { Text("剪贴板里有一个作品链接，是否打开这个作品？") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    prefs.edit().putString(KEY_LAST_CLIP, noteId.toString()).apply()
                                    clipboardNote.value = null
                                    pendingNote.value = noteId
                                }) { Text("打开") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    // remember the refusal too, else it re-asks on the
                                    // next resume with the same clipboard
                                    prefs.edit().putString(KEY_LAST_CLIP, noteId.toString()).apply()
                                    clipboardNote.value = null
                                }) { Text("取消") }
                            }
                        )
                    }
                    // 启动时的自动检查更新：只有真的查到更新的版本才弹（见
                    // App.checkUpdateOnLaunch）。锁着的时候不弹，否则对话框会浮在
                    // 解锁页上面。
                    val pendingUpdate by App.INSTANCE.pendingUpdate.collectAsStateWithLifecycle()
                    if (!locked) {
                        pendingUpdate?.let { info ->
                            UpdateAvailableDialog(
                                info = info,
                                onOpenPage = {
                                    App.INSTANCE.dismissUpdate()
                                    openUrl(this@MainActivity, info.pageUrl)
                                },
                                onLater = { App.INSTANCE.dismissUpdate() },
                                onSkipVersion = { App.INSTANCE.ignoreUpdateVersion(info.version) }
                            )
                        }
                    }

                    // ---- 画中画（小窗）------------------------------------------
                    // 展开小窗：把播放器交回详情页并跳回去。用 PlaybackHandoff 交接而不是
                    // 只记位置：详情页会直接认领同一个 ExoPlayer，于是画面接着播、不重头开始。
                    val pipPendingDetail by
                        com.thirdparty.xhs.ui.components.PipController.pendingDetailId
                            .collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(pipPendingDetail) {
                        val id = pipPendingDetail ?: return@LaunchedEffect
                        com.thirdparty.xhs.ui.components.PipController.handBackForDetail()
                            ?.let { s ->
                                com.thirdparty.xhs.ui.components.PlaybackHandoff.givePlayer(
                                    s.noteId, s.player
                                )
                            }
                        com.thirdparty.xhs.ui.components.PipController.pendingDetailId.value = null
                        navController.navigate(com.thirdparty.xhs.navigation.Routes.detail(id))
                    }
                    // 小窗里只画视频：系统把整个 Activity 缩成小窗，其余 chrome 一律不要。
                    val pipSession by
                        com.thirdparty.xhs.ui.components.PipController.session
                            .collectAsStateWithLifecycle()
                    // 播放状态变了（播放/暂停/播完）就重设一次窗口参数：
                    // 小窗按钮的图标必须跟着真实状态走，播完要变成「重播」
                    val pipParamsVersion by
                        com.thirdparty.xhs.ui.components.PipController.paramsVersion
                            .collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(pipParamsVersion) {
                        refreshPipParams()
                    }
                    // `pipActive` 已在上面（NavHost 之前）收集，这里复用同一个值
                    if (pipActive) {
                        pipSession?.let { s ->
                            // 小窗里按**视频自己的比例**画（信箱式留边），不要拉满整窗：
                            // 窗口比例是系统按 PiP 参数给的，两者不一定相等（尤其横屏视频
                            // 切小窗时窗口可能仍是竖的），拉满就是用户看到的"画面被拉伸"。
                            var pipAspect by androidx.compose.runtime.remember(s.player) {
                                androidx.compose.runtime.mutableFloatStateOf(
                                    com.thirdparty.xhs.ui.components.PipController
                                        .videoAspectOf(s.player.videoSize)
                                )
                            }
                            androidx.compose.runtime.DisposableEffect(s.player) {
                                val l = object : androidx.media3.common.Player.Listener {
                                    override fun onVideoSizeChanged(
                                        videoSize: androidx.media3.common.VideoSize
                                    ) {
                                        pipAspect = com.thirdparty.xhs.ui.components.PipController
                                            .videoAspectOf(videoSize)
                                    }
                                }
                                s.player.addListener(l)
                                pipAspect = com.thirdparty.xhs.ui.components.PipController
                                    .videoAspectOf(s.player.videoSize)
                                onDispose { s.player.removeListener(l) }
                            }
                            com.thirdparty.xhs.ui.components.VideoSurface(
                                player = s.player,
                                videoAspect = pipAspect.takeIf { it > 0f },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** last share link already offered, so the same clipboard does not re-prompt */
        const val KEY_LAST_CLIP = "last_clipboard_note"

        /** 退出小窗后等多久判断"展开还是关闭"（实测展开到 onResume 要 1.15s） */
        const val PIP_EXIT_GRACE_MS = 2_500L

        /** 已经收到退出回调、又走到 onStop 时的确认窗口（几乎肯定是被关掉了） */
        const val PIP_STOP_CONFIRM_MS = 300L

        /** 进小窗后的这段时间内，`onStop` 不当作"小窗被关掉"（个别设备会瞬停一下） */
        const val PIP_ENTRY_GRACE_MS = 2_000L
    }
}