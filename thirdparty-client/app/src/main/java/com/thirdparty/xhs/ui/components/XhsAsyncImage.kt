package com.thirdparty.xhs.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.thirdparty.xhs.App
import com.thirdparty.xhs.net.XhsCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Self-contained Compose image loader:
 *  - downloads bytes via the shared OkHttp client
 *  - decrypts AES-ECB when the URL contains "codstatic" (the backend's scheme)
 *  - decodes to a Bitmap and renders it
 * Placeholder colors come from the active Material3 ColorScheme.
 */
@Composable
fun XhsAsyncImage(
    url: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (url.isNullOrBlank()) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        return
    }
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(BitmapCache[url]) }
    var failed by remember(url) { mutableStateOf(false) }
    val currentUrl by rememberUpdatedState(url)

    LaunchedEffect(url) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadBitmap(currentUrl) }
        if (loaded != null) {
            BitmapCache[currentUrl] = loaded
            bitmap = loaded
        } else {
            failed = true
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        // placeholder / error surface from the theme
        val bg = if (failed) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceVariant
        Box(modifier = modifier.background(bg))
    }
}

/** Circular avatar with consistent sizing and an MD3 surface background. */
@Composable
fun XhsAvatar(
    url: String?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    XhsAsyncImage(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface),
        contentScale = ContentScale.Crop
    )
}

private fun loadBitmap(url: String): Bitmap? {
    return try {
        App.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            var bytes = resp.body?.bytes() ?: return@use null
            if (url.contains("codstatic")) {
                bytes = runCatching { XhsCrypto.zdecrypt(bytes) }.getOrElse { bytes }
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    } catch (e: Exception) {
        null
    }
}

/** Tiny LRU-ish in-memory bitmap cache (bounded). */
private object BitmapCache {
    private val cache = LinkedHashMap<String, Bitmap>(0, 0.75f, true)
    private val maxEntries = 80

    operator fun get(key: String): Bitmap? = synchronized(cache) { cache[key] }

    operator fun set(key: String, bmp: Bitmap) = synchronized(cache) {
        cache[key] = bmp
        while (cache.size > maxEntries) {
            val it = cache.entries.iterator()
            if (it.hasNext()) { it.next(); it.remove() }
        }
    }
}