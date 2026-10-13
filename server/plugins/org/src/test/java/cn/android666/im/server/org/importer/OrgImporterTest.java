package cn.android666.im.server.org.importer;

import cn.android666.im.server.org.MutableClock;
import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.PasswordRules;
import cn.android666.im.server.org.TestDb;
import cn.android666.im.server.org.model.Employee;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

/** {@link OrgImporter} 测试：账号用假实现，组织架构用 H2 */
public class OrgImporterTest {

    /** 假账号库：工号 → [密码, 姓名, 邮箱] */
    private final Map<String, String[]> accounts = new HashMap<>();

    private final AccountGateway gateway = new AccountGateway() {
        @Override
        public boolean exists(String username) {
            return accounts.containsKey(username);
        }

        @Override
        public void create(String username, String password, String name, String email) {
            accounts.put(username, new String[]{password, name, email});
        }

        @Override
        public void update(String username, String name, String email) {
            accounts.get(username)[1] = name;
            accounts.get(username)[2] = email;
        }
    };

    private OrgService service;
    private OrgImporter importer;

    @Before
    public void setUp() throws Exception {
        service = new OrgService(new TestDb().dao(), new MutableClock(0));
        service.load();
        importer = new OrgImporter(service, gateway);
    }

    private ImportResult importCsv(String csv, boolean mustChange) throws IOException {
        return importer.importFile("员工.csv", csv.getBytes(StandardCharsets.UTF_8), mustChange);
    }

    @Test
    public void newEmployees_accountsDepartmentsPinyin() throws IOException {
        ImportResult r = importCsv("工号,姓名,部门路径,职位,手机,座机,邮箱,初始密码\n"
                + "10001,张三,总部/研发中心,工程师,13800000001,,zs@example.com,Init1234\n"
                + "10002,李四,总部/市场部,,,,,\n", true);

        assertThat(r.created()).isEqualTo(2);
        assertThat(r.errors()).isEmpty();
        assertThat(accounts.get("10001")[0]).isEqualTo("Init1234");
        // 初始密码为空：随机生成并在结果中列出，且满足密码规则
        assertThat(r.generatedPasswords()).hasSize(1);
        ImportResult.GeneratedPassword generated = r.generatedPasswords().get(0);
        assertThat(generated.username()).isEqualTo("10002");
        assertThat(accounts.get("10002")[0]).isEqualTo(generated.password());
        assertThat(PasswordRules.isAcceptable(generated.password())).isTrue();

        Employee e = service.employee("10001").orElseThrow();
        assertThat(e.pinyin()).isEqualTo("zhangsan");
        assertThat(e.title()).isEqualTo("工程师");
        assertThat(e.mustChangePassword()).isTrue();
        assertThat(service.path(e.deptId())).isEqualTo("总部/研发中心");
        assertThat(service.departmentCount()).isEqualTo(3);
        assertThat(r.toText()).contains(generated.password());
    }

    /** 开发环境：新账号不强制改密 */
    @Test
    public void mustChangeFalse() throws IOException {
        importCsv("工号,姓名,部门路径\n10001,张三,总部\n", false);
        assertThat(service.mustChangePassword("10001")).isFalse();
    }

    /** 重复导入：更新信息，不改密码、不改禁用状态和强制改密标记，内容相同时计为未变化 */
    @Test
    public void reimport_updatesInfoKeepsAccountState() throws Exception {
        importCsv("工号,姓名,部门路径,初始密码\n10001,张三,总部,Init1234\n", true);
        service.setMustChangePassword("10001", false);
        service.setDisabled("10001", true);

        ImportResult r = importCsv("工号,姓名,部门路径,初始密码\n10001,张三丰,总部/研发,Other123\n10001x,,总部,\n", true);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.errors()).hasSize(1);
        assertThat(accounts.get("10001")[0]).isEqualTo("Init1234");
        assertThat(accounts.get("10001")[1]).isEqualTo("张三丰");
        Employee e = service.employee("10001").orElseThrow();
        assertThat(e.name()).isEqualTo("张三丰");
        assertThat(e.disabled()).isTrue();
        assertThat(e.mustChangePassword()).isFalse();
        assertThat(r.warnings()).hasSize(2);

        ImportResult again = importCsv("工号,姓名,部门路径\n10001,张三丰,总部/研发\n", true);
        assertThat(again.unchanged()).isEqualTo(1);
    }

    /** 账号已存在但组织架构中没有记录（如开发环境自动创建的测试账号）：补充员工信息，不强制改密 */
    @Test
    public void existingAccountWithoutEmployee() throws IOException {
        accounts.put("10001", new String[]{"Test1234", "旧名", ""});
        ImportResult r = importCsv("工号,姓名,部门路径\n10001,测试用户01,测试部\n", true);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(accounts.get("10001")[0]).isEqualTo("Test1234");
        assertThat(service.mustChangePassword("10001")).isFalse();
        assertThat(service.employee("10001").orElseThrow().name()).isEqualTo("测试用户01");
    }

    /** 某一行失败（创建账号出错）不影响其他行 */
    @Test
    public void rowFailure_isolated() {
        AccountGateway failing = new AccountGateway() {
            @Override
            public boolean exists(String username) {
                return false;
            }

            @Override
            public void create(String username, String password, String name, String email) throws Exception {
                if (username.equals("bad")) {
                    throw new Exception("模拟失败");
                }
                accounts.put(username, new String[]{password, name, email});
            }

            @Override
            public void update(String username, String name, String email) {
            }
        };
        OrgImporter imp = new OrgImporter(service, failing);
        ImportResult r = imp.importRows(List.of(
                new ImportRow(2, "bad", "坏", List.of("总部"), "", "", "", "", "Init1234"),
                new ImportRow(3, "good", "好", List.of("总部"), "", "", "", "", "Init1234")), true);
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.errors()).containsExactly("第 2 行：模拟失败");
    }

    @Test
    public void generatePassword_satisfiesRules() {
        for (int i = 0; i < 200; i++) {
            String p = importer.generatePassword();
            assertThat(p).hasLength(OrgImporter.GENERATED_PASSWORD_LENGTH);
            assertThat(PasswordRules.isAcceptable(p)).isTrue();
        }
    }
}
