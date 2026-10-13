package cn.android666.im.it

import cn.android666.im.testing.IntegrationTests
import cn.android666.im.testing.ItServer
import com.google.common.truth.Truth.assertThat
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode
import org.jivesoftware.smack.ConnectionListener
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPConnection
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.MessageBuilder
import org.jivesoftware.smack.packet.Mechanisms
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.StreamError
import org.jivesoftware.smack.packet.UnparsedIQ
import org.jivesoftware.smack.sasl.SASLErrorException
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smackx.carbons.CarbonManager
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager
import org.jivesoftware.smackx.iqregister.AccountManager
import org.jivesoftware.smackx.mam.MamManager
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.jxmpp.jid.impl.JidCreate
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.xml.namespace.QName

/**
 * 服务器配置集成测试：用 JVM 版 Smack 连接本地 Openfire（server/up.sh 部署），
 * 验证开发计划 1.4 ~ 1.7 中服务器配置的效果。
 *
 * 运行：`bash server/up.sh && ./gradlew testDebugUnitTest -Pim.it=true --tests '*ServerSetupIT*'`
 *
 * 测试账号分配（互不干扰，测试可重复运行）：
 * 10001/10002 收发消息；10003 同端冲突；10004 离线消息；10005 MAM；10006 文件上传；
 * 10007 TURN 凭证；10008 明文连接；10009 修改密码。
 */
class ServerSetupIT {

    /** 本测试创建的连接，测试结束统一断开 */
    private val connections = mutableListOf<XMPPTCPConnection>()

    @Before
    fun setUp() {
        IntegrationTests.assumeEnabled()
    }

    @After
    fun tearDown() {
        connections.forEach { runCatching { it.disconnect() } }
    }

    /**
     * 登录并记录连接，测试结束时自动断开。
     *
     * @param index 测试账号序号 1 ~ 10
     * @param resource 端
     * @return 已登录的连接
     */
    private fun login(index: Int, resource: String = "android"): XMPPTCPConnection =
        ItServer.login(ItServer.user(index), resource).also { connections += it }

    /**
     * 收集某个连接收到的单聊消息。
     *
     * @param connection 连接（可在登录前调用，以便收到登录时投递的离线消息）
     * @return 消息队列
     */
    private fun collectMessages(connection: XMPPConnection): LinkedBlockingQueue<Message> {
        val queue = LinkedBlockingQueue<Message>()
        ChatManager.getInstanceFor(connection).addIncomingListener { _, message, _ -> queue.add(message) }
        return queue
    }

    /**
     * 发送单聊消息。
     *
     * @param from 发送方连接
     * @param toUser 接收方工号
     * @param body 正文
     */
    private fun sendChat(from: XMPPConnection, toUser: String, body: String) {
        from.sendStanza(
            MessageBuilder.buildMessage(UUID.randomUUID().toString())
                .to(ItServer.jid(toUser))
                .ofType(Message.Type.chat)
                .setBody(body)
                .build(),
        )
    }

    /** 1.7 / 1.10：测试账号可以登录，两个账号互发消息 */
    @Test
    fun twoUsers_exchangeMessages() {
        val a = login(1)
        val b = login(2)
        val inboxB = collectMessages(b)
        val inboxA = collectMessages(a)
        // 登录后客户端异步发送初始 presence；服务器处理完之前发来的消息会被当作离线消息存储，
        // 要等下次上线才投递（App 依靠离线消息 + MAM 补拉处理这种情况），这里稍等服务器处理完 presence
        Thread.sleep(500)

        sendChat(a, ItServer.user(2), "你好，我是 10001")
        sendChat(b, ItServer.user(1), "收到，我是 10002")

        assertThat(inboxB.poll(10, TimeUnit.SECONDS)?.body).isEqualTo("你好，我是 10001")
        assertThat(inboxA.poll(10, TimeUnit.SECONDS)?.body).isEqualTo("收到，我是 10002")
    }

    /** 1.4：错误密码登录失败（SASL not-authorized） */
    @Test
    fun wrongPassword_saslFails() {
        val connection = XMPPTCPConnection(ItServer.config(ItServer.user(1), password = "wrong-password"))
        connections += connection
        connection.connect()

        val e = assertThrows(SASLErrorException::class.java) { connection.login() }
        assertThat(e.saslFailure.saslErrorString).isEqualTo("not-authorized")
    }

