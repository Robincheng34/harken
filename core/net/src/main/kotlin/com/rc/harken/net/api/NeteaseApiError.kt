// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.api

/**
 * Every protocol-layer failure (iOS `NeteaseAPIError`).
 *
 * - [Http] — non-2xx transport status
 * - [Business] — JSON `code != 200` (301 maps to [NeedLogin])
 * - [NeedLogin] — credential required
 * - [Decoding] — response body did not match the model
 * - [Network] — transport-level failure
 */
sealed class NeteaseApiError : Exception() {
    data class Http(val status: Int) : NeteaseApiError()

    data class Business(val code: Int, override val message: String?) : NeteaseApiError()

    object NeedLogin : NeteaseApiError()

    data class Decoding(val detail: String) : NeteaseApiError()

    data class Network(override val cause: Throwable) : NeteaseApiError()
}

/** Chinese, user-facing mapping used by toasts and error states. */
fun NeteaseApiError.userMessage(): String = when (this) {
    is NeteaseApiError.Http -> "网络错误 ($status)"
    is NeteaseApiError.Business -> message ?: "接口错误 ($code)"
    NeteaseApiError.NeedLogin -> "需要登录"
    is NeteaseApiError.Decoding -> "数据加载失败，请稍后重试"
    is NeteaseApiError.Network -> "网络连接失败，请稍后重试"
}
