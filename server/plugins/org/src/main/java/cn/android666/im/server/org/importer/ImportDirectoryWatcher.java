package cn.android666.im.server.org.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.TimerTask;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * 导入目录：把 .xlsx / .csv 文件放进 {@code {Openfire 目录}/org-import/}，插件定时（10 秒）检查并导入，
 * 导入后把文件移到 {@code done/}，并在旁边写入结果文件 {@code *.result.txt}（含生成的初始密码，权限 600）。
 *
 * 用于脚本化部署（开发环境的测试账号由 up.sh 放入）和不方便打开管理后台时的批量导入；管理后台的导入页面效果相同。
 */
public class ImportDirectoryWatcher extends TimerTask {

    private static final Logger Log = LoggerFactory.getLogger(ImportDirectoryWatcher.class);

    /** 文件名中的时间格式 */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 导入目录 */
    private final Path dir;

    /** 导入器 */
    private final OrgImporter importer;

    /** 新账号是否强制改密（读取系统属性，修改后立即生效） */
    private final BooleanSupplier mustChangePassword;

    /**
     * 创建导入目录监视任务。
     *
     * @param dir                导入目录（不存在时自动创建）
     * @param importer           导入器
     * @param mustChangePassword 新账号是否强制改密
     */
    public ImportDirectoryWatcher(Path dir, OrgImporter importer, BooleanSupplier mustChangePassword) {
        this.dir = dir;
        this.importer = importer;
        this.mustChangePassword = mustChangePassword;
    }

    /** 定时执行：导入目录中的全部文件 */
    @Override
    public void run() {
        try {
            scan();
        } catch (Exception e) {
            Log.error("检查导入目录 {} 失败", dir, e);
        }
    }

    /**
     * 检查并导入目录中的文件（按文件名顺序）。
     *
     * @return 处理的文件数
     * @throws IOException 目录读写失败
     */
    public int scan() throws IOException {
        Files.createDirectories(dir.resolve("done"));
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream.filter(Files::isRegularFile).filter(ImportDirectoryWatcher::isImportFile).sorted().toList();
        }
        for (Path file : files) {
            importOne(file);
        }
        return files.size();
    }

    /**
     * 导入一个文件，结果写入 done/ 目录。
     *
     * @param file 文件
     * @throws IOException 文件读写失败
     */
    private void importOne(Path file) throws IOException {
        String name = file.getFileName().toString();
        String stamp = LocalDateTime.now().format(STAMP);
        String text;
        try {
            ImportResult result = importer.importFile(name, Files.readAllBytes(file), mustChangePassword.getAsBoolean());
            text = result.toText();
        } catch (IOException | IllegalArgumentException e) {
            Log.warn("导入文件 {} 失败：{}", name, e.getMessage());
            text = "导入失败：" + e.getMessage() + "\n";
        }
        Path done = dir.resolve("done");
        Files.move(file, done.resolve(stamp + "-" + name), StandardCopyOption.REPLACE_EXISTING);
        Path resultFile = done.resolve(stamp + "-" + name + ".result.txt");
        Files.writeString(resultFile, text, StandardCharsets.UTF_8);
        try {
            // 结果中可能有初始密码，只允许 Openfire 进程的用户读取
            Files.setPosixFilePermissions(resultFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（Windows）忽略
        }
        Log.info("导入目录：已处理 {}，结果见 {}", name, resultFile);
    }

    /**
     * 是否为可导入的文件（.xlsx / .csv，忽略 Excel 打开时产生的 ~$ 临时文件和隐藏文件）。
     *
     * @param path 文件
     * @return 可导入时为 true
     */
    static boolean isImportFile(Path path) {
        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && !name.startsWith("~$") && (lower.endsWith(".xlsx") || lower.endsWith(".csv"));
    }
}
