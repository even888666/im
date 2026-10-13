#!/usr/bin/env bash
# 停止 IM 服务端容器，保留数据（数据库、上传文件、配置）。
# 用法：bash server/down.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/scripts/common.sh"
load_env
compose down
log "已停止（数据已保留，重新启动请运行 up.sh）"
