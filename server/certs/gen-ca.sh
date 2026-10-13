#!/usr/bin/env bash
# 生成企业根证书（CA），见 IM功能清单.md §18.2"自签证书方案"。
#
# App、PC 端只信任这个根证书；Openfire、文件上传、coturn、官网等服务器证书都由它签发，
# 服务器证书到期或新增服务器时 App 不用发版。
#
# 用法：bash server/certs/gen-ca.sh [输出目录]
#   输出目录默认 server/certs/out，生成：
#     ca.key  根证书私钥（务必妥善保管，绝不能提交到仓库，out/ 已在 .gitignore 中）
#     ca.crt  根证书（PEM），内置到 App（res/raw）、PC 端，Web 端需安装到办公电脑
# 环境变量：
#   CA_SUBJECT  根证书主题，默认 "/C=CN/O=Android666 IM/CN=Android666 IM Root CA"
#   CA_DAYS     有效期（天），默认 3650（10 年）
#   IM_TEST_CA_KEY_FILE  仓库中根证书（app/certs/im_root_ca.crt）对应的私钥文件路径
#   IM_TEST_CA_KEY_B64   同上，私钥 PEM 的 Base64（单行，便于配置为云环境的密钥变量）
#
# 根证书来源（按优先级）：
#   1. out/ca.key 已存在：保留现有根证书，不覆盖（覆盖会导致已发布的 App 不再信任服务器），需要重建请先手动删除；
#   2. 仓库中有 app/certs/im_root_ca.crt（App 打包的根证书），且通过上面两个环境变量提供了它的私钥：
#      使用这对证书与私钥，服务器证书由它签发，App 与服务器自然匹配；
#   3. 否则生成一个新的随机根证书。此时如果仓库中有 app/certs/im_root_ca.crt，App 打包的根证书与本机
#      服务器不匹配（集成测试直接读取 out/ca.crt，不受影响），会打印提示。
set -euo pipefail

OUT_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/out}"
CA_SUBJECT="${CA_SUBJECT:-/C=CN/O=Android666 IM/CN=Android666 IM Root CA}"
CA_DAYS="${CA_DAYS:-3650}"

log() { echo "[gen-ca] $*"; }

REPO_CA="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/app/certs/im_root_ca.crt"

mkdir -p "$OUT_DIR"
if [[ -f "$OUT_DIR/ca.key" ]]; then
  log "根证书已存在，跳过：$OUT_DIR/ca.crt"
  if [[ -f "$REPO_CA" ]] && ! cmp -s "$REPO_CA" "$OUT_DIR/ca.crt"; then
    log "提示：本机服务器的根证书与 App 打包的 app/certs/im_root_ca.crt 不同，真机上的 App 将无法连接本机服务器"
  fi
  exit 0
fi

# 私钥只允许当前用户读取
umask 077

# 仓库中的根证书 + 外部提供的私钥
if [[ -f "$REPO_CA" ]] && [[ -n "${IM_TEST_CA_KEY_FILE:-}" || -n "${IM_TEST_CA_KEY_B64:-}" ]]; then
  if [[ -n "${IM_TEST_CA_KEY_FILE:-}" ]]; then
    cp "$IM_TEST_CA_KEY_FILE" "$OUT_DIR/ca.key.tmp"
  else
    echo "$IM_TEST_CA_KEY_B64" | base64 -d > "$OUT_DIR/ca.key.tmp"
  fi
  # 校验私钥与证书是否配对：比较两者的公钥
  if [[ "$(openssl x509 -in "$REPO_CA" -pubkey -noout)" == "$(openssl pkey -in "$OUT_DIR/ca.key.tmp" -pubout 2>/dev/null)" ]]; then
    mv "$OUT_DIR/ca.key.tmp" "$OUT_DIR/ca.key"
    cp "$REPO_CA" "$OUT_DIR/ca.crt"
    chmod 644 "$OUT_DIR/ca.crt"
    log "使用仓库中的根证书 app/certs/im_root_ca.crt 及提供的私钥"
    openssl x509 -in "$OUT_DIR/ca.crt" -noout -subject -enddate | sed 's/^/[gen-ca]   /'
    exit 0
  fi
  rm -f "$OUT_DIR/ca.key.tmp"
  echo "[gen-ca] 错误：提供的私钥与 app/certs/im_root_ca.crt 不匹配" >&2
  exit 1
fi

if [[ -f "$REPO_CA" ]]; then
  log "提示：未提供 app/certs/im_root_ca.crt 的私钥（IM_TEST_CA_KEY_FILE / IM_TEST_CA_KEY_B64），生成临时根证书；"
  log "      App 打包的根证书与本机服务器不匹配，真机无法连接本机服务器（集成测试不受影响）"
fi
# RSA 4096：根证书有效期长，用较长的密钥；RSA 兼容性最好（Android 7、各浏览器、Java 均支持）
openssl genrsa -out "$OUT_DIR/ca.key" 4096 2>/dev/null
openssl req -x509 -new -key "$OUT_DIR/ca.key" -sha256 -days "$CA_DAYS" \
  -subj "$CA_SUBJECT" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash" \
  -out "$OUT_DIR/ca.crt"
chmod 644 "$OUT_DIR/ca.crt"

log "已生成根证书：$OUT_DIR/ca.crt（有效期 $CA_DAYS 天）"
openssl x509 -in "$OUT_DIR/ca.crt" -noout -subject -enddate | sed 's/^/[gen-ca]   /'
