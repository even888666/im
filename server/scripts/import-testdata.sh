#!/usr/bin/env bash
# 开发环境：导入测试员工（server/testdata/employees.csv，工号 10001 ~ 10020）。
# 把替换好密码的 CSV 放进导入目录（server/org-import/），组织架构插件 10 秒内自动导入；内容不变时跳过。
#
# 用法：bash server/scripts/import-testdata.sh（需组织架构插件已加载）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
load_env

if [[ "$IM_ENV" != "dev" ]]; then
  log "非开发环境，不导入测试数据"
  exit 0
fi

IMPORT_DIR="$SERVER_DIR/org-import"
mkdir -p "$IMPORT_DIR"
rendered="$(sed "s/@TEST_USER_PASSWORD@/${TEST_USER_PASSWORD:?需要 TEST_USER_PASSWORD}/g" "$SERVER_DIR/testdata/employees.csv")"
checksum="$(printf '%s' "$rendered" | sha256sum | cut -d' ' -f1)"
marker="$IMPORT_DIR/.dev-employees.sha256"
if [[ -f "$marker" && "$(cat "$marker")" == "$checksum" ]]; then
  log "测试员工无变化，跳过导入"
  exit 0
fi

file="$IMPORT_DIR/dev-employees.csv"
printf '%s\n' "$rendered" > "$file"
log "等待组织架构插件导入测试员工…"
for ((i = 0; i < 60; i++)); do
  [[ ! -f "$file" ]] && break
  sleep 1
done
if [[ -f "$file" ]]; then
  echo "60 秒内未导入（组织架构插件是否已加载？）" >&2
  exit 1
fi
result="$(ls -t "$IMPORT_DIR"/done/*dev-employees.csv.result.txt | head -1)"
log "导入结果：$(head -1 "$result")"
if grep -q "^失败" "$result"; then
  cat "$result" >&2
  exit 1
fi
echo "$checksum" > "$marker"
