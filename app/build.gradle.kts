plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 集成测试（IT）开关：传入 `-Pim.it=true` 时，连接本地 Openfire 的集成测试才会运行，
 * 默认跳过，避免普通构建依赖服务器（见 开发计划.md §0.2）。
 */
val runIntegrationTests: String = providers.gradleProperty("im.it").getOrElse("false")

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
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
    implementation(libs.material)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
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
