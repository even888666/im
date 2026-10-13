package cn.android666.im.core.database

import cn.android666.im.core.database.entity.DepartmentEntity
import cn.android666.im.core.database.entity.EmployeeEntity

/** 数据库测试数据 */
object DbFixtures {

    /**
     * 构造部门。
     *
     * @param id 部门 ID
     * @param parentId 上级部门 ID
     * @param name 名称
     * @param sort 排序
     * @return 部门
     */
    fun dept(id: String, parentId: String, name: String, sort: Int = 0) = DepartmentEntity(id, parentId, name, sort, 0)

    /**
     * 构造员工。
     *
     * @param empNo 工号
     * @param name 姓名
     * @param pinyin 全拼
     * @param initials 首字母
     * @param deptId 部门 ID
     * @param mobile 手机
     * @param sort 部门内排序
     * @return 员工
     */
    fun emp(
        empNo: String,
        name: String,
        pinyin: String,
        initials: String,
        deptId: String,
        mobile: String = "",
        sort: Int = 0,
    ) = EmployeeEntity(
        jid = "$empNo@im.example.com", empNo = empNo, name = name, pinyin = pinyin, initials = initials, deptId = deptId,
        title = "", mobile = mobile, phone = "", email = "", sort = sort, avatarHash = "",
    )
}
