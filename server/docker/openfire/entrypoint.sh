#!/usr/bin/env bash
# Openfire 容器入口：
#   1. 首次启动时用环境变量渲染 conf/openfire.xml（autosetup 自动安装）
#   2. 每次启动时安装服务器证书（证书更新后重启容器即可生效）
#   3. 每次启动时同步官方插件 jar
#   4. 前台运行 Openfire
set -euo pipefail

OPENFIRE_HOME="${OPENFIRE_HOME:-/opt/openfire}"
CONF="$OPENFIRE_HOME/conf/openfire.xml"

log() { echo "[entrypoint] $*"; }

# 1. 渲染 openfire.xml：已完成安装（<setup>true</setup>）时不再覆盖，否则会重新执行安装向导
if grep -q "<setup>true</setup>" "$CONF" 2>/dev/null; then
  log "Openfire 已完成安装，保留现有 openfire.xml"
else
  : "${IM_DOMAIN:?需要 IM_DOMAIN}" "${POSTGRES_PASSWORD:?需要 POSTGRES_PASSWORD}" "${OPENFIRE_ADMIN_PASSWORD:?需要 OPENFIRE_ADMIN_PASSWORD}"

  # 开发环境自动创建 10 个测试账号（工号 10001 ~ 10010），生产环境账号由组织架构插件导入
  users_block=""
  if [[ "${IM_ENV:-prod}" == "dev" ]]; then
    users_block="        <users>"
    for i in $(seq 1 10); do
      emp_no=$((10000 + i))
      users_block+="
            <user$i>
                <username>$emp_no</username>
                <password>${TEST_USER_PASSWORD:?需要 TEST_USER_PASSWORD}</password>
                <name>测试用户$(printf '%02d' "$i")</name>
                <email>$emp_no@$IM_DOMAIN</email>
            </user$i>"
    done
    users_block+="
        </users>"
  fi

  log "首次启动，渲染 openfire.xml（域名 $IM_DOMAIN，环境 ${IM_ENV:-prod}）"
  # 密码由 up.sh 生成，只含字母数字，可安全用于 sed 替换；用户块通过 awk 插入，避免多行内容转义问题
  sed -e "s|@IM_DOMAIN@|$IM_DOMAIN|g" \
      -e "s|@POSTGRES_PASSWORD@|$POSTGRES_PASSWORD|g" \
      -e "s|@OPENFIRE_ADMIN_PASSWORD@|$OPENFIRE_ADMIN_PASSWORD|g" \
      /opt/openfire-docker/openfire.xml.template \
    | USERS_BLOCK="$users_block" awk '{ if ($0 == "@USERS_BLOCK@") print ENVIRON["USERS_BLOCK"]; else print }' \
    > "$CONF"
fi

# 2. 服务器证书：由 server/certs/gen-server-cert.sh 生成，挂载到 /certs
#    Openfire 默认从 resources/security/keystore 读取（密码 changeit），Java 能直接读取 PKCS12 格式
if [[ -f /certs/openfire-keystore.p12 ]]; then
  cp /certs/openfire-keystore.p12 "$OPENFIRE_HOME/resources/security/keystore"
  log "已安装服务器证书"
else
  log "警告：未挂载 /certs/openfire-keystore.p12，Openfire 将使用自动生成的自签证书，客户端会拒绝连接"
fi

# 3. 插件：jar 内容变化时才复制（Openfire 检测到 jar 更新会重新解压）
for jar in /opt/plugins-dist/*.jar; do
  target="$OPENFIRE_HOME/plugins/$(basename "$jar")"
  if ! cmp -s "$jar" "$target"; then
    cp "$jar" "$target"
    log "已安装插件 $(basename "$jar")"
  fi
done

# 4. 前台运行，日志同时输出到容器标准输出
log "启动 Openfire"
cd "$OPENFIRE_HOME"
exec "$OPENFIRE_HOME/bin/openfire.sh"
