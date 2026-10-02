package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.data.NoteItem
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * Badge shown on a work's thumbnail: 图文 / VIP / 粉丝圈 / 免费.
 *
 * 图文 is decided by the MEDIA TYPE, not by the fee — an image post is labelled
 * 图文 rather than priced, because for a gallery the fee state is not the useful
 * thing to show on a cover (the detail page states it). Video posts keep the fee
 * label.
 *
 * Fee state (verified against the live backend, cross-checked against the
 * original app's own labels on the same author page, 4/4 correct):
 *  - `group_id > 0`            -> 粉丝圈   (published inside the author's fan group)
 *  - `group_id == 0, cin > 0`  -> VIP      (was labelled 付费)
 *  - `group_id == 0, cin == 0` -> 免费
 */
enum class FeeKind { IMAGE, FREE, PAID, FAN_GROUP }

val NoteItem.feeKind: FeeKind
    get() = when {
        !isVideo -> FeeKind.IMAGE
        groupId > 0 -> FeeKind.FAN_GROUP
        noteCin > 0 -> FeeKind.PAID
        else -> FeeKind.FREE
    }

/**
 * MD3-toned badge. Colors come from the active ColorScheme so both light and dark
 * themes stay legible:
 *  - 图文    -> surfaceContainerHighest / onSurfaceVariant
 *  - VIP     -> tertiaryContainer / onTertiaryContainer
 *  - 粉丝圈  -> secondaryContainer / onSecondaryContainer
 *  - 免费    -> surfaceVariant / onSurfaceVariant
 */
@Composable
fun FeeBadge(item: NoteItem, compact: Boolean = false, modifier: Modifier = Modifier) {
    val kind = item.feeKind
    val container = when (kind) {
        FeeKind.IMAGE -> MaterialTheme.colorScheme.surfaceContainerHighest
        FeeKind.PAID -> MaterialTheme.colorScheme.tertiaryContainer
        FeeKind.FAN_GROUP -> MaterialTheme.colorScheme.secondaryContainer
        FeeKind.FREE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (kind) {
        FeeKind.IMAGE -> MaterialTheme.colorScheme.onSurfaceVariant
        FeeKind.PAID -> MaterialTheme.colorScheme.onTertiaryContainer
        FeeKind.FAN_GROUP -> MaterialTheme.colorScheme.onSecondaryContainer
        FeeKind.FREE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = Corners.extraSmall, color = container, contentColor = content, modifier = modifier) {
        Text(
            when (kind) {
                FeeKind.IMAGE -> "图文"
                FeeKind.PAID -> "VIP"
                FeeKind.FAN_GROUP -> "粉丝圈"
                FeeKind.FREE -> "免费"
            },
            Modifier.padding(
                horizontal = if (compact) Spacing.xs else Spacing.s,
                vertical = 2.dp
            ),
            style = if (compact) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.labelMedium
        )
    }
}
