package cn.android666.im.server.org.db;

import cn.android666.im.server.org.TestDb;
import cn.android666.im.server.org.model.Department;
import cn.android666.im.server.org.model.Employee;
import cn.android666.im.server.org.model.OrgChange;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;

import static cn.android666.im.server.org.Fixtures.employee;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** {@link OrgDao} 测试：在 H2 上执行插件真实的建表脚本 */
public class OrgDaoTest {

    private OrgDao dao;

    @Before
    public void setUp() throws Exception {
        dao = new TestDb().dao();
    }

    @Test
    public void departments_insertUpdateDelete() throws SQLException {
        dao.inTransaction(c -> {
            dao.saveDepartment(c, new Department("d1", "", "总部", 10));
            dao.saveDepartment(c, new Department("d2", "d1", "研发", 20));
            return null;
        });
        dao.inTransaction(c -> {
            dao.saveDepartment(c, new Department("d2", "d1", "研发中心", 30));
            return null;
        });
        assertThat(dao.loadDepartments()).containsExactly(
                new Department("d1", "", "总部", 10), new Department("d2", "d1", "研发中心", 30));

        dao.inTransaction(c -> {
            dao.deleteDepartment(c, "d2");
            return null;
        });
        assertThat(dao.loadDepartments()).containsExactly(new Department("d1", "", "总部", 10));
    }

    @Test
    public void employees_insertUpdate_booleanFlags() throws SQLException {
        Employee e = employee("10001", "张三", "d1").withMustChangePassword(true);
        dao.inTransaction(c -> {
            dao.saveEmployee(c, e);
            return null;
        });
        assertThat(dao.loadEmployees()).containsExactly(e);

        Employee disabled = e.withDisabled(true).withMustChangePassword(false);
        dao.inTransaction(c -> {
            dao.saveEmployee(c, disabled);
            return null;
        });
        assertThat(dao.loadEmployees()).containsExactly(disabled);
    }

    /** 每次变更版本号加 1，按版本号查询变更 */
    @Test
    public void recordChange_versionIncrements() throws SQLException {
        assertThat(dao.currentVersion()).isEqualTo(0);
        long v1 = dao.inTransaction(c -> dao.recordChange(c, OrgChange.TYPE_DEPARTMENT, "d1", 1000));
        long v2 = dao.inTransaction(c -> dao.recordChange(c, OrgChange.TYPE_EMPLOYEE, "10001", 2000));
        assertThat(v1).isEqualTo(1);
        assertThat(v2).isEqualTo(2);
        assertThat(dao.currentVersion()).isEqualTo(2);
        assertThat(dao.changesSince(1)).containsExactly(new OrgChange(2, OrgChange.TYPE_EMPLOYEE, "10001"));
    }

    @Test
    public void nextDepartmentId_sequence() throws SQLException {
        assertThat(dao.inTransaction(dao::nextDepartmentId)).isEqualTo("d1001");
        assertThat(dao.inTransaction(dao::nextDepartmentId)).isEqualTo("d1002");
    }

    /** 事务中抛出异常时回滚：版本号和变更日志都不变 */
    @Test
    public void transaction_rollback() throws SQLException {
        assertThrows(SQLException.class, () -> dao.inTransaction(c -> {
            dao.recordChange(c, OrgChange.TYPE_DEPARTMENT, "d1", 1000);
            throw new SQLException("模拟失败");
        }));
        assertThat(dao.currentVersion()).isEqualTo(0);
        assertThat(dao.changesSince(0)).isEmpty();
    }

    /** 清理旧变更：记录被清理到的版本号 */
    @Test
    public void pruneChanges() throws SQLException {
        dao.inTransaction(c -> dao.recordChange(c, OrgChange.TYPE_DEPARTMENT, "d1", 1000));
        dao.inTransaction(c -> dao.recordChange(c, OrgChange.TYPE_DEPARTMENT, "d2", 2000));
        dao.inTransaction(c -> dao.recordChange(c, OrgChange.TYPE_DEPARTMENT, "d3", 3000));

        assertThat(dao.pruneChanges(2500)).isEqualTo(2);
        assertThat(dao.prunedVersion()).isEqualTo(2);
        assertThat(dao.changesSince(0)).containsExactly(new OrgChange(3, OrgChange.TYPE_DEPARTMENT, "d3"));
        // 没有可清理的变更时不改变已清理版本
        assertThat(dao.pruneChanges(2500)).isEqualTo(0);
        assertThat(dao.prunedVersion()).isEqualTo(2);
    }
}
