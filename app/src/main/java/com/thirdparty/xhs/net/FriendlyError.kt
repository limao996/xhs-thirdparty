package com.thirdparty.xhs.net

/**
 * 把底层异常翻成用户能看懂的一句话。
 *
 * 检查更新与下载的失败原因都会直接显示给用户，所以不能把 OkHttp / Java 的英文原文
 * （更不该把异常类名）丢到界面上。
 */
internal fun friendlyNetworkReason(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "无法连接服务器，请检查网络"
    is java.net.SocketTimeoutException -> "连接超时，请重试"
    is java.net.ConnectException -> "无法连接服务器，请检查网络"
    is javax.net.ssl.SSLException -> "连接不安全，已中止"
    is java.io.IOException -> "网络中断，请重试"
    else -> "请稍后重试"
}
