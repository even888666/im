#!/usr/bin/env bash
# 用企业根证书签发服务器证书（Openfire、HTTP 文件上传、coturn 共用）。
#
# 用法：bash server/certs/gen-server-cert.sh [输出目录]
#   需先运行 gen-ca.sh。输出目录默认 server/certs/out，生成：
#     server.key  服务器私钥（不提交）
#     server.crt  服务器证书（PEM）
#     server-fullchain.crt  服务器证书 + 根证书
#     openfire-keystore.p12 供 Openfire 使用的密钥库（别名 = 域名，密码 = KEYSTORE_PASSWORD）
# 环境变量：
#   IM_DOMAIN          XMPP 域名，默认 im.example.com
#   EXTRA_SANS         额外的 SAN，逗号分隔，例如 "DNS:im.corp.local,IP:10.1.2.3"
#   SERVER_DAYS        有效期（天），默认 730（2 年）
#   KEYSTORE_PASSWORD  密钥库密码，默认 changeit（Openfire 默认值）
#
# SAN 默认包含：域名、*.域名（覆盖 conference.、httpfileupload.、turn. 等子域）、localhost、127.0.0.1。
# 客户端实际访问的域名或 IP 必须在 SAN 中，否则主机名校验失败（IM功能清单.md §18.2）。
# 已存在且 SAN 未变化时跳过，变化时重新签发。
set -euo pipefail

OUT_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/out}"
IM_DOMAIN="${IM_DOMAIN:-im.example.com}"
EXTRA_SANS="${EXTRA_SANS:-}"
SERVER_DAYS="${SERVER_DAYS:-730}"
KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-changeit}"

log() { echo "[gen-server-cert] $*"; }

if [[ ! -f "$OUT_DIR/ca.key" ]]; then
  echo "[gen-server-cert] 未找到根证书，请先运行 gen-ca.sh" >&2
  exit 1
fi

SANS="DNS:$IM_DOMAIN,DNS:*.$IM_DOMAIN,DNS:localhost,IP:127.0.0.1"
if [[ -n "$EXTRA_SANS" ]]; then
  SANS="$SANS,$EXTRA_SANS"
fi

# SAN 未变化且证书由当前根证书签发时跳过，保证脚本可重复执行
if [[ -f "$OUT_DIR/server.crt" && -f "$OUT_DIR/server.sans" ]] \
  && [[ "$(cat "$OUT_DIR/server.sans")" == "$SANS" ]] \
  && openssl verify -CAfile "$OUT_DIR/ca.crt" "$OUT_DIR/server.crt" >/dev/null 2>&1; then
  log "服务器证书已存在且 SAN 未变化，跳过：$OUT_DIR/server.crt"
  exit 0
fi

umask 077
openssl genrsa -out "$OUT_DIR/server.key" 2048 2>/dev/null
openssl req -new -key "$OUT_DIR/server.key" -subj "/C=CN/O=Android666 IM/CN=$IM_DOMAIN" -out "$OUT_DIR/server.csr"

# 扩展：服务器认证 + 客户端认证（Openfire 的服务器间连接会用到 clientAuth）
cat > "$OUT_DIR/server.ext" <<EXT
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=$SANS
authorityKeyIdentifier=keyid,issuer
subjectKeyIdentifier=hash
EXT

openssl x509 -req -in "$OUT_DIR/server.csr" -CA "$OUT_DIR/ca.crt" -CAkey "$OUT_DIR/ca.key" \
  -CAcreateserial -days "$SERVER_DAYS" -sha256 -extfile "$OUT_DIR/server.ext" -out "$OUT_DIR/server.crt" 2>/dev/null
cat "$OUT_DIR/server.crt" "$OUT_DIR/ca.crt" > "$OUT_DIR/server-fullchain.crt"

# Openfire 使用 Java 密钥库；PKCS12 格式 Java 原生支持，别名用域名便于识别
rm -f "$OUT_DIR/openfire-keystore.p12"
openssl pkcs12 -export -in "$OUT_DIR/server.crt" -inkey "$OUT_DIR/server.key" -certfile "$OUT_DIR/ca.crt" \
  -name "$IM_DOMAIN" -passout "pass:$KEYSTORE_PASSWORD" -out "$OUT_DIR/openfire-keystore.p12"

echo "$SANS" > "$OUT_DIR/server.sans"
rm -f "$OUT_DIR/server.csr" "$OUT_DIR/server.ext"
chmod 644 "$OUT_DIR/server.crt" "$OUT_DIR/server-fullchain.crt"

log "已签发服务器证书：$OUT_DIR/server.crt（有效期 $SERVER_DAYS 天）"
log "SAN：$SANS"
