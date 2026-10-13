#!/bin/bash
# Claude Code 云会话的 SessionStart hook：准备 Android SDK、Gradle 镜像、Docker 等环境。
# 具体步骤见 scripts/env/session-start.sh；本地（非云会话）不执行。
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# 让会话中的后续命令也能拿到 ANDROID_HOME；
# 开发服务器域名（server/.env.example 中的 IM_DOMAIN）加入 no_proxy：云环境的 HTTPS 代理
# 会把 im.example.com 转发到公网上的真实域名，访问本机 Docker 中的服务器必须绕过代理
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo 'export ANDROID_HOME=/opt/android-sdk'
    echo 'export no_proxy="${no_proxy:+$no_proxy,}im.example.com,.im.example.com"'
    echo 'export NO_PROXY="${NO_PROXY:+$NO_PROXY,}im.example.com,.im.example.com"'
  } >> "$CLAUDE_ENV_FILE"
fi

"$CLAUDE_PROJECT_DIR/scripts/env/session-start.sh"
