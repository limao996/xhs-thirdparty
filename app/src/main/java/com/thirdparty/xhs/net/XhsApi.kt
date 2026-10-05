package com.thirdparty.xhs.net

import android.content.Context
import com.thirdparty.xhs.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /**
     * Serialises re-login after a stale identity.
     *
     * Was an `Any()` monitor + a flag, which cannot be held across the (now suspending)
     * re-login call: Kotlin rejects a suspension point inside a critical section, and
     * rightly so. `tryLock` reproduces the "one login at a time, others do not wait"
     * behaviour the flag had.
     */
    private val reauthMutex = kotlinx.coroutines.sync.Mutex()

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

    /**
     * Run before a request that needs a usable account.
     *
     * Set by `XhsRepository`, which owns the decision (it is the layer that can read a
     * profile and register a new identity). Doing it HERE — one choke point that every
     * request already goes through — is what replaced the 5-second poll: the account is
     * checked exactly when something needs it, and never while the app is idle.
     */
    var beforeAccountRequest: (suspend () -> Unit)? = null

    /**
     * 身份（账号）真的换了的时候回调一次。用途：让上层重载按账号发放的数据
     * （详情页的 media URL 就是按账号发的）。由 `XhsRepository` 接上去 bump `accountEpoch`。
     */
    var onIdentityChanged: (() -> Unit)? = null

    /** 最近一次闸门失败（诊断用；闸门本身是"尽力而为"的预处理，不阻断请求）。 */
    @Volatile
    var gateFailure: Throwable? = null
        private set

    /**
     * The account gate: a single-flight, re-entrancy-safe wrapper around
     * [beforeAccountRequest].
     *
     * 以前这里是一个普通的 `var insideAccountGate = false`，有两个真问题（见 docs/REVIEW.md 附录A）：
     *  1. 多个协程可以同时读到 false → 一起跑闸门（而闸门里会**新建身份**，于是并发多建号）；
     *  2. A 置位后、`finally` 复位前，B 直接**跳过**闸门就去发请求 —— 正是闸门要防的"带过期账号发请求"。
     *
     * 现在：`Mutex` 串行化（拿不到的会**等**），并用协程上下文里的 [AccountGateMarker] 做**同协程**
     * 的重入判断 —— 闸门自己发的 `v2/mine/user-info` 不会再来排队（那会自锁）。
     */
    private val accountGateMutex = kotlinx.coroutines.sync.Mutex()

    /** 标记"当前协程已经在闸门里"，闸门自己发的请求据此跳过。 */
    private object AccountGateMarker : kotlin.coroutines.CoroutineContext.Element {
        override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key
        object Key : kotlin.coroutines.CoroutineContext.Key<AccountGateMarker>
    }

    /** Perform a POST to an API path with the given business params. */
    suspend fun call(path: String, params: Map<String, Any> = emptyMap()): JSONObject {
        // Account gate first: a lapsed VIP window is dealt with BEFORE the request that
        // needs it, so the caller never sees the failure. The login/init paths are
        // exempt (they are what ESTABLISH an account, so there is nothing to check yet),
        // and so is the gate's own work (said marker).
        val alreadyInsideGate =
            kotlin.coroutines.coroutineContext[AccountGateMarker.Key] != null
        if (!alreadyInsideGate && path != LOGIN_PATH && path != APP_INIT_PATH) {
            beforeAccountRequest?.let { gate ->
                // 串行化：并发请求在这里排队，第一个跑完（可能刚换了号并刷新了缓存），
                // 后面的进去时闸门内的缓存判断会立刻返回 —— 这才是"一次尝试只建一个身份"。
                accountGateMutex.withLock {
                    withContext(AccountGateMarker) {
                        runCatching { gate() }.onFailure { e ->
                            // 不再静默吞掉：闸门失败要能查（否则"换号失败"看起来像"内容为空"）
                            gateFailure = e
                            if (com.thirdparty.xhs.BuildConfig.DEBUG) {
                                android.util.Log.w("XhsGate", "account gate failed: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        }

        val first = doCall(path, params)
        if (path == LOGIN_PATH || !needsReauth(first)) return first

        // The guest identity went stale (e.g. the backend no longer knows our
        // device id). Re-establish a guest session once and retry, so the app
        // self-heals instead of silently returning empty data forever.
        //
        // A Mutex, not `synchronized`: the re-login is a suspending network call, and a
        // monitor cannot be held across a suspension point. tryLock keeps the original
        // intent — whoever gets it does the re-login, everyone else returns the original
        // response instead of queueing up behind it and doing the login again.
        var reestablished = false
        if (reauthMutex.tryLock()) {
            try {
                val before = credentialStore.userToken to credentialStore.userHash
                val ok = runCatching { loginAsGuest() }.getOrNull()?.optInt("result") == 1
                val after = credentialStore.userToken to credentialStore.userHash
                // 只有**真的登录成功且凭证确实变了**才算自愈成功。
                // 原来是无条件 `reestablished = true`，即使 loginAsGuest 抛异常也当成功，
                // 于是把第一次的失败响应原样返回给上层 → 界面把它渲染成"没有内容"。
                reestablished = ok && after != before
                if (reestablished) {
                    // 身份变了要通知上层重载（详情页的 media URL 是按账号发放的）
                    onIdentityChanged?.invoke()
                }
            } finally {
                reauthMutex.unlock()
            }
        }
        if (!reestablished) return first
        return runCatching { doCall(path, params) }.getOrDefault(first)
    }

    private suspend fun doCall(path: String, params: Map<String, Any>): JSONObject {
        // Transient network failures (DNS blips, dropped connections) are common
        // on mobile. Every endpoint used here is a read-only query (or a guest
        // login, which is safe to repeat), so one bounded retry is worthwhile.
        var lastError: java.io.IOException? = null
        for (attempt in 0 until NETWORK_ATTEMPTS) {
            try {
                return doCallOnce(path, params)
            } catch (e: HttpStatusException) {
                // 4xx 是"请求本身不对"（404 / 403 / 429 …），重发一次只会再烧一次配额；
                // 5xx 与网络中断才值得重试。（docs/REVIEW.md 附录A-P1-12）
                if (e.code in 400..499) throw e
                lastError = e
                if (attempt < NETWORK_ATTEMPTS - 1) delay(RETRY_BACKOFF_MS)
            } catch (e: java.io.IOException) {
                lastError = e
                if (attempt < NETWORK_ATTEMPTS - 1) {
                    // delay(), not Thread.sleep(): this runs in a coroutine, and a
                    // sleeping thread is neither cancellable nor a thread the app can
                    // reclaim. (It was Thread.sleep, so a cancelled request still held
                    // an IO thread for the full backoff.)
                    delay(RETRY_BACKOFF_MS)
                }
            }
        }
        throw lastError ?: java.io.IOException("request failed: $path")
    }

    /** 非 2xx 响应；4xx 不重试，见 [doCall]。 */
    class HttpStatusException(val code: Int, path: String) :
        java.io.IOException("HTTP $code for $path")

    private suspend fun doCallOnce(
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

        client.newCall(request).await().use { response ->
            // Check the HTTP status BEFORE touching the body. Without this an
            // error page (HTML from a CDN 502, an empty 503 body, ...) goes
            // straight into the AES decryptor and surfaces as a crypto
            // exception — which is neither retryable nor diagnosable, and it
            // silently disabled the network retry for the most common
            // transient failure. Throwing IOException instead makes the retry
            // wrapper above do its job.
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, path)
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
    /**
     * 换一个设备身份（换号的第一步）。
     *
     * 与 [CredentialStore.setDevice] 的区别：这里把 token/hash 一起清掉，避免出现
     * "新身份 + 旧 token" 的中间状态（那组合在服务端只会得到 `-1 用戶ID錯誤`）。
     */
    suspend fun rotateDevice(advance: Boolean): String {
        if (advance) credentialStore.setDevice(IdentityGuess.randomFresh())
        credentialStore.clearSession()
        return credentialStore.deviceId
    }

    suspend fun loginAsGuest(advanceDevice: Boolean = false): JSONObject {
        if (advanceDevice) {
            rotateDevice(advance = true)
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
    suspend fun loginAsDevice(identity: String): JSONObject {
        credentialStore.setDevice(identity)
        // 一起清 token + hash：只清 hash 会留下"新身份 + 旧 token"
        credentialStore.clearSession()
        return loginAsGuest(advanceDevice = false)
    }

    /** Current account identity (server-echoed user_hash), for UI display. */
    fun currentUserHash(): String = credentialStore.userHash

    /** The identity currently in use. */
    fun currentDeviceMac(): String = credentialStore.deviceId

    /** A brand-new randomly generated identity (the manual switch path). */
    fun freshRandomMac(): String = credentialStore.freshDevice()

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