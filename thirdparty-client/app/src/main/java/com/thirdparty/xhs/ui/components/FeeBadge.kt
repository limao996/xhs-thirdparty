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
    get() = when {
        // `note_type == 1` is the ONLY gallery type. Measured across the feed and
        // several author pages, then confirmed against v2/note/view
        // (tools/probe_note_type_all.py):
        //     type 1 -> image_list 6/6, media 0/6   = 图文
        //     type 2 -> media 6/6,   image_list 0/6 = video
        //     type 3 -> media 1/1                   = video
        //     type 4 -> media 6/6,   image_list 0/6 = video
        // The earlier `when(noteType) { 1 -> …; 2 -> …; else -> … }` sent 3 and 4
        // down the fallback branch, where a list payload (no media URL) made them
        // look like 图文. That is the author-page bug. Only 1 is an image.
        noteType == 1 -> FeeKind.IMAGE
        // 0 / absent: the payload did not say, so fall back to the media URL.
        noteType == 0 -> if (isVideo) feeForVideo() else FeeKind.IMAGE
        // 2, 3, 4 and anything else the backend adds later are video.
        else -> feeForVideo()
    }

private fun NoteItem.feeForVideo(): FeeKind = when {
    groupId > 0 -> FeeKind.FAN_GROUP
    noteCin > 0 -> FeeKind.PAID
    else -> FeeKind.FREE
}

/**
 * MD3-toned badge.
 *
 * Four states have to be tellable apart at a glance on top of a photo, so the
 * roles are chosen for maximum separation rather than for a tidy gradient — and
 * M3 Expressive names the accent role (`tertiary`) as the one for badges:
 *
 *  - VIP      -> **solid tertiary** (gold). The paid tier is the one that changes
 *               what the user can do, so it gets full-strength colour, not a tint.
 *  - 粉丝圈    -> primaryContainer (brand rose tint) — the brand's own family.
 *  - 免费     -> secondaryContainer (desaturated mauve) — present but recessive.
 *  - 图文     -> surfaceContainerHighest (neutral). It describes the media type,
 *               not the price, so it must not compete with the fee labels.
 *
 * Each pair comes from the same tone family, so `on*` stays legible in light and
 * dark. (The old scheme had primaryContainer == secondaryContainer, which is why
 * these were previously indistinguishable.)
 */
@Composable
fun FeeBadge(item: NoteItem, compact: Boolean = false, modifier: Modifier = Modifier) {
    val kind = item.feeKind
    val container = when (kind) {
        FeeKind.PAID -> MaterialTheme.colorScheme.tertiary
        FeeKind.FAN_GROUP -> MaterialTheme.colorScheme.primaryContainer
        FeeKind.FREE -> MaterialTheme.colorScheme.secondaryContainer
        FeeKind.IMAGE -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (kind) {
        FeeKind.PAID -> MaterialTheme.colorScheme.onTertiary
        FeeKind.FAN_GROUP -> MaterialTheme.colorScheme.onPrimaryContainer
        FeeKind.FREE -> MaterialTheme.colorScheme.onSecondaryContainer
        FeeKind.IMAGE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = Corners.small, color = container, contentColor = content, modifier = modifier) {
        Text(
            when (kind) {
                FeeKind.IMAGE -> "图文"
                FeeKind.PAID -> "VIP"
                FeeKind.FAN_GROUP -> "粉丝圈"
                FeeKind.FREE -> "免费"
            },
            Modifier.padding(
                horizontal = if (compact) Spacing.s else Spacing.m,
                vertical = 2.dp
            ),
            style = if (compact) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.labelMedium
        )
    }
}
