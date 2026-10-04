package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Scrim
import com.thirdparty.xhs.ui.theme.Spacing
import androidx.compose.foundation.clickable

/**
 * "Reset zoom" pill, shown at the bottom of a fullscreen viewer while zoomed.
 *
 * Zooming is easy to trigger by accident with two fingers and, once an image has
 * been panned, pinching back out is not discoverable — the viewer just looks
 * broken. This gives one obvious way back, and it is only present while zoomed so
 * it never competes with the normal chrome.
 *
 * Shared by the fullscreen video player and the image viewer so both behave the
 * same way.
 */
@Composable
fun ResetZoomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = Corners.full,
        color = Scrim.chrome,
        contentColor = Scrim.onMedia,
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ZoomOutMap,
                contentDescription = null,
                tint = Scrim.onMedia,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text("恢复", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}
