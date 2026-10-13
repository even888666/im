package cn.android666.im.server.org.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把表格内容解析为 {@link ImportRow}：第一行为表头，按列名识别各列（列的顺序不限），
 * 列名见《IM功能清单.md》附录 A.5：工号、姓名、部门路径、职位、手机、座机、邮箱、初始密码。
 */
public final class ImportParser {

    /** 列名：工号（必填） */
    static final String COL_USERNAME = "工号";

    /** 列名：姓名（必填） */
    static final String COL_NAME = "姓名";

    /** 列名：部门路径（必填） */
    static final String COL_DEPT = "部门路径";

    /** 列名：职位 */
    static final String COL_TITLE = "职位";

    /** 列名：手机 */
    static final String COL_MOBILE = "手机";

    /** 列名：座机 */
    static final String COL_PHONE = "座机";

    /** 列名：邮箱 */
    static final String COL_EMAIL = "邮箱";

    /** 列名：初始密码 */
    static final String COL_PASSWORD = "初始密码";

    /** 工号允许的字符（《三端协议规范.md》§1.1） */
    static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9._-]{1,64}$");

    /**
     * 解析结果。
     *
     * @param rows   解析成功的行
     * @param errors 解析失败的行及原因（如"第 3 行：工号为空"）
     */
    public record Result(List<ImportRow> rows, List<String> errors) {
    }

    private ImportParser() {
    }

    /**
     * 解析表格。
     *
     * @param table 表格内容（第一行为表头）
     * @return 解析结果
     * @throws IllegalArgumentException 表格为空或缺少必填列
     */
    public static Result parse(List<List<String>> table) {
        if (table.isEmpty()) {
            throw new IllegalArgumentException("表格为空");
        }
        Map<String, Integer> columns = new HashMap<>();
        List<String> header = table.get(0);
        for (int i = 0; i < header.size(); i++) {
            // 表头允许带"（必填）"之类的说明和空白
            String name = header.get(i).trim().replaceAll("[（(].*[）)]", "").trim();
            columns.putIfAbsent(name, i);
        }
        for (String required : List.of(COL_USERNAME, COL_NAME, COL_DEPT)) {
            if (!columns.containsKey(required)) {
                throw new IllegalArgumentException("缺少必填列：" + required + "（表头应为：工号、姓名、部门路径、职位、手机、座机、邮箱、初始密码）");
            }
        }

        List<ImportRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (int r = 1; r < table.size(); r++) {
            List<String> cells = table.get(r);
            int rowNumber = r + 1;
            if (cells.stream().allMatch(c -> c.trim().isEmpty())) {
                continue;
            }
            String username = cell(cells, columns, COL_USERNAME).toLowerCase(Locale.ROOT);
            String name = cell(cells, columns, COL_NAME);
            List<String> deptPath = Arrays.stream(cell(cells, columns, COL_DEPT).split("/"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            String error = null;
            if (username.isEmpty()) {
                error = "工号为空";
            } else if (!USERNAME_PATTERN.matcher(username).matches()) {
                error = "工号只能包含字母、数字、点、下划线和横线：" + username;
            } else if (name.isEmpty()) {
                error = "姓名为空";
            } else if (deptPath.isEmpty()) {
                error = "部门路径为空";
            } else if (seen.containsKey(username)) {
                error = "工号 " + username + " 与第 " + seen.get(username) + " 行重复";
            }
            if (error != null) {
                errors.add("第 " + rowNumber + " 行：" + error);
                continue;
            }
            seen.put(username, rowNumber);
            rows.add(new ImportRow(rowNumber, username, name, deptPath,
                    cell(cells, columns, COL_TITLE),
                    cell(cells, columns, COL_MOBILE),
                    cell(cells, columns, COL_PHONE),
                    cell(cells, columns, COL_EMAIL),
                    cell(cells, columns, COL_PASSWORD)));
        }
        return new Result(rows, errors);
    }

    /**
     * 读取单元格（去掉首尾空白）。
     *
     * @param cells   一行的单元格
     * @param columns 列名 → 下标
     * @param column  列名
     * @return 文本；没有该列或单元格为空时为空字符串
     */
    private static String cell(List<String> cells, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        return index == null || index >= cells.size() ? "" : cells.get(index).trim();
    }
}
