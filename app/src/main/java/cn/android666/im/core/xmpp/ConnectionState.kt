package cn.android666.im.core.xmpp

/**
 * XMPP 连接状态（见 Android工程架构.md §4.2），由 [XmppClient.connectionState] 发布，
 * 界面顶部的"连接中 / 未连接"提示和重连逻辑都依据它。
 */
sealed interface ConnectionState {

    /** 未连接：尚未登录，或已主动退出登录 */
    data object Idle : ConnectionState

    /** 连接中：正在建立连接、TLS 协商或认证（含重连） */
    data object Connecting : ConnectionState

    /**
     * 已认证，可以收发消息。
     *
     * @property resumed 是否通过 XEP-0198 流恢复回到了原会话。流恢复时服务器保留了会话状态，
     * 只需补拉消息，不需要重新执行登录后的全部步骤（协议规范 §2.3）
     */
    data class Authenticated(val resumed: Boolean) : ConnectionState

    /**
     * 连接已断开。
     *
     * @property reason 断开原因，决定是否自动重连
     */
    data class Disconnected(val reason: DisconnectReason) : ConnectionState
}

/**
 * 连接断开的原因（协议规范 §2.4）。
 */
sealed interface DisconnectReason {

    /**
     * 是否应该自动重连。被挤下线、账号被禁用、认证失败时重连没有意义，
     * 只会反复失败或把另一台设备挤下线，必须回到登录页由用户处理。
     */
    val shouldReconnect: Boolean

    /**
     * 网络原因（断网、超时、服务器重启等），可以重连。
     *
     * @property cause 底层异常，用于写日志；可能为 null
     */
    data class Network(val cause: Throwable?) : DisconnectReason {
        override val shouldReconnect: Boolean = true
    }

    /** 同一端（resource）在其他设备登录，服务器发送 stream error `<conflict/>` */
    data object Conflict : DisconnectReason {
        override val shouldReconnect: Boolean = false
    }

    /** 账号被管理员禁用：stream error `<policy-violation/>` + `<account-disabled xmlns='urn:android666:im:error:1'/>` */
    data object AccountDisabled : DisconnectReason {
        override val shouldReconnect: Boolean = false
    }

    /** 重连时认证失败（例如在其他设备上改了密码），需要用户重新输入密码 */
    data object AuthenticationFailed : DisconnectReason {
        override val shouldReconnect: Boolean = false
    }

    /**
     * 其他 stream error（服务器关闭、系统维护等），可以重连。
     *
     * @property condition stream error 的条件名，例如 `system-shutdown`
     */
    data class StreamError(val condition: String) : DisconnectReason {
        override val shouldReconnect: Boolean = true
    }
}
