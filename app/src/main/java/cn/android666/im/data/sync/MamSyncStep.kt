package cn.android666.im.data.sync

import cn.android666.im.data.session.SessionContext
import cn.android666.im.data.session.SessionStep
import cn.android666.im.data.session.StepResult

/**
 * 登录后步骤：消息漫游补拉（协议规范 §2.2 第 12 步）。服务器不支持 MAM 时跳过。
 * 流恢复后也执行（协议规范 §2.3：流恢复无需重新执行第 3 ~ 11 步，但仍需补拉）。
 *
 * @property syncer 补拉器
 */
class MamSyncStep(private val syncer: MamSyncer) : SessionStep {

    override val name: String = "消息补拉"

    override val runOnResume: Boolean get() = true

    /**
     * 执行补拉。
     *
     * @param context 会话上下文
     * @return 总是继续
     */
    override suspend fun run(context: SessionContext): StepResult {
        if (context.capabilities.mam) syncer.syncSelf()
        return StepResult.Continue
    }
}
