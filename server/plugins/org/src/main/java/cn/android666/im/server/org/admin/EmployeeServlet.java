package cn.android666.im.server.org.admin;

import cn.android666.im.server.org.OrgPlugin;
import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.PinyinConverter;
import cn.android666.im.server.org.importer.ImportResult;
import cn.android666.im.server.org.importer.ImportRow;
import cn.android666.im.server.org.model.Department;
import cn.android666.im.server.org.model.Employee;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 管理后台：员工维护（开发计划 3.4）——查询、新增、编辑（姓名 / 部门 / 职位 / 联系方式 / 排序）、
 * 调岗、禁用（立即踢下线）/ 启用、重新要求修改密码。
 */
public class EmployeeServlet extends AdminPage {

    private static final long serialVersionUID = 1L;

    /** 页面文件名 */
    private static final String PAGE = "org-employees.jsp";

    /** 列表每页人数 */
    private static final int PAGE_SIZE = 50;

    /**
     * 显示员工列表与编辑表单。
     *
     * @param request  请求（keyword、dept 筛选，page 页码，edit=工号 显示编辑表单，new=1 显示新增表单）
     * @param response 响应
     * @throws IOException 输出失败
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        OrgService org = plugin().orgService();
        PrintWriter out = begin(response, "员工管理", "org-employees");
        message(out, request.getParameter("ok"), false);
        message(out, request.getParameter("error"), true);

        Employee editing = org.employee(param(request, "edit")).orElse(null);
        if (editing != null || "1".equals(request.getParameter("new"))) {
            editForm(out, request, org, editing);
        } else {
            out.println("<p><a href=\"" + PAGE + "?new=1\">新增员工</a></p>");
        }

        // 查询条件
        String keyword = param(request, "keyword");
        String dept = param(request, "dept");
        out.println("<form method=\"get\" action=\"" + PAGE + "\">关键字 <input name=\"keyword\" value=\"" + esc(keyword)
                + "\" placeholder=\"姓名 / 拼音 / 工号 / 手机\"> 部门 " + deptSelect(org, "dept", dept, true)
                + " <input type=\"submit\" value=\"查询\"></form>");

        List<Employee> list = filter(org, keyword, dept);
        int pageNo = Math.max(1, intParam(request, "page", 1));
        int from = Math.min(list.size(), (pageNo - 1) * PAGE_SIZE);
        int to = Math.min(list.size(), from + PAGE_SIZE);
        out.println("<p>共 " + list.size() + " 人</p>");
        out.println("<table class=\"org-table\"><tr><th>工号</th><th>姓名</th><th>部门</th><th>职位</th><th>手机</th>"
                + "<th>状态</th><th>操作</th></tr>");
        for (Employee e : list.subList(from, to)) {
            String status = e.disabled() ? "<span class=\"org-err\">已禁用</span>"
                    : e.mustChangePassword() ? "<span class=\"org-muted\">待改初始密码</span>" : "正常";
            out.println("<tr><td>" + esc(e.username()) + "</td><td>" + esc(e.name()) + "</td><td>" + esc(org.path(e.deptId()))
                    + "</td><td>" + esc(e.title()) + "</td><td>" + esc(e.mobile()) + "</td><td>" + status + "</td><td>"
                    + "<a href=\"" + PAGE + "?edit=" + esc(e.username()) + "\">编辑</a></td></tr>");
        }
        out.println("</table>");
        // 分页
        int pages = (list.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (pages > 1) {
            out.print("<p>");
            for (int p = 1; p <= pages; p++) {
                if (p == pageNo) {
                    out.print("<b>" + p + "</b> ");
                } else {
                    out.print("<a href=\"" + PAGE + "?keyword=" + esc(urlEncode(keyword)) + "&dept=" + esc(urlEncode(dept))
                            + "&page=" + p + "\">" + p + "</a> ");
                }
            }
            out.println("</p>");
        }
        end(out);
    }

    /**
     * 输出新增 / 编辑表单。
     *
     * @param out     输出流
     * @param request 请求
     * @param org     组织架构服务
     * @param e       编辑的员工；新增时为 null
     */
    private void editForm(PrintWriter out, HttpServletRequest request, OrgService org, Employee e) {
        boolean creating = e == null;
        out.println("<form method=\"post\" action=\"" + PAGE + "\">" + csrfField(request));
        out.println("<input type=\"hidden\" name=\"action\" value=\"" + (creating ? "create" : "save") + "\">");
        out.println("<table class=\"org-form\"><tr><td colspan=\"2\"><b>" + (creating ? "新增员工" : "编辑员工 " + esc(e.username()))
                + "</b></td></tr>");
        if (creating) {
            out.println("<tr><td>工号</td><td><input name=\"username\" size=\"20\"></td></tr>");
        } else {
            out.println("<input type=\"hidden\" name=\"username\" value=\"" + esc(e.username()) + "\">");
        }
        out.println(row("姓名", "name", creating ? "" : e.name()));
        out.println("<tr><td>部门</td><td>" + deptSelect(org, "deptId", creating ? "" : e.deptId(), false) + "</td></tr>");
        out.println(row("职位", "title", creating ? "" : e.title()));
        out.println(row("手机", "mobile", creating ? "" : e.mobile()));
        out.println(row("座机", "phone", creating ? "" : e.phone()));
        out.println(row("邮箱", "email", creating ? "" : e.email()));
        if (creating) {
            out.println("<tr><td>初始密码</td><td><input name=\"password\" size=\"20\"> <span class=\"org-muted\">为空时随机生成</span></td></tr>");
        } else {
            out.println(row("部门内排序", "sort", Integer.toString(e.sort())));
        }
        out.println("<tr><td></td><td><input type=\"submit\" value=\"保存\"> <a href=\"" + PAGE + "\">取消</a></td></tr></table></form>");
        if (!creating) {
            out.println("<p>账号状态：" + (e.disabled() ? "<span class=\"org-err\">已禁用</span>" : "正常")
                    + (e.mustChangePassword() ? "，待修改初始密码" : "") + "</p>");
            out.println(actionButton(request, e.disabled() ? "enable" : "disable", e.username(),
                    e.disabled() ? "启用账号" : "禁用账号（立即踢下线）",
                    e.disabled() ? null : "禁用后该员工立即下线且无法登录，确定吗？"));
            if (!e.mustChangePassword()) {
                out.println(actionButton(request, "requireChange", e.username(), "要求下次登录修改密码", null));
            }
            out.println("<hr>");
        }
    }

