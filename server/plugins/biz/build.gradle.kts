// 业务扩展插件（biz）：撤回校验、敏感词过滤（开发计划 4.19 ~ 4.21）；之后加入群聊扩展（M6）、诊断上报（M8）。
// 协议见《三端协议规范.md》§6.4、§8.4、§10、§14.2。
//
// 打包结果 server/build/plugins/biz.jar 的结构（Openfire 插件格式，与 org 插件相同）：
//   plugin.xml                 插件描述（名称、版本、最低 Openfire 版本、管理后台菜单、数据库版本）
//   database/*.sql             建表脚本，安装插件时 Openfire 按数据库类型自动执行
//   web/WEB-INF/web.xml        管理后台页面（Servlet）的路径映射
//   lib/plugin-biz.jar         插件代码

/** 插件版本：修改插件代码后递增，Openfire 管理后台的插件列表中显示 */
version = "1.0.0"

dependencies {
    // Openfire 本身：运行时由服务器提供，不打进插件
    "compileOnly"(libs.openfire.xmppserver)

    "testImplementation"(libs.openfire.xmppserver)
    "testImplementation"(libs.junit)
    "testImplementation"(libs.truth)
    "testImplementation"(libs.h2)
}

/** 插件代码 jar，放在插件包的 lib/ 下 */
val codeJar = tasks.named<Jar>("jar") {
    archiveFileName.set("plugin-biz.jar")
}

/** 打出 Openfire 插件包 biz.jar */
val pluginJar = tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "打出 Openfire 插件包 biz.jar"
    archiveFileName.set("biz.jar")
    // 所有插件包统一输出到 server/build/plugins/，up.sh 从这里部署
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("plugins"))
    // 插件描述、建表脚本、web.xml 等原样打包；plugin.xml 中的 @VERSION@ 替换为插件版本
    from("src/main/plugin") {
        filesMatching("plugin.xml") { filter { it.replace("@VERSION@", project.version.toString()) } }
    }
    into("lib") {
        from(codeJar)
        // 运行时依赖（不含 compileOnly 的 Openfire）
        from(configurations.named("runtimeClasspath"))
    }
    // 固定时间戳和文件顺序：代码不变时 jar 内容不变，up.sh 据此判断是否需要重新部署
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("assemble") { dependsOn(pluginJar) }
