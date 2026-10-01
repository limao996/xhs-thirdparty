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
            ?: SEED_DEVICES.first()

    /** Switch to a specific identity (used by the manual account switch). */
    fun setDevice(mac: String) {
        prefs.edit().putString(KEY_DEVICE, mac).apply()
    }

    /**
     * The identities known to work so far: the verified seed set plus everything
     * discovery has turned up since. Persisted, so the pool grows with use
     * instead of staying the hardcoded list.
     */
    val knownDevices: List<String>
        get() = prefs.getString(KEY_DISCOVERED, null)
            ?.split(',')?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: SEED_DEVICES

    /** Remember an identity that a probe confirmed works. */
    fun rememberDevice(mac: String) {
        if (mac.isBlank()) return
        val cur = knownDevices
        if (cur.contains(mac)) return
        prefs.edit().putString(KEY_DISCOVERED, (cur + mac).joinToString(",")).apply()
    }

    /**
     * Pick a random identity from the known set (any of them, including ones
     * already used). Random *generation* is impossible here — see [SEED_DEVICES].
     */
    fun randomDevice(): String {
        val pool = knownDevices
        if (pool.isEmpty()) return deviceId
        val pick = pool.random()
        setDevice(pick)
        return pick
    }

    /** Advance to the next known identity and return it. */
    fun nextDevice(): String {
        val pool = knownDevices
        val cur = pool.indexOf(deviceId)
        setDevice(pool[(cur + 1).mod(pool.size)])
        return deviceId
    }

    companion object {
        const val DEFAULT_HOST = "app.xiaohuangbook.net"

        /**
         * Verified working guest identities used to seed the pool.
         *
         * NOTE on "just use random ids": that is not possible. The backend never
         * creates accounts — `login-with-guest` only returns a session for an
         * identity it already knows. Measured: 30 fully random MACs and 20
         * structured-random MACs produced **0** working accounts, while every
         * fresh identity answers `result=-1 用戶ID錯誤` immediately and on repeat.
         *
         * What does exist is the set of "classic dummy" device ids the original
         * app falls back to when it cannot read a real MAC (all-zero, all-F,
         * 112233445566, …), which many devices have used over time. The pool is
         * therefore *discovered*, not generated: anything a scan confirms is
         * persisted in [KEY_DISCOVERED] and joins the set, and switching picks
         * randomly from the whole set.
         */
        val SEED_DEVICES = listOf(
            "AABBCCDDEEFF", // uid 3684088
            "111111111111", // uid 56347336
            "FFFFFFFFFFFF", // uid 1135580
            "123456789ABC", // uid 211839
            "123456789012", // uid 3338000
            "112233445566", // uid 1843711
            "000000000000", // uid 58077
            "000000000001", // uid 159493
            "000000000003", // uid 52605634
            // found by the hypervisor-OUI discovery sweep: devices that ran the
            // original client from a VM/emulator used these prefixes
            "00155D000000", // uid 51530196  (Hyper-V)
            "525400123456"  // uid 4117963   (QEMU/KVM default MAC)
        )

        /** @Deprecated use [SEED_DEVICES] / [knownDevices] */
        val DEVICE_POOL = SEED_DEVICES

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
            addAll(SEED_DEVICES)
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
        private const val KEY_DISCOVERED = "discovered_macs"
    }
}
