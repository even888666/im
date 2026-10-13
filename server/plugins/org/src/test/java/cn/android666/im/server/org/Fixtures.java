package cn.android666.im.server.org;

import cn.android666.im.server.org.model.Employee;

/**
 * 测试数据。
 */
public final class Fixtures {

    private Fixtures() {
    }

    /**
     * 构造在职员工（拼音由姓名生成）。
     *
     * @param username 工号
     * @param name     姓名
     * @param deptId   部门 ID
     * @return 员工
     */
    public static Employee employee(String username, String name, String deptId) {
        PinyinConverter.Result p = PinyinConverter.convert(name);
        return new Employee(username, name, p.pinyin(), p.initials(), deptId, "", "", "", "", 0, "", false, false);
    }

    /**
     * 构造带手机号的在职员工。
     *
     * @param username 工号
     * @param name     姓名
     * @param deptId   部门 ID
     * @param mobile   手机
     * @return 员工
     */
    public static Employee employee(String username, String name, String deptId, String mobile) {
        PinyinConverter.Result p = PinyinConverter.convert(name);
        return new Employee(username, name, p.pinyin(), p.initials(), deptId, "", mobile, "", "", 0, "", false, false);
    }
}
