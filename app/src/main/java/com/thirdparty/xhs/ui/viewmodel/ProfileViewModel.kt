package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.UserProfile
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.thirdparty.xhs.common.runCatchingCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop

data class ProfileUiState(
    val profile: UserProfile? = null,
    val loading: Boolean = true,
    /** true when the account request failed (so we are not just "a plain guest") */
    val error: Boolean = false,
    val guestHash: String = "",
    val savedCount: Int = 0,
    val historyCount: Int = 0,
    val followedCount: Int = 0
)

class ProfileViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(ProfileUiState(guestHash = repo.currentGuestHash()))
    val ui: StateFlow<ProfileUiState> = _ui.asStateFlow()

    init {
        // 线路在国内、接口在海外：没开 VPN 时首屏必然失败。网络一恢复（开 VPN = 新的默认网络）
        // `App.networkEpoch` 会 +1，这里自动补一次 —— 用户不用手动点重试。
        viewModelScope.launch {
            com.thirdparty.xhs.App.INSTANCE.networkEpoch.drop(1).collect { 
                if (_ui.value.error || _ui.value.profile == null) load() }
        }

        load()
        // the 关注 count must follow follows made on other screens
        viewModelScope.launch {
            repo.followVersion.collect {
                val n = repo.followedAuthors().size
                _ui.update { it.copy(followedCount = n) }
            }
        }
    }

    /** Re-readable counts; called when the profile screen is shown. */
    fun load() {
        viewModelScope.launch {
            val profile = runCatchingCancellable { repo.myProfile() }.getOrNull()
            val saved = repo.savedList().size
            val history = repo.history().size
            val followed = repo.followedAuthors().size
            _ui.value = ProfileUiState(
                profile = profile,
                loading = false,
                // a null profile here means the request failed; without this the
                // page looked like a plain guest with 0 everywhere
                error = profile == null,
                guestHash = repo.currentGuestHash(),
                savedCount = saved,
                historyCount = history,
                followedCount = followed
            )
        }
    }
}