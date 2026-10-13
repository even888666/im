package cn.android666.im.server.biz;

import cn.android666.im.server.biz.db.BizDao;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.truth.Truth.assertThat;

/** {@link BizService}、{@link RetractRules}、{@link BizDao} 测试（H2 + 真实建表脚本） */
public class BizServiceTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private BizDao dao;
    private BizService service;

    @Before
    public void setUp() throws Exception {
        dao = new TestDb().dao();
        service = new BizService(dao, now::get, () -> 120_000L);
        service.loadWords();
    }

    @Test
    public void words_addDeleteReload() throws Exception {
        assertThat(service.addWords(List.of(" 赌博 ", "", "x".repeat(101), "赌博", "发票", "!!!"))).isEqualTo(2);
        assertThat(service.words()).containsExactly("发票", "赌博");
        assertThat(service.findSensitiveWord("要发票吗")).isEqualTo("发票");

        // 再次添加已存在的跳过
        assertThat(service.addWords(List.of("赌博"))).isEqualTo(0);

        assertThat(service.deleteWord("发票")).isTrue();
        assertThat(service.deleteWord("不存在")).isFalse();
        assertThat(service.findSensitiveWord("要发票吗")).isNull();

        // 新实例从数据库加载
        BizService reloaded = new BizService(dao, now::get, () -> 120_000L);
        reloaded.loadWords();
        assertThat(reloaded.findSensitiveWord("去赌博")).isEqualTo("赌博");
    }

    @Test
    public void retract_rules() throws Exception {
        service.recordMessage("m1", "b@x", "a@x");

        now.addAndGet(120_000);
        assertThat(service.checkRetract("m1", "b@x", "a@x")).isEqualTo(RetractRules.Decision.ALLOW);
        assertThat(service.checkRetract("m1", "b@x", "c@x")).isEqualTo(RetractRules.Decision.FORBIDDEN);
        // 没有记录（未知消息、或记录已清理）
        assertThat(service.checkRetract("m1", "other@x", "a@x")).isEqualTo(RetractRules.Decision.TIMEOUT);
        assertThat(service.checkRetract("nope", "b@x", "a@x")).isEqualTo(RetractRules.Decision.TIMEOUT);

        now.addAndGet(1);
        assertThat(service.checkRetract("m1", "b@x", "a@x")).isEqualTo(RetractRules.Decision.TIMEOUT);
    }

    /** 重发同一条消息：保留最早的记录（撤回时限从第一次发送算起） */
    @Test
    public void record_keepsFirst() throws Exception {
        service.recordMessage("m1", "b@x", "a@x");
        now.addAndGet(60_000);
        service.recordMessage("m1", "b@x", "a@x");

        assertThat(dao.findMessage("m1", "b@x").sentAt()).isEqualTo(1_000_000);
    }

    @Test
    public void prune() throws Exception {
        service.recordMessage("old", "b@x", "a@x");
        now.addAndGet(BizService.MESSAGE_RETENTION_MILLIS + 1);
        service.recordMessage("new", "b@x", "a@x");

        assertThat(service.pruneMessages()).isEqualTo(1);
        assertThat(dao.findMessage("old", "b@x")).isNull();
        assertThat(dao.findMessage("new", "b@x")).isNotNull();
    }
}
