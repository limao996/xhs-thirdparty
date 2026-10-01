package com.thirdparty.xhs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.thirdparty.xhs.navigation.AppNavHost
import com.thirdparty.xhs.ui.theme.XhsTheme
import com.thirdparty.xhs.ui.theme.XhsWindowColors
import com.thirdparty.xhs.ui.theme.isDark

/** Single-Activity Compose app. Theme follows the persisted mode (default system). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    AppNavHost(navController)
                }
            }
        }
    }
}