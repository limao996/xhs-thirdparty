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
class XhsApi(private val context: Context, private val client: okhttp3.OkHttpClient) {

    private val credentialStore = CredentialStore(context)

    /** Guards against concurrent re-login storms after a stale-identity error. */
    private val reauthLock = Any()
    @Volatile private var reauthInFlight = false

    private companion object {
        const val LOGIN_PATH = "v2/user/login-with-guest"
        /**
         * Registers the device identity with the backend — this is what actually
         * creates the guest account (see [loginAsGuest]). Easy to mistake for a
         * startup-ads call, which is how it was missed for so long.
         */
        const val APP_INIT_PATH = "v2/app/init"
        const val NETWORK_ATTEMPTS = 2
        const val RETRY_BACKOFF_MS = 350L
    }

    /**
     * The identity sent in the `User-Id` header for the guest login.
     *
     * Any identity can be *registered* via [APP_INIT_PATH] (verified: brand-new
     * random ids succeed 8/8 once `app/init` runs first), so this does not have to
     * be one of the legacy device ids — `CredentialStore` owns which is current.
     */
    private fun deviceUserId(): String = credentialStore.deviceId

    /** Perform a POST to an API path with the given business params. */
    fun call(path: String, params: Map<String, Any> = emptyMap()): JSONObject {
        val first = doCall(path, params)
        if (path == LOGIN_PATH || !needsReauth(first)) return first

        // The guest identity went stale (e.g. the backend no longer knows our
        // device id). Re-establish a guest session once and retry, so the app
        // self-heals instead of silently returning empty data forever.
        synchronized(reauthLock) {
            if (!reauthInFlight) {
                reauthInFlight = true
                try {
                    loginAsGuest()
                } catch (e: Exception) {
                    // fall through and return the original response
                } finally {
                    reauthInFlight = false
                }
            }
        }
        return runCatching { doCall(path, params) }.getOrDefault(first)
    }

    private fun doCall(path: String, params: Map<String, Any>): JSONObject {
        // Transient network failures (DNS blips, dropped connections) are common
        // on mobile. Every endpoint used here is a read-only query (or a guest
        // login, which is safe to repeat), so one bounded retry is worthwhile.
        var lastError: java.io.IOException? = null
        for (attempt in 0 until NETWORK_ATTEMPTS) {
            try {
                return doCallOnce(path, params)
            } catch (e: java.io.IOException) {
                lastError = e
                if (attempt < NETWORK_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
        throw lastError ?: java.io.IOException("request failed: $path")
    }

    private fun doCallOnce(
        path: String,
        params: Map<String, Any>,
        // Overrides used by the account scanner, which must probe other identities
        // WITHOUT disturbing the session currently stored in CredentialStore.
        userIdOverride: String? = null,
        tokenOverride: String? = null
    ): JSONObject {
        val token = tokenOverride ?: credentialStore.userToken
        val hash = credentialStore.userHash
        // The login call must re-establish identity from the *device*, never
        // from a possibly-stale user_hash, otherwise a stale hash would keep
        // being echoed back and re-auth would never recover.
        val userIdHeader = userIdOverride
            ?: if (path == LOGIN_PATH) deviceUserId() else hash.ifEmpty { deviceUserId() }

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
            .header("User-Id", userIdHeader)
            .header("Client-Type", "1")
            .header("Client-Version", BuildConfig.CLIENT_VERSION)
            .header("Client-Channel", BuildConfig.CLIENT_CHANNEL)
            .header("Accept-Language", "zh-hk")
            .build()

        client.newCall(request).execute().use { response ->
            // Check the HTTP status BEFORE touching the body. Without this an
            // error page (HTML from a CDN 502, an empty 503 body, ...) goes
            // straight into the AES decryptor and surfaces as a crypto
            // exception — which is neither retryable nor diagnosable, and it
            // silently disabled the network retry for the most common
            // transient failure. Throwing IOException instead makes the retry
            // wrapper above do its job.
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code} for $path")
            }
            val bytes = response.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                throw java.io.IOException("empty response body for $path")
            }
            val text = try {
                XhsCrypto.decrypt(bytes)
            } catch (e: Exception) {
                // truncated / non-encrypted body: treat it as a transport error
                throw java.io.IOException("undecodable response for $path", e)
            }
            return JSONObject(text)
        }
    }

