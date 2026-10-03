package com.thirdparty.xhs.net

import android.content.Context

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
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var userHash: String
        get() = prefs.getString(KEY_HASH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HASH, v).apply()

    /**
     * The identity currently in use.
     *
     * On a fresh install this generates a random identity **and persists it right
     * away**. Persisting matters: the getter would otherwise return a different
     * random value on every read, so `app/init` and the login that follows would
     * use different identities and each launch would create a throwaway account.
     */
    val deviceId: String
        get() {
            prefs.getString(KEY_DEVICE, null)?.let { return it }
            val fresh = IdentityGuess.randomFresh()
            prefs.edit().putString(KEY_DEVICE, fresh).apply()
            return fresh
        }

    /** Switch to a specific identity. */
    fun setDevice(identity: String) {
        // a cached VIP window belongs to the account it was read from, and so does a
        // manual-pick exemption: both describe the account that is being left behind
        prefs.edit()
            .putString(KEY_DEVICE, identity)
            .putLong(KEY_VIP_END, 0L)
            .putString(KEY_MANUAL_PICK, "")
            .apply()
    }

    /**
     * The identity the user picked BY HAND out of 历史账号, if any ("" when none).
     *
     * The automatic VIP switch must leave that account alone. Its whole premise is
     * that the current account is anonymous and disposable, and a hand-picked one is
     * not — the user went into 历史账号 specifically to go back to it. Without this the
     * pick survived at most one poll tick on a non-VIP account, and since every
     * rotation REGISTERS a fresh identity, "switching to an old account" quietly
     * created a brand-new account seconds later.
     *
     * Cleared by [setDevice] (any identity change ends the old pick, and the history
     * path re-marks it immediately after switching), so it can never outlive the
     * account it refers to.
     */
    var manualPick: String
        get() = prefs.getString(KEY_MANUAL_PICK, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MANUAL_PICK, v).apply()

    /**
     * The current account's VIP end, as last seen from the server (epoch seconds,
     * 0 = unknown).
     *
     * Cached so the automatic-switch poll can decide from local data: a VIP window
     * does not move on its own, so re-asking the server every few seconds is pure
     * waste. The value is only ever written when the server actually reports a
     * profile, and cleared whenever the identity changes.
     */
    var vipEnd: Long
        get() = prefs.getLong(KEY_VIP_END, 0L)
        set(v) = prefs.edit().putLong(KEY_VIP_END, v).apply()

    /**
     * Require the device's biometric lock when the app is opened. Off by default —
     * turning it on is an explicit choice made in 我的.
     */
    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC, v).apply()

    /**
     * When on, the app switches to a fresh account (which starts a new VIP window)
     * as soon as the current account's VIP runs out.
     */
    var autoSwitchOnVipExpiry: Boolean
        // ON by default: the account is anonymous and disposable, so silently
        // moving to one that still has VIP beats hitting a paywall mid-browse.
        get() = prefs.getBoolean(KEY_AUTO_VIP, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_VIP, v).apply()

    /** Switch to a brand-new random identity and return it. */
    fun freshDevice(): String {
        val id = IdentityGuess.randomFresh()
        setDevice(id)
        return id
    }

    /**
     * Previously used guest accounts, most recent first.
     *
     * Stored as `identity|uid|name` so the picker can show something meaningful
     * without re-querying the backend for every entry. Capped so the list cannot
     * grow without bound; entries whose identity is blank are dropped.
     */
    val history: List<HistoryAccount>
        get() = prefs.getString(KEY_HISTORY, null)
            ?.split('\n')
            ?.mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 3 || parts[0].isBlank()) null
                else HistoryAccount(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
            }
            ?: emptyList()

    /** Replace the whole account history (used when restoring a backup). */
    fun replaceHistory(lines: List<String>) {
        prefs.edit().putString(KEY_HISTORY, lines.take(HISTORY_MAX).joinToString("\n")).apply()
    }
    /** Record the account now in use, moving it to the front of the history. */
    fun rememberAccount(uid: Int, name: String) {
        val id = deviceId
        if (id.isBlank()) return
        val entry = HistoryAccount(id, uid, name)
        val next = (listOf(entry) + history.filterNot { it.identity == id }).take(HISTORY_MAX)
        prefs.edit()
            .putString(
                KEY_HISTORY,
                next.joinToString("\n") { "${it.identity}|${it.userId}|${it.name}" }
            )
            .apply()
    }

    /** Forget one account. */
    fun forgetAccount(identity: String) {
        val next = history.filterNot { it.identity == identity }
        prefs.edit()
            .putString(KEY_HISTORY, next.joinToString("\n") { "${it.identity}|${it.userId}|${it.name}" })
            .apply()
    }

    /**
     * How many 最近浏览 entries to keep. The DAO used to hard-code 100; the
     * default is now 2000 and the user can change it in 我的.
     */
    var historyLimit: Int
        get() = prefs.getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
        set(v) = prefs.edit().putInt(KEY_HISTORY_LIMIT, v.coerceIn(100, 20000)).apply()

    companion object {
        const val DEFAULT_HOST = "app.xiaohuangbook.net"
        const val DEFAULT_HISTORY_LIMIT = 2000
        private const val HISTORY_MAX = 50
        private const val KEY_TOKEN = "user_token"
        private const val KEY_HASH = "user_hash"
        private const val KEY_DEVICE = "device_identity"
        private const val KEY_HISTORY = "account_history"
        private const val KEY_AUTO_VIP = "auto_switch_on_vip_expiry"
        private const val KEY_VIP_END = "current_vip_end"
        private const val KEY_MANUAL_PICK = "manual_pick_identity"
        private const val KEY_BIOMETRIC = "biometric_lock"
        private const val KEY_HISTORY_LIMIT = "history_limit"
    }
}

/** One entry of the guest-account history. */
data class HistoryAccount(val identity: String, val userId: Int, val name: String)
