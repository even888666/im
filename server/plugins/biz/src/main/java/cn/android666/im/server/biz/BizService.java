package cn.android666.im.server.biz;

import cn.android666.im.server.biz.db.BizDao;
import cn.android666.im.server.biz.db.BizDao.MessageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 业务扩展插件的核心逻辑（与 Openfire 无关，便于单元测试）：敏感词库、消息记录与撤回校验。
 */
public class BizService {

    private static final Logger Log = LoggerFactory.getLogger(BizService.class);

    /** 消息记录保留时间：7 天（撤回时限只有 2 分钟，多留一些便于排查问题） */
    public static final long MESSAGE_RETENTION_MILLIS = 7L * 24 * 3600 * 1000;

    /** 敏感词最大长度（与表结构一致） */
    public static final int MAX_WORD_LENGTH = 100;

    /** 数据访问 */
    private final BizDao dao;

    /** 当前时间（毫秒） */
    private final LongSupplier clock;

    /** 撤回时限（毫秒），每次校验时读取（可在管理后台的系统属性中修改） */
    private final LongSupplier retractLimitMillis;

    /** 当前的敏感词过滤器，词库变化时整体替换 */
    private volatile SensitiveWordFilter filter = SensitiveWordFilter.EMPTY;

    /**
     * 创建服务。
     *
     * @param dao                数据访问
     * @param clock              当前时间（毫秒）
     * @param retractLimitMillis 撤回时限（毫秒）
     */
    public BizService(BizDao dao, LongSupplier clock, LongSupplier retractLimitMillis) {
        this.dao = dao;
        this.clock = clock;
        this.retractLimitMillis = retractLimitMillis;
    }

    /**
     * 从数据库加载敏感词库。
     *
     * @throws SQLException 数据库错误
     */
    public void loadWords() throws SQLException {
        filter = new SensitiveWordFilter(dao.sensitiveWords());
        Log.info("敏感词库已加载：{} 个", filter.size());
    }

    /**
     * 查找文本中的敏感词。
     *
     * @param text 文本，可为 null
     * @return 命中的词；没有时为 null
     */
    public String findSensitiveWord(String text) {
        return filter.find(text);
    }

    /**
     * 全部敏感词。
     *
     * @return 敏感词
     * @throws SQLException 数据库错误
     */
    public List<String> words() throws SQLException {
        return dao.sensitiveWords();
    }

    /**
     * 添加敏感词（去掉首尾空白，空白、过长、重复的忽略）并刷新词库。
     *
     * @param words 敏感词
     * @return 新增的个数
     * @throws SQLException 数据库错误
     */
    public int addWords(Collection<String> words) throws SQLException {
        Set<String> cleaned = new LinkedHashSet<>();
        for (String word : words) {
            String w = word.trim();
            if (!w.isEmpty() && w.length() <= MAX_WORD_LENGTH && !SensitiveWordFilter.normalize(w).isEmpty()) {
                cleaned.add(w);
            }
        }
        int added = dao.addSensitiveWords(new ArrayList<>(cleaned), clock.getAsLong());
        loadWords();
        Log.info("添加敏感词 {} 个", added);
        return added;
    }

    /**
     * 删除敏感词并刷新词库。
     *
     * @param word 敏感词
     * @return 是否删除了
     * @throws SQLException 数据库错误
     */
    public boolean deleteWord(String word) throws SQLException {
        boolean deleted = dao.deleteSensitiveWord(word);
        loadWords();
        Log.info("删除敏感词：{}，结果 {}", word, deleted);
        return deleted;
    }

    /**
     * 记录一条消息（撤回校验用）。
     *
     * @param msgId    消息 ID
     * @param receiver 接收方 bare JID
     * @param sender   发送方 bare JID
     * @throws SQLException 数据库错误
     */
    public void recordMessage(String msgId, String receiver, String sender) throws SQLException {
        dao.recordMessage(new MessageRecord(msgId, receiver, sender, clock.getAsLong()));
    }

    /**
     * 校验撤回。
     *
     * @param msgId     被撤回的消息 ID
     * @param receiver  原消息的接收方 bare JID（撤回报文的接收方）
     * @param retractor 撤回者 bare JID
     * @return 结果
     * @throws SQLException 数据库错误
     */
    public RetractRules.Decision checkRetract(String msgId, String receiver, String retractor) throws SQLException {
        MessageRecord original = dao.findMessage(msgId, receiver);
        return RetractRules.decide(original, retractor, clock.getAsLong(), retractLimitMillis.getAsLong());
    }

    /**
     * 清理过期的消息记录。
     *
     * @return 删除的条数
     * @throws SQLException 数据库错误
     */
    public int pruneMessages() throws SQLException {
        int deleted = dao.pruneMessages(clock.getAsLong() - MESSAGE_RETENTION_MILLIS);
        Log.info("清理消息记录 {} 条", deleted);
        return deleted;
    }
}
