package com.thirdparty.xhs.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri

/** 用系统浏览器打开链接；没有可处理的应用时明确提示，而不是静默什么都不发生。 */
fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    val ok = runCatching { context.startActivity(intent) }.isSuccess
    if (!ok) Toast.makeText(context, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show()
}
