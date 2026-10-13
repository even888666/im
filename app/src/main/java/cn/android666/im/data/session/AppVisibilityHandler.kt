package cn.android666.im.data.session

import cn.android666.im.core.log.Logs
import cn.android666.im.core.xmpp.XmppClient
import cn.android666.im.core.xmpp.connection.XmppConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App 前后台切换的处理（Android工程架构.md §4.3"前后台"、功能清单 §3"后台省电"）：
 * - 回到前台：发送 CSI active；连接断开时立即重连，不必等退避（用户正在看，尽快恢复）；
 * - 进入后台：发送 CSI inactive，服务器减少非必要推送以省电。
 *
 * 由 App 中的 ProcessLifecycleOwner 观察者调用（进程内所有 Activity 都不可见时为后台）。
 *
 * @property client XMPP 客户端
 * @property connectionManager 连接管理
 * @property scope 应用级协程作用域
 */
class AppVisibilityHandler(
    private val client: XmppClient,
    private val connectionManager: XmppConnectionManager,
    private val scope: CoroutineScope,
) {

    private val log = Logs.file(TAG)

    /** App 回到前台 */
    fun onForeground() {
        log.info("App 回到前台")
        connectionManager.reconnectNow()
        scope.launch { client.setActive(true) }
    }

    /** App 进入后台 */
    fun onBackground() {
        log.info("App 进入后台")
        scope.launch { client.setActive(false) }
    }

    private companion object {
        const val TAG = "AppVisibility"
    }
}
