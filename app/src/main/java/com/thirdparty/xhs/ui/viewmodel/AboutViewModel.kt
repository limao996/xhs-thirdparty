package com.thirdparty.xhs.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thirdparty.xhs.BuildConfig
import com.thirdparty.xhs.net.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「关于」页的状态：版本信息 + 一次的更新检查结果。
 *
 * 检查是用户手动触发的（进页面时若从设置页的「检查更新」进来会立刻跑一次），
 * 不做后台轮询 —— 应用只在用户点的时候访问 github.com。
 */
data class AboutUiState(
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val clientVersion: String = BuildConfig.CLIENT_VERSION,
    val clientChannel: String = BuildConfig.CLIENT_CHANNEL,
    val checking: Boolean = false,
    val result: UpdateChecker.Result? = null,
    val checkedAt: Long = 0L
)

class AboutViewModel : ViewModel() {

    private val _ui = MutableStateFlow(AboutUiState())
    val ui: StateFlow<AboutUiState> = _ui.asStateFlow()

    private var entered = false

    /** 每次进入页面调用一次；[autoCheck] 为真时立刻查（设置页的「检查更新」入口）。 */
    fun onEnter(autoCheck: Boolean) {
        if (entered) return
        entered = true
        if (autoCheck) check()
    }

    fun check() {
        if (_ui.value.checking) return
        _ui.update { it.copy(checking = true, result = null) }
        viewModelScope.launch {
            val result = UpdateChecker.check(_ui.value.versionName)
            _ui.update {
                it.copy(checking = false, result = result, checkedAt = System.currentTimeMillis())
            }
        }
    }
}
