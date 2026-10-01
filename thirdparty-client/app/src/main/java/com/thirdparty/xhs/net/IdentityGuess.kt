package com.thirdparty.xhs.net

/**
 * Generates guest identities.
 *
 * An identity is the complete `User-Id` string: a hardware id plus the suffix
 * the examined client appends to it. `AppUtils.getUserId()` builds four forms
 * depending on which hardware id it can read, and the exact lengths matter —
 * they are what the backend accepts:
 *
 *     <12 hex>889X     device MAC, colons stripped
 *     <15 digits>X     IMEI, short values padded with "M" up to 15
 *     <16 hex>I        android_id, when it is 30 characters or fewer
 *     <30 hex>AI       android_id longer than 30 -> truncated to 30, then "AI"
 *
 * **Random identities work.** `XhsApi.loginAsGuest` registers the identity via
 * `app/init` before logging in, and that registration is what creates the guest
 * account: verified 8/8 for brand-new random ids in every form (each one gets a
 * fresh uid and nickname), versus 0/4 without it. So there is no need for a pool
 * of pre-discovered ids — every switch simply generates a new one.
 *
 * (The AI form is the one trap: 30 characters creates an account, 32 does not,
 * because the original code truncates before appending the suffix.)
 */
object IdentityGuess {

    private const val NIB = "0123456789ABCDEF"
    private const val DIG = "0123456789"
    private const val MAC_SUFFIX = "889X"

    /** A brand-new random identity, in a randomly chosen form. */
    fun randomFresh(): String = when ((0..3).random()) {
        0 -> random(12, NIB) + MAC_SUFFIX
        1 -> random(15, DIG) + "X"
        2 -> random(16, NIB).lowercase() + "I"
        else -> random(30, NIB).lowercase() + "AI"
    }

    private fun random(n: Int, alphabet: String): String =
        (1..n).map { alphabet.random() }.joinToString("")
}
