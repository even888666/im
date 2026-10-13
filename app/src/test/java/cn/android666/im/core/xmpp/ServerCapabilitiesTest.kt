package cn.android666.im.core.xmpp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ServerCapabilities] 测试：按协议规范 §1.7 的 feature 判断功能是否可用 */
class ServerCapabilitiesTest {

    @Test
    fun allFeatures_allEnabled() {
        val caps = ServerCapabilities(ServerCapabilities.EXPECTED.toSet(), maxUploadFileSize = 100L)

        assertThat(caps.account).isTrue()
        assertThat(caps.org).isTrue()
        assertThat(caps.presenceQuery).isTrue()
        assertThat(caps.notice).isTrue()
        assertThat(caps.mucExtension).isTrue()
        assertThat(caps.diag).isTrue()
        assertThat(caps.mam).isTrue()
        assertThat(caps.carbons).isTrue()
        assertThat(caps.externalServices).isTrue()
        assertThat(caps.fileUpload).isTrue()
        assertThat(caps.missing).isEmpty()
    }

    /** 自研插件未安装时（当前开发阶段的实际情况）：插件功能不可用，标准功能可用 */
    @Test
    fun pluginsMissing_onlyStandardFeatures() {
        val caps = ServerCapabilities(
            setOf(ServerCapabilities.MAM, ServerCapabilities.CARBONS, ServerCapabilities.EXTDISCO, "jabber:iq:version"),
        )

        assertThat(caps.org).isFalse()
        assertThat(caps.account).isFalse()
        assertThat(caps.mam).isTrue()
        assertThat(caps.fileUpload).isFalse()
        assertThat(caps.missing).containsExactly(
            ServerCapabilities.ACCOUNT,
            ServerCapabilities.ORG,
            ServerCapabilities.PRESENCE,
            ServerCapabilities.NOTICE,
            ServerCapabilities.MUC,
            ServerCapabilities.DIAG,
        ).inOrder()
    }
}
