package cn.android666.im.data.contact

import cn.android666.im.data.session.SessionContext
import cn.android666.im.data.session.SessionStep
import cn.android666.im.data.session.StepResult

/**
 * 登录后步骤：获取花名册（协议规范 §2.2 第 7 步，在发送初始 presence 之前）。
 * 流恢复后不执行（服务器会话保持，期间的变化已通过花名册推送收到）。
 *
 * @property repository 常用联系人
 */
class RosterStep(private val repository: ContactRepository) : SessionStep {

    override val name: String = "获取花名册"

    /**
     * 获取花名册。
     *
     * @param context 会话上下文
     * @return 总是继续下一步
     */
    override suspend fun run(context: SessionContext): StepResult {
        repository.refresh()
        return StepResult.Continue
    }
}
