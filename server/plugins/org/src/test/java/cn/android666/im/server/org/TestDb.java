package cn.android666.im.server.org;

import cn.android666.im.server.org.db.ConnectionSource;
import cn.android666.im.server.org.db.OrgDao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用数据库：H2 内存数据库（PostgreSQL 兼容模式），执行插件真实的建表脚本 {@code org_postgresql.sql}。
 * 每个实例是一个独立的数据库。
 */
public final class TestDb {

    /** 数据库序号，保证每个实例的库名不同 */
    private static final AtomicInteger SEQ = new AtomicInteger();

    /** JDBC 地址 */
    private final String url;

    /** 保持一个连接不关闭，内存数据库才不会被销毁 */
    private final Connection keepAlive;

    /**
     * 创建数据库并建表。
     *
     * @throws Exception 建表失败
     */
    public TestDb() throws Exception {
        url = "jdbc:h2:mem:org" + SEQ.incrementAndGet() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        keepAlive = DriverManager.getConnection(url);
        try (Statement st = keepAlive.createStatement()) {
            // Openfire 自带的版本表，建表脚本最后一行会写入它
            st.execute("CREATE TABLE ofVersion (name VARCHAR(50) NOT NULL PRIMARY KEY, version INTEGER NOT NULL)");
            for (String sql : script().split(";")) {
                String statement = stripComments(sql).trim();
                if (!statement.isEmpty()) {
                    st.execute(statement);
                }
            }
        }
    }

    /**
     * 连接来源。
     *
     * @return 每次返回新连接
     */
    public ConnectionSource connections() {
        return () -> DriverManager.getConnection(url);
    }

    /**
     * 数据访问对象。
     *
     * @return DAO
     */
    public OrgDao dao() {
        return new OrgDao(connections());
    }

    /**
     * 执行一条 SQL（测试中准备或检查数据）。
     *
     * @param sql SQL
     * @throws SQLException 执行失败
     */
    public void execute(String sql) throws SQLException {
        try (Statement st = keepAlive.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * 读取插件的建表脚本。
     *
     * @return 脚本内容
     * @throws IOException 读取失败
     */
    static String script() throws IOException {
        return Files.readString(Path.of("src/main/plugin/database/org_postgresql.sql"), StandardCharsets.UTF_8);
    }

    /**
     * 去掉 SQL 中的 -- 注释行。
     *
     * @param sql SQL
     * @return 去掉注释后的 SQL
     */
    private static String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.trim().startsWith("--")) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
