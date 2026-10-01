package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.UserProfile
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val profile: UserProfile? = null,
    val loading: Boolean = true,
    val guestHash: String = "",
    val savedCount: Int = 0,
    val historyCount: Int = 0,
    val followedCount: Int = 0
)

class ProfileViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _ui = MutableStateFlow(ProfileUiState(guestHash = repo.currentGuestHash()))
    val ui: StateFlow<ProfileUiState> = _ui.asStateFlow()

    init { load() }

    /** Re-readable counts; called when the profile screen is shown. */
    fun load() {
        viewModelScope.launch {
            val profile = runCatching { repo.myProfile() }.getOrNull()
            val saved = repo.savedList().size
            val history = repo.history().size
            val followed = repo.followedAuthors().size
            _ui.value = ProfileUiState(
                profile = profile,
                loading = false,
                guestHash = repo.currentGuestHash(),
                savedCount = saved,
                historyCount = history,
                followedCount = followed
            )
        }
    }
}