    /** 1.4：TLS 建立后服务器只提供 SCRAM-SHA-1（协议规范 §2.1 禁止 PLAIN） */
    @Test
    fun saslMechanisms_onlyScramSha1() {
        val connection = XMPPTCPConnection(ItServer.config(ItServer.user(1)))
        connections += connection
        connection.connect()

        val mechanisms = connection.getFeature<Mechanisms>(QName(Mechanisms.NAMESPACE, Mechanisms.ELEMENT))
        assertThat(connection.isSecureConnection).isTrue()
        assertThat(mechanisms.mechanisms).containsExactly("SCRAM-SHA-1")
    }

    /** 1.4：服务器要求 TLS，不使用 TLS 的客户端无法登录 */
    @Test
    fun plaintextConnection_rejected() {
        val connection = XMPPTCPConnection(ItServer.config(ItServer.user(8), securityMode = SecurityMode.disabled))
        connections += connection

        // 服务器在 features 中声明 STARTTLS 为 required，客户端拒绝使用 TLS 时 Smack 会抛出安全相关异常
        val e = assertThrows(Exception::class.java) {
            connection.connect()
            connection.login()
        }
        assertThat(e).isInstanceOf(SmackException::class.java)
        assertThat(connection.isAuthenticated).isFalse()
    }

    /** 1.4：流管理（XEP-0198）启用；连接突然中断后重连可以恢复原会话（协议规范 §2.3"流恢复"） */
    @Test
    fun streamManagement_resumesAfterAbruptDisconnect() {
        val connection = login(1)
        assertThat(connection.isSmAvailable).isTrue()
        assertThat(connection.isSmEnabled).isTrue()
        // 服务器为可恢复的会话保留的时长（秒），Openfire 默认 600
        assertThat(connection.maxSmResumptionTime).isGreaterThan(0)

        // 模拟网络突然中断：不发送 </stream>，服务器保留会话等待恢复
        // （isSmResumptionPossible 只在这种异常断开后才会为 true，正常在线时恒为 false）
        connection.instantShutdown()
        assertThat(connection.isSmResumptionPossible).isTrue()

        connection.connect()
        connection.login()
        assertThat(connection.streamWasResumed()).isTrue()
    }

    /** 1.4：服务器支持 Message Carbons（XEP-0280） */
    @Test
    fun carbons_supported() {
        val connection = login(1)

        assertThat(CarbonManager.getInstanceFor(connection).isSupportedByServer).isTrue()
    }

    /** 1.4 / 2.16：服务器支持 Client State Indication（XEP-0352） */
    @Test
    fun clientStateIndication_supported() {
        val connection = login(1)

        assertThat(org.jivesoftware.smackx.csi.ClientStateIndicationManager.isSupported(connection)).isTrue()
    }

    /** 1.4：同一 resource 重复登录时，旧连接收到 conflict 并被断开（协议规范 §2.4） */
    @Test
    fun sameResourceLogin_kicksOldConnectionWithConflict() {
        val old = login(3, resource = "android")
        val closedError = LinkedBlockingQueue<Exception>()
        old.addConnectionListener(object : ConnectionListener {
            override fun connectionClosedOnError(e: Exception) {
                closedError.add(e)
            }
        })

        login(3, resource = "android")

        val e = closedError.poll(10, TimeUnit.SECONDS)
        assertThat(e).isInstanceOf(XMPPException.StreamErrorException::class.java)
        assertThat((e as XMPPException.StreamErrorException).streamError.condition)
            .isEqualTo(StreamError.Condition.conflict)
    }

    /** 1.4：不同 resource（手机 + 电脑）可以同时在线 */
    @Test
    fun differentResources_canBeOnlineTogether() {
        val phone = login(3, resource = "android")
        val pc = login(3, resource = "pc")

        Thread.sleep(500)
        assertThat(phone.isAuthenticated).isTrue()
        assertThat(pc.isAuthenticated).isTrue()
    }

    /** 1.4：对方离线时消息被存储，上线后投递 */
    @Test
    fun offlineMessage_deliveredAfterLogin() {
        val sender = login(1)
        val body = "离线消息 ${UUID.randomUUID()}"
        sendChat(sender, ItServer.user(4), body)
        // 等服务器处理完（存入离线消息表）再让接收方上线
        Thread.sleep(1_000)

        val receiver = XMPPTCPConnection(ItServer.config(ItServer.user(4)))
        connections += receiver
        val inbox = collectMessages(receiver)
        receiver.connect()
        receiver.login()

        // 可能还有之前运行残留的离线消息，按正文查找本次发送的那条
        val received = generateSequence { inbox.poll(10, TimeUnit.SECONDS) }.firstOrNull { it.body == body }
        assertThat(received).isNotNull()
    }

