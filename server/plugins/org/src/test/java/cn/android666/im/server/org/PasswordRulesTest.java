package cn.android666.im.server.org;

import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;

/** {@link PasswordRules} 测试：与客户端规则一致 */
public class PasswordRulesTest {

    @Test
    public void acceptable() {
        assertThat(PasswordRules.isAcceptable("abcd1234")).isTrue();
        assertThat(PasswordRules.isAcceptable("A1" + "x".repeat(30))).isTrue();
    }

    @Test
    public void lengthLimits() {
        assertThat(PasswordRules.isAcceptable("abc1234")).isFalse();
        assertThat(PasswordRules.isAcceptable("A1" + "x".repeat(31))).isFalse();
        assertThat(PasswordRules.isAcceptable(null)).isFalse();
    }

    @Test
    public void needsLetterAndDigit() {
        assertThat(PasswordRules.isAcceptable("12345678")).isFalse();
        assertThat(PasswordRules.isAcceptable("abcdefgh")).isFalse();
        // 中文不算字母
        assertThat(PasswordRules.isAcceptable("密码密码1234")).isFalse();
    }

    @Test
    public void noWhitespace() {
        assertThat(PasswordRules.isAcceptable("abcd 1234")).isFalse();
    }
}
