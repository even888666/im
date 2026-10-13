#!/usr/bin/env bash
# 服务端脚本的公共函数，由 up.sh、down.sh 等 source 引入，不单独运行。

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { echo "[$(basename "$0" .sh)] $*"; }

# 加载版本与部署配置，并导出给 docker compose 使用
load_env() {
  if [[ ! -f "$SERVER_DIR/.env" ]]; then
    echo "未找到 $SERVER_DIR/.env，请先运行 up.sh" >&2
    exit 1
  fi
  set -a
  # shellcheck source=/dev/null
  source "$SERVER_DIR/versions.env"
  # shellcheck source=/dev/null
  source "$SERVER_DIR/.env"
  set +a
}

# 带上项目的 Compose 文件和环境文件执行 docker compose
compose() {
  docker compose -f "$SERVER_DIR/docker/docker-compose.yml" \
    --env-file "$SERVER_DIR/versions.env" --env-file "$SERVER_DIR/.env" "$@"
}

# 等待某个服务的健康检查通过
# 参数：$1 服务名，$2 超时秒数（默认 180）
wait_healthy() {
  local service="$1" timeout="${2:-180}" id status
  id="$(compose ps -q "$service")"
  for ((i = 0; i < timeout; i++)); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || echo unknown)"
    if [[ "$status" == "healthy" ]]; then
      return 0
    fi
    sleep 1
  done
  echo "$service 在 ${timeout} 秒内未就绪（状态：$status），最近日志：" >&2
  compose logs --tail 50 "$service" >&2
  return 1
}

# 在 PostgreSQL 容器中执行 SQL，输出不带表头和对齐
# 参数：从标准输入读取 SQL
psql_exec() {
  compose exec -T postgres psql -U openfire -d openfire -v ON_ERROR_STOP=1 -q -t -A
}
