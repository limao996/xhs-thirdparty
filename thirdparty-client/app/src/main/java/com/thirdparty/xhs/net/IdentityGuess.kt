package com.thirdparty.xhs.net

/**
 * A large pool of guessed candidate identities ("上万" entries) plus genuinely
 * random ones.
 *
 * Two deliberate design points:
 *
 *  1. **Nothing is probed up front.** A guest login appears to start/consume a
 *     ~9 hour VIP window on that account, so pre-scanning would burn exactly the
 *     accounts meant to be handed out later. The list is built cheaply and an
 *     entry is only tried when the user actually switches.
 *
 *  2. **Structured guesses, not uniform noise.** Verified against the backend:
 *     uniformly random ids NEVER exist (50 tested, 0 hits — the backend only
 *     serves accounts that already exist). What does exist are the values a
 *     device falls back to when it cannot read real hardware: repeated
 *     nibbles/digits, paired and tripled motifs, hypervisor OUIs and the classic
 *     dummy MACs. The pool enumerates those patterns across all four identity
 *     forms the original client builds:
 *
 *        MAC form        <12 hex>889X
 *        IMEI form       <15 digits>X
 *        android_id form <16 hex>I
 *        long id form    <30+ chars>AI
 *
 *     The suffix is part of the key — the same value with a different suffix is a
 *     different account ("AABBCCDDEEFF889X" exists, "AABBCCDDEEFF000X" does not).
 *
 * [randomFresh] additionally produces a fully random identity on demand, so the
 * random-id path stays available.
 */
object IdentityGuess {

    private const val NIB = "0123456789ABCDEF"
    private const val DIG = "0123456789"
    private const val MAC_SUFFIX = "889X"

    /** Roughly how many entries the pool should hold. */
    private const val TARGET = 12_000

    /**
     * The pooled candidates (10k+ short strings, well under a megabyte), built
     * once on first use.
     */
    val all: List<String> by lazy { build() }

    /** A random entry from the pool. */
    fun random(): String = all.random()

    /** A fully random identity, in a randomly chosen form (the "random id" path). */
    fun randomFresh(): String = when ((0..3).random()) {
        0 -> (1..12).map { NIB.random() }.joinToString("") + MAC_SUFFIX
        1 -> (1..15).map { DIG.random() }.joinToString("") + "X"
        2 -> (1..32).map { NIB.random() }.joinToString("").lowercase() + "AI"
        else -> (1..16).map { NIB.random() }.joinToString("").lowercase() + "I"
    }