    /**
     * 处理新增、保存、禁用、启用等操作。
     *
     * @param request  请求（action = create / save / disable / enable / requireChange）
     * @param response 响应（重定向）
     * @throws IOException 重定向失败
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        OrgPlugin plugin = plugin();
        OrgService org = plugin.orgService();
        String action = param(request, "action");
        String username = param(request, "username").toLowerCase(Locale.ROOT);
        String back = PAGE + "?edit=" + urlEncode(username) + "&";
        try {
            switch (action) {
                case "create" -> {
                    // 复用导入逻辑：建账号、生成拼音、生成初始密码、标记强制改密
                    Department d = org.department(param(request, "deptId"))
                            .orElseThrow(() -> new IllegalArgumentException("请选择部门"));
                    List<String> path = Arrays.asList(org.path(d.id()).split("/"));
                    ImportRow row = new ImportRow(0, username, param(request, "name"), path, param(request, "title"),
                            param(request, "mobile"), param(request, "phone"), param(request, "email"), param(request, "password"));
                    if (!username.matches("^[a-z0-9._-]{1,64}$") || row.name().isEmpty()) {
                        throw new IllegalArgumentException("工号只能包含字母、数字、点、下划线和横线，姓名不能为空");
                    }
                    ImportResult result = plugin.importer().importRows(List.of(row), OrgPlugin.importMustChangePassword());
                    if (!result.errors().isEmpty()) {
                        throw new IllegalArgumentException(String.join("；", result.errors()));
                    }
                    String msg = "已新增员工 " + username;
                    if (!result.generatedPasswords().isEmpty()) {
                        msg += "，初始密码：" + result.generatedPasswords().get(0).password();
                    }
                    response.sendRedirect(back.substring(0, back.length() - 1) + "&ok=" + urlEncode(msg));
                }
                case "save" -> {
                    Employee old = org.employee(username).orElseThrow(() -> new IllegalArgumentException("员工不存在：" + username));
                    String name = param(request, "name");
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException("姓名不能为空");
                    }
                    PinyinConverter.Result pinyin = PinyinConverter.convert(name);
                    org.saveEmployee(new Employee(username, name, pinyin.pinyin(), pinyin.initials(), param(request, "deptId"),
                            param(request, "title"), param(request, "mobile"), param(request, "phone"), param(request, "email"),
                            intParam(request, "sort", old.sort()), old.avatarHash(), old.disabled(), old.mustChangePassword()));
                    response.sendRedirect(back + "ok=" + urlEncode("已保存"));
                }
                case "disable" -> {
                    plugin.accountManager().disable(username);
                    response.sendRedirect(back + "ok=" + urlEncode("已禁用并踢下线"));
                }
                case "enable" -> {
                    plugin.accountManager().enable(username);
                    response.sendRedirect(back + "ok=" + urlEncode("已启用"));
                }
                case "requireChange" -> {
                    org.setMustChangePassword(username, true);
                    response.sendRedirect(back + "ok=" + urlEncode("该员工下次登录需要修改密码"));
                }
                default -> redirect(request, response, PAGE, "未知操作：" + action, true);
            }
        } catch (IllegalArgumentException e) {
            response.sendRedirect(back + "error=" + urlEncode(e.getMessage()));
        } catch (SQLException e) {
            log("员工操作失败", e);
            response.sendRedirect(back + "error=" + urlEncode("数据库错误：" + e.getMessage()));
        }
    }

    /**
     * 按关键字和部门筛选员工。
     *
     * @param org     组织架构服务
     * @param keyword 关键字（姓名、拼音、首字母、工号、手机）
     * @param dept    部门 ID（含下级部门），为空时不限
     * @return 员工列表
     */
    private static List<Employee> filter(OrgService org, String keyword, String dept) {
        String kw = keyword.toLowerCase(Locale.ROOT);
        Collection<String> depts = dept.isEmpty() ? null : org.subtree(dept);
        return org.allEmployees().stream()
                .filter(e -> depts == null || depts.contains(e.deptId()))
                .filter(e -> kw.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(kw) || e.pinyin().startsWith(kw)
                        || e.initials().startsWith(kw) || e.username().startsWith(kw) || e.mobile().contains(kw))
                .toList();
    }

