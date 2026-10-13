package cn.android666.im.testing

import org.junit.Assume.assumeTrue

/**
 * 集成测试（IT）工具。
 *
 * IT 需要连接本地 Docker 中的 Openfire（`server/up.sh` 启动），默认跳过；
 * 运行方式：`./gradlew testDebugUnitTest -Pim.it=true`（见 开发计划.md §0.2）。
 */
object IntegrationTests {

    /** Gradle 通过 `-Pim.it=true` 传入的系统属性名，见 app/build.gradle.kts */
    private const val PROPERTY = "im.it"

    /**
     * 是否开启了集成测试。
     *
     * @return 传入 `-Pim.it=true` 时返回 true
     */
    fun enabled(): Boolean = System.getProperty(PROPERTY) == "true"

    /**
     * 在 IT 的 `@Before` 或测试开头调用：未开启 IT 时把测试标记为"跳过"而不是"失败"。
     */
    fun assumeEnabled() {
        assumeTrue("未开启集成测试（需 -Pim.it=true），跳过", enabled())
    }
}
