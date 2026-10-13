package cn.android666.im.server.org.xmpp;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.junit.Test;
import org.xmpp.packet.IQ;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** {@link PresenceQueryProcessor} 测试（《三端协议规范.md》§5.3） */
public class PresenceQueryProcessorTest {

    /** 假的在线状态：10086 在 android（忙碌）和 pc 在线，10087 离线 */
    private final PresenceSource source = new PresenceSource() {
        @Override
        public List<ResourceState> onlineResources(String username) {
            if (username.equals("10086")) {
                return List.of(new ResourceState("android", "dnd", "下午 3 点前在开会", "meeting"),
                        new ResourceState("pc", null, null, null));
            }
            return List.of();
        }

        @Override
        public Long lastActiveMillis(String username) {
            return username.equals("10087") ? 1_791_854_400_000L : null;
        }
    };

    @Test
    public void onlineAndOffline() throws Exception {
        PresenceQueryProcessor processor = new PresenceQueryProcessor(source, "im.example.com");
        IQ request = new IQ(DocumentHelper.parseText("<iq type='get' id='p1' from='10001@im.example.com/android'>"
                + "<presence-query xmlns='urn:android666:im:presence:1'>"
                + "<item jid='10086@im.example.com'/><item jid='10087@im.example.com'/><item jid='10088@im.example.com'/>"
                + "</presence-query></iq>").getRootElement());
        Element el = processor.process(request).getChildElement();
        assertThat(el.getNamespaceURI()).isEqualTo("urn:android666:im:presence:1");
        List<Element> items = el.elements("item");

        assertThat(items.get(0).attributeValue("online")).isEqualTo("true");
        List<Element> resources = items.get(0).elements("resource");
        assertThat(resources.get(0).attributeValue("name")).isEqualTo("android");
        assertThat(resources.get(0).attributeValue("show")).isEqualTo("dnd");
        assertThat(resources.get(0).attributeValue("status")).isEqualTo("下午 3 点前在开会");
        assertThat(resources.get(0).attributeValue("code")).isEqualTo("meeting");
        assertThat(resources.get(1).attributes()).hasSize(1);

        assertThat(items.get(1).attributeValue("online")).isEqualTo("false");
        assertThat(items.get(1).attributeValue("lastActive")).isEqualTo("2026-10-13T01:20:00.000Z");
        // 从未登录：没有 lastActive
        assertThat(items.get(2).attributeValue("online")).isEqualTo("false");
        assertThat(items.get(2).attributeValue("lastActive")).isNull();
    }
}
