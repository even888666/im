#!/usr/bin/env bash
# 构建自研插件（server/plugins/*）并部署到运行中的 Openfire：
#   1. 有 Java 时用 Gradle 打包（./gradlew -p server assemble），输出到 server/build/plugins/；
#      没有 Java 的服务器上使用预先构建好的 jar（在开发机上构建后复制到 server/build/plugins/）；
#   2. jar 有变化时复制到容器的 plugins 目录，Openfire 自动重新加载（容器重启时入口脚本也会同步）；
#   3. 等待插件加载完成（解压出的代码与新 jar 一致）。
#
# 用法：bash server/scripts/deploy-plugins.sh（需 Openfire 已完成安装）
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
load_env

OUT="$SERVER_DIR/build/plugins"

# 1. 构建
if command -v java >/dev/null 2>&1; then
  log "构建服务端插件…"
  "$REPO_DIR/gradlew" -p "$SERVER_DIR" assemble -q
else
  log "未安装 Java，使用已有的插件 jar（$OUT）"
fi
shopt -s nullglob
jars=("$OUT"/*.jar)
if (( ${#jars[@]} == 0 )); then
  echo "没有可部署的插件 jar：请安装 JDK 17+ 后重试，或在开发机上执行 ./gradlew -p server assemble 后把 server/build/plugins/ 复制过来" >&2
  exit 1
fi

# 2. 部署有变化的插件
for jar in "${jars[@]}"; do
  name="$(basename "$jar" .jar)"
  # 插件代码的校验和：jar 中 lib/plugin-{名称}.jar 的 SHA-256，用于判断容器中解压出的插件是否为最新
  want="$(python3 -c 'import sys,zipfile,hashlib; z=zipfile.ZipFile(sys.argv[1]); print(hashlib.sha256(z.read(sys.argv[2])).hexdigest())' \
    "$jar" "lib/plugin-$name.jar")"
  current="$(compose exec -T openfire sh -c "sha256sum /opt/openfire/plugins/$name/lib/plugin-$name.jar 2>/dev/null | cut -d' ' -f1" || true)"
  if [[ "$want" == "$current" ]]; then
    log "插件 $name 已是最新"
    continue
  fi
  log "部署插件 $name…"
  compose cp "$jar" "openfire:/opt/openfire/plugins/$name.jar" >/dev/null
  # 3. 等待 Openfire 检测到新 jar 并解压加载（插件监视器约每 5 秒检查一次）
  for ((i = 0; i < 120; i++)); do
    current="$(compose exec -T openfire sh -c "sha256sum /opt/openfire/plugins/$name/lib/plugin-$name.jar 2>/dev/null | cut -d' ' -f1" || true)"
    [[ "$want" == "$current" ]] && break
    sleep 1
  done
  if [[ "$want" != "$current" ]]; then
    echo "插件 $name 在 120 秒内未加载，最近日志：" >&2
    compose exec -T openfire tail -n 50 /opt/openfire/logs/openfire.log >&2 || true
    exit 1
  fi
  # 解压后插件还需要初始化（建表、注册处理器），稍等片刻
  sleep 3
  log "插件 $name 已加载"
done
