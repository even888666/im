package cn.android666.im.server.org.importer;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 读取 Excel（.xlsx）和 CSV 表格，结果为"行 → 单元格文本"的二维列表。
 *
 * 不引入 POI / fastexcel 等第三方库：Openfire 的插件类加载器优先使用服务器自带的 commons-* 等旧版本库，
 * 第三方库容易出现版本冲突（决策记录 A37）。xlsx 本质是 zip 中的几个 XML 文件，用 JDK 的 zip 和 StAX 读取即可：
 * <ul>
 *   <li>{@code xl/workbook.xml} + {@code xl/_rels/workbook.xml.rels}：找到第一个工作表的文件；</li>
 *   <li>{@code xl/sharedStrings.xml}：共享字符串表（单元格类型 s 存的是下标）；</li>
 *   <li>{@code xl/worksheets/sheetN.xml}：单元格。</li>
 * </ul>
 * 只读取第一个工作表；日期、公式结果等按原始值读取（导入模板中都是文本列）。
 */
public final class TableReader {

    /** 单个 zip 条目的大小上限（防止 zip 炸弹） */
    private static final long MAX_ENTRY_BYTES = 50L * 1024 * 1024;

    /** 表格行数上限 */
    private static final int MAX_ROWS = 100_000;

    private TableReader() {
    }

