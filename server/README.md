# IM 服务端部署说明

> 开发环境整体搭建步骤与踩坑记录见根目录的《开发环境搭建.md》。

服务端由以下组件组成，全部通过 Docker Compose 部署（见《IM功能清单.md》§19）：

| 组件 | 说明 | 端口 |
| --- | --- | --- |
| Openfire | XMPP 服务器（含 HTTP 文件上传、Monitoring、External Service Discovery 插件，以及自研插件） | 5222（客户端 XMPP）、7443（HTTPS：文件上传 / WebSocket）、9091（管理后台） |
| PostgreSQL | Openfire 数据库 | 不对外开放 |
| coturn | 音视频通话 STUN / TURN | 3478（UDP / TCP）、中继端口范围（UDP） |

## 目录结构

```
server/
├── up.sh / down.sh / reset.sh     # 一键启动 / 停止 / 清空数据
├── fetch.sh                       # 下载 Openfire 安装包与插件
├── versions.env                   # 组件版本（提交到仓库）
├── .env.example                   # 部署配置模板；首次运行 up.sh 时生成 .env（含密码，不提交）
├── certs/                         # 证书脚本；生成的根证书、服务器证书在 certs/out/（不提交）
├── config/openfire-properties.conf  # Openfire 系统属性
├── docker/                        # docker-compose.yml、Openfire 镜像、coturn 配置模板
└── scripts/                       # up.sh 调用的子脚本
```

## 一、在开发环境（云会话）中使用

云会话启动时，SessionStart hook 会在后台自动运行 `up.sh`，日志在 `/tmp/im-server-up.log`。也可以手动运行：

```bash
bash server/up.sh        # 启动（可重复执行）
bash server/down.sh      # 停止，保留数据
bash server/reset.sh     # 停止并删除所有数据（开发环境从零重建）
```

开发环境（`.env` 中 `IM_ENV=dev`）会：

- 自动创建测试账号 `10001` ~ `10010`，密码见 `.env` 中的 `TEST_USER_PASSWORD`；
- 在 `/etc/hosts` 中把 `im.example.com` 及其子域指向 `127.0.0.1`。

运行连接本地服务器的集成测试：

```bash
./gradlew testDebugUnitTest -Pim.it=true
```

> 云会话容器是临时的：会话结束后服务器和数据都会丢失，新会话会重新部署。
> 根证书：配置了环境变量 `IM_TEST_CA_KEY_B64` 时使用仓库中的测试根证书（与 App 打包的一致），否则每次生成临时根证书，见 `app/certs/README.md`。
> 云环境的外部网络无法访问这台服务器，手机真机联调请部署到公司服务器（见下文）。

## 二、部署到公司 Linux 服务器

### 2.1 准备

| 项目 | 要求 |
| --- | --- |
| 系统 | 64 位 Linux（建议 Ubuntu 22.04 / 24.04 或其他主流发行版） |
| 软件 | Docker Engine 24+（含 Compose 插件）、OpenSSL、curl、bash |
| 配置 | 1 万人规模建议 8 核 16 GB 以上，磁盘按消息存档和文件上传量规划（功能清单 §18.2） |
| 域名 | 专网 DNS 中为服务器配置域名（如 `im.corp.local`），并解析 `conference.`、`httpfileupload.`、`turn.` 等子域（或配置泛解析） |
| 文件句柄 | 宿主机 `ulimit -Hn` 至少 65536，例如在 `/etc/security/limits.conf` 和 Docker 守护进程配置中调高 |

服务器无法访问外网时，在一台能上网的机器上运行 `bash server/fetch.sh` 并 `docker pull` 所需镜像（见 `versions.env`），再把整个仓库目录和镜像拷贝过去。

### 2.2 配置

```bash
cp server/.env.example server/.env
vi server/.env
```

必须修改的项：

| 配置项 | 说明 |
| --- | --- |
| `IM_ENV` | 改为 `prod`（不创建测试账号、不修改 hosts） |
| `IM_DOMAIN` | 实际的 XMPP 域名，例如 `im.corp.local`；**部署后不能再修改**（用户的 JID 包含域名） |
| `EXTRA_SANS` | 客户端还可能通过哪些域名或 IP 访问，例如 `IP:10.1.2.3` |
| `TURN_HOST` | 手机和电脑访问 TURN 的地址，默认 `turn.{IM_DOMAIN}` |
| `TURN_MIN_PORT` / `TURN_MAX_PORT` | 中继端口范围，按"同时通话路数 × 2"预留，例如 `49160` ~ `49999` |
| `TURN_EXTERNAL_IP` | 服务器在 NAT 后时填写对外 IP；专网内一般留空 |

