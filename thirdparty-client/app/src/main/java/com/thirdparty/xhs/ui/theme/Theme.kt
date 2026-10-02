package com.thirdparty.xhs.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.ShapeDefaults

/** Theme preference: follow system / light / dark. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark")
}

// MD3 tonal palette (brand rose-pink family)
private val LightColors = lightColorScheme(
    primary = Color(0xFFBD1E59),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E1),
    onPrimaryContainer = Color(0xFF3E001D),
    secondary = Color(0xFF74565F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E1),
    onSecondaryContainer = Color(0xFF2B151C),
    tertiary = Color(0xFF7C5635),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC3),
    onTertiaryContainer = Color(0xFF2E1500),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF1DDE1),
    onSurfaceVariant = Color(0xFF524347),
    outline = Color(0xFF847378)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C3),
    onPrimary = Color(0xFF65002F),
    primaryContainer = Color(0xFF910247),
    onPrimaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFFE2BDC6),
    onSecondary = Color(0xFF422932),
    secondaryContainer = Color(0xFF5B3F48),
    onSecondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFFF0BD95),
    onTertiary = Color(0xFF48290E),
    tertiaryContainer = Color(0xFF623E22),
    onTertiaryContainer = Color(0xFFFFDCC3),
    background = Color(0xFF1B1114),
    onBackground = Color(0xFFEDE0E0),
    surface = Color(0xFF1B1114),
    onSurface = Color(0xFFEDE0E0),
    surfaceVariant = Color(0xFF524347),
    onSurfaceVariant = Color(0xFFD5C2C6),
    outline = Color(0xFF9D8C90)
)

/**
 * App theme, built on **M3 Expressive**.
 *
 * [MaterialExpressiveTheme] is the expressive entry point (Material 3, 2025):
 * compared with [MaterialTheme] it supplies the expressive shape scale and, via
 * [MotionScheme.expressive], the springy motion specs that every Material
 * component then animates with — the motion half is what actually makes an
 * "Expressive" UI feel different, and it cannot be opted into per-component.
 *
 * Everything else (colour roles, type scale) stays as before; the app's own
 * composables read `MaterialTheme.*`, so they inherit the expressive behaviour
 * without a call-site change.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun XhsTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = mode.isDark(systemDark)
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = XhsTypography,
        shapes = XhsShapes,
        content = content
    )
}

val XhsTypography = Typography()

/**
 * Launch-window backgrounds. Must stay in sync with [LightColors.background] and
 * [DarkColors.background]; the Activity paints the window with these before
 * Compose draws, so a mismatch shows up as a colour flash on cold start.
 */
object XhsWindowColors {
    const val LIGHT = 0xFFFFF8F8.toInt()
    const val DARK = 0xFF1B1114.toInt()
}

/** Resolve a [ThemeMode] against the current system setting. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Material 3 shape scale, at the official defaults.
 *
 * `large` used to be 20dp and `extraLarge` was left unset; MD3 specifies
 * 4 / 8 / 12 / 16 / 28. Deviating from the scale is exactly what makes an app
 * look "almost Material" — and it meant two different shapes both claimed to be
 * "large" (see [Corners], which now reads this scheme instead of a hand-written
 * copy of it).
 */
val XhsShapes = Shapes(
    extraSmall = ShapeDefaults.ExtraSmall,
    small = ShapeDefaults.Small,
    medium = ShapeDefaults.Medium,
    large = ShapeDefaults.Large,
    extraLarge = ShapeDefaults.ExtraLarge
)