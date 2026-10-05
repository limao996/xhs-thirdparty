package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thirdparty.xhs.ui.theme.AvatarSize
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * One row of a locally-stored 关注 author, with an inline 取消关注 action.
 *
 * Two places render this list — the standalone 我关注的作者 page and the 关注 tab
 * inside 发现 — and they had drifted apart: different row heights, different
 * padding, and only one of them let the user actually unfollow. Both now render
 * this, so the same data looks and behaves the same wherever it is shown.
 *
 * `ListItem` rather than a hand-rolled `Row`: it brings MD3's list metrics
 * (minimum 56dp touch target, the standard leading/headline/supporting slots and
 * their alignment) which is what makes a list read as a Material list instead of
 * a stack of rows that merely resemble one.
 *
 * The unfollow button sits INSIDE the tappable row on purpose: the trailing
 * button consumes its own taps, so "open this author" and "stop following" are
 * distinct targets without a second gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowedAuthorRow(
    name: String,
    signature: String,
    avatarUrl: String,
    onClick: () -> Unit,
    onUnfollow: () -> Unit,
    showDivider: Boolean = true
) {
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            supportingContent = {
                if (signature.isNotBlank()) {
                    Text(
                        signature,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            leadingContent = {
                XhsAvatar(
                    url = avatarUrl,
                    contentDescription = name,
                    modifier = Modifier.size(AvatarSize.list)
                )
            },
            trailingContent = {
                // 小号关注按钮（与粉丝圈 tab / 详情页同一套）：整行不可点时按钮自己也要有触感
                FollowPill(
                    followed = true,
                    onClick = rememberHaptics().rejectClick(onUnfollow)
                )
            },
            modifier = Modifier.clickable(onClick = rememberHaptics().click(onClick))
        )
        if (showDivider) {
            // inset past the avatar so the line starts where the text does, the
            // MD3 list divider convention
            HorizontalDivider(
                Modifier.padding(start = Spacing.l + AvatarSize.list + Spacing.m)
            )
        }
    }
}
