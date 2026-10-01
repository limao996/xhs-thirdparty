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

/**
 * Guest account state: the current account, manual switching, and the VIP scan.
 *
 * The backend never creates accounts (see CredentialStore), so the app keeps the
 * guest it already has and only changes when the user asks. Switching is manual
 * and may reuse any previously seen account.
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

    // ---- VIP scan -----------------------------------------------------------

    data class ScanState(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val scanning: String = "",
        val found: List<AccountProbe> = emptyList(),
        /** set when the scan stopped early because it hit a VIP account */
        val stoppedAtVip: AccountProbe? = null,
        /** whether the app managed to log in as that VIP account */
        val switched: Boolean = false
    )

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

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

    /** Manual switch to a random pooled account (may repeat an older one). */
    fun switchRandom(onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val mac = repo.randomDeviceMac()
            val ok = runCatching { repo.switchGuestTo(mac) }.getOrDefault(false)
            refreshLabel()
            refreshVip()
            _rotating.value = false
            onToast(if (ok) "已随机切换游客账号" else "切换失败，沿用当前账号")
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

    /**
     * Scan candidate identities and STOP at the first one carrying VIP, switching
     * to it immediately — no need to walk the whole candidate list.
     *
     * Probing never touches the session in use (see XhsApi.probeAccount), and
     * every identity a probe confirms is persisted by CredentialStore, so the
     * account pool grows with use instead of staying a fixed hardcoded list.
     * The candidate order is shuffled so repeated scans do not repeat the same
     * path.
     */
    fun startScan() {
        if (_scan.value.running) return
        val candidates = CredentialStore.SCAN_CANDIDATES.shuffled()
        _scan.value = ScanState(running = true, total = candidates.size)
        viewModelScope.launch {
            val found = mutableListOf<AccountProbe>()
            for ((i, mac) in candidates.withIndex()) {
                _scan.update { it.copy(done = i, scanning = mac) }
                val probe = runCatching { repo.probeAccount(mac) }.getOrNull()
                if (probe != null) {
                    found.add(probe)
                    _scan.update { it.copy(found = found.sortedByDescending { p -> p.isVip }) }
                    if (probe.isVip) {
                        // found a VIP — log in with it and stop scanning
                        val switched = runCatching { repo.switchGuestTo(probe.mac) }
                            .getOrDefault(false)
                        refreshLabel()
                        refreshVip()
                        _scan.update {
                            it.copy(
                                running = false,
                                done = i + 1,
                                scanning = "",
                                stoppedAtVip = probe,
                                switched = switched
                            )
                        }
                        return@launch
                    }
                }
                // be gentle: this is a lot of sequential requests
                kotlinx.coroutines.delay(120)
            }
            _scan.update { it.copy(running = false, done = candidates.size, scanning = "") }
        }
    }

    fun clearScan() {
        if (_scan.value.running) return
        _scan.value = ScanState()
    }

    /** The MAC of the identity currently in use (for marking the scan list row). */
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