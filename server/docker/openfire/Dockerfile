# Openfire 镜像：安装包由 server/fetch.sh 预先下载到 dist/（见该脚本中的说明）。
ARG OPENFIRE_BASE_IMAGE=eclipse-temurin:21-jre
FROM ${OPENFIRE_BASE_IMAGE}

ENV OPENFIRE_HOME=/opt/openfire \
    # 时区：日志时间与服务器时间按北京时间显示
    TZ=Asia/Shanghai

COPY dist/openfire ${OPENFIRE_HOME}
# 官方插件 jar：入口脚本每次启动时同步到 plugins 目录
COPY dist/plugins/*.jar /opt/plugins-dist/
COPY openfire.xml.template entrypoint.sh /opt/openfire-docker/

RUN chmod +x /opt/openfire-docker/entrypoint.sh \
    # 安装包中的启动脚本没有执行权限
    && chmod +x ${OPENFIRE_HOME}/bin/*.sh \
    # 移除自带的 search 插件（XEP-0055 用户搜索）：协议规范不采用，且会向所有用户暴露全员目录，
    # 同事搜索由组织架构插件提供（协议规范 §4.5）
    && rm -f ${OPENFIRE_HOME}/plugins/search.jar \
    # HTTP 文件上传的存储目录（挂载数据卷）
    && mkdir -p /var/lib/openfire/upload

WORKDIR ${OPENFIRE_HOME}

# 5222：客户端 XMPP（STARTTLS）；7443：HTTPS（文件上传、WebSocket、BOSH）；9091：管理后台（HTTPS）
EXPOSE 5222 7443 9091

ENTRYPOINT ["/opt/openfire-docker/entrypoint.sh"]
