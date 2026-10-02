package com.thirdparty.xhs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.thirdparty.xhs.navigation.AppNavHost
import com.thirdparty.xhs.ui.theme.XhsTheme
import com.thirdparty.xhs.ui.theme.XhsWindowColors
import com.thirdparty.xhs.ui.theme.isDark

/**
 * Single-Activity Compose app. Theme follows the persisted mode (default system).
 *
 * Also the entry point for shared notes: the share sheet emits a
 * [DeepLink.SCHEME] link, and opening it lands here (see [shareNote]).
 */
class MainActivity : ComponentActivity() {

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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeDeepLink(intent)
        enableEdgeToEdge()
        // Paint the launch window with the colour the user will actually land on,
        // so an in-app 深色/浅色 override does not flash the system default.
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = App.INSTANCE.themeState.value.isDark(systemDark)
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (dark) XhsWindowColors.DARK else XhsWindowColors.LIGHT
            )
        )
        setContent {
            val themeMode by App.INSTANCE.themeState.collectAsState()
            // keep the window background in step when the user switches theme
            val dark = themeMode.isDark(androidx.compose.foundation.isSystemInDarkTheme())
            androidx.compose.runtime.SideEffect {
                window.setBackgroundDrawable(
                    android.graphics.drawable.ColorDrawable(
                        if (dark) XhsWindowColors.DARK else XhsWindowColors.LIGHT
                    )
                )
            }
            XhsTheme(mode = themeMode) {
                Surface(Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavHost(navController, deepLinkNoteId = pendingNote.value) {
                        pendingNote.value = null
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
                }
            }
        }
    }

    private companion object {
        /** last share link already offered, so the same clipboard does not re-prompt */
        const val KEY_LAST_CLIP = "last_clipboard_note"
    }
}