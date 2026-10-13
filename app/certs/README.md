# 企业根证书

本目录的 `im_root_ca.crt` 会在构建时打包进 App 的 `res/raw/im_root_ca`，App 只信任这个根证书
（见 `res/xml/network_security_config.xml` 与 `core/security/RootCaTrust`）。

## 当前使用的是测试根证书

| 项目 | 内容 |
| --- | --- |
| 主题 | `CN=Android666 IM Test Root CA (test only), O=Android666 IM, C=CN` |
| 有效期 | 2026-10-13 ~ 2036-10-10 |
| SHA-256 指纹 | `FB:01:1A:98:44:1D:16:B7:10:44:E0:94:AC:93:31:26:11:33:64:99:25:1B:12:28:4C:7F:95:0D:A6:ED:E6:FF` |
| 私钥 | **不在仓库中**，由用户保管（文件名 `im_test_ca.key`） |

用户确认：开发测试阶段先使用这个测试根证书，暂不使用公司的正式根证书。

### 让服务器使用测试根证书

服务器证书必须由这个根证书签发，App 才能连接。部署服务器（`server/up.sh`）时通过环境变量提供私钥，
`server/certs/gen-ca.sh` 会使用"仓库中的证书 + 提供的私钥"，并校验二者是否配对：

```bash
# 方式一：私钥文件
IM_TEST_CA_KEY_FILE=/path/to/im_test_ca.key bash server/up.sh

# 方式二：私钥的 Base64（单行，适合配置为云环境的密钥变量）
export IM_TEST_CA_KEY_B64="$(base64 -w0 /path/to/im_test_ca.key)"
bash server/up.sh
```

- 云会话：在云环境设置中把 `IM_TEST_CA_KEY_B64` 配置为环境变量（密钥），新会话的 SessionStart 部署服务器时自动使用。
- 已经用其他根证书部署过的服务器：先删除 `server/certs/out/` 下的文件（证书均可重新生成），再带上私钥运行 `up.sh`，
  会重新签发服务器证书并自动重启 Openfire。
- 不提供私钥时，`gen-ca.sh` 会生成临时根证书并提示：此时集成测试仍可运行（直接读取 `server/certs/out/ca.crt`），
  但真机上的 App 无法连接这台服务器。

## 以后换成公司正式根证书

1. 在公司服务器上运行 `server/certs/gen-ca.sh` 生成正式根证书（私钥离线妥善保管）；
2. 用正式根证书的 `ca.crt` 替换本目录的 `im_root_ca.crt`（**只要证书，不要私钥**）并提交；
3. 更新上面的说明表格。

## 构建时根证书的来源

按优先级（见 `app/build.gradle.kts` 中的 `prepareRootCa` 任务）：

1. Gradle 属性 `-Pim.rootCa=证书路径`；
2. 本目录的 `im_root_ca.crt`；
3. `server/certs/out/ca.crt`（`server/up.sh` 生成的根证书）。

都不存在时构建失败。构建任务会检查文件必须是 PEM 证书、且不能包含私钥。
