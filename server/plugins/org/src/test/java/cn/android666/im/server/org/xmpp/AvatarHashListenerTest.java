package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.MutableClock;
import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.TestDb;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.junit.Test;

import java.util.List;

import static cn.android666.im.server.org.Fixtures.employee;
import static com.google.common.truth.Truth.assertThat;

/** {@link AvatarHashListener} 测试：头像哈希与 XEP-0153 一致，变化时版本号增加 */
public class AvatarHashListenerTest {

    /** "hello" 的 Base64 与 SHA-1 */
    private static final String HELLO_BASE64 = "aGVs\nbG8=";
    private static final String HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

    private static Element vCard(String binval) throws Exception {
        String photo = binval == null ? "" : "<PHOTO><TYPE>image/jpeg</TYPE><BINVAL>" + binval + "</BINVAL></PHOTO>";
        return DocumentHelper.parseText("<vCard xmlns='vcard-temp'><FN>张三</FN>" + photo + "</vCard>").getRootElement();
    }

    @Test
    public void avatarHash() throws Exception {
        assertThat(AvatarHashListener.avatarHash(vCard(HELLO_BASE64))).isEqualTo(HELLO_SHA1);
        assertThat(AvatarHashListener.avatarHash(vCard(null))).isEmpty();
        // MIME 风格的 Base64 解码会忽略非法字符，全部是非法字符时没有数据
        assertThat(AvatarHashListener.avatarHash(vCard("!!!"))).isEmpty();
        assertThat(AvatarHashListener.avatarHash(null)).isEmpty();
    }

    @Test
    public void updated_savesHashAndBumpsVersion() throws Exception {
        OrgService service = new OrgService(new TestDb().dao(), new MutableClock(0));
        service.load();
        String d = service.ensureDepartmentPath(List.of("总部"));
        service.saveEmployee(employee("10001", "张三", d));
        long version = service.version();
        AvatarHashListener listener = new AvatarHashListener(service);

        listener.vCardUpdated("10001", vCard(HELLO_BASE64));
        assertThat(service.employee("10001").orElseThrow().avatarHash()).isEqualTo(HELLO_SHA1);
        assertThat(service.version()).isGreaterThan(version);

        long afterUpdate = service.version();
        listener.vCardUpdated("10001", vCard(HELLO_BASE64));
        assertThat(service.version()).isEqualTo(afterUpdate);

        listener.vCardDeleted("10001", vCard(HELLO_BASE64));
        assertThat(service.employee("10001").orElseThrow().avatarHash()).isEmpty();

        // 不在组织架构中的账号（如 admin）忽略
        listener.vCardUpdated("admin", vCard(HELLO_BASE64));
    }
}
