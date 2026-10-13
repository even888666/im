package cn.android666.im.server.biz.db;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库连接来源。生产环境为 Openfire 的连接池（{@code DbConnectionManager::getConnection}），
 * 测试中为内存数据库，这样 {@link BizDao} 不依赖 Openfire 的静态单例，可以直接做单元测试。
 */
@FunctionalInterface
public interface ConnectionSource {

    /**
     * 取得一个连接，用完后由调用方关闭（连接池中的连接关闭即归还）。
     *
     * @return 数据库连接
     * @throws SQLException 获取连接失败
     */
    Connection get() throws SQLException;
}
