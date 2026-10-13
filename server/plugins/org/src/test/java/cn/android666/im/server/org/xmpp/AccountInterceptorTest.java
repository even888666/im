package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.MutableClock;
import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.TestDb;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.junit.Before;
import org.junit.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Message;
import org.xmpp.packet.PacketError;

import java.util.List;

import static cn.android666.im.server.org.Fixtures.employee;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** {@link AccountInterceptor} 测试：强制改密期间不能发消息、改密时校验密码强度 */
public class AccountInterceptorTest {

    private OrgService service;
    private AccountInterceptor interceptor;

    @Before
    public void setUp() throws Exception {
        service = new OrgService(new TestDb().dao(), new MutableClock(0));
        service.load();
        String d = service.ensureDepartmentPath(List.of("总部"));
        service.saveEmployee(employee("10001", "张三", d));
        interceptor = new AccountInterceptor(service);
    }

    @Test
    public void message_mustChangePassword_rejected() throws Exception {
        Message message = new Message();
        message.setBody("hi");
        interceptor.checkMessage(message, "10001");

        service.setMustChangePassword("10001", true);
        PacketRejectedException e = assertThrows(PacketRejectedException.class, () -> interceptor.checkMessage(message, "10001"));
        assertThat(e.getRejectionError().getCondition()).isEqualTo(PacketError.Condition.forbidden);
        Element app = e.getRejectionError().getElement().element("app-error");
        assertThat(app.attributeValue("code")).isEqualTo("must-change-password");
    }

    @Test
    public void passwordChange_weakRejected_strongAllowed() throws Exception {
        PacketRejectedException e = assertThrows(PacketRejectedException.class,
                () -> interceptor.checkPasswordChange(register("12345678"), "10001"));
        assertThat(e.getRejectionError().getCondition()).isEqualTo(PacketError.Condition.not_acceptable);
        assertThat(e.getRejectionError().getElement().element("app-error").attributeValue("code")).isEqualTo("weak-password");

        interceptor.checkPasswordChange(register("NewPass123"), "10001");
    }

    /**
     * 构造 XEP-0077 改密请求。
     *
     * @param password 新密码
     * @return IQ
     */
    private static IQ register(String password) throws Exception {
        return new IQ(DocumentHelper.parseText("<iq type='set' id='a2' to='im.example.com'>"
                + "<query xmlns='jabber:iq:register'><username>10001</username><password>" + password + "</password></query>"
                + "</iq>").getRootElement());
    }
}