密码留空即可，`up.sh` 会生成随机密码并写回 `.env`。**请妥善保管 `.env`**。

### 2.3 启动

```bash
bash server/up.sh
```

完成后输出各服务地址。管理后台默认只监听本机（`ADMIN_BIND_ADDRESS=127.0.0.1`），可通过 SSH 端口转发访问：

```bash
ssh -L 9091:127.0.0.1:9091 用户@服务器
# 浏览器打开 https://127.0.0.1:9091，用户名 admin，密码为 .env 中的 OPENFIRE_ADMIN_PASSWORD
```

### 2.4 防火墙需要开放的端口

| 端口 | 协议 | 用途 | 访问方 |
| --- | --- | --- | --- |
| 5222 | TCP | 客户端 XMPP（STARTTLS） | 手机、PC |
| 7443 | TCP | HTTPS：文件上传 / 下载、Web 端 WebSocket | 手机、PC、浏览器 |
| 3478 | UDP + TCP | STUN / TURN | 手机、PC、浏览器 |
| `TURN_MIN_PORT` ~ `TURN_MAX_PORT` | UDP | TURN 中继 | 手机、PC、浏览器 |
| 9091 | TCP | 管理后台 | **仅管理员**，不要对外开放 |

### 2.5 证书

`up.sh` 首次运行时在 `server/certs/out/` 生成：

| 文件 | 说明 |
| --- | --- |
| `ca.key` | 企业根证书私钥，**务必离线备份并妥善保管**，泄露后任何人都能伪造服务器 |
| `ca.crt` | 企业根证书：需内置到 App（`app/src/main/res/raw/`）和 PC 端；Web 端需安装到办公电脑 |
| `server.crt` / `server.key` | 服务器证书（有效期 2 年），由根证书签发 |

- 根证书有效期 10 年，已存在时不会重新生成；更换根证书意味着所有已安装的 App 都要升级。
- 目前 App 打包的是仓库中的**测试根证书**（`app/certs/im_root_ca.crt`）。用它部署时，通过 `IM_TEST_CA_KEY_FILE` 或 `IM_TEST_CA_KEY_B64` 提供私钥，`gen-ca.sh` 会使用这对证书和私钥，详见 `app/certs/README.md`。
- 服务器证书重新签发后，`up.sh` 会自动重启 Openfire 以加载新证书。
- 服务器证书到期前：删除 `server.crt` 后重新运行 `up.sh` 即可重新签发，App 不需要发版。
- 修改 `EXTRA_SANS` 后运行 `up.sh` 会自动重新签发服务器证书。

## 三、日常运维

| 操作 | 方法 |
| --- | --- |
| 查看日志 | `docker compose -f server/docker/docker-compose.yml logs -f openfire`（或 `postgres`、`coturn`）；Openfire 详细日志在容器内 `/opt/openfire/logs/` |
| 修改 Openfire 系统属性 | 编辑 `config/openfire-properties.conf` 后运行 `up.sh`，有变化时自动重启 Openfire |
| 升级组件 | 修改 `versions.env` 中的版本号后运行 `up.sh`（升级前先备份） |
| 备份 | 备份 PostgreSQL（`docker compose ... exec postgres pg_dump -U openfire openfire > backup.sql`）、上传文件数据卷 `im-server_openfire-upload`、`server/.env` 和 `server/certs/out/` |
| 停止 / 启动 | `down.sh` / `up.sh`，数据保留在 Docker 数据卷中 |

## 四、配置说明

`up.sh` 依次执行：

1. 生成 `.env`（首次）并补全随机密码；
2. 生成根证书和服务器证书（`certs/`）；
3. 下载 Openfire 与插件（`fetch.sh`）；
4. 生成 coturn 配置，构建镜像并启动容器；
5. 等待 Openfire 自动安装完成（`openfire.xml` 中的 autosetup）；
6. 写入 Openfire 系统属性（`scripts/apply-properties.sh`）；
7. 配置 STUN / TURN 服务下发（`scripts/apply-external-services.sh`）；
8. 开发环境写入 hosts。

所有步骤都可重复执行，已完成的会跳过。
