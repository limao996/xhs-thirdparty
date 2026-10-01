package com.thirdparty.xhs.net

import android.content.Context

/**
 * Local, account-agnostic credential store.
 *
 * On every launch we fetch a new guest *session* (token) and overwrite this
 * store. The guest *account* cannot change: the backend only serves account
 * endpoints for the one already-established device identity (a different id
 * returns `result=-1` 用戶ID錯誤), so `user_id` stays constant while the token
 * rotates.
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
        // The only device identity the backend accepts: a new id gets
        // "用戶ID錯誤 请重新登录" from every account endpoint, so the session token
        // rotates per launch while the identity (and therefore the guest
        // account) stays fixed.
        const val DEFAULT_DEVICE_MAC = "AABBCCDDEEFF"
        private const val KEY_TOKEN = "user_token"
        private const val KEY_HASH = "user_hash"
        private const val KEY_DEVICE = "device_id"
    }
}