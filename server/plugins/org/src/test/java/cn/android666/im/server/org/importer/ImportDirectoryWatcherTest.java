package cn.android666.im.server.org.importer;

import cn.android666.im.server.org.MutableClock;
import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.TestDb;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/** {@link ImportDirectoryWatcher} 测试 */
public class ImportDirectoryWatcherTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void scan_importsMovesAndWritesResult() throws Exception {
        OrgService service = new OrgService(new TestDb().dao(), new MutableClock(0));
        service.load();
        Set<String> created = new HashSet<>();
        AccountGateway gateway = new AccountGateway() {
            @Override
            public boolean exists(String username) {
                return created.contains(username);
            }

            @Override
            public void create(String username, String password, String name, String email) {
                created.add(username);
            }

            @Override
            public void update(String username, String name, String email) {
            }
        };
        Path dir = folder.getRoot().toPath();
        Files.writeString(dir.resolve("a.csv"), "工号,姓名,部门路径\n10001,张三,总部\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("~$a.xlsx"), "临时文件", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("bad.csv"), "没有表头", StandardCharsets.UTF_8);

        ImportDirectoryWatcher watcher = new ImportDirectoryWatcher(dir, new OrgImporter(service, gateway), () -> true);
        assertThat(watcher.scan()).isEqualTo(2);

        assertThat(created).containsExactly("10001");
        assertThat(Files.exists(dir.resolve("a.csv"))).isFalse();
        assertThat(Files.exists(dir.resolve("~$a.xlsx"))).isTrue();
        List<Path> done;
        try (Stream<Path> s = Files.list(dir.resolve("done"))) {
            done = s.sorted().toList();
        }
        assertThat(done).hasSize(4);
        String result = Files.readString(done.stream().filter(p -> p.toString().endsWith("a.csv.result.txt")).findFirst().orElseThrow());
        assertThat(result).startsWith("新建 1 人");
        String badResult = Files.readString(done.stream().filter(p -> p.toString().endsWith("bad.csv.result.txt")).findFirst().orElseThrow());
        assertThat(badResult).startsWith("导入失败：缺少必填列");
        // 再次扫描没有新文件
        assertThat(watcher.scan()).isEqualTo(0);
    }
}
