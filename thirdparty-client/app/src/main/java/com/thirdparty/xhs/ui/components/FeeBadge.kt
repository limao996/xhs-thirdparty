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
 * Fee state for a work: 免费 / 付费.
 *
 * Verified against the live backend: the only fee signal it exposes is
 * `note_cin` (0 = free, > 0 = paid). An earlier version also had a 粉丝圈 kind
 * driven by guessed keys (`fan_group_gate`, `is_fan_group`,
 * `user_fan_group_only`) — none of which exist in any response, so that badge
 * could never appear. `group_id` is present but always 0 in every note payload,
 * so it is not a gate either. Removed rather than left as dead, misleading code.
 */
enum class FeeKind { FREE, PAID }

val NoteItem.feeKind: FeeKind
    get() = if (noteCin > 0) FeeKind.PAID else FeeKind.FREE

/**
 * MD3-toned fee badge. Colors come from the active ColorScheme so both light
 * and dark themes stay legible:
 *  - 付费    -> tertiaryContainer / onTertiaryContainer
 *  - 免费    -> surfaceVariant / onSurfaceVariant
 */
@Composable
fun FeeBadge(item: NoteItem, compact: Boolean = false, modifier: Modifier = Modifier) {
    val kind = item.feeKind
    val container = when (kind) {
        FeeKind.PAID -> MaterialTheme.colorScheme.tertiaryContainer
        FeeKind.FREE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (kind) {
        FeeKind.PAID -> MaterialTheme.colorScheme.onTertiaryContainer
        FeeKind.FREE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = Corners.extraSmall, color = container, contentColor = content, modifier = modifier) {
        Text(
            when (kind) {
                FeeKind.PAID -> "付费"
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