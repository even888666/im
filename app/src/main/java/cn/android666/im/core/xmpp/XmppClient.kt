package cn.android666.im.core.xmpp

import kotlinx.coroutines.flow.StateFlow

/**
 * XMPP 客户端抽象（Android工程架构.md §4.2）。业务代码只依赖此接口，Smack 实现位于
 * `core.xmpp.smack`；单元测试中替换为 `FakeXmppClient`。
 *
 * 所有方法都是 suspend 函数，内部负责切换到 IO 线程，调用方可在任意线程调用。
 * 失败时抛出 [XmppException]。
 *
 * 消息收发、IQ 请求等能力随功能开发逐步加入（开发计划 M2 ~ M7）。
 */
interface XmppClient {

    /** 当前连接状态 */
    val connectionState: StateFlow<ConnectionState>

    /**
     * 登录：建立连接、TLS、SASL 认证、绑定 resource、启用流管理（协议规范 §2.2 第 1、2 步）。
     * 已有连接时先断开旧连接。
     *
     * @param credential 登录凭证
     * @return 登录结果（首次登录时 resumed 恒为 false）
     * @throws XmppException 认证失败、网络失败、TLS 失败等
     */
    suspend fun login(credential: Credential): LoginResult

    /**
     * 断线后重新连接：优先使用 XEP-0198 流恢复，恢复失败时用上次的凭证完整重新登录（协议规范 §2.3）。
     *
     * @return 登录结果，resumed 表示是否恢复了原会话
     * @throws XmppException 失败时抛出；从未登录过时抛出 [XmppException.NotConnected]
     */
    suspend fun reconnect(): LoginResult

    /**
     * 退出登录：正常关闭连接（发送 `</stream>`，服务器立即释放会话），状态变为 [ConnectionState.Idle]。
     * 未连接时什么也不做。
     */
    suspend fun logout()
}
