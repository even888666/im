package cn.android666.im.testing

import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * 集成测试中通过 Openfire 管理后台（https://127.0.0.1:9091）执行管理操作，例如组织架构插件的禁用账号、
 * 要求改密。走真实的管理后台页面，同时验证了页面本身（登录、CSRF、表单处理）。
 *
 * 登录流程（Openfire login.jsp）：GET 登录页拿到 csrf cookie → POST 用户名、密码和同值的 csrf 参数。
 * 插件页面的表单提交需要带上页面中的 csrf 隐藏字段（PluginServlet 校验）。
 */
object ItAdmin {

    /** 管理后台地址（证书 SAN 中包含 127.0.0.1） */
    private const val BASE_URL = "https://127.0.0.1:9091"

    /** 带 Cookie 的 HTTP 客户端：只信任开发根证书，不跟随重定向（需要检查 302） */
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .sslContext(ItServer.sslContext)
            .cookieHandler(CookieManager())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    /** 是否已登录 */
    @Volatile
    private var loggedIn = false

    /** 页面中 csrf 隐藏字段的值 */
    private val csrfField = Regex("""name="csrf" value="([^"]+)"""")

    /**
     * 登录管理后台（只登录一次）。
     *
     * @throws IllegalStateException 登录失败
     */
    @Synchronized
    fun login() {
        if (loggedIn) return
        val page = get("/login.jsp")
        check(page.statusCode() == 200) { "打开登录页失败：HTTP ${page.statusCode()}" }
        val csrf = page.headers().allValues("set-cookie").firstNotNullOfOrNull { Regex("csrf=([^;]+)").find(it)?.groupValues?.get(1) }
            ?: error("登录页没有返回 csrf cookie")
        val response = post("/login.jsp", mapOf("login" to "true", "csrf" to csrf, "username" to "admin", "password" to ItServer.adminPassword))
        // 登录成功重定向到首页，失败时返回 200 并显示错误
        check(response.statusCode() == 302) { "管理后台登录失败：HTTP ${response.statusCode()}" }
        loggedIn = true
    }

    /**
     * 对员工执行组织架构插件的管理操作（员工管理页面的按钮）。
     *
     * @param username 工号
     * @param action disable（禁用并踢下线）、enable（启用）、requireChange（要求下次登录改密）
     * @throws IllegalStateException 操作失败
     */
    fun employeeAction(username: String, action: String) {
        login()
        val page = get("/plugins/org/org-employees.jsp?edit=$username")
        check(page.statusCode() == 200) { "打开员工页面失败：HTTP ${page.statusCode()}" }
        val csrf = csrfField.find(page.body())?.groupValues?.get(1) ?: error("员工页面中没有 csrf 字段")
        val response = post("/plugins/org/org-employees.jsp", mapOf("csrf" to csrf, "action" to action, "username" to username))
        val location = response.headers().firstValue("location").orElse("")
        check(response.statusCode() == 302 && "ok=" in location) { "操作 $action 失败：HTTP ${response.statusCode()}，$location" }
    }

    /**
     * 维护 biz 插件的敏感词库（管理后台"敏感词"页面，开发计划 4.21）。
     *
     * @param action add（添加，words 每个一个）或 delete（删除，逐个删除）
     * @param words 敏感词
     * @throws IllegalStateException 操作失败
     */
    fun sensitiveWords(action: String, words: List<String>) {
        login()
        val targets = if (action == "add") listOf(words.joinToString("\n")) else words
        for (value in targets) {
            val page = get("/plugins/biz/biz-sensitive-words.jsp")
            check(page.statusCode() == 200) { "打开敏感词页面失败：HTTP ${page.statusCode()}" }
            val csrf = csrfField.find(page.body())?.groupValues?.get(1) ?: error("敏感词页面中没有 csrf 字段")
            val form = mutableMapOf("csrf" to csrf, "action" to action)
            if (action == "add") form["words"] = value else form["word"] = value
            val response = post("/plugins/biz/biz-sensitive-words.jsp", form)
            check(response.statusCode() == 302) { "敏感词操作 $action 失败：HTTP ${response.statusCode()}" }
        }
    }

    /**
     * 发送 GET 请求。
     *
     * @param path 路径
     * @return 响应
     */
    private fun get(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI.create(BASE_URL + path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    /**
     * 提交表单。
     *
     * @param path 路径
     * @param form 表单字段
     * @return 响应
     */
    private fun post(path: String, form: Map<String, String>): HttpResponse<String> {
        val body = form.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
        }
        val request = HttpRequest.newBuilder(URI.create(BASE_URL + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