    /**
     * Does this response mean our identity is no longer usable?
     *
     * Empirically (verified against the live API):
     *   1     ok
     *   -1    筆記不存在或者已經刪除 / 用戶不存在或者已註銷  -> a real "not found", NOT auth
     *   -1    用戶ID錯誤 请重新登录                          -> identity gone, must re-login
     *   1002/1003/1004                                       -> session expired
     * `-1` is overloaded, so the message has to discriminate.
     */
    private fun needsReauth(res: JSONObject): Boolean {
        val code = res.optInt("result")
        if (code == 1002 || code == 1003 || code == 1004) return true
        if (code != -1) return false
        val msg = res.optString("message")
        return msg.contains("用戶ID錯誤") || msg.contains("重新登录") || msg.contains("重新登錄")
    }

    /**
     * Guest login used on every fresh launch. Calling this yields a brand-new
     * guest account credential (server issues a new user_token each time),
     * which mirrors "每次启动都更换新的游客账号".
     */
    /**
     * Log in as a guest.
     *
     * When [advanceDevice] is true the app first moves to the next identity in
     * [CredentialStore.DEVICE_POOL], so consecutive launches use different guest
     * accounts. The stored `user_hash` must be cleared at the same time —
     * `getUserId()` prefers it over the device identity, and a stale hash would
     * pin us to the previous account.
     */
    fun loginAsGuest(advanceDevice: Boolean = false): JSONObject {
        if (advanceDevice) {
            credentialStore.freshDevice()
            credentialStore.userHash = ""
        }
        // CREATE the account first. `app/init` is what registers the device
        // identity with the backend — without it every new identity answers
        // `result=-1 用戶ID錯誤` from the account endpoints and it looks like the
        // backend never issues accounts. Verified: 8/8 fresh random identities
        // succeed when this runs first, 0/4 when it does not.
        runCatching { call(APP_INIT_PATH, emptyMap()) }
        val res = call(LOGIN_PATH, emptyMap())
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

    /**
     * Log in as one specific identity (used by the manual switch), registering it
     * first so the backend creates the account.
     */
    fun loginAsDevice(identity: String): JSONObject {
        credentialStore.setDevice(identity)
        credentialStore.userHash = ""
        return loginAsGuest(advanceDevice = false)
    }

    /** Current account identity (server-echoed user_hash), for UI display. */
    fun currentUserHash(): String = credentialStore.userHash

    /** The identity currently in use. */
    fun currentDeviceMac(): String = credentialStore.deviceId

    /** A brand-new randomly generated identity (the manual switch path). */
    fun freshRandomMac(): String = credentialStore.freshDevice()

    /** Previously used accounts, most recent first. */
    fun accountHistory(): List<HistoryAccount> = credentialStore.history

    /** Record the account now in use. */
    fun rememberAccount(uid: Int, name: String) = credentialStore.rememberAccount(uid, name)

    /** Forget one history entry. */
    fun forgetAccount(identity: String) = credentialStore.forgetAccount(identity)

    /** Whether to switch accounts once the current VIP window expires. */
    var autoSwitchOnVipExpiry: Boolean
        get() = credentialStore.autoSwitchOnVipExpiry
        set(v) { credentialStore.autoSwitchOnVipExpiry = v }

    /** How many 最近浏览 entries to keep (default 2000). */
    var historyLimit: Int
        get() = credentialStore.historyLimit
        set(v) { credentialStore.historyLimit = v }
    /** Whether opening the app requires the device's biometric lock. */
    var biometricLock: Boolean
        get() = credentialStore.biometricLock
        set(v) { credentialStore.biometricLock = v }
    /** Cached VIP end of the current account (epoch seconds, 0 = unknown). */
    var cachedVipEnd: Long
        get() = credentialStore.vipEnd
        set(v) { credentialStore.vipEnd = v }
}