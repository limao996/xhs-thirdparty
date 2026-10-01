package com.thirdparty.xhs.net

import android.content.Context

/**
 * Local credential store plus the guest **account pool**.
 *
 * The backend does NOT create accounts: `login-with-guest` only returns a
 * session for a device identity it already knows. Every fresh identity probed
 * (random MACs, near-misses of the working ones, android_id-style ids, extra
 * login params, `before-login-check` first) is answered with `result=-1
 * 用戶ID錯誤 请重新登录`, immediately and on repeat, and `first_login` is always
 * false — so nothing is ever registered.
 *
 * What does exist is a finite set of identities that already carry accounts.
 * They are the well-known "dummy" device ids (the ones the original app falls
 * back to when it cannot read a real MAC: all-zero, all-F, 112233445566, …), so
 * several people have used them over time. Rotating through that set is the
 * only way to switch guests — which is what [nextDevice] implements.
 *
 * Several pool entries currently carry a VIP window (`vp_status=1`), so
 * rotation also lets the app land on a VIP guest.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("xhs_guest", Context.MODE_PRIVATE)

    var userToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var userHash: String
        get() = prefs.getString(KEY_HASH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HASH, v).apply()

    /** Index into [DEVICE_POOL] for the identity in use. */
    private var deviceIndex: Int
        get() = prefs.getInt(KEY_DEVICE_INDEX, 0)
        set(v) = prefs.edit().putInt(KEY_DEVICE_INDEX, v).apply()

    /** The device identity currently in use (a MAC; "889X" is appended later). */
    val deviceId: String
        get() = DEVICE_POOL[deviceIndex.coerceIn(0, DEVICE_POOL.size - 1)]

    /**
     * Advance to the next pooled identity and return it. Called once per launch
     * (or per day) so consecutive runs use different guest accounts.
     */
    fun nextDevice(): String {
        deviceIndex = (deviceIndex + 1) % DEVICE_POOL.size
        return deviceId
    }

    /** Reset to the first (original) identity. */
    fun resetDevice() {
        deviceIndex = 0
    }

    companion object {
        const val DEFAULT_HOST = "app.xiaohuangbook.net"

        /**
         * Verified working guest identities, in probe order. Each entry was
         * confirmed with `login-with-guest` + `mine/user-info` returning a real
         * `user_id`. Fresh random ids are rejected, so this list cannot be
         * generated — it can only be extended by discovery.
         */
        val DEVICE_POOL = listOf(
            "AABBCCDDEEFF", // uid 3684088  (the long-standing default)
            "111111111111", // uid 56347336
            "FFFFFFFFFFFF", // uid 1135580
            "123456789ABC", // uid 211839
            "123456789012", // uid 3338000
            "112233445566", // uid 1843711
            "000000000000", // uid 58077
            "000000000001", // uid 159493
            "000000000003"  // uid 52605634
        )

        /** Kept for compatibility; the first pool entry. */
        const val DEFAULT_DEVICE_MAC = "AABBCCDDEEFF"

        private const val KEY_TOKEN = "user_token"
        private const val KEY_HASH = "user_hash"
        private const val KEY_DEVICE_INDEX = "device_index"
    }
}
