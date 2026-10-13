# TLS 测试证书

单元测试 `RootCaTrustTest` 使用的证书（有效期 100 年），只含公钥证书，**私钥生成后已销毁**，不能用于任何服务器。

| 文件 | 说明 |
| --- | --- |
| `test-ca.crt` | 测试根证书 |
| `test-server.crt` | 由测试根证书签发的服务器证书，SAN：`im.example.com`、`*.im.example.com` |
| `untrusted-server.crt` | 由另一个根证书签发的服务器证书，用于验证"不信任其他 CA" |
