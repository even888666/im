package cn.android666.im.data.org

import cn.android666.im.data.session.SessionContext
import cn.android666.im.data.session.SessionStep
import cn.android666.im.data.session.StepResult

/**
 * 登录后步骤：组织架构增量同步（协议规范 §2.2 第 9 步）。服务器不支持组织架构（disco 中没有 org feature）时跳过。
 * 流恢复后不执行（服务器会话保持，回到前台时由 [OrgRepository.syncIfStale] 按时间间隔同步）。
 *
 * @property repository 组织架构
 */
class OrgSyncStep(private val repository: OrgRepository) : SessionStep {

    override val name: String = "组织架构同步"

    /**
     * 执行同步。网络错误向上抛出（重连后重新执行全部步骤），其他错误由 SessionManager 记录后跳过。
     *
     * @param context 会话上下文
     * @return 总是继续下一步
     */
    override suspend fun run(context: SessionContext): StepResult {
        if (context.capabilities.org) {
            repository.sync()
        }
        return StepResult.Continue
    }
}
