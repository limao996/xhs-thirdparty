package com.thirdparty.xhs.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars

/**
 * MD3-aligned design tokens.
 *
 * Spacing follows the 4dp baseline grid; corner radii map to Material3 shape
 * tokens; scrims are only used over media (video/photo), per MD3 guidance.
 */
/**
 * Bottom clearance for content that scrolls UNDER the floating NavigationBar.
 *
 * The bar is drawn as an overlay on top of the tab content (so the video can run
 * edge to edge behind it), which means a scrollable area that reaches the bottom
 * of the window has its last ~176dp permanently covered. Any scrollable content
 * hosted inside HomeScreen must reserve this much bottom space, otherwise the
 * final rows can never be brought into view — which reads as "it won't scroll".
 */
val BottomNavClearance: Dp = 96.dp

/**
 * [BottomNavClearance] plus the real system navigation-bar inset.
 *
 * The token alone is a fixed height, but MD3's NavigationBar adds the system
 * gesture-bar inset on top of its own height. Anything anchored with only the
 * token therefore ends up flush against the bar — which is what made the 发现
 * refresh FAB look glued to it. Read the live inset instead of guessing.
 */
@Composable
fun bottomNavClearance(): Dp =
    BottomNavClearance +
        androidx.compose.foundation.layout.WindowInsets.navigationBars
            .asPaddingValues().calculateBottomPadding()

object Spacing {
    val none: Dp = 0.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/**
 * Corner radii read straight from the active Material 3 shape scale.
 *
 * These used to be a second, hand-written copy of the scale, and the two had
 * already drifted (`large` was 16dp here and 20dp in [XhsShapes]). MD3 has one
 * shape scale; binding to it means a call site cannot silently disagree with the
 * theme, and retheming stays a one-line change.
 */
object Corners {
    val extraSmall: Shape
        @Composable @ReadOnlyComposable get() = MaterialTheme.shapes.extraSmall

    val small: Shape
        @Composable @ReadOnlyComposable get() = MaterialTheme.shapes.small

    val medium: Shape
        @Composable @ReadOnlyComposable get() = MaterialTheme.shapes.medium

    val large: Shape
        @Composable @ReadOnlyComposable get() = MaterialTheme.shapes.large

    val extraLarge: Shape
        @Composable @ReadOnlyComposable get() = MaterialTheme.shapes.extraLarge
}

/** Standard avatar sizes on the 4dp grid. */
object AvatarSize {
    val comment: Dp = 32.dp
    val list: Dp = 44.dp
    val profile: Dp = 56.dp
}

/** Cover thumbnails used across list rows. */
object Thumb {
    val width: Dp = 88.dp
    val height: Dp = 116.dp
}

/**
 * Scrims are only legitimate over media surfaces (video/photo) in MD3.
 * Everything else must use MaterialTheme.colorScheme tokens.
 */
object Scrim {
    /** bottom info bar over video */
    val strong: Color = Color(0xB3000000)
    /** translucent chrome over video */
    val chrome: Color = Color(0x66000000)
    /** header strip over video */
    val header: Color = Color(0x4D000000)
    /** on-media foreground */
    val onMedia: Color = Color(0xFFFFFFFF)
    /** on-media secondary foreground */
    val onMediaVariant: Color = Color(0xB3FFFFFF)
}

/** Semantic colors for media overlays (not part of ColorScheme). */
@Suppress("unused")
object XhsColors {
    @Composable @ReadOnlyComposable
    fun avatarBackground(): Color = MaterialTheme.colorScheme.surface

    @Composable @ReadOnlyComposable
    fun placeholder(): Color = MaterialTheme.colorScheme.surfaceVariant

    @Composable @ReadOnlyComposable
    fun placeholderError(): Color = MaterialTheme.colorScheme.surfaceContainerHighest
}