package cn.android666.im.data.org

import cn.android666.im.core.database.entity.EmployeeEntity

/**
 * 搜索同事的匹配度打分（协议规范 §4.5），与服务端 org 插件的 SearchScorer 规则一致，本地搜索与服务器搜索结果顺序相同。
 *
 * 取各字段得分的最大值：姓名完全相同 100、工号完全相同 95、姓名开头 90、首字母完全相同 85、工号开头 80、
 * 全拼开头 75、首字母开头 72、姓名包含 70、手机包含（至少 3 位）60、部门路径包含 50；0 表示不匹配。
 */
internal object SearchScorer {

    /**
     * 计算匹配度。
     *
     * @param keyword 关键字（已去空白、转小写）
     * @param employee 员工
     * @param deptPath 员工所在部门的完整路径（如 总部/研发中心/Android组）
     * @return 分数，0 表示不匹配
     */
    fun score(keyword: String, employee: EmployeeEntity, deptPath: String): Int {
        val name = employee.name.lowercase()
        var score = when {
            name == keyword -> 100
            name.startsWith(keyword) -> 90
            name.contains(keyword) -> 70
            else -> 0
        }
        if (employee.empNo == keyword) {
            score = maxOf(score, 95)
        } else if (employee.empNo.startsWith(keyword)) {
            score = maxOf(score, 80)
        }
        if (employee.initials == keyword) {
            score = maxOf(score, 85)
        } else if (employee.initials.startsWith(keyword)) {
            score = maxOf(score, 72)
        }
        if (employee.pinyin.startsWith(keyword)) score = maxOf(score, 75)
        if (keyword.length >= 3 && employee.mobile.contains(keyword)) score = maxOf(score, 60)
        if (deptPath.isNotEmpty() && deptPath.lowercase().contains(keyword)) score = maxOf(score, 50)
        return score
    }
}