    private fun build(): List<String> {
        val mac = ArrayList<String>(9000)
        val imei = ArrayList<String>(2000)
        val aid = ArrayList<String>(2000)
        val longForm = ArrayList<String>(32)

        // ------------------------------------------------------------- MAC form
        // repeated nibble
        for (c in NIB) mac.add(c.toString().repeat(12) + MAC_SUFFIX)
        // repeated nibble with one differing position, both directions
        for (c in NIB) for (t in NIB) {
            mac.add(c.toString().repeat(11) + t + MAC_SUFFIX)
            mac.add(t + c.toString().repeat(11) + MAC_SUFFIX)
        }
        // alternating two nibbles: ABABABABABAB
        for (a in NIB) for (b in NIB) mac.add("$a$b".repeat(6) + MAC_SUFFIX)
        // doubled pairs: XXYYZZXXYYZZ
        for (a in NIB) for (b in NIB) for (c in NIB) {
            mac.add("$a$a$b$b$c$c".repeat(2) + MAC_SUFFIX)
            mac.add("$a$a$b$b$c$c$c$c$b$b$a$a" + MAC_SUFFIX)
        }
        // tripled motifs: AAABBBCCCAAA
        for (a in NIB) for (b in NIB) for (c in NIB) {
            mac.add("$a$a$a$b$b$b$c$c$c$a$a$a" + MAC_SUFFIX)
        }
        // hypervisor / emulator OUIs (devices that ran the original client)
        for (oui in listOf("00155D", "525400", "080027", "000C29", "005056", "001C42",
                           "00163E", "0A0027", "020000", "001DD8", "000569", "001C14",
                           "000FFE", "001AA0", "00125A", "001B21", "0050C2", "000D3A")) {
            for (t in NIB) mac.add(oui + "00000$t$MAC_SUFFIX")
            for (t in listOf("000000", "000001", "000002", "123456", "ABCDEF", "FFFFFF",
                             "FFFFFE", "111111", "222222", "AABBCC", "010203", "654321"))
                mac.add(oui + t + MAC_SUFFIX)
        }
        // classic dummy MACs
        for (m in CLASSIC) mac.add(m + MAC_SUFFIX)
        // sequential windows over the hex alphabet, forwards and backwards
        val seq = "0123456789ABCDEF0123456789ABCDEF"
        for (s in 0..19) mac.add(seq.substring(s, s + 12) + MAC_SUFFIX)
        val rseq = seq.reversed()
        for (s in 0..19) mac.add(rseq.substring(s, s + 12) + MAC_SUFFIX)

        // ------------------------------------------------------------ IMEI form
        for (d in DIG) {
            imei.add(d.toString().repeat(15) + "X")
            imei.add(d.toString().repeat(14) + "MX")
            imei.add(d.toString().repeat(14) + "0X")
            imei.add("0" + d.toString().repeat(14) + "X")
        }
        for (d in DIG) for (t in DIG) {
            imei.add(d.toString().repeat(14) + t + "X")
            imei.add(t + d.toString().repeat(14) + "X")
            imei.add("$d$d$t$t$d$d$t$t$d$d$t$t$d$d" + "X")
        }
        for (c in NIB + "X") imei.add("0".repeat(14) + c + "X")
        for (d in DIG) imei.add("0".repeat(13) + d + "0X")
        // tripled digit motifs
        for (a in DIG) for (b in DIG) for (c in DIG) {
            imei.add("$a$a$a$b$b$b$c$c$c$a$a$a$b$b" + "X")
        }

        // ------------------------------------------------------ android_id form
        for (d in NIB) aid.add(d.toString().repeat(16).lowercase() + "I")
        for (d in NIB) for (t in NIB) aid.add(d.toString().repeat(15).lowercase() + t.lowercase() + "I")
        for (c in NIB) aid.add("0".repeat(15) + c + "I")
        for (a in NIB) for (b in NIB) aid.add("$a$b".repeat(8).lowercase() + "I")
        for (a in NIB) for (b in NIB) for (c in NIB) {
            aid.add("$a$a$a$b$b$b$c$c$c$a$a$a$b$b$b$c".lowercase() + "I")
        }

        // --------------------------------------------------------- long id form
        for (d in NIB) longForm.add(d.toString().repeat(32) + "AI")

        // Interleave so the pool keeps every form represented even if the budget
        // has to cut something: a form that is missing cannot be used at all.
        val out = LinkedHashSet<String>(TARGET * 2)
        val perForm = TARGET / 4
        out.addAll(mac.take(minOf(mac.size, perForm * 2)))
        out.addAll(imei.take(minOf(imei.size, perForm)))
        out.addAll(aid.take(minOf(aid.size, perForm)))
        out.addAll(longForm.take(minOf(longForm.size, perForm)))
        out.addAll(mac.drop(minOf(mac.size, perForm * 2)))
        out.addAll(imei.drop(minOf(imei.size, perForm)))
        out.addAll(aid.drop(minOf(aid.size, perForm)))
        out.addAll(longForm.drop(minOf(longForm.size, perForm)))
        return out.toList()
    }

    private val CLASSIC = listOf(
        "AABBCCDDEEFF", "AABBCCDDEE00", "AABBCCDDEE11", "AABBCCDDEE22",
        "112233445566", "112233445577", "123456789ABC", "123456789012",
        "ABCDEFABCDEF", "ABABABABABAB", "121212121212", "010203040506",
        "0A0B0C0D0E0F", "001122334455", "987654321ABC", "FEDCBA987654",
        "DEADBEEFDEAD", "CAFEBABECAFE", "BAADF00DBAAD", "FEEDFACE0000",
        "A1B2C3D4E5F6", "1A2B3C4D5E6F", "020000000000", "FFFFFF000000",
        "FFFFFFFF0000", "AAAABBBBCCCC", "111122223333", "444455556666",
        "777788889999", "000011112222", "333344445555", "666677778888",
        "9999AAAABBBB", "CCCCDDDDEEEE", "000102030405", "060708090A0B",
        "0C0D0E0F1011", "121314151617", "18191A1B1C1D", "1E1F20212223",
        "242526272829", "2A2B2C2D2E2F", "303132333435", "363738393A3B",
        "3C3D3E3F4041", "424344454647", "48494A4B4C4D", "4E4F50515253",
        "545556575859", "5A5B5C5D5E5F", "606162636465", "666768696A6B",
        "6C6D6E6F7071", "727374757677", "78797A7B7C7D", "7E7F80818283",
        "848586878889", "8A8B8C8D8E8F", "909192939495", "969798999A9B",
        "9C9D9E9FA0A1", "A2A3A4A5A6A7", "A8A9AAABACAD", "AEAFB0B1B2B3",
        "B4B5B6B7B8B9", "BABBBCBDBEBF", "C0C1C2C3C4C5", "C6C7C8C9CACB",
        "CCCDCECFD0D1", "D2D3D4D5D6D7", "D8D9DADBDCDD", "DEDFE0E1E2E3",
        "E4E5E6E7E8E9", "EAEBECEDEEEF", "F0F1F2F3F4F5", "F6F7F8F9FAFB",
        "FCFDFEFF0001", "1234567890AB", "FFEEDDCCBBAA", "FEDCBA098765",
        "13579BDF0246", "02468ACE1357", "1F2E3D4C5B6A"
    )
}
