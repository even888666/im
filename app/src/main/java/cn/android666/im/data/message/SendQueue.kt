package cn.android666.im.data.message

import cn.android666.im.core.common.Clock
import cn.android666.im.core.database.ImDatabase
import cn.android666.im.core.database.UserDatabaseHolder
import cn.android666.im.core.database.entity.ConversationEntity
import cn.android666.im.core.database.entity.MessageEntity
import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.XmppException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 发送队列（开发计划 4.3，Android工程架构.md §4.5，协议规范 §6.2）。
 *
 * - 消息先由 MessageRepository 以"发送中"写入数据库，再调用 [wake]；队列按写入顺序逐条发送；
 * - 收到服务器的流管理确认（XEP-0198 `<a/>`）→"已发送"；30 秒内未确认 →"发送失败"（用户可手动重发，沿用原消息 ID）；
 * - 未连接时消息保持"发送中"等待重连；等待超过 30 秒仍未连上 →"发送失败"（决策记录 A73）；
 * - 数据库中的"发送中"就是队列本身：应用被杀后重新登录，打开用户数据库时自动恢复发送；
 * - 切换账号 / 退出登录（用户数据库关闭）时停止，下次打开时重新开始。
 *
 * @property client XMPP 客户端
 * @property databases 当前账号的用户数据库
 * @property clock 时间来源（等待连接的计时，使用单调时间）
 * @property ackTimeoutMillis 等待服务器确认的超时
 * @property connectWaitMillis 未连接时消息最长等待时间
 */
