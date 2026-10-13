#!/usr/bin/env bash
# 一键启动 IM 服务端：证书 → 下载 → 构建 → 启动 → 等待就绪 → 写入系统属性。
# 可重复执行：已完成的步骤会跳过，用于首次部署、修改配置或升级版本后重新部署。
#
# 用法：bash server/up.sh
# 首次运行会根据 .env.example 生成 .env（含随机密码）。部署到公司服务器前请先阅读 README.md。
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/scripts/common.sh"

# 1. Docker
if ! docker info >/dev/null 2>&1; then
  echo "Docker 未运行，请先启动 Docker 守护进程" >&2
  exit 1
fi

# 2. .env：首次运行时复制模板，并为留空的密码生成随机值（只含字母数字，避免转义问题）
if [[ ! -f "$SERVER_DIR/.env" ]]; then
  cp "$SERVER_DIR/.env.example" "$SERVER_DIR/.env"
  chmod 600 "$SERVER_DIR/.env"
  log "已生成 $SERVER_DIR/.env"
fi
for key in POSTGRES_PASSWORD OPENFIRE_ADMIN_PASSWORD TURN_SECRET; do
  if grep -qE "^$key=$" "$SERVER_DIR/.env"; then
    sed -i "s/^$key=$/$key=$(openssl rand -hex 16)/" "$SERVER_DIR/.env"
    log "已为 $key 生成随机密码（见 .env）"
  fi
done
load_env

# 文件句柄上限：未配置时取 min(65536, 宿主机硬上限)，超过宿主机上限容器会启动失败
if [[ -z "${OPENFIRE_NOFILE:-}" ]]; then
  host_limit="$(ulimit -Hn)"
  if [[ "$host_limit" == "unlimited" || "$host_limit" -ge 65536 ]]; then
    OPENFIRE_NOFILE=65536
  else
    OPENFIRE_NOFILE="$host_limit"
    log "提示：宿主机文件句柄上限只有 $host_limit，Openfire 将使用该值（生产环境需调高，见 README.md）"
  fi
  export OPENFIRE_NOFILE
fi

# 3. 证书。记录服务器证书的指纹：重新签发后需要重启 Openfire 才能加载新证书
keystore="$SERVER_DIR/certs/out/openfire-keystore.p12"
keystore_before="$(sha256sum "$keystore" 2>/dev/null | cut -d' ' -f1 || true)"
bash "$SERVER_DIR/certs/gen-ca.sh"
IM_DOMAIN="$IM_DOMAIN" EXTRA_SANS="${EXTRA_SANS:-}" bash "$SERVER_DIR/certs/gen-server-cert.sh"
keystore_after="$(sha256sum "$keystore" | cut -d' ' -f1)"

# 4. 下载安装包与插件
bash "$SERVER_DIR/fetch.sh"

# 5. 生成 coturn 配置，构建并启动
bash "$SERVER_DIR/scripts/render-coturn-conf.sh"
log "构建并启动容器…"
# 部署前 Openfire 是否已在运行：已运行的容器不会因为证书变化而被重新创建，需要手动重启
openfire_running_before="$(compose ps -q --status running openfire 2>/dev/null || true)"
compose build --quiet
compose up -d --remove-orphans
# 服务器证书重新签发过（含原来没有证书的情况）、且 Openfire 部署前已在运行时，重启它以加载新证书
# （入口脚本在容器启动时安装证书）
if [[ -n "$openfire_running_before" && "$keystore_before" != "$keystore_after" ]]; then
  log "服务器证书已更新，重启 Openfire…"
  compose restart openfire >/dev/null
fi
wait_healthy postgres
wait_healthy openfire 300
wait_healthy coturn 60

# 6. 等待 autosetup 完成（安装完成后 openfire.xml 中出现 <setup>true</setup>）
for ((i = 0; i < 120; i++)); do
  if compose exec -T openfire grep -q "<setup>true</setup>" /opt/openfire/conf/openfire.xml; then
    break
  fi
  sleep 1
done
if ! compose exec -T openfire grep -q "<setup>true</setup>" /opt/openfire/conf/openfire.xml; then
  echo "Openfire 自动安装未完成，最近日志：" >&2
  compose logs --tail 80 openfire >&2
  exit 1
fi
log "Openfire 已完成安装"

# 7. 系统属性、外部服务（STUN / TURN）
bash "$SERVER_DIR/scripts/apply-properties.sh"
bash "$SERVER_DIR/scripts/apply-external-services.sh"

# 8. 开发环境：把域名指向本机，客户端和测试可以直接用域名访问（证书校验需要域名匹配）
if [[ "$IM_ENV" == "dev" ]]; then
  hosts_line="127.0.0.1 $IM_DOMAIN conference.$IM_DOMAIN httpfileupload.$IM_DOMAIN turn.$IM_DOMAIN"
  if ! grep -qF "$hosts_line" /etc/hosts 2>/dev/null; then
    if echo "$hosts_line" >> /etc/hosts 2>/dev/null; then
      log "已写入 /etc/hosts：$hosts_line"
    else
      log "提示：无权限写入 /etc/hosts，请手动添加：$hosts_line"
    fi
  fi
fi

log "服务端已就绪："
log "  XMPP：$IM_DOMAIN:5222（STARTTLS）"
log "  HTTPS（文件上传 / WebSocket）：https://$IM_DOMAIN:7443"
log "  管理后台：https://${ADMIN_BIND_ADDRESS}:9091（用户名 admin，密码见 .env 中的 OPENFIRE_ADMIN_PASSWORD）"
log "  STUN / TURN：${TURN_HOST:-turn.$IM_DOMAIN}:${TURN_PORT:-3478}（UDP，中继端口 ${TURN_MIN_PORT:-49160}-${TURN_MAX_PORT:-49200}）"
log "  根证书：$SERVER_DIR/certs/out/ca.crt"
