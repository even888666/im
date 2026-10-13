package cn.android666.im.server.org;

import cn.android666.im.server.org.model.Employee;

import java.util.Locale;

/**
 * 搜索同事的匹配度打分（《三端协议规范.md》§4.5）。分数越高越靠前，0 表示不匹配。
 *
 * 规则（取各字段得分的最大值）：
 * <pre>
 * 姓名完全相同 100、工号完全相同 95、首字母完全相同 85、姓名开头 90、工号开头 80、全拼开头 75、
 * 首字母开头 72、姓名包含 70、手机包含（至少 3 位）60、部门路径（任一级部门名称）包含 50
 * </pre>
 * 客户端本地搜索采用同样的规则，结果顺序与服务器一致。
 */
public final class SearchScorer {

    private SearchScorer() {
    }

    /**
     * 计算匹配度。
     *
     * @param keyword  关键字（已去空白、转小写）
     * @param employee 员工
     * @param deptPath 员工所在部门的完整路径（如 {@code 总部/研发中心/Android组}），搜上级部门名称也能找到
     * @return 分数，0 表示不匹配
     */
    public static int score(String keyword, Employee employee, String deptPath) {
        String name = employee.name().toLowerCase(Locale.ROOT);
        int score = 0;
        if (name.equals(keyword)) {
            score = 100;
        } else if (name.startsWith(keyword)) {
            score = 90;
        } else if (name.contains(keyword)) {
            score = 70;
        }
        if (employee.username().equals(keyword)) {
            score = Math.max(score, 95);
        } else if (employee.username().startsWith(keyword)) {
            score = Math.max(score, 80);
        }
        if (employee.initials().equals(keyword)) {
            score = Math.max(score, 85);
        } else if (employee.initials().startsWith(keyword)) {
            score = Math.max(score, 72);
        }
        if (employee.pinyin().startsWith(keyword)) {
            score = Math.max(score, 75);
        }
        if (keyword.length() >= 3 && employee.mobile().contains(keyword)) {
            score = Math.max(score, 60);
        }
        if (!deptPath.isEmpty() && deptPath.toLowerCase(Locale.ROOT).contains(keyword)) {
            score = Math.max(score, 50);
        }
        return score;
    }
}
