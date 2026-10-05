package com.thirdparty.xhs.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.thirdparty.xhs.App
import com.thirdparty.xhs.data.XhsRepository

/**
 * Simple ViewModel factory that supplies the shared [XhsRepository] to
 * ViewModels taking it as a constructor arg. Manual DI keeps the build light
 * (no Hilt/KSP churn) while staying testable.
 */
class RepoViewModelFactory(
    private val repo: XhsRepository = App.repo
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        // `parameterTypes.size`，不是 `parameterCount`：后者是 API 26 才有的方法，
        // 在 API 24/25 上会抛 NoSuchMethodError（lint: NewApi）。这里不用它。
        val ctor = modelClass.constructors.firstOrNull { it.parameterTypes.size == 1 }
            ?: modelClass.constructors.firstOrNull()
            ?: throw IllegalArgumentException("No compatible ctor for ${modelClass.name}")
        val arg = if (ctor.parameterTypes.firstOrNull() == XhsRepository::class.java)
            repo else null
        @Suppress("UNCHECKED_CAST")
        return ctor.newInstance(arg) as T
    }
}