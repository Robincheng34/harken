// Ported from kumone by missuo (LGPL-3.0)

package im.missuo.kumone.net.model

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
)
