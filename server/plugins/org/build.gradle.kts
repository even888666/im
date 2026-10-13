// 组织架构插件（org）：账号状态、组织架构查询与同步、同事搜索、在线状态查询、Excel 导入与后台维护。
// 协议见《三端协议规范.md》§3 ~ §5，开发计划 M3（3.1 ~ 3.11）。
//
// 打包结果 server/build/plugins/org.jar 的结构（Openfire 插件格式）：
//   plugin.xml                 插件描述（名称、版本、最低 Openfire 版本、管理后台菜单、数据库版本）
//   database/*.sql             建表脚本，安装插件时 Openfire 按数据库类型自动执行
//   web/WEB-INF/web.xml        管理后台页面（Servlet）的路径映射
//   lib/plugin-org.jar         插件代码
//   lib/*.jar                  插件依赖（pinyin4j）

/** 插件版本：修改插件代码后递增，Openfire 管理后台的插件列表中显示 */
version = "1.0.0"

dependencies {
    // Openfire 本身：运行时由服务器提供，不打进插件
    "compileOnly"(libs.openfire.xmppserver)
    "implementation"(libs.pinyin4j)

    "testImplementation"(libs.openfire.xmppserver)
    "testImplementation"(libs.junit)
    "testImplementation"(libs.truth)
    "testImplementation"(libs.h2)
    "testImplementation"(libs.fastexcel.writer)
}

/** 插件代码 jar，放在插件包的 lib/ 下 */
val codeJar = tasks.named<Jar>("jar") {
    archiveFileName.set("plugin-org.jar")
}

/** 打出 Openfire 插件包 org.jar */
val pluginJar = tasks.register<Jar>("pluginJar") {
    group = "build"
    description = "打出 Openfire 插件包 org.jar"
    archiveFileName.set("org.jar")
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
