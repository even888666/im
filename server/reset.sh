#!/usr/bin/env bash
# 停止 IM 服务端并删除所有数据（数据库、上传文件、Openfire 配置），用于开发环境从零重建。
# 证书（certs/out）和 .env 不删除。生产环境慎用！
#
# 用法：bash server/reset.sh [--yes]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/scripts/common.sh"
load_env

if [[ "${1:-}" != "--yes" ]]; then
  if [[ "$IM_ENV" != "dev" ]]; then
    echo "当前为 $IM_ENV 环境，删除数据不可恢复。确认请加参数 --yes" >&2
    exit 1
  fi
fi
compose down -v
log "已停止并删除所有数据卷"
