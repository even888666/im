// 服务端（Openfire 插件）的独立 Gradle 构建，与 Android 工程互不影响。
//
// 用法（在仓库根目录执行，复用根目录的 Gradle Wrapper）：
//   ./gradlew -p server build        # 编译、测试并打出插件 jar（server/build/plugins/*.jar）
//   ./gradlew -p server test         # 只运行插件的单元测试
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Openfire 的 xmppserver 及其部分依赖（libidn、shaj）只发布在 Ignite Realtime 的 Maven 仓库
        maven("https://igniterealtime.org/archiva/repository/maven/") {
            content {
                includeGroupByRegex("org\\.igniterealtime.*")
                includeGroup("org.gnu.inet")
                includeGroup("com.cenqua.shaj")
            }
        }
    }
}

rootProject.name = "im-server"
// 插件放在 plugins/ 目录下，项目路径直接用插件名（避免多出一个空的 :plugins 项目）
include(":org")
project(":org").projectDir = file("plugins/org")
