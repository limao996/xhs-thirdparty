package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.data.AccountProbe
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Result list for the VIP account scan.
 *
 * The backend never creates accounts, so "scanning" means probing the set of
 * well-known device ids to find the ones that already carry an account — and
 * among those, which are currently VIP. Scanning never touches the session in
 * use; switching is an explicit tap on a row.
 */
@Composable
fun AccountScanDialog(
    running: Boolean,
    done: Int,
    total: Int,
    scanning: String,
    found: List<AccountProbe>,
    currentMac: String,
    onSwitch: (AccountProbe) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("扫描 VIP 账号") },
        text = {
            Column {
                if (running) {
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        "已扫描 $done / $total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        scanning,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "找到 ${found.size} 个可用账号，其中 ${found.count { it.isVip }} 个带 VIP",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(Modifier.height(Spacing.m))

                if (found.isEmpty() && !running) {
                    Text(
                        "没有发现可用账号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(found, key = { it.mac }) { p ->
                        val isCurrent = p.mac == currentMac
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
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            p.mac,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(Modifier.width(Spacing.s))
                                        VipTag(p.isVip)
                                    }
                                    Text(
                                        "ID ${p.userId} · ${p.userName}" +
                                            vipEndSuffix(p.vipEnd),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(
                                    onClick = { onSwitch(p) },
                                    enabled = !running && !isCurrent
                                ) {
                                    Text(if (isCurrent) "使用中" else "切换")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("关闭") }
        }
    )
}

@Composable
private fun VipTag(isVip: Boolean) {
    Surface(
        shape = Corners.extraSmall,
        color = if (isVip) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isVip) MaterialTheme.colorScheme.onTertiaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            if (isVip) "VIP" else "普通",
            Modifier.padding(horizontal = Spacing.xs + 2.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private fun vipEndSuffix(vipEnd: Long): String {
    if (vipEnd <= System.currentTimeMillis() / 1000) return ""
    val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    return " · VIP 至 " + fmt.format(Date(vipEnd * 1000))
}
