package cn.android666.im.server.org.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * 导入结果（管理后台显示、导入目录写入结果文件）。
 */
public class ImportResult {

    /**
     * 系统生成的初始密码（初始密码列为空的新账号），需要管理员发给员工。
     *
     * @param username 工号
     * @param name     姓名
     * @param password 初始密码
     */
    public record GeneratedPassword(String username, String name, String password) {
    }

    /** 新建的账号数 */
    private int created;

    /** 更新的员工数（账号已存在） */
    private int updated;

    /** 内容没有变化的员工数 */
    private int unchanged;

    /** 失败的行及原因 */
    private final List<String> errors = new ArrayList<>();

    /** 提示信息（不影响导入，例如"已存在的账号不修改密码"） */
    private final List<String> warnings = new ArrayList<>();

    /** 系统生成的初始密码 */
    private final List<GeneratedPassword> generatedPasswords = new ArrayList<>();

    /** 新建数加 1 */
    void addCreated() {
        created++;
    }

    /** 更新数加 1 */
    void addUpdated() {
        updated++;
    }

    /** 未变化数加 1 */
    void addUnchanged() {
        unchanged++;
    }

    /**
     * 记录失败。
     *
     * @param error 原因（含行号）
     */
    void addError(String error) {
        errors.add(error);
    }

    /**
     * 记录提示。
     *
     * @param warning 提示（含行号）
     */
    void addWarning(String warning) {
        warnings.add(warning);
    }

    /**
     * 记录生成的初始密码。
     *
     * @param password 生成的密码
     */
    void addGeneratedPassword(GeneratedPassword password) {
        generatedPasswords.add(password);
    }

    /**
     * 新建的账号数。
     *
     * @return 数量
     */
    public int created() {
        return created;
    }

    /**
     * 更新的员工数。
     *
     * @return 数量
     */
    public int updated() {
        return updated;
    }

    /**
     * 内容没有变化的员工数。
     *
     * @return 数量
     */
    public int unchanged() {
        return unchanged;
    }

    /**
     * 失败的行及原因。
     *
     * @return 列表
     */
    public List<String> errors() {
        return errors;
    }

    /**
     * 提示信息。
     *
     * @return 列表
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * 系统生成的初始密码。
     *
     * @return 列表
     */
    public List<GeneratedPassword> generatedPasswords() {
        return generatedPasswords;
    }

    /**
     * 文本形式的结果（导入目录的结果文件、日志用）。
     *
     * @return 多行文本
     */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("新建 ").append(created).append(" 人，更新 ").append(updated).append(" 人，未变化 ")
                .append(unchanged).append(" 人，失败 ").append(errors.size()).append(" 行\n");
        if (!errors.isEmpty()) {
            sb.append("\n失败：\n");
            errors.forEach(e -> sb.append("  ").append(e).append('\n'));
        }
        if (!warnings.isEmpty()) {
            sb.append("\n提示：\n");
            warnings.forEach(w -> sb.append("  ").append(w).append('\n'));
        }
        if (!generatedPasswords.isEmpty()) {
            sb.append("\n系统生成的初始密码（请发给员工，首次登录需修改）：\n");
            generatedPasswords.forEach(p -> sb.append("  ").append(p.username()).append('\t').append(p.name())
                    .append('\t').append(p.password()).append('\n'));
        }
        return sb.toString();
    }
}
