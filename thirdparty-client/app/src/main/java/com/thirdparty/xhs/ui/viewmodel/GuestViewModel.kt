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
        /** how many freshly generated random ids have been probed so far */
        val randomTried: Int = 0,
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

    /**
     * Manual switch, random-id first.
     *
     * Tries a freshly generated random device id, and only when the backend
     * refuses it (verified: it never issues accounts for new ids, so this is the
     * normal outcome) falls back to a randomly chosen known-good account. The
     * random path is kept so the app starts using such an id automatically if the
     * backend ever begins handing out accounts.
     */
    fun switchRandom(onToast: (String) -> Unit = {}) {
        if (_rotating.value) return
        viewModelScope.launch {
            _rotating.value = true
            val fresh = repo.freshRandomMac()
            val freshProbe = runCatching { repo.probeAccount(fresh) }.getOrNull()
            val usedFresh = if (freshProbe != null) {
                runCatching { repo.switchGuestTo(freshProbe.mac) }.getOrDefault(false)
            } else false
            val mac = if (usedFresh) freshProbe!!.mac else repo.randomDeviceMac()
            val ok = if (usedFresh) true
            else runCatching { repo.switchGuestTo(mac) }.getOrDefault(false)
            refreshLabel()
            refreshVip()
            _rotating.value = false
            onToast(
                when {
                    !ok -> "切换失败，沿用当前账号"
                    usedFresh -> "已切换到随机新账号 ${freshProbe!!.userId}"
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
        // Random ids first, as requested. Verified: the backend answers
        // `result=-1 用戶ID錯誤` for every freshly generated id, so the known-good
        // identities are interleaved — otherwise the scan could never find
        // anything. The random attempts are kept so a backend that starts
        // issuing accounts for new ids is picked up with no code change.
        val randomCount = 24
        val randomIds = List(randomCount) { repo.freshRandomMac() }
        val candidates = buildList {
            val known = repo.knownDeviceMacs().shuffled().iterator()
            randomIds.forEach { r ->
                add(r)
                if (known.hasNext()) add(known.next())
            }
            while (known.hasNext()) add(known.next())
        }
        _scan.value = ScanState(running = true, total = candidates.size)
        viewModelScope.launch {
            val found = mutableListOf<AccountProbe>()
            var triedRandom = 0
            for ((i, mac) in candidates.withIndex()) {
                if (randomIds.contains(mac)) triedRandom++
                _scan.update { it.copy(done = i, scanning = mac, randomTried = triedRandom) }
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
                                randomTried = triedRandom,
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
            _scan.update {
                it.copy(running = false, done = candidates.size, scanning = "", randomTried = triedRandom)
            }
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