    /** 1.5：MAM（XEP-0313）可用，并能查到刚发送的消息 */
    @Test
    fun mam_supportedAndArchivesMessages() {
        val a = login(5)
        val mam = MamManager.getInstanceFor(a)
        assertThat(mam.isSupported).isTrue()

        val body = "存档消息 ${UUID.randomUUID()}"
        sendChat(a, ItServer.user(1), body)

        // Monitoring 插件异步写入存档，轮询直到查到（最多 15 秒）
        val deadline = System.currentTimeMillis() + 15_000
        var found = false
        while (!found && System.currentTimeMillis() < deadline) {
            val query = mam.queryArchive(
                MamManager.MamQueryArgs.builder()
                    .limitResultsToJid(ItServer.jid(ItServer.user(1)))
                    .setResultPageSizeTo(50)
                    .queryLastPage()
                    .build(),
            )
            found = query.messages.any { it.body == body }
            if (!found) Thread.sleep(500)
        }
        assertThat(found).isTrue()
    }

    /** 1.5：HTTP 文件上传（XEP-0363）可用，上传后可用同一根证书下载 */
    @Test
    fun httpFileUpload_uploadThenDownload() {
        val connection = login(6)
        val upload = HttpFileUploadManager.getInstanceFor(connection)
        upload.setTlsContext(ItServer.sslContext)
        assertThat(upload.discoverUploadService()).isTrue()
        // 上传上限为 100 MB（openfire-properties.conf）
        assertThat(upload.defaultUploadService.maxFileSize).isEqualTo(104_857_600L)

        val content = "文件内容 ${UUID.randomUUID()}"
        val file = File.createTempFile("upload", ".txt").apply {
            writeText(content)
            deleteOnExit()
        }
        val url: URL = upload.uploadFile(file)
        assertThat(url.host).isEqualTo(ItServer.domain)
        assertThat(url.port).isEqualTo(7443)

        val download = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ItServer.sslContext.socketFactory
        }
        assertThat(download.responseCode).isEqualTo(200)
        assertThat(download.inputStream.bufferedReader().readText()).isEqualTo(content)
    }

    /** 1.6：External Service Discovery（XEP-0215）下发 STUN 地址与带临时凭证的 TURN 地址 */
    @Test
    fun externalServiceDiscovery_returnsStunAndTurnCredentials() {
        val connection = login(7)
        val serverJid = JidCreate.domainBareFrom(ItServer.domain)
        val info = ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(serverJid)
        assertThat(info.containsFeature("urn:xmpp:extdisco:2")).isTrue()

        val request = object : IQ("services", "urn:xmpp:extdisco:2") {
            override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
                xml.setEmptyElement()
                return xml
            }
        }.apply {
            type = IQ.Type.get
            to = serverJid
        }
        // Smack 没有 XEP-0215 的解析器，结果为 UnparsedIQ，content 是 <services> 内部的原始 XML
        val response = connection.sendIqRequestAndWaitForResponse<UnparsedIQ>(request).content.toString()

        assertThat(response).contains("type='stun'")
        assertThat(response).contains("type='turn'")
        assertThat(response).contains("host='turn.${ItServer.domain}'")
        // TURN REST API 凭证：用户名 = 过期时间戳:URL 编码的 bare JID（插件的实际格式，见协议规范 §12.1）
        assertThat(response).containsMatch("username='\\d+:${ItServer.user(7)}%40${ItServer.domain}'")
        assertThat(response).containsMatch("password='[^']+'")
        assertThat(response).contains("expires=")
    }

    /** 1.4：允许通过 XEP-0077 修改密码（协议规范 §3.2），修改后新密码可登录 */
    @Test
    fun changePassword_thenLoginWithNewPassword() {
        val user = ItServer.user(9)
        val newPassword = "New${UUID.randomUUID().toString().take(8)}1"
        val connection = login(9)
        AccountManager.getInstance(connection).changePassword(newPassword)
        connection.disconnect()

        val relogin = ItServer.login(user, password = newPassword).also { connections += it }
        assertThat(relogin.isAuthenticated).isTrue()
        // 改回原密码，保证测试可重复运行
        AccountManager.getInstance(relogin).changePassword(ItServer.testUserPassword)
    }

    /** 1.4：关闭了自助注册（企业内部账号由管理员导入） */
    @Test
    fun inBandRegistration_disabled() {
        val connection = login(1)

        // 关闭自助注册后，Openfire 对查询注册表单的请求直接返回 forbidden
        val supported = try {
            AccountManager.getInstance(connection).supportsAccountCreation()
        } catch (e: XMPPException.XMPPErrorException) {
            assertThat(e.stanzaError.condition).isEqualTo(StanzaError.Condition.forbidden)
            false
        }
        assertThat(supported).isFalse()
    }
}
