#!/usr/bin/env bash
# 把 config/openfire-properties.conf 中的系统属性写入 Openfire 数据库（ofProperty 表）。
# 有属性变化时重启 Openfire 使其生效（直接改数据库会绕过 Openfire 的属性缓存）。
#
# 用法：bash server/scripts/apply-properties.sh（需 Openfire 已完成安装）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
load_env

CONF="$SERVER_DIR/config/openfire-properties.conf"
sql=""
while IFS= read -r line || [[ -n "$line" ]]; do
  # 跳过空行和注释
  [[ -z "${line// }" || "$line" =~ ^[[:space:]]*# ]] && continue
  key="${line%%=*}"
  value="${line#*=}"
  value="${value//\$\{IM_DOMAIN\}/$IM_DOMAIN}"
  # SQL 字符串中的单引号转义
  key_sql="${key//\'/\'\'}"
  value_sql="${value//\'/\'\'}"
  # 只有值确实变化时才更新，RETURNING 输出发生变化的属性名，用于判断是否需要重启
  sql+="INSERT INTO ofProperty (name, propValue, encrypted) VALUES ('$key_sql', '$value_sql', 0)
ON CONFLICT (name) DO UPDATE SET propValue = EXCLUDED.propValue, encrypted = 0
WHERE ofProperty.propValue IS DISTINCT FROM EXCLUDED.propValue RETURNING name;
"
done < "$CONF"

changed="$(echo "$sql" | psql_exec)"
if [[ -z "$changed" ]]; then
  log "系统属性无变化"
  exit 0
fi

log "以下系统属性已更新：$(echo "$changed" | tr '\n' ' ')"
log "重启 Openfire 使属性生效…"
compose restart openfire >/dev/null
wait_healthy openfire
log "Openfire 已重启"