    /**
     * 部门下拉框。
     *
     * @param org      组织架构服务
     * @param name     表单字段名
     * @param selected 选中的部门 ID
     * @param allowAll 是否有"全部"选项
     * @return HTML
     */
    private static String deptSelect(OrgService org, String name, String selected, boolean allowAll) {
        StringBuilder sb = new StringBuilder("<select name=\"" + name + "\">");
        if (allowAll) {
            sb.append("<option value=\"\">（全部）</option>");
        }
        for (String[] node : DepartmentServlet.tree(org)) {
            sb.append("<option value=\"").append(esc(node[0])).append('"').append(node[0].equals(selected) ? " selected" : "")
                    .append('>').append(esc(node[1])).append("</option>");
        }
        return sb.append("</select>").toString();
    }

    /**
     * 表单中的一行文本输入框。
     *
     * @param label 标签
     * @param name  字段名
     * @param value 值
     * @return HTML
     */
    private static String row(String label, String name, String value) {
        return "<tr><td>" + esc(label) + "</td><td><input name=\"" + name + "\" size=\"30\" value=\"" + esc(value) + "\"></td></tr>";
    }

    /**
     * 单个操作按钮（独立表单）。
     *
     * @param request  请求
     * @param action   操作
     * @param username 工号
     * @param label    按钮文字
     * @param confirm  确认提示，null 时不确认
     * @return HTML
     */
    private static String actionButton(HttpServletRequest request, String action, String username, String label, String confirm) {
        return "<form method=\"post\" action=\"" + PAGE + "\" style=\"display:inline\""
                + (confirm == null ? "" : " onsubmit=\"return confirm('" + esc(confirm) + "')\"") + ">" + csrfField(request)
                + "<input type=\"hidden\" name=\"action\" value=\"" + action + "\">"
                + "<input type=\"hidden\" name=\"username\" value=\"" + esc(username) + "\">"
                + "<input type=\"submit\" value=\"" + esc(label) + "\"></form> ";
    }

    /**
     * URL 编码。
     *
     * @param text 文本
     * @return 编码后的文本
     */
    private static String urlEncode(String text) {
        return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }
}
