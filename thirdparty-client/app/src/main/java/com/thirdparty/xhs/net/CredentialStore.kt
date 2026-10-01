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

    /** The device identity currently in use (a MAC; "889X" is appended later). */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null)
            ?: DEVICE_POOL.first()

    /** Switch to a specific identity (used by the manual account switch). */
    fun setDevice(mac: String) {
        prefs.edit().putString(KEY_DEVICE, mac).apply()
    }

    /**
     * Advance to the next pooled identity and return it.
     * Kept for the pool rotation path; switching is manual nowadays.
     */
    fun nextDevice(): String {
        val cur = DEVICE_POOL.indexOf(deviceId)
        setDevice(DEVICE_POOL[(cur + 1).mod(DEVICE_POOL.size)])
        return deviceId
    }

    /** Pick a random pooled identity different from the current one. */
    fun randomDevice(): String {
        if (DEVICE_POOL.size <= 1) return deviceId
        val cur = DEVICE_POOL.indexOf(deviceId)
        var pick = cur
        while (pick == cur) pick = (0 until DEVICE_POOL.size).random()
        setDevice(DEVICE_POOL[pick])
        return deviceId
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

        /**
         * Candidate identities handed to the scanner.
         *
         * The backend only serves accounts that already exist, and empirically
         * those are the "classic dummy" device ids the original app falls back
         * to when it cannot read a real MAC. So the scan space is exactly this
         * family of well-known values — random MACs are never accepted.
         */
        val SCAN_CANDIDATES: List<String> = buildList {
            addAll(DEVICE_POOL)
            // repeated nibbles: 000000000000 … FFFFFFFFFFFF
            for (c in "0123456789ABCDEF") add(c.toString().repeat(12))
            // repeated nibbles + incrementing last char
            for (c in listOf('0', '1', 'F', 'A', '9')) {
                for (t in "0123456789ABCDEF") add(c.toString().repeat(11) + t)
            }
            // the canonical "looks fake but pretty" MACs
            addAll(
                listOf(
                    "AABBCCDDEEFF", "AABBCCDDEE00", "AABBCCDDEE11", "AABBCCDDEE22",
                    "112233445566", "112233445577", "123456789ABC", "123456789012",
                    "ABCDEFABCDEF", "ABABABABABAB", "121212121212", "010203040506",
                    "0A0B0C0D0E0F", "001122334455", "987654321ABC", "FEDCBA987654",
                    "DEADBEEFDEAD", "CAFEBABECAFE", "BAADF00DBAAD", "FEEDFACE0000",
                    "A1B2C3D4E5F6", "1A2B3C4D5E6F", "0F1E2D3C4B5A", "020000000000",
                    "000000000001", "000000000003", "0000000000FF", "FFFFFFFF0000"
                )
            )
        }.distinct()

        private const val KEY_TOKEN = "user_token"
        private const val KEY_HASH = "user_hash"
        private const val KEY_DEVICE = "device_mac"
    }
}
