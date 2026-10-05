package com.thirdparty.xhs.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The confirmation dialog shared by every "this removes something" action.
 *
 * 取消关注 and 取消收藏 are both one-tap and neither has an undo, so a stray tap
 * silently drops data: an author disappears from 关注（列表就在下面，点错了不会立刻
 * 察觉）or a note leaves 我的收藏. 关注/收藏 themselves stay single-tap — adding is
 * trivially reversible by tapping again, so only the destructive direction asks.
 *
 * One composable rather than a dialog per screen so the wording, the button order
 * and the 取消-on-the-right placement cannot drift between 详情 / 作者页 / 关注列表.
 *
 * @param confirmText the action's own verb ("移除" / "取消关注"), never a bare "确定".
 */
@Composable
fun ConfirmActionDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // 确认类对话框统一在组件内部给触感：所有调用方一次覆盖（用户反复反馈漏加）
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = haptics.confirmClick(onConfirm)) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = haptics.click(onDismiss)) { Text("取消") }
        }
    )
}
