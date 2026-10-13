package cn.android666.im.service

import cn.android666.im.R
import cn.android666.im.core.xmpp.ConnectionState
import cn.android666.im.core.xmpp.DisconnectReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ServiceNotificationText] 连接状态 → 通知文字 */
class ServiceNotificationTextTest {

    @Test
    fun mapping() {
        assertThat(ServiceNotificationText.forState(ConnectionState.Authenticated(false))).isEqualTo(R.string.service_status_connected)
        assertThat(ServiceNotificationText.forState(ConnectionState.Authenticated(true))).isEqualTo(R.string.service_status_connected)
        assertThat(ServiceNotificationText.forState(ConnectionState.Connecting)).isEqualTo(R.string.service_status_connecting)
        assertThat(ServiceNotificationText.forState(ConnectionState.Idle)).isEqualTo(R.string.service_status_idle)
        assertThat(ServiceNotificationText.forState(ConnectionState.Disconnected(DisconnectReason.Network(null))))
            .isEqualTo(R.string.service_status_disconnected)
        assertThat(ServiceNotificationText.forState(ConnectionState.Disconnected(DisconnectReason.StreamError("x"))))
            .isEqualTo(R.string.service_status_disconnected)
        assertThat(ServiceNotificationText.forState(ConnectionState.Disconnected(DisconnectReason.Conflict)))
            .isEqualTo(R.string.service_status_conflict)
        assertThat(ServiceNotificationText.forState(ConnectionState.Disconnected(DisconnectReason.AccountDisabled)))
            .isEqualTo(R.string.service_status_disabled)
        assertThat(ServiceNotificationText.forState(ConnectionState.Disconnected(DisconnectReason.AuthenticationFailed)))
            .isEqualTo(R.string.service_status_auth_failed)
    }
}
