package com.thirdparty.xhs.net

import android.content.Context

/**
 * Local, account-agnostic credential store.
 *
 * The whole point of the "fresh guest every launch" feature: on each startup we
 * fetch a NEW guest credential and overwrite this store, so the previous guest
 * account is abandoned and never reused.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("xhs_guest", Context.MODE_PRIVATE)

    var userToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    var userHash: String
        get() = prefs.getString(KEY_HASH, "") ?: ""
        set(v) = prefs.edit().putString(KEY_HASH, v).apply()

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE, DEFAULT_DEVICE_MAC) ?: DEFAULT_DEVICE_MAC

    fun saveDevice(id: String) {
        prefs.edit().putString(KEY_DEVICE, id).apply()
    }

    companion object {
        const val DEFAULT_HOST = "app.xiaohuangbook.net"
        // A stable, already-established guest device identity (the backend only
        // serves account endpoints for established ids). Persisted; token still
        // rotates each launch for a fresh guest session.
        const val DEFAULT_DEVICE_MAC = "AABBCCDDEEFF"
        private const val KEY_TOKEN = "user_token"
        private const val KEY_HASH = "user_hash"
        private const val KEY_DEVICE = "device_id"
    }
}