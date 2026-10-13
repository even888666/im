package cn.android666.im.server.org.xmpp;

import cn.android666.im.server.org.OrgService;
import org.dom4j.Element;
import org.jivesoftware.openfire.vcard.VCardListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 监听 vCard 变化，更新员工的头像哈希（《三端协议规范.md》§4.1 的 avatarHash，与 XEP-0153 一致：头像图片数据的 SHA-1，小写十六进制）。
 * 哈希变化会使组织架构版本号加 1，客户端增量同步后发现头像变了，再去拉取 vCard 中的头像。
 */
public class AvatarHashListener implements VCardListener {

    private static final Logger Log = LoggerFactory.getLogger(AvatarHashListener.class);

    /** 组织架构服务 */
    private final OrgService orgService;

    /**
     * 创建监听器。
     *
     * @param orgService 组织架构服务
     */
    public AvatarHashListener(OrgService orgService) {
        this.orgService = orgService;
    }

    /**
     * vCard 被创建。
     *
     * @param username 工号
     * @param vCard    vCard 元素
     */
    @Override
    public void vCardCreated(String username, Element vCard) {
        update(username, vCard);
    }

    /**
     * vCard 被修改。
     *
     * @param username 工号
     * @param vCard    vCard 元素
     */
    @Override
    public void vCardUpdated(String username, Element vCard) {
        update(username, vCard);
    }

    /**
     * vCard 被删除：清除头像哈希。
     *
     * @param username 工号
     * @param vCard    被删除的 vCard 元素
     */
    @Override
    public void vCardDeleted(String username, Element vCard) {
        update(username, null);
    }

    /**
     * 计算并保存头像哈希。
     *
     * @param username 工号
     * @param vCard    vCard 元素；null 表示没有 vCard
     */
    private void update(String username, Element vCard) {
        try {
            String hash = avatarHash(vCard);
            orgService.setAvatarHash(username, hash);
            Log.debug("用户 {} 的头像哈希：{}", username, hash.isEmpty() ? "（无头像）" : hash);
        } catch (SQLException e) {
            Log.error("保存用户 {} 的头像哈希失败", username, e);
        }
    }

    /**
     * 头像哈希：vCard 中 PHOTO/BINVAL（Base64）解码后的 SHA-1，小写十六进制。
     *
     * @param vCard vCard 元素，可为 null
     * @return 哈希；没有头像或数据无法解码时为空字符串
     */
    public static String avatarHash(Element vCard) {
        if (vCard == null) {
            return "";
        }
        Element photo = vCard.element("PHOTO");
        String binval = photo == null ? null : photo.elementText("BINVAL");
        if (binval == null || binval.isBlank()) {
            return "";
        }
        try {
            // BINVAL 中可能有换行和空白（MIME 风格的 Base64）
            byte[] data = Base64.getMimeDecoder().decode(binval.getBytes(StandardCharsets.US_ASCII));
            if (data.length == 0) {
                return "";
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (IllegalArgumentException | NoSuchAlgorithmException e) {
            Log.warn("头像数据无法解码：{}", e.getMessage());
            return "";
        }
    }
}