    /**
     * 按文件名后缀读取表格。
     *
     * @param fileName 文件名（.xlsx 或 .csv）
     * @param data     文件内容
     * @return 各行单元格文本（行尾的空单元格已去掉）
     * @throws IOException              读取或解析失败
     * @throws IllegalArgumentException 不支持的文件类型
     */
    public static List<List<String>> read(String fileName, byte[] data) throws IOException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return readXlsx(data);
        }
        if (lower.endsWith(".csv")) {
            return readCsv(data);
        }
        throw new IllegalArgumentException("只支持 .xlsx 和 .csv 文件（旧版 .xls 请在 Excel 中另存为 .xlsx）");
    }

    // ---------------------------------------------------------------- CSV

    /**
     * 读取 CSV（RFC 4180：逗号分隔，双引号包围的字段可包含逗号、换行，"" 表示一个双引号）。
     * 编码自动识别：合法的 UTF-8（可带 BOM）按 UTF-8，否则按 GB18030（中文版 Excel 另存的 CSV 默认是 GBK）。
     *
     * @param data 文件内容
     * @return 各行单元格文本
     * @throws IOException 编码无法识别
     */
    public static List<List<String>> readCsv(byte[] data) throws IOException {
        String text = decode(data);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                // \r\n 算一个换行
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                addRow(rows, row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
            i++;
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            addRow(rows, row);
        }
        return rows;
    }

    /**
     * 识别编码并解码。
     *
     * @param data 文件内容
     * @return 文本（已去掉 UTF-8 BOM）
     * @throws IOException 无法解码
     */
    static String decode(byte[] data) throws IOException {
        int offset = 0;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, offset, data.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer).toString();
        } catch (CharacterCodingException e) {
            try {
                return Charset.forName("GB18030").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data)).toString();
            } catch (CharacterCodingException e2) {
                throw new IOException("无法识别 CSV 文件的编码（请使用 UTF-8 或 GBK）", e2);
            }
        }
    }

    // ---------------------------------------------------------------- XLSX

    /**
     * 读取 xlsx 的第一个工作表。
     *
     * @param data 文件内容
     * @return 各行单元格文本
     * @throws IOException 不是有效的 xlsx 文件
     */
    public static List<List<String>> readXlsx(byte[] data) throws IOException {
        Map<String, byte[]> entries = unzip(data);
        String sheetPath = firstSheetPath(entries);
        byte[] sheet = entries.get(sheetPath);
        if (sheet == null) {
            throw new IOException("Excel 文件中找不到工作表：" + sheetPath);
        }
        byte[] shared = entries.get("xl/sharedStrings.xml");
        List<String> sharedStrings = shared == null ? List.of() : readSharedStrings(shared);
        try {
            return readSheet(sheet, sharedStrings);
        } catch (XMLStreamException e) {
            throw new IOException("Excel 工作表格式错误", e);
        }
    }

    /**
     * 解压 xlsx 中的 XML 文件。
     *
     * 用 {@link ZipFile} 按中央目录读取，而不是 ZipInputStream 顺序读取：有些工具生成的 xlsx 在本地文件头中
     * 不写条目大小（ZipInputStream 会报 invalid entry size），中央目录中的信息总是完整的。
     * ZipFile 需要文件，所以先写入临时文件，读完删除。
     *
     * @param data 文件内容
     * @return 路径 → 内容
     * @throws IOException 不是 zip 文件或条目过大
     */
    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        Path temp = Files.createTempFile("org-import-", ".xlsx");
        try {
            Files.write(temp, data);
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry entry = all.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !(name.endsWith(".xml") || name.endsWith(".rels"))) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        entries.put(name, readLimited(in, name));
                    }
                }
            } catch (ZipException e) {
                throw new IOException("不是有效的 .xlsx 文件", e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        if (entries.isEmpty()) {
            throw new IOException("不是有效的 .xlsx 文件");
        }
        return entries;
    }

    /**
     * 读取一个 zip 条目，超过上限时报错（防止 zip 炸弹）。
     *
     * @param in   条目内容
     * @param name 条目名（用于错误提示）
     * @return 内容
     * @throws IOException 读取失败或超过上限
     */
    private static byte[] readLimited(InputStream in, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY_BYTES) {
                throw new IOException("Excel 文件过大：" + name);
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 找到第一个工作表的文件路径：workbook.xml 中第一个 sheet 的 r:id → workbook.xml.rels 中的 Target。
     *
     * @param entries 解压后的文件
     * @return 工作表路径，找不到时退回 {@code xl/worksheets/sheet1.xml}
     * @throws IOException XML 格式错误
     */
    private static String firstSheetPath(Map<String, byte[]> entries) throws IOException {
        String fallback = "xl/worksheets/sheet1.xml";
        byte[] workbook = entries.get("xl/workbook.xml");
        byte[] rels = entries.get("xl/_rels/workbook.xml.rels");
        if (workbook == null || rels == null) {
            return fallback;
        }
        try {
            String relId = null;
            XMLStreamReader r = newReader(workbook);
            while (r.hasNext() && relId == null) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "sheet".equals(r.getLocalName())) {
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        if ("id".equals(r.getAttributeLocalName(i))) {
                            relId = r.getAttributeValue(i);
                        }
                    }
                }
            }
            if (relId == null) {
                return fallback;
            }
            XMLStreamReader rr = newReader(rels);
            while (rr.hasNext()) {
                if (rr.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(rr.getLocalName())
                        && relId.equals(rr.getAttributeValue(null, "Id"))) {
                    String target = rr.getAttributeValue(null, "Target");
                    if (target.startsWith("/")) {
                        return target.substring(1);
                    }
                    return "xl/" + target;
                }
            }
            return fallback;
        } catch (XMLStreamException e) {
            throw new IOException("Excel 工作簿格式错误", e);
        }
    }

    /**
     * 读取共享字符串表：每个 si 的文本为其中所有 t 的拼接（富文本有多个 r/t），跳过拼音提示 rPh。
     *
     * @param xml sharedStrings.xml
     * @return 字符串列表
     * @throws IOException XML 格式错误
     */
    private static List<String> readSharedStrings(byte[] xml) throws IOException {
        List<String> result = new ArrayList<>();
        try {
            XMLStreamReader r = newReader(xml);
            StringBuilder current = null;
            int phoneticDepth = 0;
            boolean inText = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> current = new StringBuilder();
                        case "rPh" -> phoneticDepth++;
                        case "t" -> inText = phoneticDepth == 0;
                        default -> {
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> {
                            result.add(current == null ? "" : current.toString());
                            current = null;
                        }
                        case "rPh" -> phoneticDepth--;
                        case "t" -> inText = false;
                        default -> {
                        }
                    }
                } else if (inText && current != null
                        && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                    current.append(r.getText());
                }
            }
            return result;
        } catch (XMLStreamException e) {
            throw new IOException("Excel 共享字符串表格式错误", e);
        }
    }

    /**
     * 读取工作表的单元格。
     *
     * @param xml           sheetN.xml
     * @param sharedStrings 共享字符串表
     * @return 各行单元格文本
     * @throws XMLStreamException XML 格式错误
     */
    private static List<List<String>> readSheet(byte[] xml, List<String> sharedStrings) throws XMLStreamException {
        List<List<String>> rows = new ArrayList<>();
        XMLStreamReader r = newReader(xml);
        List<String> row = null;
        int rowNumber = 0;
        // 当前单元格
        String cellType = null;
        int cellColumn = -1;
        StringBuilder value = new StringBuilder();
        boolean inValue = false;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        // 行号可能跳跃（中间的空行没有 row 元素），补齐空行，保证行号与 Excel 一致
                        String ref = r.getAttributeValue(null, "r");
                        int number = ref == null ? rowNumber + 1 : Integer.parseInt(ref);
                        while (rowNumber + 1 < number && rows.size() < MAX_ROWS) {
                            rows.add(List.of());
                            rowNumber++;
                        }
                        rowNumber = number;
                        row = new ArrayList<>();
                    }
                    case "c" -> {
                        cellType = r.getAttributeValue(null, "t");
                        String ref = r.getAttributeValue(null, "r");
                        cellColumn = ref == null ? (row == null ? 0 : row.size()) : columnIndex(ref);
                        value.setLength(0);
                    }
                    case "v", "t" -> inValue = true;
                    default -> {
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> inValue = false;
                    case "c" -> {
                        if (row != null) {
                            while (row.size() < cellColumn) {
                                row.add("");
                            }
                            row.add(cellText(cellType, value.toString(), sharedStrings));
                        }
                    }
                    case "row" -> {
                        if (row != null) {
                            addRow(rows, row);
                            if (rows.size() >= MAX_ROWS) {
                                return rows;
                            }
                        }
                        row = null;
                    }
                    default -> {
                    }
                }
            } else if (inValue && (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)) {
                value.append(r.getText());
            }
        }
        return rows;
    }

    /**
     * 单元格的显示文本。
     *
     * @param type          单元格类型（s 共享字符串、inlineStr 内联字符串、str 公式字符串、b 布尔、空或 n 数字）
     * @param raw           v 或 is/t 中的原始值
     * @param sharedStrings 共享字符串表
     * @return 文本
     */
    static String cellText(String type, String raw, List<String> sharedStrings) {
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw.trim());
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if ("b".equals(type)) {
            return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        }
        if (type == null || "n".equals(type)) {
            // 数字：手机号等长数字按原样输出，不要科学计数法和多余的 .0
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                return "";
            }
            try {
                return new BigDecimal(trimmed).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return trimmed;
            }
        }
        return raw;
    }

    /**
     * 单元格引用（如 {@code AB12}）的列下标（从 0 开始）。
     *
     * @param ref 单元格引用
     * @return 列下标
     */
    static int columnIndex(String ref) {
        int column = 0;
        for (char c : ref.toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                column = column * 26 + (c - 'A' + 1);
            } else {
                break;
            }
        }
        return column - 1;
    }

    /**
     * 添加一行：去掉行尾的空单元格。
     *
     * @param rows 结果
     * @param row  行
     */
    private static void addRow(List<List<String>> rows, List<String> row) {
        int end = row.size();
        while (end > 0 && row.get(end - 1).trim().isEmpty()) {
            end--;
        }
        rows.add(new ArrayList<>(row.subList(0, end)));
    }

    /**
     * 创建安全的 XML 读取器：禁用 DTD 和外部实体（防止 XXE）。
     *
     * @param xml XML 内容
     * @return 读取器
     * @throws XMLStreamException 创建失败
     */
    private static XMLStreamReader newReader(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        InputStream in = new ByteArrayInputStream(xml);
        return factory.createXMLStreamReader(in);
    }
}
