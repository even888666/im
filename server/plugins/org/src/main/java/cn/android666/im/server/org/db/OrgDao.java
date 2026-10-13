package cn.android666.im.server.org.db;

import cn.android666.im.server.org.model.Department;
import cn.android666.im.server.org.model.Employee;
import cn.android666.im.server.org.model.OrgChange;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 组织架构数据的数据库访问（表结构见 {@code database/org_postgresql.sql}）。
 *
 * SQL 只用各数据库通用的语法（不用 UPSERT / MERGE 等方言），PostgreSQL、MySQL、HSQLDB 都能执行；
 * 单元测试用 H2 内存数据库执行同样的建表脚本。
 *
 * 写操作需要和变更日志在同一个事务中完成，所以写方法都接收调用方传入的连接，由 {@link #inTransaction} 管理事务。
 */
public class OrgDao {

    /** 计数器：组织架构版本号 */
    static final String META_VERSION = "version";

    /** 计数器：已清理的变更日志的最大版本号 */
    static final String META_PRUNED_VERSION = "prunedVersion";

    /** 计数器：部门 ID 序号 */
    static final String META_DEPT_SEQ = "deptSeq";

    private static final String EMPLOYEE_COLUMNS = "username, name, pinyin, initials, deptId, title, mobile, phone, "
            + "email, sort, avatarHash, disabled, mustChangePassword";

    /** 数据库连接来源 */
    private final ConnectionSource connections;

    /**
     * 创建数据访问对象。
     *
     * @param connections 数据库连接来源
     */
    public OrgDao(ConnectionSource connections) {
        this.connections = connections;
    }

    /**
     * 事务中执行的操作。
     *
     * @param <T> 返回值类型
     */
    @FunctionalInterface
    public interface TransactionWork<T> {

        /**
         * 执行操作。
         *
         * @param connection 事务所在的连接
         * @return 操作结果
         * @throws SQLException 数据库错误（事务会回滚）
         */
        T run(Connection connection) throws SQLException;
    }

    /**
     * 在一个事务中执行操作：成功则提交，抛出异常则回滚。
     *
     * @param work 要执行的操作
     * @param <T>  返回值类型
     * @return 操作结果
     * @throws SQLException 数据库错误
     */
    public <T> T inTransaction(TransactionWork<T> work) throws SQLException {
        try (Connection connection = connections.get()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * 读取全部部门。
     *
     * @return 部门列表
     * @throws SQLException 数据库错误
     */
    public List<Department> loadDepartments() throws SQLException {
        try (Connection connection = connections.get();
             PreparedStatement ps = connection.prepareStatement("SELECT id, parentId, name, sort FROM imOrgDept");
             ResultSet rs = ps.executeQuery()) {
            List<Department> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new Department(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
            }
            return result;
        }
    }

    /**
     * 读取全部员工（含已禁用的）。
     *
     * @return 员工列表
     * @throws SQLException 数据库错误
     */
    public List<Employee> loadEmployees() throws SQLException {
        try (Connection connection = connections.get();
             PreparedStatement ps = connection.prepareStatement("SELECT " + EMPLOYEE_COLUMNS + " FROM imOrgEmployee");
             ResultSet rs = ps.executeQuery()) {
            List<Employee> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new Employee(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                        rs.getString(11), rs.getInt(12) != 0, rs.getInt(13) != 0));
            }
            return result;
        }
    }

    /**
     * 新增或更新部门。
     *
     * @param connection 事务所在的连接
     * @param department 部门
     * @throws SQLException 数据库错误
     */
    public void saveDepartment(Connection connection, Department department) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE imOrgDept SET parentId = ?, name = ?, sort = ? WHERE id = ?")) {
            update.setString(1, department.parentId());
            update.setString(2, department.name());
            update.setInt(3, department.sort());
            update.setString(4, department.id());
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO imOrgDept (id, parentId, name, sort) VALUES (?, ?, ?, ?)")) {
            insert.setString(1, department.id());
            insert.setString(2, department.parentId());
            insert.setString(3, department.name());
            insert.setInt(4, department.sort());
            insert.executeUpdate();
        }
    }

    /**
     * 删除部门。
     *
     * @param connection 事务所在的连接
     * @param id         部门 ID
     * @throws SQLException 数据库错误
     */
    public void deleteDepartment(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM imOrgDept WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    /**
     * 新增或更新员工。
     *
     * @param connection 事务所在的连接
     * @param employee   员工
     * @throws SQLException 数据库错误
     */
    public void saveEmployee(Connection connection, Employee employee) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE imOrgEmployee SET name = ?, pinyin = ?, initials = ?, deptId = ?, title = ?, mobile = ?, "
                        + "phone = ?, email = ?, sort = ?, avatarHash = ?, disabled = ?, mustChangePassword = ? "
                        + "WHERE username = ?")) {
            int i = bindEmployeeFields(update, employee, 1);
            update.setString(i, employee.username());
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO imOrgEmployee (name, pinyin, initials, deptId, title, mobile, phone, email, sort, "
                        + "avatarHash, disabled, mustChangePassword, username) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = bindEmployeeFields(insert, employee, 1);
            insert.setString(i, employee.username());
            insert.executeUpdate();
        }
    }

    /**
     * 绑定员工除工号以外的字段（UPDATE 与 INSERT 的参数顺序一致，工号放在最后）。
     *
     * @param ps       语句
     * @param employee 员工
     * @param start    第一个参数的下标
     * @return 下一个参数的下标（用于绑定工号）
     * @throws SQLException 数据库错误
     */
    private static int bindEmployeeFields(PreparedStatement ps, Employee employee, int start) throws SQLException {
        int i = start;
        ps.setString(i++, employee.name());
        ps.setString(i++, employee.pinyin());
        ps.setString(i++, employee.initials());
        ps.setString(i++, employee.deptId());
        ps.setString(i++, employee.title());
        ps.setString(i++, employee.mobile());
        ps.setString(i++, employee.phone());
        ps.setString(i++, employee.email());
        ps.setInt(i++, employee.sort());
        ps.setString(i++, employee.avatarHash());
        ps.setInt(i++, employee.disabled() ? 1 : 0);
        ps.setInt(i++, employee.mustChangePassword() ? 1 : 0);
        return i;
    }

    /**
     * 记录一条变更：版本号加 1，并写入变更日志。
     *
     * @param connection 事务所在的连接
     * @param entityType 实体类型（{@link OrgChange#TYPE_DEPARTMENT} / {@link OrgChange#TYPE_EMPLOYEE}）
     * @param entityId   部门 ID 或工号
     * @param time       变更时间（毫秒）
     * @return 本次变更的版本号
     * @throws SQLException 数据库错误
     */
    public long recordChange(Connection connection, char entityType, String entityId, long time) throws SQLException {
        long version = incrementMeta(connection, META_VERSION);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO imOrgChange (version, entityType, entityId, changeTime) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, version);
            ps.setString(2, String.valueOf(entityType));
            ps.setString(3, entityId);
            ps.setLong(4, time);
            ps.executeUpdate();
        }
        return version;
    }

    /**
     * 生成新的部门 ID：{@code d} + 递增序号。
     *
     * @param connection 事务所在的连接
     * @return 部门 ID
     * @throws SQLException 数据库错误
     */
    public String nextDepartmentId(Connection connection) throws SQLException {
        return "d" + incrementMeta(connection, META_DEPT_SEQ);
    }

    /**
     * 当前组织架构版本号。
     *
     * @return 版本号，没有任何变更时为 0
     * @throws SQLException 数据库错误
     */
    public long currentVersion() throws SQLException {
        try (Connection connection = connections.get()) {
            return readMeta(connection, META_VERSION);
        }
    }

    /**
     * 已清理的变更日志的最大版本号：客户端版本号小于它时无法增量同步。
     *
     * @return 版本号
     * @throws SQLException 数据库错误
     */
    public long prunedVersion() throws SQLException {
        try (Connection connection = connections.get()) {
            return readMeta(connection, META_PRUNED_VERSION);
        }
    }

    /**
     * 读取指定版本之后的变更。
     *
     * @param afterVersion 客户端已同步到的版本号
     * @return 变更列表，按版本号升序
     * @throws SQLException 数据库错误
     */
    public List<OrgChange> changesSince(long afterVersion) throws SQLException {
        try (Connection connection = connections.get();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT version, entityType, entityId FROM imOrgChange WHERE version > ? ORDER BY version")) {
            ps.setLong(1, afterVersion);
            try (ResultSet rs = ps.executeQuery()) {
                List<OrgChange> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new OrgChange(rs.getLong(1), rs.getString(2).charAt(0), rs.getString(3)));
                }
                return result;
            }
        }
    }

    /**
     * 清理早于指定时间的变更日志，并记录被清理的最大版本号。
     *
     * @param beforeTime 时间（毫秒），早于它的变更被删除
     * @return 删除的条数
     * @throws SQLException 数据库错误
     */
    public int pruneChanges(long beforeTime) throws SQLException {
        return inTransaction(connection -> {
            long maxVersion;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT MAX(version) FROM imOrgChange WHERE changeTime < ?")) {
                ps.setLong(1, beforeTime);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    maxVersion = rs.getLong(1);
                    if (rs.wasNull()) {
                        return 0;
                    }
                }
            }
            int deleted;
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM imOrgChange WHERE version <= ?")) {
                ps.setLong(1, maxVersion);
                deleted = ps.executeUpdate();
            }
            writeMeta(connection, META_PRUNED_VERSION, maxVersion);
            return deleted;
        });
    }

    /**
     * 计数器加 1 并返回新值。先 UPDATE 再读取：UPDATE 会锁住该行，并发事务按顺序取得不同的值。
     *
     * @param connection 事务所在的连接
     * @param name       计数器名称
     * @return 加 1 后的值
     * @throws SQLException 数据库错误
     */
    private static long incrementMeta(Connection connection, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE imOrgMeta SET metaValue = metaValue + 1 WHERE name = ?")) {
            ps.setString(1, name);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("缺少计数器 " + name);
            }
        }
        return readMeta(connection, name);
    }

    /**
     * 读取计数器。
     *
     * @param connection 连接
     * @param name       计数器名称
     * @return 值
     * @throws SQLException 数据库错误或计数器不存在
     */
    private static long readMeta(Connection connection, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT metaValue FROM imOrgMeta WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("缺少计数器 " + name);
                }
                return rs.getLong(1);
            }
        }
    }

    /**
     * 写入计数器。
     *
     * @param connection 事务所在的连接
     * @param name       计数器名称
     * @param value      新值
     * @throws SQLException 数据库错误
     */
    private static void writeMeta(Connection connection, String name, long value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE imOrgMeta SET metaValue = ? WHERE name = ?")) {
            ps.setLong(1, value);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }
}
