/*
 * 云会话专用的 Gradle 初始化脚本：把 Maven Central 的请求转到 Google 托管的 Maven Central 镜像。
 *
 * 原因：云环境出口 IP 由很多会话共用，直连 repo.maven.apache.org 会间歇性收到 HTTP 429（限流），
 * 导致依赖下载失败；Google 镜像内容与 Maven Central 一致且不限流。
 *
 * 只由 scripts/env/session-start.sh 复制到 ~/.gradle/init.d/ 生效，不影响工程本身的
 * settings.gradle.kts，开发者在本地构建时仍直连 Maven Central（或自己配置的镜像）。
 */

/** Google 托管的 Maven Central 镜像地址 */
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

/**
 * 把仓库列表中指向 Maven Central 的仓库地址替换为镜像地址。
 * 使用 all {} 而不是 forEach，保证之后才添加的仓库（如 settings 中声明的 mavenCentral()）也会被替换。
 *
 * @param repos 要处理的仓库列表
 */
fun redirectCentral(repos: RepositoryHandler) {
    repos.all {
        if (this is MavenArtifactRepository && url.toString().startsWith("https://repo.maven.apache.org/maven2")) {
            setUrl(centralMirror)
        }
    }
}

beforeSettings {
    redirectCentral(pluginManagement.repositories)
    redirectCentral(dependencyResolutionManagement.repositories)
}

allprojects {
    redirectCentral(buildscript.repositories)
    redirectCentral(repositories)
}
