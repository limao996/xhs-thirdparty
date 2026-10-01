package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.net.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import com.thirdparty.xhs.net.HistoryAccount
import com.thirdparty.xhs.net.IdentityGuess

/**
 * Guest account state: the current account, manual switching, and the history of
 * accounts used so far.
 *
 * The identity is persisted, so the app keeps the SAME account across restarts
 * until the user switches. `app/init` registers a new identity and that creates
 * the account (see XhsApi.loginAsGuest), which is why a switch can simply
 * generate a fresh random id.
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

    private val _history = MutableStateFlow<List<HistoryAccount>>(emptyList())
    /** previously used guest accounts, most recent first */
    val history: StateFlow<List<HistoryAccount>> = _history.asStateFlow()

    fun refreshHistory() {
        _history.value = runCatching { repo.accountHistory() }.getOrDefault(emptyList())
    }

    /** Switch back to an account already used before. */
    fun switchToHistory(entry: HistoryAccount, onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val ok = runCatching { repo.switchGuestTo(entry.identity) }.getOrDefault(false)
            refreshLabel()
            refreshVip()
            remember()
            refreshHistory()
            _rotating.value = false
            onToast(if (ok) "已切换回 ${entry.name}" else "切换失败，沿用当前账号")
        }
    }

    /** Drop one entry from the history list. */
    fun forget(entry: HistoryAccount) {
        repo.forgetAccount(entry.identity)
        refreshHistory()
    }

    /** Record the account now in use, so it can be switched back to later. */
    private fun remember() {
        viewModelScope.launch { runCatching { repo.rememberCurrentAccount() }; refreshHistory() }
    }


    // ---- VIP scan -----------------------------------------------------------
    // REMOVED on purpose. Logging in as a guest appears to start/consume a
    // ~9 hour VIP window on that account, so scanning the candidate space would
    // burn exactly the accounts it is meant to find. Candidates are now guessed
    // on demand (IdentityGuess) and only ever tried when the user switches.

    /**
     * Called once on cold start. Logs in with the account already stored — no
     * rotation, so repeated launches keep the same guest.
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
            remember()
            _rotating.value = false
        }
    }

    /** Manual switch to the next pooled account. */
    fun rotate(onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val ok = runCatching { repo.rotateGuest() }.getOrNull()?.optInt("result") == 1
            refreshLabel()
            refreshVip()
            _rotating.value = false
            onToast(if (ok) "已切换游客账号" else "切换失败，沿用当前账号")
        }
    }

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
            remember()
            _rotating.value = false
            onToast(if (ok) "已切换到新的随机账号" else "切换失败，沿用当前账号")
        }
    }

    /** The identity currently in use. */
    fun currentDeviceMac(): String = repo.currentDeviceMac()

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