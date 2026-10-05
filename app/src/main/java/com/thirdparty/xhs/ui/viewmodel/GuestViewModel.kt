package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Guest account state: the current account and manual switching.
 *
 * The identity is persisted, so the app keeps the SAME account across restarts
 * until the user switches. `app/init` registers a new identity and that creates
 * the account (see XhsApi.loginAsGuest), which is why a switch can simply
 * generate a fresh random id.
 *
 * There is no timer in here any more. The VIP-expiry check moved into the request
 * path (XhsRepository.ensureAccountForRequest): it runs when something actually
 * needs the account, so nothing polls, nothing happens while the app is idle, and a
 * lapsed window is replaced by a fresh account BEFORE the request that needs it.
 */
class GuestViewModel(private val repo: XhsRepository) : ViewModel() {
    private val _accountLabel = MutableStateFlow("游客ID：加载中…")
    val accountLabel: StateFlow<String> = _accountLabel.asStateFlow()

    private val _rotating = MutableStateFlow(false)
    val rotating: StateFlow<Boolean> = _rotating.asStateFlow()

    private val _applied = MutableStateFlow(false)
    val applied: StateFlow<Boolean> = _applied.asStateFlow()
    private val _vip = MutableStateFlow(false)
    /** whether the account currently in use carries VIP */
    val vip: StateFlow<Boolean> = _vip.asStateFlow()

    private val _vipEnd = MutableStateFlow(0L)
    val vipEnd: StateFlow<Long> = _vipEnd.asStateFlow()

    private val _autoVip = MutableStateFlow(repo.autoSwitchOnVipExpiry)

    init {
        // The account can now change inside a request, with no user action behind it
        // (that is the whole point of the on-demand gate), so the label and VIP state
        // have to follow the epoch the repository bumps. drop(1) skips the replayed
        // current value — an epoch from an earlier switch is not a new change.
        viewModelScope.launch {
            repo.accountEpoch.drop(1).collect {
                if (it > 0) {
                    refreshLabel()
                    refreshVip()
                }
            }
        }
        // Network recovery: the guest login itself can have failed while there was no
        // connection, which leaves the header on 「游客ID：—」 even after everything else
        // recovers (nothing else ever asks again).
        viewModelScope.launch {
            com.thirdparty.xhs.App.INSTANCE.networkEpoch.drop(1).collect {
                val label = _accountLabel.value
                if (label.contains("—") || label.contains("加载中")) {
                    runCatching { repo.rotateGuest() }
                }
                refreshLabel()
                refreshVip()
            }
        }
    }

    /**
     * Called once on cold start. Logs in with the account already stored — no
     * rotation, so repeated launches keep the same guest.
     *
     * There is deliberately NO VIP check here any more: the first request the app
     * makes (the feed) goes through the account gate, which performs exactly that
     * check and upgrades the account before the request if the window has lapsed. One
     * path, and the check happens right before something needs it.
     */
    fun ensureFreshGuest() {
        if (_applied.value) return
        _applied.value = true
        viewModelScope.launch {
            _rotating.value = true
            _accountLabel.value = "游客ID：加载中…"
            runCatching { repo.rotateGuest() }
            refreshLabel()
            refreshVip()
            _rotating.value = false
        }
    }

    /** Whether the automatic VIP-expiry switch is on. */
    val autoVip: StateFlow<Boolean> = _autoVip.asStateFlow()

    fun setAutoVip(on: Boolean, onToast: (String) -> Unit = {}) {
        repo.autoSwitchOnVipExpiry = on
        _autoVip.value = on
        if (!on) {
            onToast("已关闭：VIP 到期后不再自动切换")
            return
        }
        onToast("已开启：VIP 到期后自动切到有 VIP 的账号")
        // apply immediately — the current account may already be expired
        viewModelScope.launch {
            _rotating.value = true
            val switched = runCatching { repo.switchToVipAccount() }.getOrDefault(false)
            refreshLabel()
            refreshVip()
            _rotating.value = false
            if (switched) onToast("当前账号 VIP 已到期，已自动切换")
        }
    }

    /**
     * Re-read the cached account state from storage. Used after a backup restore
     * replaced the identity behind the app's back.
     */
    fun refreshAccountState() {
        refreshLabel()
        refreshVip()
    }

    /**
     * Re-check on demand (e.g. when the profile page loads).
     *
     * The request path already runs this same check before every request that needs an
     * account, so this only makes the 我的 page's own numbers current immediately rather
     * than one request later.
     */
    /**
     * Manual switch: a brand-new RANDOM identity every time.
     *
     * `app/init` registers the identity and that creates the account, so no pool
     * or probing is needed — each switch simply becomes a new guest.
     */
    fun switchRandom(onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val fresh = repo.freshRandomMac()
            val ok = runCatching { repo.switchGuestTo(fresh) }.getOrDefault(false)
            refreshLabel()
            refreshVip()
            _rotating.value = false
            onToast(if (ok) "已切换到新的随机账号" else "切换失败，沿用当前账号")
        }
    }

    private fun refreshLabel() {
        viewModelScope.launch {
            val id = runCatching { repo.myUserId() }.getOrDefault(0)
            _accountLabel.value = if (id > 0) "游客ID：$id" else "游客ID：—"
        }
    }

    private fun refreshVip() {
        viewModelScope.launch {
            val profile = runCatching { repo.myProfile() }.getOrNull()
            _vip.value = profile?.isVip == true
            _vipEnd.value = profile?.vipEnd ?: 0L
        }
    }
}