package cn.android666.im.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [LogFilePrefix.sanitize] 的清洗规则测试 */
class LogFilePrefixTest {

    @Test
    fun sanitize_validEmpNo_keptAsIs() {
        assertThat(LogFilePrefix.sanitize("10086")).isEqualTo("10086")
        assertThat(LogFilePrefix.sanitize("zhang.san_01-a")).isEqualTo("zhang.san_01-a")
    }

    @Test
    fun sanitize_trimsWhitespace() {
        assertThat(LogFilePrefix.sanitize("  10086 \n")).isEqualTo("10086")
    }

    @Test
    fun sanitize_nullOrBlank_returnsNull() {
        assertThat(LogFilePrefix.sanitize(null)).isNull()
        assertThat(LogFilePrefix.sanitize("")).isNull()
        assertThat(LogFilePrefix.sanitize("   ")).isNull()
    }

    /** 路径分隔符等字符被替换，防止日志写到其他目录 */
    @Test
    fun sanitize_unsafeChars_replacedWithUnderscore() {
        assertThat(LogFilePrefix.sanitize("../10086")).isEqualTo(".._10086")
        assertThat(LogFilePrefix.sanitize("a/b\\c:d")).isEqualTo("a_b_c_d")
        assertThat(LogFilePrefix.sanitize("张三")).isEqualTo(null)
    }

    @Test
    fun sanitize_onlyUnsafeChars_returnsNull() {
        assertThat(LogFilePrefix.sanitize("///")).isNull()
    }
}
