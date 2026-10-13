# 企业根证书

把公司正式环境的企业根证书（`server/certs/gen-ca.sh` 生成的 `ca.crt`，PEM 格式，**只要证书，不要私钥**）
放在本目录，命名为 `im_root_ca.crt` 并提交到仓库。构建时会打包进 App 的 `res/raw/im_root_ca`。

构建时根证书的来源（按优先级，见 `app/build.gradle.kts` 中的 `prepareRootCa` 任务）：

1. Gradle 属性 `-Pim.rootCa=证书路径`；
2. 本目录的 `im_root_ca.crt`（公司正式根证书）；
3. `server/certs/out/ca.crt`（开发环境 `server/up.sh` 生成的根证书，云会话中使用）。

都不存在时构建失败。**发布正式版前务必确认使用的是公司正式根证书**，否则 App 无法连接公司服务器。
