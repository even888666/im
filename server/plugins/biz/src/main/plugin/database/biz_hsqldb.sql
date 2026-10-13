-- 业务扩展插件（biz）建表脚本：hsqldb。安装插件时由 Openfire 自动执行，最后一行登记数据库版本。
-- 修改表结构时：plugin.xml 的 databaseVersion 加 1，并在 database/upgrade/{新版本}/ 下提供升级脚本，三种数据库的脚本保持一致。

-- 消息记录（撤回校验，协议规范 §8.4）：每条单聊 / 群聊消息的发送方与服务器时间；定期清理 7 天前的记录
CREATE TABLE imBizMessage (
    msgId       VARCHAR(64)  NOT NULL,
    receiver    VARCHAR(255) NOT NULL,
    sender      VARCHAR(255) NOT NULL,
    sentAt      BIGINT       NOT NULL,
    CONSTRAINT imBizMessage_pk PRIMARY KEY (msgId, receiver)
);
CREATE INDEX imBizMessage_time_idx ON imBizMessage (sentAt);

-- 敏感词（协议规范 §6.4）：在管理后台维护
CREATE TABLE imBizSensitiveWord (
    word        VARCHAR(100) NOT NULL,
    createdAt   BIGINT       NOT NULL,
    CONSTRAINT imBizSensitiveWord_pk PRIMARY KEY (word)
);

INSERT INTO ofVersion (name, version) VALUES ('biz', 1);
