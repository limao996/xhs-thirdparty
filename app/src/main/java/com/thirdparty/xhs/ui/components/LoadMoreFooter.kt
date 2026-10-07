package com.thirdparty.xhs.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thirdparty.xhs.ui.theme.Spacing

/**
 * 列表底部的"下一页失败"重试条（所有网络列表共用）。
 *
 * 出现的唯一理由：分页失败以前是**静默**的 —— 底部既没有 spinner 也没有错误，
 * 用户会把"加载不出来"当成"内容就这些"。凡是能翻页的网络列表都要用它（硬约束 26）。
 */
@Composable
fun FooterRetry(onClick: (() -> Unit)?, label: String = "加载失败，点这里重试") {
    val haptics = rememberHaptics()
    Box(
        Modifier.fillMaxWidth().padding(Spacing.m),
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = haptics.click { onClick?.invoke() }) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
