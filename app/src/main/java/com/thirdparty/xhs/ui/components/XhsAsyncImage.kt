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
import com.thirdparty.xhs.net.await
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
    // All remember/LaunchedEffect calls are unconditional on purpose: an early
    // `return` before them would change the number of slots used by this
    // composable whenever `url` flips between null and non-null (which happens
    // for every async-loaded avatar), corrupting state association.
    val key = url.orEmpty()
    var bitmap by remember(key) { mutableStateOf<Bitmap?>(BitmapCache[key]) }
    var failed by remember(key) { mutableStateOf(false) }
    val currentKey by rememberUpdatedState(key)

    LaunchedEffect(key) {
        if (key.isEmpty() || bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) { loadBitmap(currentKey) }
        if (loaded != null) {
            BitmapCache[currentKey] = loaded
            bitmap = loaded
        } else {
            failed = true
        }
    }

    val bmp = bitmap
    when {
        key.isEmpty() ->
            Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))

        bmp != null ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale
            )

        else -> {
            // placeholder / error surface from the theme
            val bg = if (failed) MaterialTheme.colorScheme.surfaceContainerHighest
            else MaterialTheme.colorScheme.surfaceVariant
            Box(modifier = modifier.background(bg))
        }
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

private suspend fun loadBitmap(url: String): Bitmap? {
    return try {
        // await(), not execute(): a grid of covers is composed of dozens of these, and
        // scrolling away cancels their coroutines. With a blocking execute() the
        // cancellation only dropped the result — every one of those downloads kept
        // running to completion, on the user's data, for a picture nobody would see.
        App.http.newCall(Request.Builder().url(url).build()).await().use { resp ->
            if (!resp.isSuccessful) return@use null
            var bytes = resp.body?.bytes() ?: return@use null
            if (url.contains("codstatic")) {
                bytes = runCatching { XhsCrypto.zdecrypt(bytes) }.getOrElse { bytes }
            }
            decodeDownsampled(bytes)
        }
    } catch (e: Exception) {
        // includes CancellationException's IOException twin from a cancelled call
        null
    }
}

/**
 * Decode with sub-sampling.
 *
 * Screens never display more than ~1280px on the long edge, so decoding the
 * full-size source wastes huge amounts of heap (a 1080x1440 cover is ~6MB in
 * ARGB_8888; caching 80 of those would be ~480MB and would OOM).
 * Software (non-hardware) bitmaps are also required for the LRU cache to be
 * able to report size accurately.
 */
private const val MAX_DECODE_EDGE = 1280

private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    var w = bounds.outWidth
    var h = bounds.outHeight
    while (w / 2 >= MAX_DECODE_EDGE || h / 2 >= MAX_DECODE_EDGE) {
        w /= 2
        h /= 2
        sample *= 2
    }

    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565   // halves the footprint; these are photos
        inScaled = false
    }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
}

/**
 * Memory-bounded LRU bitmap cache.
 *
 * Sized as a fraction of the app's heap rather than by entry count, so it can
 * never grow past what the process can actually hold.
 */
private object BitmapCache {
    private val maxKb: Int = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt() // ~12.5% of heap
    private val lru = object : android.util.LruCache<String, Bitmap>(maxKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    operator fun get(key: String): Bitmap? = lru.get(key)

    operator fun set(key: String, bmp: Bitmap) {
        if (bmp.byteCount / 1024 <= maxKb / 4) lru.put(key, bmp)
    }

    fun clear() = lru.evictAll()
}

/** Drop all decoded bitmaps (called when the system reports memory pressure). */
fun clearImageMemoryCache() = BitmapCache.clear()