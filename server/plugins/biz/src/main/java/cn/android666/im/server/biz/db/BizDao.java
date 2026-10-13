package cn.android666.im.server.biz.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 业务扩展插件的数据访问：消息记录（撤回校验）、敏感词。建表脚本见插件包的 database/biz_*.sql。
 */
public class BizDao {

    /** 数据库连接来源 */
    private final ConnectionSource connections;

    /**
     * 消息记录。
     *
     * @param msgId    消息 ID
     * @param receiver 接收方 bare JID（单聊为对方，群聊为群 JID）
     * @param sender   发送方 bare JID
     * @param sentAt   服务器收到消息的时间（毫秒）
     */
    public record MessageRecord(String msgId, String receiver, String sender, long sentAt) {
    }

    /**
     * 创建 DAO。
     *
     * @param connections 数据库连接来源
     */
    public BizDao(ConnectionSource connections) {
        this.connections = connections;
    }

    /**
     * 记录一条消息；同一接收方、同一消息 ID 已存在时（重发）保留最早的记录。
     *
     * @param record 消息记录
     * @throws SQLException 数据库错误
     */
    public void recordMessage(MessageRecord record) throws SQLException {
        try (Connection c = connections.get()) {
            try (PreparedStatement exists = c.prepareStatement("SELECT 1 FROM imBizMessage WHERE msgId = ? AND receiver = ?")) {
                exists.setString(1, record.msgId());
                exists.setString(2, record.receiver());
                try (ResultSet rs = exists.executeQuery()) {
                    if (rs.next()) {
                        return;
                    }
                }
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO imBizMessage (msgId, receiver, sender, sentAt) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, record.msgId());
                insert.setString(2, record.receiver());
                insert.setString(3, record.sender());
                insert.setLong(4, record.sentAt());
                insert.executeUpdate();
            } catch (SQLException e) {
                // 并发重发时可能已被另一条插入：主键冲突时忽略
                if (!isDuplicate(c, record)) {
                    throw e;
                }
            }
        }
    }

    /**
     * 判断记录是否已存在（插入失败后确认是不是主键冲突）。
     *
     * @param c      连接
     * @param record 记录
     * @return 已存在时为 true
     * @throws SQLException 数据库错误
     */
    private static boolean isDuplicate(Connection c, MessageRecord record) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT 1 FROM imBizMessage WHERE msgId = ? AND receiver = ?")) {
            st.setString(1, record.msgId());
            st.setString(2, record.receiver());
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * 查询消息记录。
     *
     * @param msgId    消息 ID
     * @param receiver 接收方 bare JID
     * @return 记录；没有时为 null
     * @throws SQLException 数据库错误
     */
    public MessageRecord findMessage(String msgId, String receiver) throws SQLException {
        try (Connection c = connections.get();
             PreparedStatement st = c.prepareStatement(
                     "SELECT msgId, receiver, sender, sentAt FROM imBizMessage WHERE msgId = ? AND receiver = ?")) {
            st.setString(1, msgId);
            st.setString(2, receiver);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? new MessageRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) : null;
            }
        }
    }

    /**
     * 删除某时间之前的消息记录（定期清理）。
     *
     * @param before 时间（毫秒），不含
     * @return 删除的条数
     * @throws SQLException 数据库错误
     */
    public int pruneMessages(long before) throws SQLException {
        try (Connection c = connections.get();
             PreparedStatement st = c.prepareStatement("DELETE FROM imBizMessage WHERE sentAt < ?")) {
            st.setLong(1, before);
            return st.executeUpdate();
        }
    }

    /**
     * 全部敏感词（按词排序）。
     *
     * @return 敏感词
     * @throws SQLException 数据库错误
     */
    public List<String> sensitiveWords() throws SQLException {
        List<String> words = new ArrayList<>();
        try (Connection c = connections.get();
             PreparedStatement st = c.prepareStatement("SELECT word FROM imBizSensitiveWord ORDER BY word");
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                words.add(rs.getString(1));
            }
        }
        return words;
    }

    /**
     * 添加敏感词（已存在的跳过）。
     *
     * @param words 敏感词
     * @param now   当前时间（毫秒）
     * @return 新增的个数
     * @throws SQLException 数据库错误
     */
    public int addSensitiveWords(Collection<String> words, long now) throws SQLException {
        List<String> existing = sensitiveWords();
        int added = 0;
        try (Connection c = connections.get();
             PreparedStatement st = c.prepareStatement("INSERT INTO imBizSensitiveWord (word, createdAt) VALUES (?, ?)")) {
            for (String word : words) {
                if (existing.contains(word)) {
                    continue;
                }
                st.setString(1, word);
                st.setLong(2, now);
                st.executeUpdate();
                existing.add(word);
                added++;
            }
        }
        return added;
    }

    /**
     * 删除敏感词。
     *
     * @param word 敏感词
     * @return 是否删除了
     * @throws SQLException 数据库错误
     */
    public boolean deleteSensitiveWord(String word) throws SQLException {
        try (Connection c = connections.get();
             PreparedStatement st = c.prepareStatement("DELETE FROM imBizSensitiveWord WHERE word = ?")) {
            st.setString(1, word);
            return st.executeUpdate() > 0;
        }
    }
}
