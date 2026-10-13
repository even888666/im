package cn.android666.im.ui.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [NameAvatar] 文字头像规则测试 */
class NameAvatarTest {

    @Test
    fun chineseNames() {
        assertThat(NameAvatar.text("张三")).isEqualTo("张三")
        assertThat(NameAvatar.text("张小明")).isEqualTo("小明")
        assertThat(NameAvatar.text("欧阳娜娜")).isEqualTo("娜娜")
        assertThat(NameAvatar.text("买买提·艾力")).isEqualTo("艾力")
        assertThat(NameAvatar.text("王")).isEqualTo("王")
    }

    @Test
    fun englishNames() {
        assertThat(NameAvatar.text("John Smith")).isEqualTo("JS")
        assertThat(NameAvatar.text("alice")).isEqualTo("A")
        assertThat(NameAvatar.text(" ")).isEqualTo("")
    }

    @Test
    fun colorStableForSameKey() {
        val a = NameAvatar.colorIndex("10001@im.example.com")
        assertThat(NameAvatar.colorIndex("10001@im.example.com")).isEqualTo(a)
        assertThat(a).isAtLeast(0)
    }
}
