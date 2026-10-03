package com.thirdparty.xhs.net

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Await an OkHttp call, and actually CANCEL THE REQUEST if the coroutine is cancelled.
 *
 * Every request in this app runs inside a coroutine whose life is tied to a screen —
 * `viewModelScope` for a page's data, `rememberCoroutineScope` for a one-off action,
 * `LaunchedEffect` for an image. Cancellation therefore happens constantly: pop a
 * screen, switch a tab, type another letter in the search box.
 *
 * But `Call.execute()` is not cancellable. Cancelling the coroutine running it only
 * abandons the RESULT: the socket keeps reading until the body is complete or the read
 * timeout expires. The visible consequences were real —
 *
 *  - leaving a waterfall grid left every cover still downloading: forty requests for
 *    pictures nobody would ever see, on the user's mobile data;
 *  - closing 备份 mid-transfer kept the whole WebDAV upload/download going;
 *  - a screen that was already gone still held an IO thread busy for up to the 30s
 *    read timeout, and the process could not go idle.
 *
 * `enqueue` + `invokeOnCancellation` is what breaks the connection instead. The body is
 * still read by the caller, on whatever dispatcher it needs.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        // cancel() closes the socket/stream; the callback will fire with an IOException,
        // which is ignored below because the continuation is already gone.
        runCatching { cancel() }
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isCancelled) {
                // nobody will read this body — close it, or the connection stays leased
                runCatching { response.close() }
                return
            }
            cont.resume(response)
        }
    })
}
