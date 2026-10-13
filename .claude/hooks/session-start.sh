#!/bin/bash
# Claude Code 云会话的 SessionStart hook：准备 Android SDK、Gradle 镜像、Docker 等环境。
# 具体步骤见 scripts/env/session-start.sh；本地（非云会话）不执行。
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# 让会话中的后续命令也能拿到 ANDROID_HOME
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo 'export ANDROID_HOME=/opt/android-sdk' >> "$CLAUDE_ENV_FILE"
fi

"$CLAUDE_PROJECT_DIR/scripts/env/session-start.sh"
