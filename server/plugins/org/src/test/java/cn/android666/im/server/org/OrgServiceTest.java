package cn.android666.im.server.org;

import cn.android666.im.server.org.model.Department;
import cn.android666.im.server.org.model.Employee;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static cn.android666.im.server.org.Fixtures.employee;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** {@link OrgService} 测试：部门 / 员工维护、版本号、同步分页、下级查询、搜索 */
public class OrgServiceTest {

    private TestDb db;
    private MutableClock clock;
    private OrgService service;

    @Before
    public void setUp() throws Exception {
        db = new TestDb();
        clock = new MutableClock(1_000_000);
        service = new OrgService(db.dao(), clock);
        service.load();
    }

    /** 准备：总部 / 研发中心、总部 / 市场部，3 名员工 */
    private void seed() throws SQLException {
        String rd = service.ensureDepartmentPath(List.of("总部", "研发中心"));
        String mk = service.ensureDepartmentPath(List.of("总部", "市场部"));
        service.saveEmployee(employee("10001", "张三", rd, "13800000001"));
        service.saveEmployee(employee("10002", "李四", rd, "13800000002"));
        service.saveEmployee(employee("10003", "王五", mk, "13800000003"));
    }

    @Test
    public void ensureDepartmentPath_createsMissingAndReusesExisting() throws SQLException {
        String a = service.ensureDepartmentPath(List.of("总部", "研发中心", "Android组"));
        String b = service.ensureDepartmentPath(List.of(" 总部 ", "研发中心", "Android组"));
        assertThat(b).isEqualTo(a);
        assertThat(service.departmentCount()).isEqualTo(3);
        assertThat(service.path(a)).isEqualTo("总部/研发中心/Android组");
    }

    @Test
    public void memberCount_onlyActiveDirectMembers() throws SQLException {
        seed();
        String rd = service.ensureDepartmentPath(List.of("总部", "研发中心"));
        String hq = service.ensureDepartmentPath(List.of("总部"));
        assertThat(service.memberCount(rd)).isEqualTo(2);
        assertThat(service.memberCount(hq)).isEqualTo(0);

        service.setDisabled("10001", true);
        assertThat(service.memberCount(rd)).isEqualTo(1);
    }

    /** 重新加载后数据与版本号一致（数据确实写入了数据库） */
    @Test
    public void load_restoresState() throws SQLException {
        seed();
        long version = service.version();
        OrgService reloaded = new OrgService(db.dao(), clock);
        reloaded.load();
        assertThat(reloaded.version()).isEqualTo(version);
        assertThat(reloaded.allEmployees()).isEqualTo(service.allEmployees());
        assertThat(reloaded.allDepartments()).isEqualTo(service.allDepartments());
    }

    @Test
    public void mustChangePassword_notVisibleChange_noVersionBump() throws SQLException {
        seed();
        long version = service.version();
        assertThat(service.setMustChangePassword("10001", true)).isTrue();
        assertThat(service.mustChangePassword("10001")).isTrue();
        assertThat(service.version()).isEqualTo(version);
        assertThat(service.setMustChangePassword("10001", true)).isFalse();
        // 不在组织架构中的账号（如 admin）不需要改密
        assertThat(service.mustChangePassword("admin")).isFalse();
    }

    // ---------------------------------------------------------------- 部门维护

    @Test
    public void department_updateAndDelete() throws SQLException {
        Department hq = service.createDepartment("", "总部", 10);
        Department rd = service.createDepartment(hq.id(), "研发", 10);
        service.updateDepartment(new Department(rd.id(), hq.id(), "研发中心", 5));
        assertThat(service.department(rd.id()).orElseThrow().name()).isEqualTo("研发中心");

        // 有下级部门的部门不能删除
        assertThrows(IllegalArgumentException.class, () -> service.deleteDepartment(hq.id()));
        service.deleteDepartment(rd.id());
        assertThat(service.department(rd.id())).isEmpty();
    }

