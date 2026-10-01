package com.thirdparty.xhs.net

import android.content.Context
import com.thirdparty.xhs.BuildConfig
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Thin client for the examined app's HTTP API.
 *
 * Every request:
 *   POST https://{baseHost}/v2/{path}
 *   body   = AES-CBC({ "s_time": ms, "user_token": <cred>, ...params })
 *   header User-Id = <guest user_hash or device-derived id>
 *   header Client-Type/Client-Version/Client-Channel ...
 *
 * Response body is AES-CBC encrypted JSON:
 *   { "result": 1, "message": "...", "data": {...} }
 */
class XhsApi(private val context: Context) {

    private val credentialStore = CredentialStore(context)

    /** The identity sent in the User-Id header before the first guest login. */
    private fun deviceUserId(): String {
        // The backend only serves account endpoints (mine/user-info etc.) for
        // *established* guest device ids. A brand-new device id gets "用戶ID錯誤".
        // So the device identity must be stable & persistent across launches
        // (only the token/session rotates each launch = fresh guest account).
        credentialStore.deviceId.let { if (it.isNotEmpty()) return it + "889X" }
        // seed from android_id when possible, so each install is stable
        val mac = macLikeId()
        credentialStore.saveDevice(mac)
        return mac + "889X"
    }

    private fun macLikeId(): String {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "0"
        val hex = androidId.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (hex.length >= 12) return hex.take(12).uppercase()
        val seed = (androidId.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        return (seed + "abcdef" + androidId.length.toString(16) + "13579bdf").take(12).uppercase()
    }

    /** Perform a POST to an API path with the given business params. */
    fun call(path: String, params: Map<String, Any> = emptyMap()): JSONObject {
        val token = credentialStore.userToken
        val hash = credentialStore.userHash

        val body = JSONObject()
        body.put("s_time", System.currentTimeMillis())
        body.put("user_token", token)
        for ((k, v) in params) {
            when (v) {
                is Int -> body.put(k, v)
                is Long -> body.put(k, v)
                is Double -> body.put(k, v)
                is Boolean -> body.put(k, v)
                else -> body.put(k, v.toString())
            }
        }

        val url = "https://${CredentialStore.DEFAULT_HOST}/$path"
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(XhsCrypto.encrypt(body.toString()).toRequestBody(
                "application/octet-stream; charset=utf-8".toMediaTypeOrNull()
            ))
            .header("Content-Type", "application/octet-stream; charset=utf-8")
            .header("Accept", "application/octet-stream")
            .header("User-Id", hash.ifEmpty { deviceUserId() })
            .header("Client-Type", "1")
            .header("Client-Version", BuildConfig.CLIENT_VERSION)
            .header("Client-Channel", BuildConfig.CLIENT_CHANNEL)
            .header("Accept-Language", "zh-hk")
            .build()

        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val text = XhsCrypto.decrypt(bytes)
            return JSONObject(text)
        }
    }

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Guest login used on every fresh launch. Calling this yields a brand-new
     * guest account credential (server issues a new user_token each time),
     * which mirrors "每次启动都更换新的游客账号".
     */
    fun loginAsGuest(): JSONObject {
        val res = call("v2/user/login-with-guest", emptyMap())
        if (res.optInt("result") == 1) {
            val data = res.optJSONObject("data") ?: JSONObject()
            val token = data.optString("user_token")
            val hash = data.optString("user_hash")
            if (token.isNotEmpty()) {
                credentialStore.userToken = token
                if (hash.isNotEmpty()) credentialStore.userHash = hash
            }
        }
        return res
    }

    /** Current account identity (server-echoed user_hash), for UI display. */
    fun currentUserHash(): String = credentialStore.userHash
}