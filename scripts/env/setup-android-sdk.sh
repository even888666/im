#!/usr/bin/env bash
# 安装 / 补全 Android SDK（命令行工具、platform-tools、编译所需的 platform 与 build-tools）。
#
# 用途：云会话容器是临时的，每次新会话都要重新安装 SDK；本脚本可重复执行，已安装的组件会跳过。
# 用法：bash scripts/env/setup-android-sdk.sh
# 环境变量：
#   ANDROID_HOME        SDK 安装目录，默认 /opt/android-sdk
#   ANDROID_PLATFORM    要安装的 platform，默认 platforms;android-37.0（与 app/build.gradle.kts 的 compileSdk 对应）
#   ANDROID_BUILD_TOOLS 要安装的 build-tools，默认 build-tools;37.0.0
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-platforms;android-37.0}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-build-tools;37.0.0}"
REPO_URL="https://dl.google.com/android/repository"

log() { echo "[android-sdk] $*"; }

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# 1. 命令行工具：从仓库索引中找出编号最大的 commandlinetools 包（即最新版），不写死版本号
if [[ ! -x "$SDKMANAGER" ]]; then
  log "下载命令行工具索引…"
  latest_zip=$(curl -fsSL "$REPO_URL/repository2-3.xml" \
    | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' \
    | sort -t- -k3 -n | tail -1)
  log "安装 $latest_zip 到 $ANDROID_HOME"
  tmp=$(mktemp -d)
  curl -fsSL "$REPO_URL/$latest_zip" -o "$tmp/cmdline-tools.zip"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  # 压缩包内的目录名是 cmdline-tools，sdkmanager 要求放在 cmdline-tools/latest 下
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
else
  log "命令行工具已存在，跳过"
fi

# 2. 接受许可（非交互）
yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null || true

# 3. 安装编译所需组件（已安装的会被 sdkmanager 自动跳过）
log "安装 platform-tools、$ANDROID_PLATFORM、$ANDROID_BUILD_TOOLS"
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "platform-tools" "$ANDROID_PLATFORM" "$ANDROID_BUILD_TOOLS" >/dev/null

log "完成：ANDROID_HOME=$ANDROID_HOME"

# 4. 让 Gradle 找到 SDK：写入工程根目录的 local.properties（已在 .gitignore 中，不会提交）
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if ! grep -qs '^sdk.dir=' "$PROJECT_DIR/local.properties"; then
  echo "sdk.dir=$ANDROID_HOME" >> "$PROJECT_DIR/local.properties"
  log "已写入 $PROJECT_DIR/local.properties"
fi
