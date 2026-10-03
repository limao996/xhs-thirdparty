package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.XhsRepository
import com.thirdparty.xhs.net.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import com.thirdparty.xhs.net.HistoryAccount
import com.thirdparty.xhs.net.IdentityGuess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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
    private companion object {
        /**
         * How often the VIP validity is re-checked while 自动切换 is on.
         *
         * 5s — down from 30s — so an expiring window is caught promptly instead of
         * up to half a minute late.
         *
         * This is only safe because the check is **free in the common case**: it
         * decides from the cached expiry window first and returns without a request
         * while that window still has time left (see `XhsRepository.switchToVipAccount`).
         * A shorter interval therefore costs a cheap local read per tick, not a
         * network round trip. The expensive path — the one that costs ~4s against
         * the server — is still gated by that cache plus the escalating cooldown,
         * and it still creates exactly one identity per attempt and records it
         * immediately.
         *
         * That is what went wrong the first time this was set to 5s: it was an
         * uncompensated loop that hit the server every tick, so it really ran every
         * ~9s and piled up work. The loop is time-compensated and cache-gated now.
         */
        const val VIP_POLL_MS = 5_000L
    }

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

    private val _autoVip = MutableStateFlow(repo.autoSwitchOnVipExpiry)

    fun refreshHistory() {
        _history.value = runCatching { repo.accountHistory() }.getOrDefault(emptyList())
    }

    /** Switch back to an account already used before. */
    fun switchToHistory(entry: HistoryAccount, onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val ok = runCatching { repo.switchGuestTo(entry.identity) }.getOrDefault(false)
            if (ok) {
                // Exempt it from the automatic VIP switch: the user came here on
                // purpose, so this account must not be rotated away a few seconds
                // later just because it has no VIP left. (Rotation also creates
                // accounts, so the previous behaviour was doubly wrong.) Cleared by
                // the next identity change, and re-set on the next history pick.
                repo.manualPickAccount = entry.identity
            }
            refreshLabel()
            refreshVip()
            remember()
            refreshHistory()
            _rotating.value = false
            onToast(
                if (ok) "已切换回 ${entry.name}：该账号不参与 VIP 自动切换"
                else "切换失败，沿用当前账号"
            )
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
            // first launch / any launch: a random identity is chosen and registered
            // automatically, then the VIP-expiry rule (if enabled) is applied
            runCatching { repo.switchToVipAccount() }
            refreshLabel()
            refreshVip()
            remember()
            _rotating.value = false
        }
    }

    /**
     * While 自动切换 is on, re-check the account's VIP validity every
     * [VIP_POLL_MS] (5s) and swap as soon as it lapses (or is about to — see
     * VIP_MIN_REMAINING_S).
     *
     * Driving this off the [autoVip] flow means the first pass starts immediately
     * when the app opens with the toggle already on (satisfying "进入软件先判断一次"),
     * and `collectLatest` cancels the loop the moment the user turns it off.
     *
     * A 5s tick does NOT mean a request every 5s. The decision is made from the
     * CACHED VIP end first (see XhsRepository.switchToVipAccount): a window that is
     * still good returns immediately, so a normal session sends nothing at all and
     * only the expiry moment costs a round-trip. What the shorter tick buys is that
     * the switch happens within seconds of lapsing rather than up to half a minute
     * later. The safety rails for the case where the window HAS run out are the
     * repository's own escalating cooldown and its one-identity-per-attempt rule.
     *
     * Offline / weak-network behaviour: a VIP window is not a live quantity, so
     * there is nothing to gain from asking while there is no usable connection.
     * The loop skips the round entirely when the network is down (no request, no
     * wakeup beyond the tick) and, when a round does fail, waits for the next tick
     * rather than retrying tighter.
     */
    init {
        viewModelScope.launch {
            _autoVip.collectLatest { on ->
                if (!on) return@collectLatest
                while (true) {
                    // Time the work and subtract it, so the cadence is a real 5s
                    // rather than 5s of sleep ON TOP of a network round-trip.
                    // (Uncompensated, a tick that costs ~4s against the server
                    // drifts: the old 5s target actually ticked every ~9s.)
                    val startedAt = System.currentTimeMillis()
                    // Only while the app is actually on screen.
                    //
                    // This loop is owned by the ViewModel, which is alive for as long as
                    // the HOME entry sits in the back stack — i.e. also while the app is
                    // in the background. A poll that keeps ticking there is network
                    // activity nobody can see the result of, and on a lapsed VIP window
                    // it is the one path that still talks to the server (and can even
                    // register a new account) with the screen off. The first pass on
                    // entering the app is covered separately by ensureFreshGuest(), and
                    // the window is not a live quantity, so nothing is lost by waiting
                    // for the next tick after the app comes back.
                    val visible = App.INSTANCE.appForeground.value
                    if (visible && repo.hasNetwork()) {
                        val switched = runCatching { repo.switchToVipAccount() }.getOrDefault(false)
                        if (switched) {
                            refreshLabel()
                            refreshVip()
                            remember()
                        }
                    }
                    val elapsed = System.currentTimeMillis() - startedAt
                    delay((VIP_POLL_MS - elapsed).coerceAtLeast(0L))
                }
            }
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
            remember()
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
        refreshHistory()
    }
    /** Re-check on demand (e.g. when the profile page loads). */
    fun checkVipExpiry() {
        if (!_autoVip.value) return
        viewModelScope.launch {
            if (runCatching { repo.switchToVipAccount() }.getOrDefault(false)) {
                refreshLabel()
                refreshVip()
                remember()
            }
        }
    }

    /** Manual switch to the next pooled account. */
    fun rotate(onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val ok = runCatching { repo.rotateGuest() }.getOrNull()?.optInt("result") == 1
            // a deliberately fresh account is disposable again, so any earlier
            // history-pick exemption must not linger
            if (ok) repo.manualPickAccount = ""
            refreshLabel()
            refreshVip()
            // Record the account now in use. Every other switch path did this; this
            // one did not, so a manually switched-to account never entered 历史账号
            // and could not be switched back to.
            remember()
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
            // same reasoning as rotate(): the user asked for a new account, so no
            // history-pick exemption applies to it
            if (ok) repo.manualPickAccount = ""
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