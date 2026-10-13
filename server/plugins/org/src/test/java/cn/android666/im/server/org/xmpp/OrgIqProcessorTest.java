package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.MutableClock;
import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.TestDb;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.junit.Before;
import org.junit.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

import java.util.List;

import static cn.android666.im.server.org.Fixtures.employee;
import static com.google.common.truth.Truth.assertThat;

/** {@link OrgIqProcessor} 与 {@link AccountIqProcessor} 测试：请求、应答按《三端协议规范.md》§3.1、§4 的示例 */
public class OrgIqProcessorTest {

    private static final String DOMAIN = "im.example.com";

    private OrgService service;
    private OrgIqProcessor processor;
    private String rd;

    @Before
    public void setUp() throws Exception {
        service = new OrgService(new TestDb().dao(), new MutableClock(0));
        service.load();
        rd = service.ensureDepartmentPath(List.of("总部", "研发中心"));
        service.saveEmployee(employee("10001", "张三", rd, "13800000001"));
        service.saveEmployee(employee("10002", "李四", rd));
        processor = new OrgIqProcessor(service, DOMAIN);
    }

    /**
     * 把 XML 字符串解析为 IQ 请求。
     *
     * @param xml 请求 XML
     * @return IQ
     */
    private static IQ iq(String xml) throws Exception {
        Element root = DocumentHelper.parseText(xml).getRootElement();
        return new IQ(root);
    }

    @Test
    public void deptChildren_topLevel() throws Exception {
        IQ reply = processor.process(iq("<iq type='get' id='o1' from='10001@im.example.com/android' to='im.example.com'>"
                + "<dept-children xmlns='urn:android666:im:org:1' deptId=''/></iq>"));
        assertThat(reply.getType()).isEqualTo(IQ.Type.result);
        Element el = reply.getChildElement();
        assertThat(el.getNamespaceURI()).isEqualTo("urn:android666:im:org:1");
        List<Element> depts = el.elements("department");
        assertThat(depts).hasSize(1);
        assertThat(depts.get(0).attributeValue("name")).isEqualTo("总部");
        assertThat(depts.get(0).attributeValue("parentId")).isEqualTo("");
        assertThat(depts.get(0).attributeValue("memberCount")).isEqualTo("0");
        assertThat(el.elements("employee")).isEmpty();
        assertThat(el.element("set").elementText("count")).isEqualTo("0");
        assertThat(el.element("set").element("first")).isNull();
    }

    @Test
    public void deptChildren_employeesPaged() throws Exception {
        IQ reply = processor.process(iq("<iq type='get' id='o1' from='10001@im.example.com/android'>"
                + "<dept-children xmlns='urn:android666:im:org:1' deptId='" + rd + "'>"
                + "<set xmlns='http://jabber.org/protocol/rsm'><max>1</max><after>0</after></set>"
                + "</dept-children></iq>"));
        Element el = reply.getChildElement();
        List<Element> employees = el.elements("employee");
        assertThat(employees).hasSize(1);
        Element e = employees.get(0);
        // 第二个员工（按拼音：李四、张三）
        assertThat(e.attributeValue("jid")).isEqualTo("10001@im.example.com");
        assertThat(e.attributeValue("empNo")).isEqualTo("10001");
        assertThat(e.attributeValue("pinyin")).isEqualTo("zhangsan");
        assertThat(e.attributeValue("initials")).isEqualTo("zs");
        assertThat(e.attributeValue("deptId")).isEqualTo(rd);
        assertThat(e.attributeValue("mobile")).isEqualTo("13800000001");
        assertThat(e.attributeValue("sort")).isEqualTo("0");
        assertThat(e.attributeValue("avatarHash")).isEqualTo("");
        assertThat(e.attributeValue("op")).isNull();
        Element set = el.element("set");
        assertThat(set.getNamespaceURI()).isEqualTo("http://jabber.org/protocol/rsm");
        assertThat(set.elementText("first")).isEqualTo("1");
        assertThat(set.elementText("last")).isEqualTo("1");
        assertThat(set.elementText("count")).isEqualTo("2");
    }

    @Test
    public void employees_existingAndDeleted() throws Exception {
        IQ reply = processor.process(iq("<iq type='get' id='o2' from='10001@im.example.com/android'>"
                + "<employees xmlns='urn:android666:im:org:1'>"
                + "<item jid='10002@im.example.com'/><item jid='10087@im.example.com'/><item jid='10001@other.com'/>"
                + "</employees></iq>"));
        List<Element> employees = reply.getChildElement().elements("employee");
        assertThat(employees).hasSize(3);
        assertThat(employees.get(0).attributeValue("name")).isEqualTo("李四");
        assertThat(employees.get(1).attributeValue("jid")).isEqualTo("10087@im.example.com");
        assertThat(employees.get(1).attributeValue("op")).isEqualTo("delete");
        assertThat(employees.get(2).attributeValue("op")).isEqualTo("delete");
    }

