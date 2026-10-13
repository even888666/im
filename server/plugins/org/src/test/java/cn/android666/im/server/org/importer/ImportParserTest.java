package cn.android666.im.server.org.importer;

import org.junit.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** {@link ImportParser} 测试 */
public class ImportParserTest {

    private static final List<String> HEADER = List.of("工号", "姓名", "部门路径", "职位", "手机", "座机", "邮箱", "初始密码");

    @Test
    public void parse_fullRow_trimsAndLowercases() {
        ImportParser.Result r = ImportParser.parse(List.of(HEADER,
                List.of(" ZS001 ", "张三", "总部/ 研发中心 //Android组", "工程师", "138", "010", "a@b.c", "Init1234")));
        assertThat(r.errors()).isEmpty();
        ImportRow row = r.rows().get(0);
        assertThat(row.rowNumber()).isEqualTo(2);
        assertThat(row.username()).isEqualTo("zs001");
        assertThat(row.deptPath()).containsExactly("总部", "研发中心", "Android组").inOrder();
        assertThat(row.initialPassword()).isEqualTo("Init1234");
    }

    /** 列顺序不限，表头可带说明，可选列可以没有 */
    @Test
    public void parse_columnOrderAndOptionalColumns() {
        ImportParser.Result r = ImportParser.parse(List.of(
                List.of("部门路径", "姓名（必填）", "工号(必填)"),
                List.of("总部", "李四", "10002")));
        ImportRow row = r.rows().get(0);
        assertThat(row.username()).isEqualTo("10002");
        assertThat(row.name()).isEqualTo("李四");
        assertThat(row.title()).isEmpty();
        assertThat(row.initialPassword()).isEmpty();
    }

    @Test
    public void parse_rowErrors_otherRowsKept() {
        ImportParser.Result r = ImportParser.parse(List.of(HEADER,
                List.of("", "张三", "总部"),
                List.of("10001", "", "总部"),
                List.of("10002", "李四", " / "),
                List.of("张三", "张三", "总部"),
                List.of("10003", "王五", "总部"),
                List.of(),
                List.of("10003", "王五2", "总部")));
        assertThat(r.rows()).hasSize(1);
        assertThat(r.errors()).containsExactly(
                "第 2 行：工号为空",
                "第 3 行：姓名为空",
                "第 4 行：部门路径为空",
                "第 5 行：工号只能包含字母、数字、点、下划线和横线：张三",
                "第 8 行：工号 10003 与第 6 行重复").inOrder();
    }

    @Test
    public void parse_missingRequiredColumn() {
        assertThrows(IllegalArgumentException.class, () -> ImportParser.parse(List.of(List.of("工号", "姓名"))));
        assertThrows(IllegalArgumentException.class, () -> ImportParser.parse(List.of()));
    }
}
