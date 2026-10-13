#!/usr/bin/env bash
# 云会话环境准备脚本（由 .claude/hooks/session-start.sh 在每次会话启动时调用，也可手动执行）。
#
# 云会话容器是临时的，每次新会话都要重新准备：
#   1. Android SDK（编译 App 必需）
#   2. Gradle 的 Maven Central 镜像初始化脚本（规避云环境出口 IP 被 Maven Central 限流的 HTTP 429）
#   3. Docker 守护进程，并在后台启动 IM 服务端（server/up.sh：Openfire、PostgreSQL、coturn）
#   4. 预热 Gradle 依赖缓存，使会话开始后能直接编译、运行测试
# 脚本可重复执行，已完成的步骤会跳过。
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

log() { echo "[session-start] $*"; }

# 1. Android SDK
bash "$PROJECT_DIR/scripts/env/setup-android-sdk.sh"

# 2. Gradle 镜像初始化脚本：只装到当前用户的 ~/.gradle/init.d，不影响工程配置
mkdir -p "$HOME/.gradle/init.d"
cp "$PROJECT_DIR/scripts/env/gradle-init/cloud-maven-mirror.init.gradle.kts" "$HOME/.gradle/init.d/"
log "已安装 Gradle Maven Central 镜像初始化脚本"

# 3. Docker 守护进程：容器里没有 systemd 托管，未运行时手动在后台拉起
if ! docker info >/dev/null 2>&1; then
  log "启动 Docker 守护进程…"
  nohup dockerd >/var/log/dockerd.log 2>&1 &
  for _ in $(seq 1 30); do
    docker info >/dev/null 2>&1 && break
    sleep 1
  done
fi
if docker info >/dev/null 2>&1; then
  log "Docker 守护进程已就绪"
  # 服务端首次部署要拉取镜像、构建，需要一两分钟，放到后台执行，不阻塞会话启动；
  # 集成测试（-Pim.it=true）前确认日志中出现"服务端已就绪"
  log "在后台启动 IM 服务端，日志：/tmp/im-server-up.log"
  nohup bash "$PROJECT_DIR/server/up.sh" >/tmp/im-server-up.log 2>&1 &
else
  # Docker 只影响服务器联调，不影响编译和单元测试，所以只告警不退出
  log "警告：Docker 守护进程启动失败，详见 /var/log/dockerd.log"
fi

# 4. 预热 Gradle：下载依赖并编译一次，结果会随容器状态缓存，之后的构建更快。
#    编译失败（例如当前分支代码有问题）不应阻止会话启动，所以只告警
log "预热 Gradle 依赖缓存…"
if (cd "$PROJECT_DIR" && ./gradlew assembleDebug compileDebugUnitTestKotlin --console=plain -q); then
  log "Gradle 预热完成"
else
  log "警告：Gradle 预热失败，请在会话中手动运行 ./gradlew assembleDebug 查看原因"
fi
