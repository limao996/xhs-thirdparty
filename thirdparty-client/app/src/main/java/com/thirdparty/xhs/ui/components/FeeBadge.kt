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
 * Fee state for a work: 免费 / 付费 / 粉丝圈.
 *
 * Verified against the live backend (and cross-checked against the original
 * app's own labels on the same author page, 4/4 correct):
 *  - `group_id > 0`            -> 粉丝圈   (published inside the author's fan group)
 *  - `group_id == 0, cin > 0`  -> 付费
 *  - `group_id == 0, cin == 0` -> 免费
 */
enum class FeeKind { FREE, PAID, FAN_GROUP }

val NoteItem.feeKind: FeeKind
    get() = when {
        groupId > 0 -> FeeKind.FAN_GROUP
        noteCin > 0 -> FeeKind.PAID
        else -> FeeKind.FREE
    }

/**
 * MD3-toned fee badge. Colors come from the active ColorScheme so both light
 * and dark themes stay legible:
 *  - 付费    -> tertiaryContainer / onTertiaryContainer
 *  - 粉丝圈  -> secondaryContainer / onSecondaryContainer
 *  - 免费    -> surfaceVariant / onSurfaceVariant
 */
@Composable
fun FeeBadge(item: NoteItem, compact: Boolean = false, modifier: Modifier = Modifier) {
    val kind = item.feeKind
    val container = when (kind) {
        FeeKind.PAID -> MaterialTheme.colorScheme.tertiaryContainer
        FeeKind.FAN_GROUP -> MaterialTheme.colorScheme.secondaryContainer
        FeeKind.FREE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (kind) {
        FeeKind.PAID -> MaterialTheme.colorScheme.onTertiaryContainer
        FeeKind.FAN_GROUP -> MaterialTheme.colorScheme.onSecondaryContainer
        FeeKind.FREE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = Corners.extraSmall, color = container, contentColor = content, modifier = modifier) {
        Text(
            when (kind) {
                FeeKind.PAID -> "付费"
                FeeKind.FAN_GROUP -> "粉丝圈"
                FeeKind.FREE -> "免费"
            },
            Modifier.padding(
                horizontal = if (compact) Spacing.xs + 1.dp else Spacing.s,
                vertical = 2.dp
            ),
            style = if (compact) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.labelMedium
        )
    }
}