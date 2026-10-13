package cn.android666.im.server.org.importer;

import java.util.List;

/**
 * 导入表中的一行员工（《IM功能清单.md》附录 A.5 导入模板）。
 *
 * @param rowNumber       在表格中的行号（从 1 开始，表头为第 1 行），用于提示错误位置
 * @param username        工号（已去空白、转小写）
 * @param name            姓名
 * @param deptPath        部门路径（从顶级到末级的部门名称）
 * @param title           职位
 * @param mobile          手机
 * @param phone           座机
 * @param email           邮箱
 * @param initialPassword 初始密码，为空时由系统随机生成
 */
public record ImportRow(
        int rowNumber,
        String username,
        String name,
        List<String> deptPath,
        String title,
        String mobile,
        String phone,
        String email,
        String initialPassword) {
}
