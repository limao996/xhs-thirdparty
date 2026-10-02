package com.thirdparty.xhs.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.BackupManager
import com.thirdparty.xhs.net.WebDavClient
import com.thirdparty.xhs.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复.
 *
 * Two transports over one payload: a local file (via the system file picker, so
 * the user chooses where it lands — no storage permission needed) and a WebDAV
 * drive. The payload itself is built by [BackupManager].
 *
 * Restore asks 覆盖/合并 before it runs: silently wiping the local favourites is
 * the kind of thing a user only notices after the fact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var merge by remember { mutableStateOf(true) }
    // restore replaces local favourites/history/follows, so it asks before acting
    var confirmRestore by remember { mutableStateOf<(() -> Unit)?>(null) }

    var url by remember { mutableStateOf(WebDavClient.config(context).url) }
    var user by remember { mutableStateOf(WebDavClient.config(context).user) }
    var pass by remember { mutableStateOf(WebDavClient.config(context).password) }

    fun run(label: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        status = "$label…"
        scope.launch {
            status = runCatching { block() }
                .getOrElse { e -> "失败：${e.message ?: e.javaClass.simpleName}" }
            busy = false
        }
    }

    // System pickers instead of a hard-coded path: works on every Android version
    // and needs no storage permission.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("正在写入本地文件") {
            val bytes = BackupManager.exportCompressed(context)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw java.io.IOException("无法写入所选位置")
            }
            "已备份到本地（压缩后 ${bytes.size / 1024} KB）"
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("正在读取本地文件") {
            // read as bytes and let BackupManager detect gzip, so backups written
            // by an older uncompressed build still restore
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("无法读取所选文件")
            }
            val text = BackupManager.decode(bytes)
            BackupManager.restore(context, text, merge).let {
                if (!it.ok) throw java.io.IOException(it.detail)
                it.detail
            }.also { App.INSTANCE.notifyDataRestored() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { pad ->
        // Restore replaces local favourites / history / follows, so it always asks
        // first — the merge-vs-replace choice alone is easy to miss.
        confirmRestore?.let { action ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmRestore = null },
                title = { Text("确定恢复备份？") },
                text = {
                    Text(if (merge) "将把备份内容合并进当前数据，现有收藏/浏览/关注会保留。" else "将先清空本机收藏/浏览/关注，再写入备份内容，且不可撤销。")
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val act = action
                        confirmRestore = null
                        act()
                    }) { Text("恢复") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmRestore = null }) { Text("取消") }
                }
            )
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                .imePadding().padding(horizontal = Spacing.l)
        ) {
            if (status.isNotEmpty()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = Spacing.s)
                )
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                    Spacer(Modifier.size(Spacing.s))
                    Text("处理中…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(Spacing.m))
            Text("本地", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "备份包含：游客账号与切换历史、收藏、最近浏览、关注的作者、外观与自动切换设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.s))
            Row {
                Button(onClick = { saveLauncher.launch("xhs-thirdparty-backup.json.gz") }) { Text("备份到文件") }
                Spacer(Modifier.size(Spacing.s))
                OutlinedButton(onClick = {
                    confirmRestore = {
                        openLauncher.launch(arrayOf("application/gzip", "application/json", "*/*"))
                    }
                }) {
                    Text("从文件恢复")
                }
            }

            Spacer(Modifier.height(Spacing.l))
            HorizontalDivider()
            Spacer(Modifier.height(Spacing.l))

            Text("WebDAV", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "支持坚果云 / Nextcloud 等。填服务器根地址即可，备份固定存放在 " + WebDavClient.DIR + "/ 目录下（不存在时会自动创建）。坚果云请在「安全选项」里为应用生成专用密码。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.s))
            OutlinedTextField(
                value = url, onValueChange = { url = it },
                label = { Text("服务器地址（根目录）") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.s))
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("账号") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.s))
            OutlinedTextField(
                value = pass, onValueChange = { pass = it },
                label = { Text("密码 / 应用密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(Spacing.s))
            // 保存 and 测试 sit side by side. Previously an empty duplicate
            // "保存配置" button (a leftover from adding 测试连接) was left in the
            // column, so the label appeared twice and the three buttons stacked
            // one per line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    WebDavClient.save(context, WebDavClient.WebDavConfig(url, user, pass))
                    status = "配置已保存"
                }) { Text("保存配置") }
                Spacer(Modifier.size(Spacing.s))
                OutlinedButton(onClick = {
                    WebDavClient.save(context, WebDavClient.WebDavConfig(url, user, pass))
                    run("正在测试连接") {
                        val cfg = WebDavClient.WebDavConfig(url, user, pass)
                        if (cfg.url.isBlank()) throw java.io.IOException("请先填写服务器地址")
                        WebDavClient(App.INSTANCE.httpClient, cfg).testConnection().getOrThrow()
                        "连接正常"
                    }
                }) { Text("测试连接") }
            }

            Spacer(Modifier.height(Spacing.s))
            Row {
                Button(onClick = {
                    WebDavClient.save(context, WebDavClient.WebDavConfig(url, user, pass))
                    run("正在上传到云端") {
                        val cfg = WebDavClient.WebDavConfig(url, user, pass)
                        if (cfg.url.isBlank()) throw java.io.IOException("请先填写服务器地址")
                        val dav = WebDavClient(App.INSTANCE.httpClient, cfg)
                        dav.ensureDir().getOrThrow()
                        dav.upload(WebDavClient.FILE_NAME, BackupManager.exportCompressed(context))
                            .getOrThrow()
                        "已上传到 WebDAV"
                    }
                }) { Text("上传备份") }
                Spacer(Modifier.size(Spacing.s))
                OutlinedButton(onClick = {
                    WebDavClient.save(context, WebDavClient.WebDavConfig(url, user, pass))
                    confirmRestore = { run("正在从云端恢复") {
                        val cfg = WebDavClient.WebDavConfig(url, user, pass)
                        if (cfg.url.isBlank()) throw java.io.IOException("请先填写服务器地址")
                        val dav = WebDavClient(App.INSTANCE.httpClient, cfg)
                        val bytes = dav.downloadBytes(WebDavClient.FILE_NAME).getOrThrow()
                        val text = BackupManager.decode(bytes)
                        BackupManager.restore(context, text, merge).let {
                            if (!it.ok) throw java.io.IOException(it.detail)
                            "云端恢复完成：${it.detail}"
                        }.also { App.INSTANCE.notifyDataRestored() }
                    } }
                }) { Text("从云端恢复") }
            }

            Spacer(Modifier.height(Spacing.l))
            HorizontalDivider()
            Spacer(Modifier.height(Spacing.s))
            Text(
                "恢复方式",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = Spacing.s)
            )
            Text(
                if (merge) "合并：保留现有收藏/浏览/关注，只补充备份里的内容"
                else "覆盖：先清空本机收藏/浏览/关注，再写入备份内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            OutlinedButton(onClick = { merge = !merge }) {
                Text(if (merge) "当前：合并（点此改为覆盖）" else "当前：覆盖（点此改为合并）")
            }
            Spacer(Modifier.height(Spacing.l))
        }
    }
}