    @Test
    public void department_cannotMoveUnderItself() throws SQLException {
        Department hq = service.createDepartment("", "总部", 10);
        Department rd = service.createDepartment(hq.id(), "研发", 10);
        assertThrows(IllegalArgumentException.class,
                () -> service.updateDepartment(new Department(hq.id(), rd.id(), "总部", 10)));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateDepartment(new Department(hq.id(), hq.id(), "总部", 10)));
    }

    @Test
    public void department_withMembersCannotBeDeleted() throws SQLException {
        seed();
        String mk = service.ensureDepartmentPath(List.of("总部", "市场部"));
        // 已禁用的员工也算（禁用的员工仍属于该部门，启用后需要回到原部门）
        service.setDisabled("10003", true);
        assertThrows(IllegalArgumentException.class, () -> service.deleteDepartment(mk));
    }

    @Test
    public void saveEmployee_unknownDepartment_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.saveEmployee(employee("1", "张三", "d404")));
    }

    // ---------------------------------------------------------------- 下级与批量查询

    @Test
    public void children_departmentsAndPagedEmployees() throws SQLException {
        seed();
        String rd = service.ensureDepartmentPath(List.of("总部", "研发中心"));
        String hq = service.ensureDepartmentPath(List.of("总部"));

        OrgService.Children top = service.children("", -1, 200);
        assertThat(top.departments()).hasSize(1);
        assertThat(top.departments().get(0).id()).isEqualTo(hq);

        OrgService.Children hqChildren = service.children(hq, -1, 200);
        assertThat(hqChildren.departments().stream().map(Department::name).toList()).containsExactly("研发中心", "市场部");

        // 员工按 sort、拼音排序：李四（lisi）在张三（zhangsan）前面；每页 1 人
        OrgService.Children page1 = service.children(rd, -1, 1);
        assertThat(page1.employees().stream().map(Employee::username).toList()).containsExactly("10002");
        assertThat(page1.firstIndex()).isEqualTo(0);
        assertThat(page1.total()).isEqualTo(2);
        OrgService.Children page2 = service.children(rd, 0, 1);
        assertThat(page2.employees().stream().map(Employee::username).toList()).containsExactly("10001");
        OrgService.Children page3 = service.children(rd, 1, 1);
        assertThat(page3.employees()).isEmpty();
    }

    @Test
    public void employees_missingAndDisabledAreNull() throws SQLException {
        seed();
        service.setDisabled("10002", true);
        List<Employee> result = service.employees(Arrays.asList("10001", "10002", "99999", null));
        assertThat(result.get(0).username()).isEqualTo("10001");
        assertThat(result.subList(1, 4)).containsExactly(null, null, null);
    }

    // ---------------------------------------------------------------- 同步

    /**
     * 按页拉取全部同步数据。
     *
     * @param clientVersion 客户端版本号
     * @param pageSize      每页条数
     * @return 全部页面
     */
    private List<OrgService.SyncPage> syncAll(long clientVersion, int pageSize) throws SQLException {
        List<OrgService.SyncPage> pages = new ArrayList<>();
        String after = null;
        while (true) {
            OrgService.SyncPage page = service.sync(clientVersion, after, pageSize);
            pages.add(page);
            if (page.items().isEmpty()) {
                return pages;
            }
            after = page.items().get(page.items().size() - 1).key();
        }
    }

    @Test
    public void sync_versionZero_fullWithPaging_departmentsFirst() throws SQLException {
        seed();
        List<OrgService.SyncPage> pages = syncAll(0, 2);
        // 3 个部门 + 3 名员工，每页 2 条 → 3 页数据 + 1 个空页
        assertThat(pages).hasSize(4);
        assertThat(pages.stream().allMatch(OrgService.SyncPage::full)).isTrue();
        assertThat(pages.stream().allMatch(p -> p.total() == 6)).isTrue();
        List<OrgService.SyncItem> items = pages.stream().flatMap(p -> p.items().stream()).toList();
        assertThat(items.stream().map(OrgService.SyncItem::isDepartment).toList())
                .containsExactly(true, true, true, false, false, false).inOrder();
        assertThat(items.stream().noneMatch(OrgService.SyncItem::isDelete)).isTrue();
    }

    @Test
    public void sync_full_excludesDisabledEmployees() throws SQLException {
        seed();
        service.setDisabled("10003", true);
        List<OrgService.SyncItem> items = service.sync(0, null, 500).items();
        assertThat(items.stream().filter(i -> !i.isDepartment()).map(i -> i.employee().username()).toList())
                .containsExactly("10001", "10002");
    }

    @Test
    public void sync_incremental_upsertAndDelete() throws SQLException {
        seed();
        long v = service.version();
        String rd = service.ensureDepartmentPath(List.of("总部", "研发中心"));

        // 改名、禁用、新部门
        service.saveEmployee(employee("10001", "张三丰", rd, "13800000001"));
        service.setDisabled("10002", true);
        Department test = service.createDepartment(rd, "测试组", 30);

        OrgService.SyncPage page = service.sync(v, null, 500);
        assertThat(page.full()).isFalse();
        assertThat(page.version()).isEqualTo(service.version());
        // 研发中心（人数变化）、测试组、10001（upsert）、10002（delete）
        List<String> keys = page.items().stream().map(OrgService.SyncItem::key).toList();
        assertThat(keys).containsExactly("d:" + rd, "d:" + test.id(), "e:10001", "e:10002").inOrder();
        OrgService.SyncItem e1 = page.items().get(2);
        assertThat(e1.employee().name()).isEqualTo("张三丰");
        assertThat(page.items().get(3).isDelete()).isTrue();
        assertThat(page.items().get(3).deletedId()).isEqualTo("10002");
    }

    @Test
    public void sync_deletedDepartment() throws SQLException {
        Department hq = service.createDepartment("", "总部", 10);
        long v = service.version();
        service.deleteDepartment(hq.id());
        OrgService.SyncItem item = service.sync(v, null, 500).items().get(0);
        assertThat(item.isDepartment()).isTrue();
        assertThat(item.isDelete()).isTrue();
        assertThat(item.deletedId()).isEqualTo(hq.id());
    }

    @Test
    public void sync_upToDate_empty() throws SQLException {
        seed();
        OrgService.SyncPage page = service.sync(service.version(), null, 500);
        assertThat(page.full()).isFalse();
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(0);
    }

    /** 客户端版本号比服务器新（服务器数据被重建）：全量 */
    @Test
    public void sync_clientAhead_full() throws SQLException {
        seed();
        assertThat(service.sync(service.version() + 100, null, 500).full()).isTrue();
    }

    /** 变更日志已清理（超过 30 天）：旧版本号返回全量，清理之后的版本号仍可增量 */
    @Test
    public void sync_afterPrune_oldVersionFull() throws SQLException {
        seed();
        long old = service.version() - 1;
        clock.advance(OrgService.CHANGE_RETENTION_MS + 1);
        String rd = service.ensureDepartmentPath(List.of("总部", "研发中心"));
        service.saveEmployee(employee("10004", "赵六", rd));
        long afterSeed = service.version() - 2;

        assertThat(service.pruneChanges()).isGreaterThan(0);
        assertThat(service.sync(old, null, 500).full()).isTrue();
        // 清理到的版本号本身仍可以增量（之后的变更都还在）
        OrgService.SyncPage page = service.sync(afterSeed, null, 500);
        assertThat(page.full()).isFalse();
        assertThat(page.items().stream().map(OrgService.SyncItem::key).toList()).contains("e:10004");
    }

    // ---------------------------------------------------------------- 搜索

    @Test
    public void search_rankingAndLimit() throws SQLException {
        seed();
        String rd = service.ensureDepartmentPath(List.of("总部", "研发中心"));
        service.saveEmployee(employee("10004", "张三丰", rd));

        assertThat(service.search("张三", 20).stream().map(Employee::username).toList())
                .containsExactly("10001", "10004").inOrder();
        assertThat(service.search("zs", 20).stream().map(Employee::username).toList()).contains("10001");
        assertThat(service.search("研发", 20)).hasSize(3);
        assertThat(service.search("研发", 2)).hasSize(2);
        // 上级部门名称也能搜到（王五在 总部/市场部）
        assertThat(service.search("总部", 20)).hasSize(4);
        assertThat(service.search("  ", 20)).isEmpty();
    }

    @Test
    public void search_excludesDisabled() throws SQLException {
        seed();
        service.setDisabled("10001", true);
        assertThat(service.search("张三", 20)).isEmpty();
    }
}
