// Ported from the upstream LGPL-3.0 project (see NOTICE)

package com.rc.harken.net.model

import kotlinx.serialization.Serializable

/** Logged-in user profile (`/w/nuser/account/get` → `profile`). */
@Serializable
data class UserProfile(
    @Serializable(with = LenientLongSerializer::class)
    val userId: Long,
    val nickname: String = "",
    val avatarUrl: String? = null,
    val backgroundUrl: String? = null,
    val signature: String? = null,
    @Serializable(with = LenientIntSerializer::class)
    val vipType: Int = 0,
    // 以下 4 项仅在部分账号/端点回传；服务端不回时为 null（不得填假数据），
    // UI 据此走「兜底三项」分支。LenientNullableInt 容错数字/字符串/null。
    @Serializable(with = LenientNullableIntSerializer::class)
    val level: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val follows: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val followeds: Int? = null,
    @Serializable(with = LenientNullableIntSerializer::class)
    val listenSongs: Int? = null,
)
