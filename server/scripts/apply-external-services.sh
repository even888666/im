#!/usr/bin/env bash
# 配置 External Service Discovery 插件（XEP-0215），向客户端下发 STUN / TURN 地址和临时凭证
# （协议规范 §12.1）。服务列表保存在插件自己的 ofExternalServices 表中，有变化时重启 Openfire。
#
# 用法：bash server/scripts/apply-external-services.sh（需 Openfire 已完成安装、插件已加载建表）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
load_env

TURN_HOST="${TURN_HOST:-turn.$IM_DOMAIN}"
TURN_PORT="${TURN_PORT:-3478}"

# 等待插件建表（插件在 Openfire 启动后异步加载）
for ((i = 0; i < 60; i++)); do
  if [[ "$(echo "SELECT to_regclass('ofexternalservices') IS NOT NULL;" | psql_exec)" == "t" ]]; then
    break
  fi
  sleep 1
done

# 固定使用 serviceID 1、2，便于幂等更新；restricted=true 表示需要凭证
# 用 md5 比较新旧内容，只有变化时才写入并重启
desired="1|STUN|$TURN_HOST|$TURN_PORT|f|udp|stun||
2|TURN|$TURN_HOST|$TURN_PORT|t|udp|turn|$TURN_SECRET"
current="$(echo "SELECT serviceID || '|' || name || '|' || host || '|' || port || '|' || CASE WHEN restricted THEN 't' ELSE 'f' END || '|' || trim(transport) || '|' || type || '|' || COALESCE(sharedSecret, '') FROM ofExternalServices ORDER BY serviceID;" | psql_exec)"

if [[ "$current" == "$desired" ]]; then
  log "STUN / TURN 服务配置无变化"
  exit 0
fi

psql_exec <<SQL
DELETE FROM ofExternalServices;
INSERT INTO ofExternalServices (serviceID, name, host, port, restricted, transport, type, username, password, sharedSecret)
VALUES (1, 'STUN', '$TURN_HOST', $TURN_PORT, false, 'udp', 'stun', NULL, NULL, NULL),
       (2, 'TURN', '$TURN_HOST', $TURN_PORT, true, 'udp', 'turn', NULL, NULL, '$TURN_SECRET');
-- 插件通过 ofID 分配新 ID，避免以后在管理后台新增服务时与固定 ID 冲突
UPDATE ofID SET id = GREATEST(id, 3) WHERE idType = 937;
SQL
log "已配置 STUN / TURN：$TURN_HOST:$TURN_PORT，重启 Openfire 使插件重新加载…"
compose restart openfire >/dev/null
wait_healthy openfire
log "Openfire 已重启"
