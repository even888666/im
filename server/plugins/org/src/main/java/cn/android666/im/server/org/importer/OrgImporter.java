package cn.android666.im.server.org.importer;

import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.PinyinConverter;
import cn.android666.im.server.org.model.Employee;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

/**
 * Excel / CSV 导入（开发计划 3.3，《IM功能清单.md》附录 A.5）：
 * <ul>
 *   <li>以工号为准：已存在的员工更新信息，不存在的新建账号；表中没有的员工不删除（离职在后台单独禁用）；</li>
 *   <li>部门路径中不存在的部门自动创建；</li>
 *   <li>姓名自动生成拼音和首字母；</li>
 *   <li>新账号的初始密码为空时随机生成，并在结果中列出；新账号标记为首次登录强制改密；</li>
 *   <li>已存在的账号不修改密码、不改变禁用状态和强制改密标记，保留部门内排序和头像。</li>
 * </ul>
 */
public class OrgImporter {

    private static final Logger Log = LoggerFactory.getLogger(OrgImporter.class);

    /** 随机密码使用的字符（去掉了容易看错的 0 O o 1 l I） */
    private static final String PASSWORD_LETTERS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ";

    /** 随机密码使用的数字 */
    private static final String PASSWORD_DIGITS = "23456789";

    /** 随机密码长度 */
    static final int GENERATED_PASSWORD_LENGTH = 10;

    /** 组织架构服务 */
    private final OrgService orgService;

    /** 账号操作 */
    private final AccountGateway accounts;

    /** 随机数（生成初始密码） */
    private final SecureRandom random = new SecureRandom();

    /**
     * 创建导入器。
     *
     * @param orgService 组织架构服务
     * @param accounts   账号操作
     */
    public OrgImporter(OrgService orgService, AccountGateway accounts) {
        this.orgService = orgService;
        this.accounts = accounts;
    }

    /**
     * 导入文件。
     *
     * @param fileName           文件名（.xlsx / .csv）
     * @param data               文件内容
     * @param mustChangePassword 新账号是否标记为首次登录强制改密（生产环境为 true；开发环境的测试账号为 false）
     * @return 导入结果
     * @throws IOException              文件读取失败
     * @throws IllegalArgumentException 文件类型不支持或缺少必填列
     */
    public ImportResult importFile(String fileName, byte[] data, boolean mustChangePassword) throws IOException {
        ImportParser.Result parsed = ImportParser.parse(TableReader.read(fileName, data));
        ImportResult result = importRows(parsed.rows(), mustChangePassword);
        parsed.errors().forEach(result::addError);
        Log.info("导入 {}：{}", fileName, result.toText().lines().findFirst().orElse(""));
        return result;
    }

    /**
     * 逐行导入，某一行失败不影响其他行。
     *
     * @param rows               已解析的行
     * @param mustChangePassword 新账号是否强制改密
     * @return 导入结果
     */
    public ImportResult importRows(List<ImportRow> rows, boolean mustChangePassword) {
        ImportResult result = new ImportResult();
        for (ImportRow row : rows) {
            try {
                importRow(row, mustChangePassword, result);
            } catch (Exception e) {
                Log.warn("导入第 {} 行（工号 {}）失败", row.rowNumber(), row.username(), e);
                result.addError("第 " + row.rowNumber() + " 行：" + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        return result;
    }

    /**
     * 导入一行。
     *
     * @param row                行
     * @param mustChangePassword 新账号是否强制改密
     * @param result             导入结果
     * @throws Exception 导入失败
     */
    private void importRow(ImportRow row, boolean mustChangePassword, ImportResult result) throws Exception {
        String deptId = orgService.ensureDepartmentPath(row.deptPath());
        PinyinConverter.Result pinyin = PinyinConverter.convert(row.name());
        Optional<Employee> existing = orgService.employee(row.username());
        boolean accountExists = accounts.exists(row.username());

        if (!accountExists) {
            String password = row.initialPassword();
            if (password.isEmpty()) {
                password = generatePassword();
                result.addGeneratedPassword(new ImportResult.GeneratedPassword(row.username(), row.name(), password));
            }
            accounts.create(row.username(), password, row.name(), row.email());
        } else {
            accounts.update(row.username(), row.name(), row.email());
            if (!row.initialPassword().isEmpty()) {
                result.addWarning("第 " + row.rowNumber() + " 行：账号 " + row.username() + " 已存在，不修改其密码");
            }
        }

        Employee old = existing.orElse(null);
        Employee employee = new Employee(
                row.username(), row.name(), pinyin.pinyin(), pinyin.initials(), deptId,
                row.title(), row.mobile(), row.phone(), row.email(),
                old == null ? 0 : old.sort(),
                old == null ? "" : old.avatarHash(),
                old != null && old.disabled(),
                // 新账号按参数决定；已有账号（含组织架构中还没有记录的旧账号）保留原标记
                old == null ? (!accountExists && mustChangePassword) : old.mustChangePassword());
        if (old != null && old.disabled()) {
            result.addWarning("第 " + row.rowNumber() + " 行：员工 " + row.username() + " 已禁用，信息已更新但仍保持禁用");
        }
        if (employee.equals(old)) {
            result.addUnchanged();
            return;
        }
        orgService.saveEmployee(employee);
        if (accountExists) {
            result.addUpdated();
        } else {
            result.addCreated();
        }
    }

    /**
     * 生成随机初始密码：10 位，至少包含字母和数字，满足密码规则。
     *
     * @return 密码
     */
    String generatePassword() {
        String all = PASSWORD_LETTERS + PASSWORD_DIGITS;
        char[] chars = new char[GENERATED_PASSWORD_LENGTH];
        chars[0] = PASSWORD_LETTERS.charAt(random.nextInt(PASSWORD_LETTERS.length()));
        chars[1] = PASSWORD_DIGITS.charAt(random.nextInt(PASSWORD_DIGITS.length()));
        for (int i = 2; i < chars.length; i++) {
            chars[i] = all.charAt(random.nextInt(all.length()));
        }
        // 打乱顺序，避免固定"字母 + 数字"开头
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char t = chars[i];
            chars[i] = chars[j];
            chars[j] = t;
        }
        return new String(chars);
    }
}
