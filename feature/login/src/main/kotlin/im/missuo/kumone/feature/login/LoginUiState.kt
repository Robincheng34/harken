// Ported from kumone by missuo (LGPL-3.0)
//
// 登录页 UI 状态（T17 扩展：短信验证码登录模式）。

package im.missuo.kumone.feature.login

/** 扫码登录阶段。 */
enum class LoginStatus {
    Idle,       // 未开始
    Loading,    // 正在获取二维码
    Waiting,    // 已展示，等待扫描（801）
    Scanned,    // 已扫描，待确认（802）
    Confirmed,  // 登录成功（803）
    Expired,    // 二维码过期（800）
    Error,      // 出错
}

/** 登录方式（iOS `LoginSheet.Mode`）。 */
enum class LoginMode {
    QR,   // 扫码登录
    SMS,  // 手机验证码
}

/**
 * 登录页单一 UI 状态。
 *
 * 扫码字段（[unikey]/[qrUrl]）与短信字段（[phone]/[code]/[smsCooldown]…）分居；
 * [mode] 决定面板展示哪一侧。
 */
data class LoginUiState(
    val mode: LoginMode = LoginMode.QR,
    val status: LoginStatus = LoginStatus.Idle,
    val unikey: String? = null,
    val qrUrl: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val errorMessage: String? = null,
    // MARK: 短信登录
    val phone: String = "",
    val code: String = "",
    /** 发送冷却剩余秒数；0 表示可再次发送。 */
    val smsCooldown: Int = 0,
    val smsSending: Boolean = false,
    val smsLoggingIn: Boolean = false,
    /** 短信通道提示（"验证码已发送" / 风控错误等）。 */
    val smsMessage: String? = null,
) {
    val isTerminal: Boolean get() = status == LoginStatus.Confirmed || status == LoginStatus.Expired

    /** 手机号满 11 位才允许获取验证码（iOS `canSendCode`）。 */
    val canSendSmsCode: Boolean
        get() = !smsSending && smsCooldown == 0 && phone.length >= 11

    /** 手机号 + 4 位以上验证码才允许登录（iOS `canLogin`）。 */
    val canLoginWithSms: Boolean
        get() = !smsLoggingIn && phone.length >= 11 && code.length >= 4
}
