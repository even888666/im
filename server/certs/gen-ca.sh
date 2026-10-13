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
#
# 已存在 ca.key 时不会覆盖（覆盖会导致已发布的 App 不再信任服务器），需要重建请先手动删除。
set -euo pipefail

OUT_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/out}"
CA_SUBJECT="${CA_SUBJECT:-/C=CN/O=Android666 IM/CN=Android666 IM Root CA}"
CA_DAYS="${CA_DAYS:-3650}"

log() { echo "[gen-ca] $*"; }

mkdir -p "$OUT_DIR"
if [[ -f "$OUT_DIR/ca.key" ]]; then
  log "根证书已存在，跳过：$OUT_DIR/ca.crt"
  exit 0
fi

# 私钥只允许当前用户读取
umask 077
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
