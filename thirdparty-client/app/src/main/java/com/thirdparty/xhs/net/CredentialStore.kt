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
        prefs.edit().putString(KEY_DEVICE, identity).apply()
    }

    /**
     * When on, the app switches to a fresh account (which starts a new VIP window)
     * as soon as the current account's VIP runs out.
     */
    var autoSwitchOnVipExpiry: Boolean
        get() = prefs.getBoolean(KEY_AUTO_VIP, false)
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

    companion object {
        const val DEFAULT_HOST = "app.xiaohuangbook.net"
        private const val HISTORY_MAX = 50
        private const val KEY_TOKEN = "user_token"
        private const val KEY_HASH = "user_hash"
        private const val KEY_DEVICE = "device_identity"
        private const val KEY_HISTORY = "account_history"
        private const val KEY_AUTO_VIP = "auto_switch_on_vip_expiry"
    }
}

/** One entry of the guest-account history. */
data class HistoryAccount(val identity: String, val userId: Int, val name: String)
