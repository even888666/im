package cn.android666.im.core.xmpp

/**
 * 文件上传槽位（XEP-0363，协议规范 §9）。
 *
 * @property putUrl 上传地址（HTTP PUT）
 * @property getUrl 下载地址（填入消息的 file@url）
 * @property headers 上传时必须带上的请求头（服务器指定，可能为空）
 */
data class UploadSlot(val putUrl: String, val getUrl: String, val headers: Map<String, String> = emptyMap())
