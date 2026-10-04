package com.thirdparty.xhs.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thirdparty.xhs.data.formatBytes
import com.thirdparty.xhs.ui.components.ListSection
import com.thirdparty.xhs.ui.theme.Spacing
import com.thirdparty.xhs.ui.viewmodel.CacheViewModel

/**
 * 清除缓存：每一项缓存单独列出、单独勾选，清哪几项由用户决定。
 *
 * 这不是「一键清空」：图片磁盘缓存清掉要重新下载，内存缓存只要重新解码，
 * 两者代价不同，所以默认全不勾，让用户按需选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheScreen(
    onBack: () -> Unit,
    viewModel: CacheViewModel = viewModel()
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }

    val selected = state.entries.filter { it.kind in state.selected }
    val selectedBytes = selected.sumOf { it.bytes }
    val totalBytes = state.entries.sumOf { it.bytes }

    // one-shot: the ViewModel hands over the freed size exactly once
    LaunchedEffect(state.freed) {
        state.freed?.let { freed ->
            Toast.makeText(context, "已释放 ${formatBytes(freed)}", Toast.LENGTH_SHORT).show()
            viewModel.consumeFreed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("清除缓存") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Button(
                    onClick = { confirm = true },
                    enabled = state.selected.isNotEmpty() && !state.clearing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.l, vertical = Spacing.m)
                ) {
                    if (state.clearing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            if (selected.isEmpty()) "清除选中"
                            else "清除选中（${selected.size} 项 · ${formatBytes(selectedBytes)}）"
                        )
                    }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            ListSection("当前占用") {
                // measuring means flushing the OkHttp journal, so the first frame
                // would otherwise show a confident "0 B" that is simply not true yet
                if (state.loading) {
                    ListItem(
                        headlineContent = { Text("全部缓存") },
                        supportingContent = { Text("正在统计…") },
                        leadingContent = {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    )
                } else {
                    ListItem(
                        headlineContent = { Text("全部缓存") },
                        supportingContent = {
                            Text("共 ${state.entries.size} 项，勾选后一次性清除")
                        },
                        trailingContent = {
                            Text(
                                formatBytes(totalBytes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }

            if (state.loading) {
                Spacer(Modifier.height(Spacing.l))
                return@Column
            }

            ListSection("可清除的缓存") {
                state.entries.forEach { entry ->
                    ListItem(
                        headlineContent = { Text(entry.kind.title) },
                        supportingContent = { Text(entry.kind.hint) },
                        leadingContent = {
                            Checkbox(
                                checked = entry.kind in state.selected,
                                onCheckedChange = { viewModel.toggle(entry.kind) }
                            )
                        },
                        trailingContent = {
                            Text(
                                formatBytes(entry.bytes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        // the whole row toggles: a 48dp checkbox is a small target
                        // for a row the user is already reading
                        modifier = Modifier.clickable { viewModel.toggle(entry.kind) }
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.s)
                ) {
                    TextButton(onClick = { viewModel.setAll(true) }) { Text("全选") }
                    TextButton(onClick = { viewModel.setAll(false) }) { Text("全不选") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "已选 ${selected.size} 项",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = Spacing.l)
                    )
                }
            }

            ListSection("说明") {
                Text(
                    "收藏、最近浏览、我关注的作者是你的数据，不是缓存，不会被这里清掉" +
                        "（要搬走请用「备份与恢复」）。视频是边看边下的，退出即释放，不占缓存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.l)
                )
            }

            Spacer(Modifier.height(Spacing.l))
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("清除选中的 ${selected.size} 项缓存？") },
            text = {
                Column {
                    selected.forEach { entry ->
                        Text(
                            "· ${entry.kind.title}（${formatBytes(entry.bytes)}）",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(Spacing.m))
                    Text(
                        "合计约 ${formatBytes(selectedBytes)}。图片缓存清掉后，" +
                            "下次浏览会重新下载，其余内容不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.clearSelected()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("取消") }
            }
        )
    }
}
