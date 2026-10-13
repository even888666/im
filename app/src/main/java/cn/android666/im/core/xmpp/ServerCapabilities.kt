package cn.android666.im.core.xmpp

/**
 * 服务器能力（协议规范 §1.7）：登录后通过 disco#info 查询服务器支持的 feature，
 * 不支持的功能在界面上隐藏（例如插件未安装时不显示组织架构）。
 *
 * @property features 服务器声明的全部 feature
 * @property maxUploadFileSize 文件上传服务允许的单个文件大小上限（字节）；没有上传服务时为 null
 */
data class ServerCapabilities(
    val features: Set<String>,
    val maxUploadFileSize: Long? = null,
) {
    /** 账号状态（org 插件） */
    val account: Boolean get() = ACCOUNT in features

    /** 组织架构（org 插件） */
    val org: Boolean get() = ORG in features

    /** 在线状态查询（org 插件） */
    val presenceQuery: Boolean get() = PRESENCE in features

    /** 公司公告（org 插件） */
    val notice: Boolean get() = NOTICE in features

    /** 群聊扩展（biz 插件） */
    val mucExtension: Boolean get() = MUC in features

    /** 诊断上报（biz 插件） */
    val diag: Boolean get() = DIAG in features

    /** 消息漫游 MAM（Monitoring 插件） */
    val mam: Boolean get() = MAM in features

    /** 多端同步 Carbons */
    val carbons: Boolean get() = CARBONS in features

    /** TURN 凭证（External Service Discovery 插件） */
    val externalServices: Boolean get() = EXTDISCO in features

    /** 是否有文件上传服务 */
    val fileUpload: Boolean get() = maxUploadFileSize != null

    companion object {
        const val ACCOUNT = "urn:android666:im:account:1"
        const val ORG = "urn:android666:im:org:1"
        const val PRESENCE = "urn:android666:im:presence:1"
        const val NOTICE = "urn:android666:im:notice:1"
        const val MUC = "urn:android666:im:muc:1"
        const val DIAG = "urn:android666:im:diag:1"
        const val MAM = "urn:xmpp:mam:2"
        const val CARBONS = "urn:xmpp:carbons:2"
        const val EXTDISCO = "urn:xmpp:extdisco:2"

        /** 协议规范 §1.7 表中列出的、客户端关心的全部 feature，用于日志中输出缺失项 */
        val EXPECTED: List<String> = listOf(ACCOUNT, ORG, PRESENCE, NOTICE, MUC, DIAG, MAM, CARBONS, EXTDISCO)
    }

    /** 协议规范 §1.7 中缺失的 feature，写日志用 */
    val missing: List<String> get() = EXPECTED.filterNot { it in features }
}

/**
 * XEP-0202 查询服务器时间的结果，用于 [cn.android666.im.core.common.ServerClock.calibrate]。
 *
 * @property serverMillis 服务器返回的时间（毫秒）
 * @property requestSentAtMillis 发出请求时的本地时间（毫秒）
 * @property roundTripMillis 请求往返耗时（毫秒）
 */
data class ServerTime(val serverMillis: Long, val requestSentAtMillis: Long, val roundTripMillis: Long)
