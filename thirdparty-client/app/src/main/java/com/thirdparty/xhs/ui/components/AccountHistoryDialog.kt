package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.net.HistoryAccount
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * Picker for previously used guest accounts.
 *
 * The current account is highlighted and cannot be re-selected; each row can also
 * be forgotten (e.g. a VIP window that has run out).
 */
@Composable
fun AccountHistoryDialog(
    history: List<HistoryAccount>,
    currentIdentity: String,
    onPick: (HistoryAccount) -> Unit,
    onForget: (HistoryAccount) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("历史账号") },
        text = {
            if (history.isEmpty()) {
                Text(
                    "还没有切换过账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(history, key = { it.identity }) { entry ->
                        val isCurrent = entry.identity == currentIdentity
                        Surface(
                            shape = Corners.small,
                            color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(Spacing.s),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.name.ifBlank { "游客" },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "ID ${entry.userId}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(Spacing.s))
                                        Text(
                                            // the form suffix is what distinguishes two
                                            // accounts that share a hardware-id prefix
                                            entry.identity.takeLast(4),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (isCurrent) {
                                    Text(
                                        "使用中",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    TextButton(onClick = { onPick(entry) }) { Text("切换") }
                                    IconButton(onClick = { onForget(entry) }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
