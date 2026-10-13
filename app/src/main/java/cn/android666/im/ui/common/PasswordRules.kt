package cn.android666.im.ui.common

import androidx.annotation.StringRes
import cn.android666.im.R

/**
 * 新密码的本地校验（协议规范 §3.2：8 ~ 32 位，至少包含字母和数字；服务器 org 插件会再校验一次）。
 */
object PasswordRules {

    /** 最短长度 */
    const val MIN_LENGTH = 8

    /** 最长长度 */
    const val MAX_LENGTH = 32

    /**
     * 校验新密码。
     *
     * @param password 新密码
     * @param confirm 再次输入的密码
     * @return 不符合规则时返回提示文字的字符串资源 ID；符合时返回 null
     */
    @StringRes
    fun validate(password: String, confirm: String): Int? = when {
        password.length < MIN_LENGTH || password.length > MAX_LENGTH -> R.string.password_rule_length
        password.none { it.isAsciiLetter() } || password.none { it.isAsciiDigit() } -> R.string.password_rule_letter_digit
        password.any { it.isWhitespace() } -> R.string.password_rule_no_space
        password != confirm -> R.string.password_rule_mismatch
        else -> null
    }

    /** 是否为英文字母（只认 ASCII，中文等字符不算字母） */
    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    /** 是否为阿拉伯数字 */
    private fun Char.isAsciiDigit() = this in '0'..'9'
}
