package cn.android666.im.server.org.importer;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** {@link TableReader} 测试：CSV（UTF-8 / GBK、引号、换行）与 xlsx（用 fastexcel 生成） */
public class TableReaderTest {

    @Test
    public void csv_utf8WithBomQuotesAndNewlines() throws IOException {
        String text = "﻿工号,姓名,备注\r\n10001,张三,\"a,b\"\r\n10002,\"李\"\"四\",\"第一行\n第二行\"\n";
        List<List<String>> rows = TableReader.readCsv(text.getBytes(StandardCharsets.UTF_8));
        assertThat(rows).containsExactly(
                List.of("工号", "姓名", "备注"),
                List.of("10001", "张三", "a,b"),
                List.of("10002", "李\"四", "第一行\n第二行")).inOrder();
    }

    /** 中文版 Excel 另存的 CSV 是 GBK 编码 */
    @Test
    public void csv_gbk() throws IOException {
        byte[] data = "工号,姓名\n10001,张三".getBytes(Charset.forName("GBK"));
        assertThat(TableReader.readCsv(data)).containsExactly(List.of("工号", "姓名"), List.of("10001", "张三")).inOrder();
    }

    /** 行尾的空单元格去掉，空行保留为空列表 */
    @Test
    public void csv_trailingEmptyCells() throws IOException {
        List<List<String>> rows = TableReader.readCsv("a,b,,\n\nc".getBytes(StandardCharsets.UTF_8));
        assertThat(rows).containsExactly(List.of("a", "b"), List.of(), List.of("c")).inOrder();
    }

    @Test
    public void xlsx_textNumbersAndGaps() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(out, "test", "1.0")) {
            Worksheet ws = wb.newWorksheet("员工");
            ws.value(0, 0, "工号");
            ws.value(0, 1, "姓名");
            ws.value(0, 3, "手机");
            // 数字单元格：手机号不能变成科学计数法
            ws.value(1, 0, 10001);
            ws.value(1, 1, "张三");
            ws.value(1, 3, 13800000000L);
            // 第 3 行空，第 4 行有数据
            ws.value(3, 0, "10002");
            ws.value(3, 1, "李四");
        }
        List<List<String>> rows = TableReader.read("员工.XLSX", out.toByteArray());
        assertThat(rows).containsExactly(
                List.of("工号", "姓名", "", "手机"),
                List.of("10001", "张三", "", "13800000000"),
                List.of(),
                List.of("10002", "李四")).inOrder();
    }

    @Test
    public void cellText_numbers() {
        assertThat(TableReader.cellText(null, "1.38E10", List.of())).isEqualTo("13800000000");
        assertThat(TableReader.cellText("n", "12.50", List.of())).isEqualTo("12.5");
        assertThat(TableReader.cellText("b", "1", List.of())).isEqualTo("TRUE");
        assertThat(TableReader.cellText("s", "1", List.of("a", "b"))).isEqualTo("b");
        assertThat(TableReader.cellText("s", "9", List.of("a"))).isEqualTo("");
    }

    @Test
    public void columnIndex() {
        assertThat(TableReader.columnIndex("A1")).isEqualTo(0);
        assertThat(TableReader.columnIndex("Z9")).isEqualTo(25);
        assertThat(TableReader.columnIndex("AA10")).isEqualTo(26);
    }

    @Test
    public void unsupportedOrBrokenFiles() {
        assertThrows(IllegalArgumentException.class, () -> TableReader.read("a.xls", new byte[0]));
        assertThrows(IOException.class, () -> TableReader.read("a.xlsx", "not a zip".getBytes(StandardCharsets.UTF_8)));
    }
}
