package cn.android666.im.data.session

import cn.android666.im.core.xmpp.ServerCapabilities

/**
 * 登录后执行的一个步骤（协议规范 §2.2 / 附录 B）。各功能模块（组织架构同步、群列表、MAM 补拉等）
 * 实现本接口并注册到 [SessionManager]，按注册顺序执行。
 */
interface SessionStep {

    /** 步骤名称，写日志用 */
    val name: String

    /**
     * 流恢复（XEP-0198 resume）后是否也要执行。流恢复时服务器保留了会话状态，只需补拉消息
     * （协议规范 §2.3），所以大多数步骤为 false。
     */
    val runOnResume: Boolean get() = false

    /**
     * 执行步骤。
     *
     * 失败处理：抛出 [cn.android666.im.core.xmpp.XmppException] 中的连接类异常（未连接、连接失败、超时）时，
     * 中止本轮步骤，等重连后重新执行；其他异常记录日志后跳过本步骤、继续后面的步骤（单个功能失败不影响登录）。
     *
     * @param context 会话上下文
     * @return 执行结果
     */
    suspend fun run(context: SessionContext): StepResult
}

/** 步骤执行结果 */
sealed interface StepResult {

    /** 继续执行下一步 */
    data object Continue : StepResult

    /** 需要强制改密：暂停后续步骤，改密成功后从下一步继续（协议规范 §2.2 第 6 步） */
    data object MustChangePassword : StepResult
}

/**
 * 步骤之间共享的会话信息。
 *
 * @property username 当前登录的工号
 */
class SessionContext(val username: String) {

    /** 服务器能力，由能力发现步骤填写；之前的步骤读到的是"全部不支持" */
    @Volatile
    var capabilities: ServerCapabilities = ServerCapabilities(emptySet())
}
