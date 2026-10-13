// 服务端插件的公共配置：Java 17 字节码（Openfire 5 的最低 Java 版本）、UTF-8、JUnit 4。
// 各插件的打包规则见 plugins/<name>/build.gradle.kts。

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        // 编译时使用当前 JDK（21），按 Java 17 生成字节码并检查 API
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(17)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
    }

    tasks.withType<Test>().configureEach {
        // 测试输出中文日志
        systemProperty("file.encoding", "UTF-8")
        testLogging { events("failed") }
    }
}
