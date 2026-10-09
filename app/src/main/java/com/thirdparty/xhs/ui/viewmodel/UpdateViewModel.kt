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
    /**
     * 显示用：「正在检查…」。初值 true（进页面就会查一次，否则第一帧闪「尚未检查」）。
     * **不要拿它当重入锁** —— 防重入用 `UpdateViewModel.inFlight`。
     */
    val checking: Boolean = true,
    val result: UpdateChecker.Result? = null,
    val checkedAt: Long = 0L
)

class UpdateViewModel : ViewModel() {

    private val _ui = MutableStateFlow(UpdateUiState())
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    private var entered = false

    /**
     * 「真的有一次请求在路上」——**只有它在防重入**。
     *
     * 为什么不用 `_ui.value.checking` 当判据：那是**显示用**的状态，初值是 true（为了不闪
     * 「尚未检查」）。2026-10-10 我把它当成了重入锁，结果进页面时 `check()` 一看 checking 是 true
     * 就直接 return —— 请求根本没发出去，页面永远停在「正在检查」（用户报的就是这个，而且是发出去的
     * 正式包 v1.3.2）。同一条教训在"推荐页一直 loading"那次已经吃过：**loading 类标记只用于显示，
     * 不参与"要不要发起"的判断**。
     */
    private var inFlight = false

    /** 每次进入页面调用一次；本页面的存在意义就是检查，所以直接查。 */
    fun onEnter() {
        if (entered) return
        entered = true
        check()
    }

    fun check() {
        // 正在查就忽略重复点击：OkHttp 那边一次请求要几百毫秒到十几秒
        if (inFlight) return
        inFlight = true
        _ui.update { it.copy(checking = true, result = null) }
        viewModelScope.launch {
            // finally 复位：UpdateChecker 只兜 IOException/JSONException，别的异常（比如 OOM、
            // 意料外的 RuntimeException）会让 `checking` 永久停在 true，按钮就再也点不动了（审计 P2）。
            val result = try {
                UpdateChecker.check(_ui.value.versionName)
            } finally {
                // 取消也要复位，否则取消后同样卡住
                inFlight = false
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
