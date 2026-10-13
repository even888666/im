package cn.android666.im.data.presence

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 在线状态识别规则测试（协议规范 §5.2） */
class PresenceModelsTest {

    @Test
    fun statusOf_codeFirstThenShow() {
        assertThat(statusOf("meeting", "dnd")).isEqualTo(MyStatus.MEETING)
        assertThat(statusOf("business-trip", "away")).isEqualTo(MyStatus.BUSINESS_TRIP)
        // 不认识的 code 按 show
        assertThat(statusOf("vacation", "away")).isEqualTo(MyStatus.AWAY)
        assertThat(statusOf(null, "dnd")).isEqualTo(MyStatus.BUSY)
        assertThat(statusOf(null, "xa")).isEqualTo(MyStatus.AWAY)
        assertThat(statusOf(null, null)).isEqualTo(MyStatus.ONLINE)
    }

    /** 多端在线时显示"最忙"的状态；离线时为 null */
    @Test
    fun overall() {
        val presence = ColleaguePresence(
            "a@b", true,
            listOf(ResourceStatus("pc", MyStatus.ONLINE, null), ResourceStatus("android", MyStatus.MEETING, null)),
            null,
        )
        assertThat(presence.overall).isEqualTo(MyStatus.MEETING)
        assertThat(presence.copy(online = false, resources = emptyList()).overall).isNull()
        assertThat(presence.copy(resources = emptyList()).overall).isEqualTo(MyStatus.ONLINE)
    }
}
