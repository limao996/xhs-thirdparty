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

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        repository = XhsRepository(this)
        httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
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

    companion object {
        lateinit var INSTANCE: App
            private set
        val repo: XhsRepository get() = INSTANCE.repository
        val http: OkHttpClient get() = INSTANCE.httpClient
    }
}