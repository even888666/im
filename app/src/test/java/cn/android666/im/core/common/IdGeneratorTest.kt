package cn.android666.im.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

/** [UuidIdGenerator] 生成的 ID 格式符合协议规范 §1.1、§1.3、§12.5 */
class IdGeneratorTest {

    private val generator = UuidIdGenerator()

    /** 小写 UUID v4（带横线）：第 13 位为版本号 4，第 17 位为变体 8/9/a/b */
    private val uuidV4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun messageId_isLowercaseUuidV4() {
        assertThat(generator.messageId()).matches(uuidV4.pattern)
    }

    @Test
    fun roomId_isGPlus32HexChars() {
        val id = generator.roomId()
        assertThat(id).hasLength(33)
        assertThat(id).matches("^g[0-9a-f]{32}$")
    }

    @Test
    fun callId_isCDashUuid() {
        val id = generator.callId()
        assertThat(id).startsWith("c-")
        assertThat(id.removePrefix("c-")).matches(uuidV4.pattern)
    }

    @Test
    fun ids_areUnique() {
        val ids = List(1_000) { generator.messageId() }
        assertThat(ids.toSet()).hasSize(1_000)
    }

    /** 自定义 UUID 来源产生大写时，结果仍为小写 */
    @Test
    fun customSource_resultIsLowercase() {
        val fixed = UUID.fromString("3F2A9C1E-5B7D-4F6A-8C0E-2B4D6F8A0C2E")
        val gen = UuidIdGenerator { fixed }

        assertThat(gen.messageId()).isEqualTo("3f2a9c1e-5b7d-4f6a-8c0e-2b4d6f8a0c2e")
        assertThat(gen.roomId()).isEqualTo("g3f2a9c1e5b7d4f6a8c0e2b4d6f8a0c2e")
        assertThat(gen.callId()).isEqualTo("c-3f2a9c1e-5b7d-4f6a-8c0e-2b4d6f8a0c2e")
    }
}
