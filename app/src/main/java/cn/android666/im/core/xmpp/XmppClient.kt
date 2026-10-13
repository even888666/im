package cn.android666.im.core.xmpp

import cn.android666.im.core.xmpp.iq.IqRequest
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
     * 启用 Message Carbons（XEP-0280，协议规范 §2.2 第 3 步）：本人其他端收发的单聊消息会抄送到本端。
     *
     * @throws XmppException 未连接、服务器不支持或返回错误
     */
    suspend fun enableCarbons()

    /**
     * 查询服务器时间（XEP-0202，协议规范 §2.2 第 4 步），用于计算本地与服务器的时钟偏差。
     *
     * @return 服务器时间与本次请求的耗时
     * @throws XmppException 未连接、超时或服务器返回错误
     */
    suspend fun queryServerTime(): ServerTime

    /**
     * 能力发现（XEP-0030，协议规范 §1.7、§2.2 第 5 步）：查询服务器域名的 feature，以及文件上传服务的大小上限（§9）。
     *
     * @return 服务器能力
     * @throws XmppException 未连接、超时或服务器返回错误
     */
    suspend fun discoverCapabilities(): ServerCapabilities

    /**
     * 发送初始在线状态（协议规范 §2.2 第 8 步）。服务器收到后才开始投递离线消息，
     * 所以必须在获取 Roster 之后、开始收消息之前调用。
     *
     * @throws XmppException 未连接
     */
    suspend fun sendInitialPresence()

    /**
     * 发送自定义 IQ 并等待结果（协议规范中的所有自定义 IQ，Android工程架构.md §4.2）。
     *
     * @param request 请求
     * @return 解析后的结果
     * @throws XmppException 未连接、超时、服务器返回错误（[XmppException.StanzaError]，含自定义错误码）、
     * 响应格式错误（[XmppException.InvalidResponse]）
     */
    suspend fun <T> sendIq(request: IqRequest<T>): T

    /**
     * 立即检测连接是否仍然可用（发送 XEP-0199 ping，10 秒内无响应视为失效）。
     * 用于切换网络后：旧网络上的 TCP 连接通常已经失效，但要等心跳超时才会发现，主动检测能更快重连。
     * 检测失败时连接会被断开，状态变为 [ConnectionState.Disconnected]（网络原因），由重连逻辑接手。
     *
     * @return 连接可用返回 true；未连接或检测失败返回 false
     */
    suspend fun checkAlive(): Boolean

    /**
     * 退出登录：正常关闭连接（发送 `</stream>`，服务器立即释放会话），状态变为 [ConnectionState.Idle]。
     * 未连接时什么也不做。
     */
    suspend fun logout()
}
