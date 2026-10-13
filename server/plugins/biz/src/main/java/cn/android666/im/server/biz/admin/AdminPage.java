package cn.android666.im.server.biz.admin;

import cn.android666.im.server.biz.BizPlugin;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 管理后台页面的公共部分：HTML 转义、页面框架（Openfire 管理后台按 pageID 高亮左侧菜单）、CSRF 隐藏字段、提示信息。
 *
 * 页面由 Servlet 直接输出 HTML，不用 JSP（插件构建不需要 JSP 预编译）。
 * 与 org 插件的同名类相同（两个插件的类加载器相互独立，不能共用），样式类名沿用 org-*。
 */
public abstract class AdminPage extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /**
     * 当前运行的插件。
     *
     * @return 插件实例
     */
    protected static BizPlugin plugin() {
        return BizPlugin.instance();
    }

    /**
     * HTML 转义。
     *
     * @param text 文本，可为 null
     * @return 转义后的文本
     */
    protected static String esc(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 本次请求的 CSRF 令牌（PluginServlet 每次请求生成新的令牌，表单提交时必须带上）。
     *
     * @param request 请求
     * @return 令牌
     */
    protected static String csrf(HttpServletRequest request) {
        Object token = request.getAttribute("csrf");
        return token == null ? "" : token.toString();
    }

    /**
     * 表单中的 CSRF 隐藏字段。
     *
     * @param request 请求
     * @return HTML
     */
    protected static String csrfField(HttpServletRequest request) {
        return "<input type=\"hidden\" name=\"csrf\" value=\"" + esc(csrf(request)) + "\">";
    }

    /**
     * 开始输出页面：设置编码，输出 head（标题、pageID）和 body 开头。
     *
     * @param response 响应
     * @param title    页面标题
     * @param pageId   plugin.xml 中菜单项的 id
     * @return 输出流
     * @throws IOException 输出失败
     */
    protected static PrintWriter begin(HttpServletResponse response, String title, String pageId) throws IOException {
        response.setContentType("text/html; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        out.println("<html><head><title>" + esc(title) + "</title>");
        out.println("<meta name=\"pageID\" content=\"" + esc(pageId) + "\"/>");
        out.println("<style>.org-table td,.org-table th{padding:4px 8px;border-bottom:1px solid #ddd;text-align:left}"
                + ".org-ok{color:#2e7d32}.org-err{color:#c62828}.org-muted{color:#888}"
                + ".org-form td{padding:3px 6px}pre.org-result{background:#f5f5f5;padding:8px;white-space:pre-wrap}</style>");
        out.println("</head><body>");
        return out;
    }

    /**
     * 输出提示信息（成功或失败）。
     *
     * @param out     输出流
     * @param message 提示，null 时不输出
     * @param error   是否为错误
     */
    protected static void message(PrintWriter out, String message, boolean error) {
        if (message != null && !message.isEmpty()) {
            out.println("<p class=\"" + (error ? "org-err" : "org-ok") + "\">" + esc(message) + "</p>");
        }
    }

    /**
     * 结束输出页面。
     *
     * @param out 输出流
     */
    protected static void end(PrintWriter out) {
        out.println("</body></html>");
    }

    /**
     * 读取请求参数（去掉首尾空白）。
     *
     * @param request 请求
     * @param name    参数名
     * @return 参数值，没有时为空字符串
     */
    protected static String param(HttpServletRequest request, String name) {
        String value = request.getParameter(name);
        return value == null ? "" : value.trim();
    }

    /**
     * 读取整数参数。
     *
     * @param request      请求
     * @param name         参数名
     * @param defaultValue 没有或不是整数时的默认值
     * @return 整数
     */
    protected static int intParam(HttpServletRequest request, String name, int defaultValue) {
        try {
            return Integer.parseInt(param(request, name));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 提交后重定向回列表页（避免刷新时重复提交），通过参数带上提示信息。
     *
     * @param request  请求
     * @param response 响应
     * @param page     页面文件名（如 org-departments.jsp）
     * @param message  提示信息
     * @param error    是否为错误
     * @throws IOException 重定向失败
     */
    protected static void redirect(HttpServletRequest request, HttpServletResponse response, String page,
                                   String message, boolean error) throws IOException {
        String url = page + "?" + (error ? "error=" : "ok=") + java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8);
        response.sendRedirect(url);
    }
}
