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
import androidx.compose.ui.text.font.FontWeight

/** Theme preference: follow system / light / dark. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark")
}

/**
 * Tonal palettes, rebuilt for M3 Expressive.
 *
 * The previous scheme had `primaryContainer` and `secondaryContainer` set to the
 * **same** `#FFD9E1`, so any two UIs styled with those roles were indistinguishable
 * — it is why the work badges could not be told apart. Every role below is now a
 * distinct colour, and container/on-container pairs keep a tone delta of ~40 so
 * the label stays legible in both light and dark.
 *
 * Hues are deliberately spread: brand rose for primary, a desaturated mauve for
 * secondary, and a **gold** tertiary. Expressive design calls for the accent role
 * on badges and hero highlights, and gold both reads as "premium" and separates
 * cleanly from the rose family.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFFBD1E59),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9E1),
    onPrimaryContainer = Color(0xFF3E001D),
    inversePrimary = Color(0xFFFFB1C5),
    secondary = Color(0xFF6B5A60),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3DEE4),
    onSecondaryContainer = Color(0xFF25181C),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9E),
    onTertiaryContainer = Color(0xFF261A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F8),
    onBackground = Color(0xFF22191C),
    surface = Color(0xFFFFF8F8),
    onSurface = Color(0xFF22191C),
    surfaceVariant = Color(0xFFF2DDE3),
    onSurfaceVariant = Color(0xFF514347),
    outline = Color(0xFF837377),
    outlineVariant = Color(0xFFD6C2C7),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF382E31),
    inverseOnSurface = Color(0xFFFFECEF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C5),
    onPrimary = Color(0xFF65002A),
    primaryContainer = Color(0xFF8E0040),
    onPrimaryContainer = Color(0xFFFFD9E1),
    inversePrimary = Color(0xFFBD1E59),
    secondary = Color(0xFFD7C1C6),
    onSecondary = Color(0xFF3B2C31),
    secondaryContainer = Color(0xFF534247),
    onSecondaryContainer = Color(0xFFF3DEE4),
    tertiary = Color(0xFFF0C048),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4500),
    onTertiaryContainer = Color(0xFFFFDF9E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1114),
    onBackground = Color(0xFFF0DEE2),
    surface = Color(0xFF1A1114),
    onSurface = Color(0xFFF0DEE2),
    surfaceVariant = Color(0xFF514347),
    onSurfaceVariant = Color(0xFFD6C2C7),
    outline = Color(0xFF9F8C91),
    outlineVariant = Color(0xFF514347),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF0DEE2),
    inverseOnSurface = Color(0xFF382E31)
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

    // M3 Expressive "Personal": derive the whole scheme from the user's wallpaper
    // where the platform supports it, and fall back to the brand scheme elsewhere.
    // The brand still shows through on API < 31 and via the app icon/window colours.
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
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

/**
 * Expressive type scale.
 *
 * M3 Expressive calls for **heavier weights and tighter tracking** on the
 * emphasis roles, which is what gives an Expressive screen its bolder voice.
 * This is done once at the theme level, not per call site: an override on a
 * single `Text` drifts the moment the scale changes, whereas a themed scale
 * keeps every screen consistent.
 *
 * Only the leading roles are adjusted — body copy stays at the default weight so
 * long passages remain comfortable to read.
 */
val XhsTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

/**
 * Launch-window background.
 *
 * **Black, not the theme background.** The app opens on the 推荐 feed, which is a
 * full-screen video surface that paints black immediately. Painting the window with
 * the light theme colour (or the purple-tinted dark one) meant every cold start
 * showed that colour for a frame or two before the feed covered it — the
 * "每次进入软件都得闪那么几下".
 *
 * The window is only visible before Compose draws its first frame and behind
 * navigation transitions, and the screen coming up on cold start is always the
 * feed, so black is the colour that cannot flash there. Screens with a light
 * background are reached through a transition, which covers the change.
 */
const val XhsWindowBackground = 0xFF000000.toInt()

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