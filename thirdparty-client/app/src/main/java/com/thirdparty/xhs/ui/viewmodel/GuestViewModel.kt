package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.AccountProbe
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.net.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import com.thirdparty.xhs.net.IdentityGuess

/**
 * Guest account state: the current account and manual switching.
 *
 * The backend never creates accounts (see CredentialStore), so the app keeps the
 * guest it already has and only changes when the user asks. Switching is manual
 * and may reuse any previously seen account.
 */
class GuestViewModel(private val repo: XhsRepository) : ViewModel() {

    private companion object {
        /** how many pool candidates a manual switch tries before falling back */
        const val GUESS_ATTEMPTS = 5
    }

    private val _accountLabel = MutableStateFlow("游客ID：加载中…")
    val accountLabel: StateFlow<String> = _accountLabel.asStateFlow()

    private val _rotating = MutableStateFlow(false)
    val rotating: StateFlow<Boolean> = _rotating.asStateFlow()

    private val _applied = MutableStateFlow(false)
    val applied: StateFlow<Boolean> = _applied.asStateFlow()

    /** Size of the guessed candidate pool, for display. */
    val poolSize: Int get() = IdentityGuess.all.size

    private val _vip = MutableStateFlow(false)
    /** whether the account currently in use carries VIP */
    val vip: StateFlow<Boolean> = _vip.asStateFlow()

    private val _vipEnd = MutableStateFlow(0L)
    val vipEnd: StateFlow<Long> = _vipEnd.asStateFlow()

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
     * Manual switch, guessed-id first.
     *
     * Tries a few RANDOMLY GUESSED identities first (see [IdentityGuess]) — these
     * are never pre-scanned, because a guest login appears to start/consume a
     * ~9 hour VIP window, so probing a list up front would burn exactly the
     * accounts we want to hand out. The first guess that the backend accepts is
     * used and remembered; otherwise we fall back to one of the already-verified
     * identities so the switch always succeeds.
     */
    fun switchRandom(onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val candidates = buildList {
                // a fully random id first, so the random-id path stays live
                add(IdentityGuess.randomFresh())
                addAll(IdentityGuess.all.shuffled().take(GUESS_ATTEMPTS))
            }
            var probe: AccountProbe? = null
            var usedRandom = false
            for ((i, id) in candidates.withIndex()) {
                probe = runCatching { repo.probeAccount(id) }.getOrNull()
                if (probe != null) {
                    usedRandom = i == 0
                    break
                }
            }
            val ok = when {
                probe != null -> runCatching { repo.switchGuestTo(probe!!.mac) }.getOrDefault(false)
                else -> runCatching { repo.switchGuestTo(repo.randomDeviceMac()) }.getOrDefault(false)
            }
            refreshLabel()
            refreshVip()
            _rotating.value = false
            onToast(
                when {
                    !ok -> "切换失败，沿用当前账号"
                    usedRandom -> "已切到随机新账号 ${probe!!.userId}"
                    probe != null -> "已切到猜测账号 ${probe!!.userId}"
                    else -> "已随机切换游客账号"
                }
            )
        }
    }

    /** Switch to an account found by the scanner. */
    fun switchTo(probe: AccountProbe, onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val ok = runCatching { repo.switchGuestTo(probe.mac) }.getOrDefault(false)
            refreshLabel()
            refreshVip()
            _rotating.value = false
            onToast(
                if (!ok) "切换失败，沿用当前账号"
                else if (probe.isVip) "已切换到 VIP 账号 ${probe.userId}" else "已切换到账号 ${probe.userId}"
            )
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