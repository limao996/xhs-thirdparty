package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.data.XhsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the "fresh guest account every launch" requirement and exposes the
 * current guest ID for the top bar (e.g. "游客 3684088").
 */
class GuestViewModel(private val repo: XhsRepository) : ViewModel() {

    private val _accountLabel = MutableStateFlow("游客ID：加载中…")
    val accountLabel: StateFlow<String> = _accountLabel.asStateFlow()

    private val _rotating = MutableStateFlow(false)
    val rotating: StateFlow<Boolean> = _rotating.asStateFlow()

    private val _applied = MutableStateFlow(false)
    val applied: StateFlow<Boolean> = _applied.asStateFlow()

    /** Called once on cold start: fetch a brand-new guest credential. */
    fun ensureFreshGuest(onToast: (String) -> Unit = {}) {
        if (_applied.value) return
        _applied.value = true
        viewModelScope.launch {
            _rotating.value = true
            refreshLabel()
            val ok = runCatching { repo.rotateGuest() }
                .getOrNull()?.optInt("result") == 1
            _rotating.value = false
            if (ok) onToast("已切换新的游客账号")
            refreshLabel()
        }
    }

    /** Manual rotate triggered from the top bar. */
    fun rotate(onToast: (String) -> Unit = {}) {
        viewModelScope.launch {
            _rotating.value = true
            val ok = runCatching { repo.rotateGuest() }
                .getOrNull()?.optInt("result") == 1
            _rotating.value = false
            onToast(if (ok) "已切换新的游客账号" else "切换失败，沿用当前账号")
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