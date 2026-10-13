package cn.android666.im.server.org.model;

/**
 * 员工扩展信息（《三端协议规范.md》§4.1）。账号本身（用户名、密码）在 Openfire 的用户表中。
 *
 * @param username           工号（等于 JID 的用户名部分）
 * @param name               姓名
 * @param pinyin             姓名全拼，小写无空格
 * @param initials           拼音首字母
 * @param deptId             所属部门 ID
 * @param title              职位，可为空字符串
 * @param mobile             手机，可为空字符串
 * @param phone              座机，可为空字符串
 * @param email              邮箱，可为空字符串
 * @param sort               部门内排序，升序
 * @param avatarHash         头像 SHA-1，无头像时为空字符串
 * @param disabled           是否已禁用（离职），禁用的员工在同步结果中视为删除
 * @param mustChangePassword 是否需要强制修改初始密码（§3.1）
 */
public record Employee(
        String username,
        String name,
        String pinyin,
        String initials,
        String deptId,
        String title,
        String mobile,
        String phone,
        String email,
        int sort,
        String avatarHash,
        boolean disabled,
        boolean mustChangePassword) {

    /**
     * 复制并修改禁用状态。
     *
     * @param value 新的禁用状态
     * @return 新对象
     */
    public Employee withDisabled(boolean value) {
        return new Employee(username, name, pinyin, initials, deptId, title, mobile, phone, email, sort,
                avatarHash, value, mustChangePassword);
    }

    /**
     * 复制并修改强制改密标记。
     *
     * @param value 新的标记
     * @return 新对象
     */
    public Employee withMustChangePassword(boolean value) {
        return new Employee(username, name, pinyin, initials, deptId, title, mobile, phone, email, sort,
                avatarHash, disabled, value);
    }

    /**
     * 复制并修改头像哈希。
     *
     * @param value 新的头像 SHA-1，无头像时为空字符串
     * @return 新对象
     */
    public Employee withAvatarHash(String value) {
        return new Employee(username, name, pinyin, initials, deptId, title, mobile, phone, email, sort,
                value, disabled, mustChangePassword);
    }
}
