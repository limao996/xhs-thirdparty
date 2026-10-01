package com.thirdparty.xhs

/**
 * Share-link contract.
 *
 * A shared note has to open THIS app on the right note, so the share sheet emits
 * a custom-scheme link rather than the backend's web URL — we cannot register an
 * App Link for a domain we do not own, and `assetlinks.json` is not an option
 * here. A custom scheme needs no verification and launches straight into the app.
 *
 * Format: `xhstp://note/<noteId>`
 */
object DeepLink {
    const val SCHEME = "xhstp"
    const val HOST = "note"

    fun noteUrl(noteId: Long): String = "$SCHEME://$HOST/$noteId"
}