    @Test
    public void employees_tooMany() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 101; i++) {
            items.append("<item jid='").append(i).append("@im.example.com'/>");
        }
        IQ reply = processor.process(iq("<iq type='get' id='o2' from='10001@im.example.com/android'>"
                + "<employees xmlns='urn:android666:im:org:1'>" + items + "</employees></iq>"));
        assertError(reply, PacketError.Condition.bad_request, "too-many-items");
    }

    @Test
    public void sync_fullThenIncremental() throws Exception {
        IQ full = processor.process(iq("<iq type='get' id='o3' from='10001@im.example.com/android'>"
                + "<sync xmlns='urn:android666:im:org:1' version='0'>"
                + "<set xmlns='http://jabber.org/protocol/rsm'><max>500</max></set></sync></iq>"));
        Element el = full.getChildElement();
        assertThat(el.attributeValue("full")).isEqualTo("true");
        assertThat(el.attributeValue("version")).isEqualTo(Long.toString(service.version()));
        assertThat(el.elements("department")).hasSize(2);
        assertThat(el.elements("employee")).hasSize(2);
        assertThat(el.elements("department").get(0).attributeValue("op")).isEqualTo("upsert");
        assertThat(el.element("set").elementText("count")).isEqualTo("4");
        String last = el.element("set").elementText("last");

        // 下一页为空：客户端据此结束分页
        IQ next = processor.process(iq("<iq type='get' id='o4' from='10001@im.example.com/android'>"
                + "<sync xmlns='urn:android666:im:org:1' version='0'>"
                + "<set xmlns='http://jabber.org/protocol/rsm'><max>500</max><after>" + last + "</after></set></sync></iq>"));
        assertThat(next.getChildElement().elements()).hasSize(1);

        long v = service.version();
        service.setDisabled("10002", true);
        IQ inc = processor.process(iq("<iq type='get' id='o5' from='10001@im.example.com/android'>"
                + "<sync xmlns='urn:android666:im:org:1' version='" + v + "'/></iq>"));
        Element incEl = inc.getChildElement();
        assertThat(incEl.attributeValue("full")).isEqualTo("false");
        Element deleted = incEl.elements("employee").get(0);
        assertThat(deleted.attributeValue("jid")).isEqualTo("10002@im.example.com");
        assertThat(deleted.attributeValue("op")).isEqualTo("delete");
        // 部门人数变化也同步
        assertThat(incEl.elements("department").get(0).attributeValue("memberCount")).isEqualTo("1");
    }

    @Test
    public void search() throws Exception {
        IQ reply = processor.process(iq("<iq type='get' id='o4' from='10001@im.example.com/android'>"
                + "<search xmlns='urn:android666:im:org:1' keyword='zs'>"
                + "<set xmlns='http://jabber.org/protocol/rsm'><max>20</max></set></search></iq>"));
        List<Element> employees = reply.getChildElement().elements("employee");
        assertThat(employees).hasSize(1);
        assertThat(employees.get(0).attributeValue("name")).isEqualTo("张三");
    }

    @Test
    public void badRequests() throws Exception {
        assertError(processor.process(iq("<iq type='set' id='x' from='10001@im.example.com/android'>"
                + "<sync xmlns='urn:android666:im:org:1'/></iq>")), PacketError.Condition.bad_request, null);
        assertError(processor.process(iq("<iq type='get' id='x' from='10001@im.example.com/android'>"
                + "<sync xmlns='urn:android666:im:org:1' version='abc'/></iq>")), PacketError.Condition.bad_request, null);
        assertError(processor.process(iq("<iq type='get' id='x' from='10001@im.example.com/android'>"
                + "<dept-children xmlns='urn:android666:im:org:1'><set xmlns='http://jabber.org/protocol/rsm'><max>0</max></set>"
                + "</dept-children></iq>")), PacketError.Condition.bad_request, null);
        assertError(processor.process(iq("<iq type='get' id='x' from='10001@im.example.com/android'>"
                + "<unknown xmlns='urn:android666:im:org:1'/></iq>")), PacketError.Condition.feature_not_implemented, null);
    }

    @Test
    public void account_mustChangePassword() throws Exception {
        AccountIqProcessor account = new AccountIqProcessor(service);
        String request = "<iq type='get' id='a1' from='10001@im.example.com/android' to='im.example.com'>"
                + "<account xmlns='urn:android666:im:account:1'/></iq>";
        assertThat(account.process(iq(request)).getChildElement().attributeValue("mustChangePassword")).isEqualTo("false");
        service.setMustChangePassword("10001", true);
        IQ reply = account.process(iq(request));
        assertThat(reply.getType()).isEqualTo(IQ.Type.result);
        assertThat(reply.getChildElement().getNamespaceURI()).isEqualTo("urn:android666:im:account:1");
        assertThat(reply.getChildElement().attributeValue("mustChangePassword")).isEqualTo("true");
    }

    /**
     * 检查错误应答。
     *
     * @param reply     应答
     * @param condition 标准错误条件
     * @param appCode   自定义错误码，null 表示没有
     */
    static void assertError(IQ reply, PacketError.Condition condition, String appCode) {
        assertThat(reply.getType()).isEqualTo(IQ.Type.error);
        assertThat(reply.getError().getCondition()).isEqualTo(condition);
        Element app = reply.getError().getElement().element("app-error");
        if (appCode == null) {
            assertThat(app).isNull();
        } else {
            assertThat(app.getNamespaceURI()).isEqualTo("urn:android666:im:error:1");
            assertThat(app.attributeValue("code")).isEqualTo(appCode);
        }
    }
}
