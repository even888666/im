package cn.android666.im.server.biz.xmpp;

import cn.android666.im.server.biz.BizService;
import cn.android666.im.server.biz.TestDb;
import cn.android666.im.server.biz.db.BizDao;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.junit.Before;
import org.junit.Test;
import org.xmpp.packet.Message;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** {@link BizMessageInterceptor} 测试：敏感词拒绝、消息记录、撤回校验（协议规范 §6.4、§8.4） */
public class BizMessageInterceptorTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private BizDao dao;
    private BizMessageInterceptor interceptor;

    @Before
    public void setUp() throws Exception {
        dao = new TestDb().dao();
        BizService service = new BizService(dao, now::get, () -> 120_000L);
        service.addWords(List.of("赌博"));
        interceptor = new BizMessageInterceptor(service);
    }

    /**
     * 解析报文。
     *
     * @param xml message XML
     * @return 消息
     */
    private static Message message(String xml) throws Exception {
        return new Message(DocumentHelper.parseText(xml).getRootElement());
    }

    /** 协议 §6.1 的普通消息 */
    private static Message chat(String id, String body) throws Exception {
        return message("<message type='chat' id='" + id + "' from='10086@im.example.com/android' to='10087@im.example.com'>"
                + "<body>" + body + "</body><origin-id xmlns='urn:xmpp:sid:0' id='" + id + "'/></message>");
    }

    /** 协议 §8.4 的撤回 */
    private static Message retract(String id, String from) throws Exception {
        return message("<message type='chat' id='x-" + id + "' from='" + from + "/android' to='10087@im.example.com'>"
                + "<body>张三撤回了一条消息</body><retract xmlns='urn:xmpp:message-retract:1' id='" + id + "'/>"
                + "<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:message-retract:1'/></message>");
    }

    /**
     * 断言被拒绝，返回错误元素。
     *
     * @param message 消息
     * @return error 元素
     */
    private Element rejected(Message message) {
        PacketRejectedException e = assertThrows(PacketRejectedException.class, () -> interceptor.check(message));
        return e.getRejectionError().getElement();
    }

    /** §6.4：命中敏感词 → policy-violation + sensitive-word，不记录 */
    @Test
    public void sensitiveWord_rejected() throws Exception {
        Element error = rejected(chat("m1", "周末去 赌 博"));

        assertThat(error.attributeValue("type")).isEqualTo("modify");
        assertThat(error.element("policy-violation").getNamespaceURI()).isEqualTo("urn:ietf:params:xml:ns:xmpp-stanzas");
        assertThat(error.element("app-error").attributeValue("code")).isEqualTo("sensitive-word");
        assertThat(dao.findMessage("m1", "10087@im.example.com")).isNull();
    }

    /** 正常消息放行并记录发送方与时间 */
    @Test
    public void normal_recorded() throws Exception {
        interceptor.check(chat("m1", "你好"));

        BizDao.MessageRecord record = dao.findMessage("m1", "10087@im.example.com");
        assertThat(record.sender()).isEqualTo("10086@im.example.com");
        assertThat(record.sentAt()).isEqualTo(1_000_000);
    }

    /** 回执、已读等没有 body 的报文、error / headline 类型：不检查、不记录 */
    @Test
    public void nonUserMessages_ignored() throws Exception {
        interceptor.check(message("<message id='r1' from='10086@im.example.com/a' to='10087@im.example.com'>"
                + "<received xmlns='urn:xmpp:receipts' id='m1'/></message>"));
        interceptor.check(message("<message type='headline' id='h1' from='10086@im.example.com/a' to='10087@im.example.com'>"
                + "<body>赌博</body></message>"));
        interceptor.check(message("<message type='chat' id='d1' from='10086@im.example.com/a' to='10087@im.example.com'>"
                + "<displayed xmlns='urn:xmpp:chat-markers:0' id='m1'/></message>"));

        assertThat(dao.findMessage("r1", "10087@im.example.com")).isNull();
        assertThat(dao.findMessage("h1", "10087@im.example.com")).isNull();
    }

    /** §8.4：2 分钟内撤回自己的消息允许；超时、撤回他人的、未知消息拒绝 */
    @Test
    public void retract_rules() throws Exception {
        interceptor.check(chat("m1", "说错了"));

        now.addAndGet(60_000);
        interceptor.check(retract("m1", "10086@im.example.com"));

        Element forbidden = rejected(retract("m1", "10088@im.example.com"));
        assertThat(forbidden.element("forbidden")).isNotNull();
        assertThat(forbidden.element("app-error").attributeValue("code")).isEqualTo("retract-forbidden");

        Element unknown = rejected(retract("unknown", "10086@im.example.com"));
        assertThat(unknown.element("app-error").attributeValue("code")).isEqualTo("retract-timeout");

        now.addAndGet(60_001);
        Element timeout = rejected(retract("m1", "10086@im.example.com"));
        assertThat(timeout.element("not-allowed")).isNotNull();
        assertThat(timeout.element("app-error").attributeValue("code")).isEqualTo("retract-timeout");
    }

    /** 撤回报文的降级文本包含敏感词也不拒绝（只校验撤回） */
    @Test
    public void retract_notCheckedForSensitiveWords() throws Exception {
        interceptor.check(chat("m1", "x"));
        Message retract = message("<message type='chat' id='x1' from='10086@im.example.com/a' to='10087@im.example.com'>"
                + "<body>赌博撤回了一条消息</body><retract xmlns='urn:xmpp:message-retract:1' id='m1'/></message>");

        interceptor.check(retract);
    }

    /** 消息 ID：优先 origin-id，没有时用报文 id */
    @Test
    public void messageId() throws Exception {
        assertThat(BizMessageInterceptor.messageId(message("<message id='a'><origin-id xmlns='urn:xmpp:sid:0' id='b'/></message>")))
                .isEqualTo("b");
        assertThat(BizMessageInterceptor.messageId(message("<message id='a'/>"))).isEqualTo("a");
        assertThat(BizMessageInterceptor.messageId(message("<message/>"))).isNull();
    }
}
