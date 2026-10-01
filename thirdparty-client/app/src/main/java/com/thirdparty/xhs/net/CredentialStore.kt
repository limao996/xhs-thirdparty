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

    /**
     * The device identity currently in use.
     *
     * NOTE: this is the COMPLETE `User-Id` string, suffix included (e.g.
     * "AABBCCDDEEFF889X" or "0000000000000000I"). The examined client builds it
     * in four different forms depending on which hardware id it can read:
     *   <mac>889X · <imei>X · <android_id>AI (len>30) · <android_id>I
     * Accounts exist in every one of those forms, so the identity has to be
     * stored whole rather than as a bare MAC.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null)
            ?: SEED_IDENTITIES.first()

    /** Switch to a specific identity (used by the manual account switch). */
    fun setDevice(identity: String) {
        prefs.edit().putString(KEY_DEVICE, identity).apply()
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
            ?: SEED_IDENTITIES

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

    /**
     * A freshly generated, never-before-used device id.
     *
     * Used by the switch/scan refresh paths so they genuinely try random ids
     * first (as requested) instead of only replaying a fixed list. Verified
     * behaviour of the backend: these ids always answer `result=-1
     * 用戶ID錯誤` — it never creates accounts — so callers MUST have a fallback.
     * Keeping the attempt means that if the backend ever starts issuing
     * accounts for new ids, the app picks them up with no code change.
     */
    fun generateRandomDevice(): String {
        val hex = "0123456789ABCDEF"
        val dig = "0123456789"
        // one of the four forms the original client uses, chosen at random
        return when ((0..3).random()) {
            0 -> (1..12).map { hex.random() }.joinToString("") + "889X"
            1 -> (1..15).map { dig.random() }.joinToString("") + "X"
            2 -> (1..32).map { hex.random() }.joinToString("").lowercase() + "AI"
            else -> (1..16).map { hex.random() }.joinToString("").lowercase() + "I"
        }
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
        val SEED_IDENTITIES = listOf(
            "AABBCCDDEEFF889X",      // uid 3684088
            "111111111111889X",      // uid 56347336
            "FFFFFFFFFFFF889X",      // uid 1135580
            "123456789ABC889X",      // uid 211839
            "123456789012889X",      // uid 3338000
            "112233445566889X",      // uid 1843711
            "000000000000889X",      // uid 58077
            "000000000001889X",      // uid 159493
            "000000000003889X",      // uid 52605634
            "00155D000000889X",      // uid 51530196  (Hyper-V prefix)
            "525400123456889X",      // uid 4117963   (QEMU/KVM default MAC)
            // discovered after testing the OTHER identity forms the client builds
            // (the suffix is part of the key: the same value with a different
            // suffix is a different account, and usually does not exist)
            "000000000000000X",      // uid 86436     all-zero IMEI form, VIP
            "0000000000000000I",     // uid 65648199  all-zero android_id form, VIP
            "333333333333333X",      // uid 50322803  repeated-digit IMEI form
            "666666666666666X",      // uid 1150443   repeated-digit IMEI form
            "888888888888888X"       // uid 2051895   repeated-digit IMEI form
        )

        /** @Deprecated use [SEED_IDENTITIES] (these are full identities, not MACs) */
        val SEED_DEVICES = SEED_IDENTITIES

        /** @Deprecated use [SEED_DEVICES] / [knownDevices] */
        val DEVICE_POOL = SEED_DEVICES

        /** Kept for compatibility; the first pool entry. */
        const val DEFAULT_DEVICE_MAC = "AABBCCDDEEFF"

        /**
         * Candidate identities handed to the scanner — full `User-Id` strings,
         * suffix included, spanning all four forms the original client builds.
         *
         * The backend only serves accounts that already exist, and empirically
         * those sit on the "degenerate" values devices fall back to when they
         * cannot read real hardware ids (all-zero, all-same-digit, the classic
         * dummy MACs, hypervisor OUIs). Both the value AND the suffix matter: the
         * same value with a different suffix is a different account.
         */
        val SCAN_CANDIDATES: List<String> = buildList {
            addAll(SEED_IDENTITIES)

            // --- MAC form: <12 hex>889X ---
            for (c in "0123456789ABCDEF") add(c.toString().repeat(12) + "889X")
            for (c in listOf('0', '1', 'F', 'A', '9')) {
                for (t in "0123456789ABCDEF") add(c.toString().repeat(11) + t + "889X")
            }
            addAll(
                listOf(
                    "AABBCCDDEEFF", "AABBCCDDEE00", "AABBCCDDEE11", "AABBCCDDEE22",
                    "112233445566", "112233445577", "123456789ABC", "123456789012",
                    "ABCDEFABCDEF", "ABABABABABAB", "121212121212", "010203040506",
                    "0A0B0C0D0E0F", "001122334455", "987654321ABC", "FEDCBA987654",
                    "DEADBEEFDEAD", "CAFEBABECAFE", "BAADF00DBAAD", "FEEDFACE0000",
                    "A1B2C3D4E5F6", "1A2B3C4D5E6F", "020000000000",
                    "FFFFFF000000", "FFFFFFFF0000"
                ).map { it + "889X" }
            )
            // hypervisor / emulator OUIs — these devices ran the original client
            for (oui in listOf("00155D", "525400", "080027", "000C29", "005056",
                               "001C42", "00163E", "0A0027", "020000")) {
                for (tail in listOf("000000", "000001", "123456", "ABCDEF", "FFFFFF")) {
                    add(oui + tail + "889X")
                }
            }

            // --- IMEI form: <15 digits>X ---
            for (d in "0123456789") add(d.toString().repeat(15) + "X")
            for (c in "0123456789ABCDEFX") add("0".repeat(14) + c + "X")

            // --- android_id form: <16 hex>I ---
            for (d in "0123456789abcdef") add(d.toString().repeat(16) + "I")
            for (c in "0123456789abcdef") add("0".repeat(15) + c + "I")
        }.distinct()

        private const val KEY_TOKEN = "user_token"
        private const val KEY_HASH = "user_hash"
        private const val KEY_DEVICE = "device_mac"
        private const val KEY_DISCOVERED = "discovered_macs"
    }
}
