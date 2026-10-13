#!/usr/bin/env bash
# 下载 Openfire 安装包和官方插件到 docker/openfire/dist/（不提交到仓库）。
#
# 在宿主机上下载而不是在 docker build 中下载：方便在无外网的公司服务器上部署
# （可在有网的机器上运行本脚本后，把 dist/ 目录拷过去）。已下载的文件会跳过。
#
# 用法：bash server/fetch.sh
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env
source "$SERVER_DIR/versions.env"
DIST="$SERVER_DIR/docker/openfire/dist"
GH="https://github.com/igniterealtime"

log() { echo "[fetch] $*"; }

# 下载文件：先下载到临时文件，成功后再改名，避免中断后留下不完整的文件被当作已下载
download() {
  local url="$1" dest="$2"
  if [[ -s "$dest" ]]; then
    log "已存在，跳过：$(basename "$dest")"
    return
  fi
  log "下载 $url"
  curl -fsSL --retry 3 --retry-delay 2 -o "$dest.part" "$url"
  mv "$dest.part" "$dest"
}

mkdir -p "$DIST/plugins"

# Openfire 安装包：文件名中的版本号用下划线分隔，例如 openfire_5_1_2.tar.gz
OF_TAR="$DIST/openfire_${OPENFIRE_VERSION//./_}.tar.gz"
download "$GH/Openfire/releases/download/v$OPENFIRE_VERSION/openfire_${OPENFIRE_VERSION//./_}.tar.gz" "$OF_TAR"
# 解压到 dist/openfire，版本变化时重新解压
if [[ "$(cat "$DIST/openfire/.version" 2>/dev/null)" != "$OPENFIRE_VERSION" ]]; then
  log "解压 Openfire $OPENFIRE_VERSION"
  rm -rf "$DIST/openfire"
  tar -xzf "$OF_TAR" -C "$DIST"
  echo "$OPENFIRE_VERSION" > "$DIST/openfire/.version"
fi

# 插件：jar 文件名不带版本号，所以按版本号存放到单独的文件名，再复制成插件名
fetch_plugin() {
  local repo="$1" version="$2" name="$3"
  download "$GH/$repo/releases/download/v$version/$name.jar" "$DIST/plugins/$name-$version.jar.dl"
  cp "$DIST/plugins/$name-$version.jar.dl" "$DIST/plugins/$name.jar"
}
# 清掉旧的插件 jar，只保留 versions.env 中列出的插件
rm -f "$DIST/plugins/"*.jar
fetch_plugin openfire-monitoring-plugin "$PLUGIN_MONITORING_VERSION" monitoring
fetch_plugin openfire-httpfileupload-plugin "$PLUGIN_HTTPFILEUPLOAD_VERSION" httpfileupload
fetch_plugin openfire-externalservicediscovery-plugin "$PLUGIN_EXTERNALSERVICEDISCOVERY_VERSION" externalservicediscovery

log "完成：$DIST"
