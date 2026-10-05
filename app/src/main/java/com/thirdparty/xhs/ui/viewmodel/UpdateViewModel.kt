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
 * 「检查更新」页的状态。
 *
 * 这是独立的页面（路由 `update`）：进页面自动查一次，之后由用户点「重新检查」。
 * 没有任何后台轮询 —— 应用只在用户进这个页面的时候访问 github.com。
 */
data class UpdateUiState(
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val checking: Boolean = false,
    val result: UpdateChecker.Result? = null,
    val checkedAt: Long = 0L
)

class UpdateViewModel : ViewModel() {

    private val _ui = MutableStateFlow(UpdateUiState())
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    private var entered = false

    /** 每次进入页面调用一次；本页面的存在意义就是检查，所以直接查。 */
    fun onEnter() {
        if (entered) return
        entered = true
        check()
    }

    fun check() {
        // 正在查就忽略重复点击：OkHttp 那边一次请求要几百毫秒到十几秒
        if (_ui.value.checking) return
        _ui.update { it.copy(checking = true, result = null) }
        viewModelScope.launch {
            // finally 复位：UpdateChecker 只兜 IOException/JSONException，别的异常（比如 OOM、
            // 意料外的 RuntimeException）会让 `checking` 永久停在 true，按钮就再也点不动了（审计 P2）。
            val result = try {
                UpdateChecker.check(_ui.value.versionName)
            } finally {
                // 取消也要复位，否则取消后同样卡住
                if (_ui.value.checking) {
                    _ui.update { it.copy(checking = false) }
                }
            }
            _ui.update {
                it.copy(checking = false, result = result, checkedAt = System.currentTimeMillis())
            }
        }
    }
}
