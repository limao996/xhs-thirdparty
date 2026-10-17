package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thirdparty.xhs.data.DownloadDedupe
import com.thirdparty.xhs.data.DownloadKind
import com.thirdparty.xhs.data.DownloadTaskEntity
import com.thirdparty.xhs.ui.theme.Corners
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 下载对话框：让用户**多选**要保存的内容（用户明确要求"对话框确认时让用户多选"）。
 *
 * - 视频：1 项（作品只有一路 HLS，成品是 mp4），默认勾上；
 * - 图片：网格多选，**默认全选**，并提供「全选 / 全不选」一键切换；
 * - **已经在下载 / 已下载的内容会被标出来且不可再选**（用户可能反复点「下载」，
 *   不去重就会重复下载、还会在公共目录堆出 `xxx(2).mp4`，见 `data/DownloadDedupe.kt`）；
 * - 确认后由调用方建任务并跳转到下载管理页。
 *
 * 用 `material3.AlertDialog`（硬约束 16：菜单 / 弹窗一律原生对话框，不要自绘外壳）。
 */
@Composable
fun DownloadPickerDialog(
    title: String,
    /** 视频地址（HLS）；为空表示这个作品没有视频 */
    videoUrl: String?,
    /** 图片地址列表（可能为空） */
    imageUrls: List<String>,
    /** 该作品已存在的下载任务（用来标记"已下载 / 下载中 / 已暂停"并禁止重复选择） */
    existingTasks: List<DownloadTaskEntity> = emptyList(),
    noteId: Long = 0L,
    onConfirm: (downloadVideo: Boolean, imageIndices: List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = rememberHaptics()
    // 已存在任务的标记（同一作品、同类型的某一条）
    fun badgeOf(kind: String, imageIndex: Int): String? =
        DownloadDedupe.badgeFor(
            existingTasks.firstOrNull {
                it.kind == kind && it.imageIndex == imageIndex
            }
        )

    val videoBadge = if (videoUrl != null) badgeOf(DownloadKind.VIDEO, 0) else null
    val imageBadges = imageUrls.indices.associateWith { badgeOf(DownloadKind.IMAGE, it) }
    val selectableImages = imageUrls.indices.filter { imageBadges[it] == null }

    var wantVideo by remember { mutableStateOf(videoUrl != null && videoBadge == null) }
    var selected by remember { mutableStateOf(selectableImages.toSet()) }
    // 已存在的（含被"全不选"顺手清掉的）不计入可选项
    selected = selected.intersect(selectableImages.toSet())

    val hasSelectable = (videoUrl != null && videoBadge == null) || selectableImages.isNotEmpty()
    val selectedCount = selected.size + if (wantVideo && videoBadge == null && videoUrl != null) 1 else 0
    val alreadyCount = imageBadges.values.count { it != null } + if (videoBadge != null) 1 else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.ifBlank { "作品" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (alreadyCount > 0) {
                    Text(
                        "这件作品已有 $alreadyCount 项在下载或已下载，已为你标出并跳过。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
                if (videoUrl != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(Corners.small)
                            .clickable(enabled = videoBadge == null) {
                                haptics.tick(); wantVideo = !wantVideo
                            }
                            .padding(vertical = Spacing.s),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (videoBadge == null) {
                            CheckCircle(wantVideo)
                        } else {
                            Text(
                                videoBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.width(Spacing.m))
                        Icon(Icons.Filled.Movie, contentDescription = null)
                        Spacer(Modifier.width(Spacing.s))
                        Column(Modifier.weight(1f)) {
                            Text("视频", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "保存为 mp4，保存到 Movies/小黄书/",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (imageUrls.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.s))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "图片 · ${selected.size}/${selectableImages.size} 张可下载" +
                                if (imageBadges.values.any { it != null }) "（已跳过已下载/下载中的）" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            haptics.tick()
                            selected = if (selected.size == selectableImages.size) {
                                emptySet()
                            } else {
                                selectableImages.toSet()
                            }
                        }) {
                            Text(if (selected.size == selectableImages.size) "全不选" else "全选")
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        items(imageUrls.indices.toList(), key = { it }) { index ->
                            val picked = index in selected
                            val badge = imageBadges[index]
                            Box(
                                Modifier
                                    .aspectRatio(1f)
                                    .clip(Corners.small)
                                    .clickable(enabled = badge == null) {
                                        haptics.tick()
                                        selected = if (picked) selected - index else selected + index
                                    }
                            ) {
                                XhsAsyncImage(
                                    url = imageUrls[index],
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                )
                                if (picked) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                    ) {
                                        CheckCircle(true)
                                    }
                                }
                                if (badge != null) {
                                    // 已下载 / 下载中：压一层半透明底 + 文字，点不动
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .align(Alignment.BottomCenter)
                                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                                            .padding(vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            badge,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.inverseOnSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.s))
                Text(
                    "保存位置：视频保存到 Movies/小黄书/，图片保存到 Pictures/小黄书/。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedCount > 0,
                onClick = {
                    haptics.confirm()
                    val indices = selected.toList().sorted()
                    onConfirm(wantVideo && videoUrl != null, indices)
                }
            ) { Text(if (selectedCount > 0) "下载 $selectedCount 项" else "请选择内容") }
        },
        dismissButton = {
            TextButton(onClick = { haptics.click(onDismiss); onDismiss() }) { Text("取消") }
        }
    )
}

/** 圆形勾选框（选中 = 主色实心 + 对勾）。 */
@Composable
private fun CheckCircle(checked: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
