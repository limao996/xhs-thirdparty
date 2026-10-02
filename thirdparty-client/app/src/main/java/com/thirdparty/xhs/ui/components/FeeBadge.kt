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
 * Badge shown on a work's thumbnail: 图文 / 粉丝圈 / VIP / 免费.
 *
 * ## Which fields can be trusted WHERE
 *
 * The feeds and the local lists do NOT receive the same payload, and getting this
 * wrong labelled every feed item 图文. Measured against the live backend
 * (tools/probe_label_fields.py):
 *
 * | field            | list (`v2/home/discover-note`) | detail (`v2/note/view`) |
 * |------------------|-------------------------------|-------------------------|
 * | `note_type`      | present, 1=图文 2=视频         | present                 |
 * | `note_cin`       | present (0/2/3/4/8/10/12/18/28/38) | present            |
 * | `note_media_url` | **absent**                     | present                 |
 * | `group_id`       | **absent**                     | present                 |
 *
 * 收藏 / 最近浏览 store the full detail JSON, which is exactly why they looked
 * right while the feeds did not.
 *
 * So the rule keys off `note_type` — the one media-type signal a list carries —
 * and only reports 粉丝圈 where `group_id` is actually available. In a feed a
 * fan-group post therefore falls back to VIP/免费 rather than claiming a group it
 * cannot see; the detail page states it exactly. `note_cin` is a coin price, so
 * >0 is the paid signal.
 */
enum class FeeKind { IMAGE, FREE, PAID, FAN_GROUP }

val NoteItem.feeKind: FeeKind
    get() = when (noteType) {
        // 1 = gallery / 图文. Authoritative, and present in list payloads too.
        1 -> FeeKind.IMAGE
        // 2 = video. 粉丝圈 needs group_id, which only the detail carries.
        2 -> feeForVideo()
        // note_type absent: fall back to whatever else the payload can tell us.
        else -> if (!isVideo) FeeKind.IMAGE else feeForVideo()
    }

private fun NoteItem.feeForVideo(): FeeKind = when {
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
