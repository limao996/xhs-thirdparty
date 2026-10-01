package com.thirdparty.xhs.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MD3-aligned design tokens.
 *
 * Spacing follows the 4dp baseline grid; corner radii map to Material3 shape
 * tokens; scrims are only used over media (video/photo), per MD3 guidance.
 */
object Spacing {
    val none: Dp = 0.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Corner radii bound to Material3 shape tokens (avoids ad-hoc RoundedCornerShape). */
object Corners {
    val extraSmall: RoundedCornerShape = RoundedCornerShape(4.dp)
    val small: RoundedCornerShape = RoundedCornerShape(8.dp)
    val medium: RoundedCornerShape = RoundedCornerShape(12.dp)
    val large: RoundedCornerShape = RoundedCornerShape(16.dp)
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