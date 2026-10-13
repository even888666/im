package cn.android666.im.core.xmpp.smack

import cn.android666.im.core.xmpp.DisconnectReason
import cn.android666.im.core.xmpp.XmppException
import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.XMPPException
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StreamError
import org.jivesoftware.smack.packet.XmlElement
import org.jivesoftware.smack.sasl.SASLErrorException
import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * 把 Smack 的异常映射为业务层的 [XmppException] / [DisconnectReason]。
 * 纯函数，便于单元测试；只有 core.xmpp.smack 包允许引用 Smack 类（Android工程架构.md §3.3）。
 */
internal object SmackErrors {

    /** 自定义错误命名空间（协议规范 §16） */
    const val ERROR_NAMESPACE = "urn:android666:im:error:1"

    /** stanza error 中携带自定义错误码的元素（协议规范 §16） */
    const val APP_ERROR = "app-error"

    /** 账号被禁用时 stream error 中的自定义元素（协议规范 §2.4） */
    const val ACCOUNT_DISABLED = "account-disabled"

    /**
     * 登录 / 重连过程中的异常 → [XmppException]。
     *
     * @param e Smack 或 IO 异常
     * @return 对应的业务异常；已经是 [XmppException] 时原样返回
     */
    fun toXmppException(e: Throwable): XmppException = when {
        e is XmppException -> e
        e is SASLErrorException -> XmppException.AuthenticationFailed(e)
        // TLS 失败可能被包在 ConnectionException / IOException 里，沿异常链查找
        e.causeChain().any { it is SSLException || it is CertificateException } -> XmppException.TlsFailed(e)
        e is SmackException.SecurityRequiredException -> XmppException.TlsFailed(e)
        e is SmackException.NoResponseException -> XmppException.Timeout(e)
        e is XMPPException.XMPPErrorException -> e.stanzaError.toXmppException(e)
        e is SmackException.NotConnectedException -> XmppException.NotConnected(e)
        e is XMPPException.StreamErrorException -> when (toDisconnectReason(e)) {
            DisconnectReason.Conflict -> XmppException.Conflict(e)
            DisconnectReason.AccountDisabled -> XmppException.AccountDisabled(e)
            else -> XmppException.ConnectionFailed("服务器关闭了连接：${e.streamError.condition}", e)
        }
        e is SmackException || e is IOException -> XmppException.ConnectionFailed(e.message ?: e.javaClass.simpleName, e)
        else -> XmppException.ConnectionFailed("未知错误：${e.javaClass.simpleName}：${e.message}", e)
    }

    /**
     * 连接意外断开时的异常 → [DisconnectReason]（协议规范 §2.4）。
     *
     * @param e Smack 通过 `connectionClosedOnError` 回调的异常
     * @return 断开原因
     */
    fun toDisconnectReason(e: Throwable): DisconnectReason {
        if (e !is XMPPException.StreamErrorException) return DisconnectReason.Network(e)
        val error = e.streamError
        return when {
            error.condition == StreamError.Condition.conflict -> DisconnectReason.Conflict
            // 账号被禁用：policy-violation + <account-disabled xmlns='urn:android666:im:error:1'/>
            error.condition == StreamError.Condition.policy_violation &&
                error.getExtension<XmlElement>(ACCOUNT_DISABLED, ERROR_NAMESPACE) != null ->
                DisconnectReason.AccountDisabled
            // Smack 的枚举名把横线换成了下划线，转回协议中的写法
            else -> DisconnectReason.StreamError(error.condition.toString())
        }
    }

    /**
     * stanza error → [XmppException.StanzaError]，带上自定义错误码
     * （`<app-error xmlns='urn:android666:im:error:1' code='...'/>`，协议规范 §16）。
     *
     * @param cause 原始异常
     * @return 业务异常
     */
    fun StanzaError.toXmppException(cause: Throwable? = null): XmppException.StanzaError {
        val appError = getExtension<XmlElement>(APP_ERROR, ERROR_NAMESPACE)
        // 自定义扩展元素被 Smack 解析为 StandardExtensionElement，从中读取 code 属性
        val code = (appError as? StandardExtensionElement)?.getAttributeValue("code")
        return XmppException.StanzaError(condition.toString(), code, cause)
    }

    /**
     * 异常及其所有 cause 组成的序列。
     *
     * @return 从自身开始的异常链，最多 10 层，防止循环引用
     */
    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.take(10)
}
