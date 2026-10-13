package cn.android666.im.core.common

import java.util.UUID

/**
 * 生成协议中各类 ID（见 三端协议规范.md §1.1、§1.3、§12.5）。
 * 定义为接口，测试时可替换为生成固定 ID 的实现，让断言可预期。
 */
interface IdGenerator {

    /**
     * 消息 ID：小写 UUID v4（带横线），也用作 message@id 与 origin-id。
     *
     * @return 新的消息 ID
     */
    fun messageId(): String

    /**
     * 群 ID：`g` + 去掉横线的小写 UUID，共 33 个字符；群 JID 为 `{群ID}@conference.{域名}`。
     *
     * @return 新的群 ID
     */
    fun roomId(): String

    /**
     * 通话 ID：`c-` + 小写 UUID。
     *
     * @return 新的通话 ID
     */
    fun callId(): String

    /**
     * 一般用途的唯一 ID（IQ ID、上传文件名等）：小写 UUID v4（带横线）。
     *
     * @return 新的 ID
     */
    fun uuid(): String
}

/**
 * 基于随机 UUID 的实现。
 *
 * @property uuidSource UUID 来源，默认 [UUID.randomUUID]；测试时可传入固定值
 */
class UuidIdGenerator(
    private val uuidSource: () -> UUID = UUID::randomUUID,
) : IdGenerator {

    override fun messageId(): String = uuid()

    override fun roomId(): String = "g" + uuid().replace("-", "")

    override fun callId(): String = "c-" + uuid()

    // UUID.toString() 本身就是小写，这里再 lowercase 一次，防止自定义的 uuidSource 产生大写
    override fun uuid(): String = uuidSource().toString().lowercase()
}
