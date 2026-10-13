#!/usr/bin/env bash
# 根据 docker/coturn/turnserver.conf.template 和 .env 生成 docker/coturn/turnserver.conf。
# 由 up.sh 调用；生成的文件含共享密钥，已在 .gitignore 中。
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
load_env

TEMPLATE="$SERVER_DIR/docker/coturn/turnserver.conf.template"
OUT="$SERVER_DIR/docker/coturn/turnserver.conf"

umask 077
sed -e "s|@TURN_PORT@|${TURN_PORT:-3478}|g" \
    -e "s|@TURN_MIN_PORT@|${TURN_MIN_PORT:-49160}|g" \
    -e "s|@TURN_MAX_PORT@|${TURN_MAX_PORT:-49200}|g" \
    -e "s|@TURN_SECRET@|${TURN_SECRET:?需要 TURN_SECRET}|g" \
    -e "s|@IM_DOMAIN@|$IM_DOMAIN|g" \
    "$TEMPLATE" > "$OUT.tmp"

# 服务器在 NAT 后面时才需要对外通告的 IP
if [[ -n "${TURN_EXTERNAL_IP:-}" ]]; then
  printf '\n# 服务器在 NAT 后面，对外通告的 IP\nexternal-ip=%s\n' "$TURN_EXTERNAL_IP" >> "$OUT.tmp"
fi
# coturn 容器内以非 root 用户运行，需要能读取配置文件
chmod 644 "$OUT.tmp"

if cmp -s "$OUT.tmp" "$OUT"; then
  rm "$OUT.tmp"
  log "coturn 配置无变化"
else
  mv "$OUT.tmp" "$OUT"
  log "已生成 coturn 配置"
fi
