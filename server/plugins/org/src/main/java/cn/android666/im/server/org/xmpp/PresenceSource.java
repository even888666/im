package cn.android666.im.server.org.xmpp;

import java.util.List;

/**
 * 在线状态来源。生产环境读取 Openfire 的会话和最后活跃时间（{@link OpenfirePresenceSource}），测试中用假实现。
 */
public interface PresenceSource {

    /**
     * 一个在线端的状态（《三端协议规范.md》§5.2 / §5.3）。
     *
     * @param resource 端（android / pc / web）
     * @param show     show（away / dnd 等），在线时为 null
     * @param status   自定义文字，没有时为 null
     * @param code     自定义状态码（busy、meeting 等），没有时为 null
     */
    record ResourceState(String resource, String show, String status, String code) {
    }

    /**
     * 用户各在线端的状态。
     *
     * @param username 工号
     * @return 在线端列表；离线时为空列表
     */
    List<ResourceState> onlineResources(String username);

    /**
     * 用户最后活跃（下线）的时间。
     *
     * @param username 工号
     * @return 时间（毫秒）；未知时为 null
     */
    Long lastActiveMillis(String username);
}
