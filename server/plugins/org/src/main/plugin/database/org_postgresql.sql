-- 组织架构插件（org）建表脚本：PostgreSQL。安装插件时由 Openfire 自动执行，最后一行登记数据库版本。
-- 修改表结构时：plugin.xml 的 databaseVersion 加 1，并在 database/upgrade/{新版本}/ 下提供升级脚本，三种数据库的脚本保持一致。

-- 部门：parentId 为空字符串表示顶级部门
CREATE TABLE imOrgDept (
    id          VARCHAR(64)  NOT NULL,
    parentId    VARCHAR(64)  NOT NULL,
    name        VARCHAR(255) NOT NULL,
    sort        INTEGER      NOT NULL,
    CONSTRAINT imOrgDept_pk PRIMARY KEY (id)
);
CREATE INDEX imOrgDept_parent_idx ON imOrgDept (parentId);

-- 员工扩展信息（账号本身在 Openfire 的 ofUser 表）：disabled = 1 表示已禁用 / 离职，同步时视为删除
CREATE TABLE imOrgEmployee (
    username            VARCHAR(64)  NOT NULL,
    name                VARCHAR(100) NOT NULL,
    pinyin              VARCHAR(255) NOT NULL,
    initials            VARCHAR(100) NOT NULL,
    deptId              VARCHAR(64)  NOT NULL,
    title               VARCHAR(100) NOT NULL,
    mobile              VARCHAR(32)  NOT NULL,
    phone               VARCHAR(32)  NOT NULL,
    email               VARCHAR(255) NOT NULL,
    sort                INTEGER      NOT NULL,
    avatarHash          VARCHAR(64)  NOT NULL,
    disabled            INTEGER      NOT NULL,
    mustChangePassword  INTEGER      NOT NULL,
    CONSTRAINT imOrgEmployee_pk PRIMARY KEY (username)
);
CREATE INDEX imOrgEmployee_dept_idx ON imOrgEmployee (deptId);

-- 变更日志：每次部门或员工变更占用一个版本号，增量同步按版本号查询（保留 30 天）
CREATE TABLE imOrgChange (
    version     BIGINT      NOT NULL,
    entityType  CHAR(1)     NOT NULL,
    entityId    VARCHAR(64) NOT NULL,
    changeTime  BIGINT      NOT NULL,
    CONSTRAINT imOrgChange_pk PRIMARY KEY (version)
);
CREATE INDEX imOrgChange_time_idx ON imOrgChange (changeTime);

-- 计数器：version（组织架构版本号）、prunedVersion（已清理的最大版本号）、deptSeq（部门 ID 序号）
CREATE TABLE imOrgMeta (
    name        VARCHAR(64) NOT NULL,
    metaValue   BIGINT      NOT NULL,
    CONSTRAINT imOrgMeta_pk PRIMARY KEY (name)
);
INSERT INTO imOrgMeta (name, metaValue) VALUES ('version', 0);
INSERT INTO imOrgMeta (name, metaValue) VALUES ('prunedVersion', 0);
INSERT INTO imOrgMeta (name, metaValue) VALUES ('deptSeq', 1000);

INSERT INTO ofVersion (name, version) VALUES ('org', 1);
