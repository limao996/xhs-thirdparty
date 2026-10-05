package com.thirdparty.xhs.common

import kotlinx.coroutines.CancellationException

/**
 * 和 [runCatching] 一样，但**不吞协程取消**。
 *
 * 为什么需要它：Kotlin 的 `runCatching` 会捕获 `Throwable`，于是把 `CancellationException`
 * 也一起吃掉 —— 协程被取消时本该顺着栈往上抛、让结构化并发收尾，结果被转成一个"失败结果"，
 * 调用方继续走错误分支：页面已经关了还在改状态、在已取消的作用域里继续发请求。
 *
 * 规矩：**在 `suspend` 函数里包可能挂起的调用，用这个；**纯同步的解析/计算仍然可以用 `runCatching`
 * （那里压根不会有 `CancellationException`）。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
