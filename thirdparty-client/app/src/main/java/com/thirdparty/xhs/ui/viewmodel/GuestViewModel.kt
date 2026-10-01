package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the guest session and exposes the current guest ID for the top bar
 * (e.g. "游客ID：3684088").
 *
 * VERIFIED LIMITATION: the guest *account* cannot be rotated. Every experiment
 * against the live backend (different device ids, different suffixes, extra
 * login params) either returns `result=-1` 用戶ID錯誤 or a `user_hash` that is
 * merely the echoed device id — the backend only serves account endpoints for
 * the one already-established device identity, so `user_id` is always the same.
 * What DOES change on each launch is the session token. The wording here says so
 * instead of claiming an account switch that does not happen.
 */
class GuestViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _accountLabel = MutableStateFlow("游客ID：加载中…")
    val accountLabel: StateFlow<String> = _accountLabel.asStateFlow()

    private val _rotating = MutableStateFlow(false)
    val rotating: StateFlow<Boolean> = _rotating.asStateFlow()

    private val _applied = MutableStateFlow(false)
    val applied: StateFlow<Boolean> = _applied.asStateFlow()

    /**
     * Called once on cold start: fetch a fresh guest session token.
     * Deliberately silent — a new session token is not user-visible news, and
     * the account itself does not change.
     */
    fun ensureFreshGuest() {
        if (_applied.value) return
        _applied.value = true
        viewModelScope.launch {
            _rotating.value = true
            refreshLabel()
            runCatching { repo.rotateGuest() }
            _rotating.value = false
            refreshLabel()
        }
    }

    /** Manual session refresh triggered from the 我的 screen. */
    fun rotate(onToast: (String) -> Unit = {}) {
        // guard against parallel logins from repeated taps
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val ok = runCatching { repo.rotateGuest() }
                .getOrNull()?.optInt("result") == 1
            _rotating.value = false
            onToast(if (ok) "已刷新游客会话" else "刷新失败，沿用当前会话")
            refreshLabel()
        }
    }

    private fun refreshLabel() {
        viewModelScope.launch {
            val id = runCatching { repo.myUserId() }.getOrDefault(0)
            _accountLabel.value = if (id > 0) "游客ID：$id" else "游客ID：—"
        }
    }
}