class SendQueue(
    private val client: XmppClient,
    private val databases: UserDatabaseHolder,
    scope: CoroutineScope,
    private val clock: Clock,
    private val ackTimeoutMillis: Long = ACK_TIMEOUT_MILLIS,
    private val connectWaitMillis: Long = CONNECT_WAIT_MILLIS,
) {

    private val log = Logs.file(TAG)

    /** 唤醒信号：有新消息或手动重发（合并为一个，队列每次被唤醒都会重新查询数据库） */
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    init {
        // 每打开一个用户数据库启动一轮队列，数据库关闭或切换时 collectLatest 自动取消上一轮
        scope.launch {
            databases.database.collectLatest { db -> if (db != null) runQueue(db) }
        }
    }

    /** 有新的待发送消息：唤醒队列 */
    fun wake() {
        wakeups.trySend(Unit)
    }

    /**
     * 队列主循环：查询"发送中"的消息，已连接时逐条发送，未连接时等待连接并计时。
     *
     * @param db 当前账号的数据库
     */
    private suspend fun runQueue(db: ImDatabase) {
        val dao = db.messageDao()
        log.info("发送队列启动，待发送 {} 条", dao.pending().size)
        // rowId → 开始等待连接的时间（单调时间）
        val waitingSince = HashMap<Long, Long>()
        while (true) {
            val pending = dao.pending()
            if (pending.isEmpty()) {
                waitingSince.clear()
                wakeups.receive()
                continue
            }
            if (client.connectionState.value is ConnectionState.Authenticated) {
                waitingSince.clear()
                val connectionLost = sendAll(db, pending)
                // 状态还没来得及变成断开时避免空转
                if (connectionLost) delay(RETRY_DELAY_MILLIS)
                continue
            }
            waitForConnection(db, pending, waitingSince)
        }
    }

    /**
     * 按顺序发送。
     *
     * @param db 数据库
     * @param pending 待发送的消息
     * @return 中途发现连接已断开时为 true
     */
    private suspend fun sendAll(db: ImDatabase, pending: List<MessageEntity>): Boolean {
        for (message in pending) {
            if (!sendOne(db, message)) return true
        }
        return false
    }

    /**
     * 发送一条消息并更新状态。
     *
     * @param db 数据库
     * @param message 消息
     * @return false 表示未连接（消息保持"发送中"，等待重连）；其余情况为 true
     */
    private suspend fun sendOne(db: ImDatabase, message: MessageEntity): Boolean {
        val dao = db.messageDao()
        val xml = try {
            buildStanza(db, message)
        } catch (e: IllegalArgumentException) {
            log.error("消息无法生成报文：rowId={}，msgId={}", message.rowId, message.msgId, e)
            dao.markSendFailed(message.rowId, SendError.INVALID)
            return true
        }
        return try {
            val acked = client.sendMessage(xml, ackTimeoutMillis)
            dao.markSent(message.rowId)
            log.info("消息已发送：msgId={}，会话={}，服务器确认={}", message.msgId, message.conversationId, acked)
            true
        } catch (e: XmppException.NotConnected) {
            log.info("发送时未连接，等待重连：msgId={}", message.msgId)
            false
        } catch (e: XmppException.Timeout) {
            log.warn("消息 {}ms 内未确认，标记失败：msgId={}", ackTimeoutMillis, message.msgId)
            dao.markSendFailed(message.rowId, SendError.TIMEOUT)
            true
        } catch (e: XmppException) {
            log.warn("消息发送失败：msgId={}，{}", message.msgId, e.message, e)
            dao.markSendFailed(message.rowId, SendError.FAILED)
            true
        } catch (e: IllegalArgumentException) {
            log.error("消息报文格式错误：msgId={}", message.msgId, e)
            dao.markSendFailed(message.rowId, SendError.INVALID)
            true
        }
    }

    /**
     * 生成报文：群聊与否取决于会话类型；引用回复需要查出原消息的发送方。
     *
     * @param db 数据库
     * @param message 消息
     * @return message 报文
     */
    private suspend fun buildStanza(db: ImDatabase, message: MessageEntity): String {
        val conversation = db.conversationDao().get(message.conversationId)
        val groupChat = conversation?.type == ConversationEntity.TYPE_GROUP
        val replyToSender = message.replyToMsgId?.let { id ->
            val original = db.messageDao().get(message.conversationId, id)
            if (original == null) log.warn("引用的原消息不在本地，不带引用发送：msgId={}，原消息={}", message.msgId, id)
            original?.senderJid
        }
        return OutgoingStanzaBuilder.build(message, groupChat, replyToSender)
    }

    /**
     * 未连接：把等待超时的消息标记失败，然后等待连接成功、新消息或下一条消息超时。
     *
     * @param db 数据库
     * @param pending 待发送的消息
     * @param waitingSince 各消息开始等待的时间（在此更新）
     */
    private suspend fun waitForConnection(db: ImDatabase, pending: List<MessageEntity>, waitingSince: HashMap<Long, Long>) {
        val now = clock.elapsedMillis()
        pending.forEach { waitingSince.getOrPut(it.rowId) { now } }
        val pendingIds = pending.mapTo(HashSet()) { it.rowId }
        waitingSince.keys.retainAll(pendingIds)

        val expired = pending.filter { now - waitingSince.getValue(it.rowId) >= connectWaitMillis }
        if (expired.isNotEmpty()) {
            log.warn("{} 秒内未能连接服务器，{} 条消息标记失败", connectWaitMillis / 1000, expired.size)
            expired.forEach {
                db.messageDao().markSendFailed(it.rowId, SendError.NOT_CONNECTED)
                waitingSince.remove(it.rowId)
            }
            return
        }
        val nextDeadline = waitingSince.values.min() + connectWaitMillis
        withTimeoutOrNull(nextDeadline - now) {
            merge(
                client.connectionState.filter { it is ConnectionState.Authenticated }.map { },
                wakeups.receiveAsFlow(),
            ).first()
        }
    }

    private companion object {
        const val TAG = "SendQueue"

        /** 等待服务器确认的超时（协议规范 §6.2：30 秒内未确认为失败） */
        const val ACK_TIMEOUT_MILLIS = 30_000L

        /** 未连接时消息最长等待时间 */
        const val CONNECT_WAIT_MILLIS = 30_000L

        /** 发送时发现未连接、但连接状态尚未更新时，稍等再重试 */
        const val RETRY_DELAY_MILLIS = 1_000L
    }
}

/** 发送失败原因（消息表 errorCode 列），界面据此显示提示 */
object SendError {

    /** 30 秒内未收到服务器确认 */
    const val TIMEOUT = "timeout"

    /** 长时间未能连接服务器 */
    const val NOT_CONNECTED = "not-connected"

    /** 报文无法生成或格式错误（程序错误） */
    const val INVALID = "invalid"

    /** 其他发送错误 */
    const val FAILED = "failed"

    /** 附件上传失败（开发计划 5.4） */
    const val UPLOAD_FAILED = "upload-failed"

    /** 附件超过服务器的上传上限 */
    const val FILE_TOO_LARGE = "file-too-large"

    /** 附件的本地文件已不存在（被清理），只能重新选择 */
    const val FILE_MISSING = "file-missing"
}
