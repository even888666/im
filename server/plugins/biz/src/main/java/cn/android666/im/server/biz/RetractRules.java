package cn.android666.im.server.biz;

import cn.android666.im.server.biz.db.BizDao.MessageRecord;

/**
 * 撤回校验规则（协议规范 §8.4）：普通用户只能撤回自己 2 分钟内发送的消息。
 * 群主、管理员撤回他人消息、不限时的规则在群聊（M6）中加入。
 */
public final class RetractRules {

    private RetractRules() {
    }

    /** 校验结果 */
    public enum Decision {
        /** 允许 */
        ALLOW,

        /** 超过时限（或找不到原消息的记录：记录已清理，必然超过时限） */
        TIMEOUT,

        /** 撤回他人的消息 */
        FORBIDDEN,
    }

    /**
     * 校验。
     *
     * @param original  原消息的记录；没有记录时为 null
     * @param retractor 撤回者 bare JID
     * @param now       当前时间（毫秒）
     * @param limitMillis 时限（毫秒）
     * @return 结果
     */
    public static Decision decide(MessageRecord original, String retractor, long now, long limitMillis) {
        if (original == null) {
            return Decision.TIMEOUT;
        }
        if (!original.sender().equals(retractor)) {
            return Decision.FORBIDDEN;
        }
        return now - original.sentAt() > limitMillis ? Decision.TIMEOUT : Decision.ALLOW;
    }
}
