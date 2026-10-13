package cn.android666.im.server.org.model;

/**
 * 一条组织架构变更记录（增量同步用）。
 *
 * @param version    变更占用的版本号
 * @param entityType 实体类型：{@link #TYPE_DEPARTMENT} 或 {@link #TYPE_EMPLOYEE}
 * @param entityId   部门 ID 或工号
 */
public record OrgChange(long version, char entityType, String entityId) {

    /** 实体类型：部门 */
    public static final char TYPE_DEPARTMENT = 'D';

    /** 实体类型：员工 */
    public static final char TYPE_EMPLOYEE = 'E';
}
