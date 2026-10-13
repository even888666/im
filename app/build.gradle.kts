plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

/**
 * 把企业根证书复制为生成资源 `res/raw/im_root_ca.crt`，供 network_security_config 和
 * core.security.RootCaTrust 使用（功能清单 §18.2）。候选来源在配置阶段确定（见 [rootCaCandidates]），
 * 取第一个存在的文件；都不存在时报错。
 */
abstract class PrepareRootCaTask : DefaultTask() {

    /** 存在的候选根证书文件，按优先级排列 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val candidates: ConfigurableFileCollection

    /** 生成的资源目录（其下的 raw/im_root_ca.crt） */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val source = candidates.files.firstOrNull()
            ?: throw GradleException(
                "未找到企业根证书。请把公司根证书放到 app/certs/im_root_ca.crt，" +
                    "或用 -Pim.rootCa=路径 指定，开发环境可先运行 server/certs/gen-ca.sh（见 app/certs/README.md）",
            )
        val text = source.readText()
        if (!text.contains("-----BEGIN CERTIFICATE-----")) {
            throw GradleException("${source.path} 不是 PEM 格式的证书")
        }
        if (text.contains("PRIVATE KEY")) {
            throw GradleException("${source.path} 中包含私钥，App 只能打包证书，绝不能打包私钥")
        }
        val target = outputDir.file("raw/im_root_ca.crt").get().asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
        logger.lifecycle("企业根证书：${source.path}")
    }
}

/** 根证书候选来源，按优先级：-Pim.rootCa、app/certs/im_root_ca.crt（公司正式）、server/certs/out/ca.crt（开发） */
val rootCaCandidates: List<File> = listOfNotNull(
    providers.gradleProperty("im.rootCa").orNull?.let { file(it) },
    file("certs/im_root_ca.crt"),
    rootProject.file("server/certs/out/ca.crt"),
).filter { it.exists() }

val prepareRootCa = tasks.register<PrepareRootCaTask>("prepareRootCa") {
    candidates.from(rootCaCandidates)
    outputDir.set(layout.buildDirectory.dir("generated/rootCa/res"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(prepareRootCa, PrepareRootCaTask::outputDir)
    }
}

/**
 * 集成测试（IT）开关：传入 `-Pim.it=true` 时，连接本地 Openfire 的集成测试才会运行，
 * 默认跳过，避免普通构建依赖服务器（见 开发计划.md §0.2）。
 */
val runIntegrationTests: String = providers.gradleProperty("im.it").getOrElse("false")

/** 集成测试使用的服务器目录（server/，由 server/up.sh 部署），测试从中读取根证书和测试账号密码 */
val serverDir: File = rootProject.layout.projectDirectory.dir("server").asFile

/**
 * 不走 HTTP 代理的主机。云环境通过 JAVA_TOOL_OPTIONS 给所有 JVM 设置了 HTTPS 代理，
 * 代理会把开发域名 im.example.com 转发到公网上的真实域名，集成测试访问本机 Docker 中的
 * 服务器（文件上传等 HTTP 请求）必须绕过代理，所以在原有列表后追加开发域名。
 */
val testNonProxyHosts: String = listOfNotNull(
    System.getProperty("http.nonProxyHosts")?.takeIf { it.isNotBlank() } ?: "localhost|127.*",
    "im.example.com|*.im.example.com",
).joinToString("|")

android {
    namespace = "cn.android666.im"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "cn.android666.im"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 服务器地址（多环境切换见开发计划，P1）：用 -Pim.domain=... -Pim.host=... 指定，
        // 默认连接开发环境域名；im.host 为空时直接连接域名（由 DNS 解析）
        buildConfigField("String", "IM_DOMAIN", "\"${providers.gradleProperty("im.domain").getOrElse("im.example.com")}\"")
        buildConfigField("String", "IM_HOST", "\"${providers.gradleProperty("im.host").getOrElse("")}\"")
    }

    buildTypes {
        release {
            optimization {
                // 开启 R8 代码压缩与混淆（功能清单 §18"代码混淆"）；keep 规则见 src/main/keepRules/
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Java 库脱糖：Smack 等库使用了 minSdk 24 上不完整的 Java 8+ API（如 java.time）
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources {
            // logback-classic 与 logback-core 都带有 META-INF/INDEX.LIST 等清单文件，打包时冲突；
            // 这些文件只用于 JVM 的 jar 索引，Android 上无用，直接排除
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
        }
    }
    buildFeatures {
        viewBinding = true
        // LogInitializer 需要 BuildConfig.DEBUG 决定日志级别（AGP 9 默认不生成 BuildConfig）
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric 需要读取合并后的资源与清单
            isIncludeAndroidResources = true
            all {
                it.systemProperty("im.it", runIntegrationTests)
                it.systemProperty("im.it.serverDir", serverDir.absolutePath)
                it.systemProperty("http.nonProxyHosts", testNonProxyHosts)
                // Robolectric 在新版 SDK（API 36+）中需要反射访问 JDK 内部类（FileDescriptor 相关），
                // JDK 17+ 默认不导出这些包，不加会报 IllegalAccessException
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                )
            }
        }
    }
}

// Room：schema 导出到 app/schemas/（纳入版本控制，Android工程架构.md §5.3），迁移测试从这里读取旧版本的 schema
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.android)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // XMPP（Smack），只允许 core/xmpp/smack 包引用（Android工程架构.md §3.3）
    implementation(libs.smack.android.extensions)
    implementation(libs.smack.tcp)
    implementation(libs.smack.experimental)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.material)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.smack.java11)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// kotlinx-coroutines-test 的虚拟时间 API（advanceTimeBy、currentTime 等）都标记为实验性，
// 只对单元测试的编译统一开启 opt-in，避免每个测试类重复写注解；生产代码不受影响
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("UnitTest")) {
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

// Smack 的 XML 解析器（smack-xmlparser-xpp3）依赖 xpp3 的 jar，其中的 org.xmlpull 类与 Android 系统自带的重复
// （Android 已内置 XmlPullParser 及其实现），Smack 4.5 依赖的是 org.codelibs:xpp3 和 xpp3:xpp3_min，
// 在 App 中全部排除，smack-xmlparser-xpp3 会使用系统的实现。单元测试运行在 JVM 上，需要保留
configurations.configureEach {
    if (!name.contains("UnitTest", ignoreCase = true)) {
        exclude(group = "org.codelibs", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3")
        exclude(group = "xpp3", module = "xpp3_min")
    }
}
