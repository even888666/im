package cn.android666.im.server.org;

/**
 * 密码规则（《三端协议规范.md》§3.2）：8 ~ 32 位，至少包含字母和数字，不能包含空白字符。
 * 与客户端的 PasswordRules 保持一致；不满足时服务器返回 {@code not-acceptable} + {@code weak-password}。
 */
public final class PasswordRules {

    /** 最短长度 */
    public static final int MIN_LENGTH = 8;

    /** 最长长度 */
    public static final int MAX_LENGTH = 32;

    private PasswordRules() {
    }

    /**
     * 检查密码是否满足规则。
     *
     * @param password 密码
     * @return 满足时为 true
     */
    public static boolean isAcceptable(String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (char c : password.toCharArray()) {
            if (Character.isWhitespace(c)) {
                return false;
            }
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                letter = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            }
        }
        return letter && digit;
    }
}
