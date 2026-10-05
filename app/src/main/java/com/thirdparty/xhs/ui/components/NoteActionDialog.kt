package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 瀑布流 / 推荐页长按弹出的作品对话框。
 *
 * 用对话框而不是 `DropdownMenu`：菜单是「就地小面板」，长按这一下本来就没有明确的锚点
 * （推荐页甚至是整屏视频），而对话框把作品标题、可选动作一次说清楚，也不会被卡片边缘裁掉。
 *
 * 只有两个动作（收藏、稍后观看）+ 一个可选的多选入口：这是「随手处理一件作品」，
 * 不是作品管理页 —— 排序、删除在「稍后观看」页里做。
 */
@Composable
fun NoteActionDialog(
    /** 作品标题，对话框顶部显示是哪一件 */
    title: String,
    saved: Boolean,
    inWatchLater: Boolean,
    onToggleSave: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onDismiss: () -> Unit,
    /** 需要多选的页面（收藏 / 最近浏览）才给这个入口 */
    onEnterSelection: (() -> Unit)? = null
) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title.ifBlank { "作品" },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                ActionRow(
                    icon = if (saved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (saved) "取消收藏" else "收藏"
                ) {
                    if (saved) haptics.reject() else haptics.confirm()
                    onDismiss()
                    onToggleSave()
                }
                ActionRow(
                    icon = if (inWatchLater) Icons.Filled.PlaylistRemove else Icons.Filled.PlaylistAdd,
                    label = if (inWatchLater) "移出稍后观看" else "稍后观看"
                ) {
                    if (inWatchLater) haptics.reject() else haptics.confirm()
                    onDismiss()
                    onToggleWatchLater()
                }
                if (onEnterSelection != null) {
                    ActionRow(icon = Icons.Filled.Checklist, label = "多选") {
                        haptics.longPress()
                        onDismiss()
                        onEnterSelection()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Spacing.l))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
