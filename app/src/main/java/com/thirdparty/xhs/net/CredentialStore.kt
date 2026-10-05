package com.thirdparty.xhs.net

import android.content.Context
import androidx.core.content.edit

/**
 * Credentials for the current guest session.
 *
 * The identity is stored whole — it is the complete `User-Id` string including
 * the form suffix the examined client appends (e.g. "AABBCCDDEEFF889X"), not a
 * bare MAC. See [IdentityGuess] for the four forms and their exact lengths.
 *
 * There is no pool of pre-discovered ids: `XhsApi.loginAsGuest` registers the
 * identity through `app/init`, which is what creates the guest account, so every
 * switch can simply generate a brand-new random identity (verified working).
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("xhs_guest", Context.MODE_PRIVATE)

    var userToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit { putString(KEY_TOKEN, v) }

    var userHash: String
        get() = prefs.getString(KEY_HASH, "") ?: ""
        set(v) = prefs.edit { putString(KEY_HASH, v) }

    /**
     * The identity currently in use.
     *
     * On a fresh install this generates a random identity **and persists it right
     * away**. Persisting matters: the getter would otherwise return a different
     * random value on every read, so `app/init` and the login that follows would
     * use different identities and each launch would create a throwaway account.
     */
    val deviceId: String
        get() = synchronized(this) {
            prefs.getString(KEY_DEVICE, null)?.let { return it }
            val fresh = IdentityGuess.randomFresh()
            prefs.edit { putString(KEY_DEVICE, fresh) }
            fresh
        }

    /** Switch to a specific identity. */
    fun setDevice(identity: String) {
        // a cached VIP window belongs to the account it was read from
        prefs.edit {
            putString(KEY_DEVICE, identity)
            putLong(KEY_VIP_END, 0L)
        }
    }

    /**
     * 换号前清掉**上一条会话**：token 与 hash 必须一起清。
     *
     * 只清 hash 会留下"新设备身份 + 旧 token"的组合，服务端只会回 `-1 用戶ID錯誤`；
     * 而这两次写原本是两次 `apply()`，中间状态可被其它协程读到（docs/REVIEW.md 附录A-P1-9）。
     * 现在合并成一次提交。
     */
    fun clearSession() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_HASH)
            putLong(KEY_VIP_END, 0L)
        }
    }

    /**
     * The current account's VIP end, as last seen from the server (epoch seconds,
     * 0 = unknown).
     *
     * Cached so the automatic switch can decide from local data: a VIP window does not
     * move on its own, so re-asking the server on every request would be pure waste.
     * The value is only ever written when the server actually reports a profile, and
     * cleared whenever the identity changes.
     */
    var vipEnd: Long
        get() = prefs.getLong(KEY_VIP_END, 0L)
        set(v) = prefs.edit { putLong(KEY_VIP_END, v) }

    /**
     * Require the device's biometric lock when the app is opened. Off by default —
     * turning it on is an explicit choice made in 我的.
     */
    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(v) = prefs.edit { putBoolean(KEY_BIOMETRIC, v) }

    /**
     * When on, the app switches to a fresh account (which starts a new VIP window)
     * as soon as the current account's VIP runs out.
     */
    var autoSwitchOnVipExpiry: Boolean
        // ON by default: the account is anonymous and disposable, so silently
        // moving to one that still has VIP beats hitting a paywall mid-browse.
        get() = prefs.getBoolean(KEY_AUTO_VIP, true)
        set(v) = prefs.edit { putBoolean(KEY_AUTO_VIP, v) }

    /** Switch to a brand-new random identity and return it. */
    fun freshDevice(): String {
        val id = IdentityGuess.randomFresh()
        setDevice(id)
        return id
    }

    /**
     * How many 最近浏览 entries to keep. The DAO used to hard-code 100; the
     * default is now 2000 and the user can change it in 我的.
     */
    var historyLimit: Int
        get() = prefs.getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
        set(v) = prefs.edit { putInt(KEY_HISTORY_LIMIT, v.coerceIn(100, 20000)) }

    companion object {
        const val DEFAULT_HOST = "app.xiaohuangbook.net"
        const val DEFAULT_HISTORY_LIMIT = 2000
        private const val KEY_TOKEN = "user_token"
        private const val KEY_HASH = "user_hash"
        private const val KEY_DEVICE = "device_identity"
        private const val KEY_AUTO_VIP = "auto_switch_on_vip_expiry"
        private const val KEY_VIP_END = "current_vip_end"
        private const val KEY_BIOMETRIC = "biometric_lock"
        private const val KEY_HISTORY_LIMIT = "history_limit"
    }
}
