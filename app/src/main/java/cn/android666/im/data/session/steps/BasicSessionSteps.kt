package cn.android666.im.data.session.steps

import cn.android666.im.core.common.ServerClock
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.OwnPresence
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import cn.android666.im.core.xmpp.extension.account.AccountStatusRequest
import cn.android666.im.data.session.SessionContext
import cn.android666.im.data.session.SessionStep
import cn.android666.im.data.session.StepResult

/**
 * 第 3 步：启用 Message Carbons（XEP-0280），本人其他端收发的单聊消息会抄送到本端。
 *
 * @property client XMPP 客户端
 */
class EnableCarbonsStep(private val client: XmppClient) : SessionStep {
    override val name = "启用 Carbons"

    override suspend fun run(context: SessionContext): StepResult {
        client.enableCarbons()
        return StepResult.Continue
    }
}

/**
 * 第 4 步：查询服务器时间（XEP-0202），校准时钟偏差，之后的实时消息时间以服务器时间为准（协议规范 §1.4）。
 *
 * @property client XMPP 客户端
 * @property serverClock 服务器时间
 */
class ServerTimeStep(private val client: XmppClient, private val serverClock: ServerClock) : SessionStep {
    override val name = "校准服务器时间"

    private val log = Logs.file(TAG)

    override suspend fun run(context: SessionContext): StepResult {
        val time = client.queryServerTime()
        val offset = serverClock.calibrate(time.serverMillis, time.requestSentAtMillis, time.roundTripMillis)
        log.info("时钟偏差（服务器 − 本地）={}ms，往返 {}ms", offset, time.roundTripMillis)
        return StepResult.Continue
    }

    private companion object {
        const val TAG = "ServerTimeStep"
    }
}

/**
 * 第 5 步：能力发现（协议规范 §1.7），结果保存到 [SessionContext.capabilities]，供后续步骤和界面判断功能是否可用。
 *
 * @property client XMPP 客户端
 */
class CapabilitiesStep(private val client: XmppClient) : SessionStep {
    override val name = "能力发现"

    private val log = Logs.file(TAG)

    override suspend fun run(context: SessionContext): StepResult {
        val capabilities = client.discoverCapabilities()
        context.capabilities = capabilities
        if (capabilities.missing.isNotEmpty()) {
            log.warn("服务器缺少以下能力，相应功能将隐藏：{}", capabilities.missing)
        }
        log.info("文件上传上限={}", capabilities.maxUploadFileSize ?: "无上传服务")
        return StepResult.Continue
    }

    private companion object {
        const val TAG = "CapabilitiesStep"
    }
}

/**
 * 第 6 步：查询账号状态（协议规范 §3.1）。需要强制改密时暂停后续步骤。
 * 服务器没有组织架构插件（能力发现中没有 account feature）时跳过。
 *
 * @property client XMPP 客户端
 */
class AccountStatusStep(private val client: XmppClient) : SessionStep {
    override val name = "查询账号状态"

    private val log = Logs.file(TAG)

    override suspend fun run(context: SessionContext): StepResult {
        if (!context.capabilities.account) {
            log.warn("服务器不支持账号状态查询（组织架构插件未安装），跳过")
            return StepResult.Continue
        }
        val status = try {
            client.sendIq(AccountStatusRequest())
        } catch (e: XmppException.StanzaError) {
            log.warn("查询账号状态失败，跳过：{}", e.message)
            return StepResult.Continue
        }
        log.info("账号状态：需要改密={}", status.mustChangePassword)
        return if (status.mustChangePassword) StepResult.MustChangePassword else StepResult.Continue
    }

    private companion object {
        const val TAG = "AccountStatusStep"
    }
}

/**
 * 第 8 步：发送初始在线状态，服务器开始投递离线消息（协议规范 §2.2）。获取 Roster（第 7 步，RosterStep）注册在本步骤之前。
 *
 * @property client XMPP 客户端
 * @property presence 本人当前的在线状态（用户设置的忙碌、开会等，开发计划 3.20），默认在线
 */
class InitialPresenceStep(
    private val client: XmppClient,
    private val presence: suspend () -> OwnPresence = { OwnPresence.AVAILABLE },
) : SessionStep {
    override val name = "发送初始 presence"

    override suspend fun run(context: SessionContext): StepResult {
        client.sendPresence(presence())
        return StepResult.Continue
    }
}
