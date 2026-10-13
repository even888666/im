package cn.android666.im.data.session

import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.ServerCapabilities

/**
 * 会话状态（登录流程的业务状态），与连接状态（连接中 / 已连接 / 断开）分开：
 * 连接状态用于界面顶部的提示条，会话状态决定显示哪个页面。
 */
sealed interface SessionState {

    /** 未登录：显示登录页 */
    data object LoggedOut : SessionState

    /**
     * 登录中或正在执行登录后的步骤（自动登录时界面可以先显示本地数据）。
     *
     * @property username 工号
     */
    data class Starting(val username: String) : SessionState

    /**
     * 必须修改初始密码（协议规范 §3.1）：改密成功前不进入主界面，后续步骤暂停。
     *
     * @property username 工号
     */
    data class MustChangePassword(val username: String) : SessionState

    /**
     * 登录完成，所有步骤已执行（之后断线重连不会离开这个状态，连接情况看连接状态）。
     *
     * @property username 工号
     * @property capabilities 服务器能力，决定哪些功能可用
     */
    data class Ready(val username: String, val capabilities: ServerCapabilities) : SessionState

    /**
     * 会话被结束，需要回到登录页（协议规范 §2.4）：被挤下线、账号被禁用、认证失败。
     *
     * @property username 工号（登录页回显）
     * @property reason 原因，界面据此显示提示
     */
    data class Ended(val username: String, val reason: DisconnectReason) : SessionState
}
