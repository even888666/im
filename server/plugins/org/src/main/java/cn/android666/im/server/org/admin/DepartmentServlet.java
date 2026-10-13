package cn.android666.im.server.org.admin;

import cn.android666.im.server.org.OrgService;
import cn.android666.im.server.org.model.Department;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台：部门维护（开发计划 3.4）——树形列表、新增、修改（名称 / 上级 / 排序）、删除（只能删除空部门）。
 * 每次修改都会写变更日志并使组织架构版本号加 1，客户端下次同步时拿到。
 */
public class DepartmentServlet extends AdminPage {

    private static final long serialVersionUID = 1L;

    /** 页面文件名 */
    private static final String PAGE = "org-departments.jsp";

    /**
     * 显示部门树和新增 / 修改表单。
     *
     * @param request  请求（edit=部门 ID 时显示修改表单）
     * @param response 响应
     * @throws IOException 输出失败
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        OrgService org = plugin().orgService();
        PrintWriter out = begin(response, "部门管理", "org-departments");
        message(out, request.getParameter("ok"), false);
        message(out, request.getParameter("error"), true);

        List<String[]> tree = tree(org);
        Department editing = org.department(param(request, "edit")).orElse(null);

        // 新增 / 修改表单
        out.println("<form method=\"post\" action=\"" + PAGE + "\">" + csrfField(request));
        out.println("<input type=\"hidden\" name=\"action\" value=\"" + (editing == null ? "create" : "update") + "\">");
        if (editing != null) {
            out.println("<input type=\"hidden\" name=\"id\" value=\"" + esc(editing.id()) + "\">");
        }
        out.println("<table class=\"org-form\"><tr><td colspan=\"2\"><b>" + (editing == null ? "新增部门" : "修改部门 " + esc(editing.id()))
                + "</b></td></tr>");
        out.println("<tr><td>名称</td><td><input name=\"name\" size=\"30\" value=\"" + esc(editing == null ? "" : editing.name()) + "\"></td></tr>");
        out.println("<tr><td>上级部门</td><td><select name=\"parentId\"><option value=\"\">（顶级部门）</option>");
        for (String[] node : tree) {
            String selected = editing != null && node[0].equals(editing.parentId()) ? " selected" : "";
            out.println("<option value=\"" + esc(node[0]) + "\"" + selected + ">" + esc(node[1]) + "</option>");
        }
        out.println("</select></td></tr>");
        out.println("<tr><td>排序</td><td><input name=\"sort\" size=\"6\" value=\"" + (editing == null ? 0 : editing.sort())
                + "\"> <span class=\"org-muted\">同级部门按此升序排列</span></td></tr>");
        out.println("<tr><td></td><td><input type=\"submit\" value=\"保存\">"
                + (editing == null ? "" : " <a href=\"" + PAGE + "\">取消</a>") + "</td></tr></table></form>");

        // 部门树
        out.println("<h3>部门（共 " + tree.size() + " 个）</h3>");
        out.println("<table class=\"org-table\"><tr><th>部门</th><th>ID</th><th>排序</th><th>直属人数</th><th>操作</th></tr>");
        for (String[] node : tree) {
            Department d = org.department(node[0]).orElseThrow();
            out.println("<tr><td>" + esc(node[1]) + "</td><td>" + esc(d.id()) + "</td><td>" + d.sort() + "</td><td>"
                    + org.memberCount(d.id()) + "</td><td><a href=\"" + PAGE + "?edit=" + esc(d.id()) + "\">修改</a> "
                    + "<a href=\"org-employees.jsp?dept=" + esc(d.id()) + "\">员工</a> "
                    + "<form method=\"post\" action=\"" + PAGE + "\" style=\"display:inline\" onsubmit=\"return confirm('确定删除该部门吗？')\">"
                    + csrfField(request) + "<input type=\"hidden\" name=\"action\" value=\"delete\">"
                    + "<input type=\"hidden\" name=\"id\" value=\"" + esc(d.id()) + "\"><input type=\"submit\" value=\"删除\"></form>"
                    + "</td></tr>");
        }
        out.println("</table>");
        end(out);
    }

    /**
     * 新增、修改或删除部门。
     *
     * @param request  请求（action = create / update / delete）
     * @param response 响应（重定向回列表页）
     * @throws IOException 重定向失败
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        request.setCharacterEncoding("UTF-8");
        OrgService org = plugin().orgService();
        String action = param(request, "action");
        try {
            switch (action) {
                case "create" -> {
                    Department d = org.createDepartment(param(request, "parentId"), param(request, "name"), intParam(request, "sort", 0));
                    redirect(request, response, PAGE, "已新增部门：" + d.name(), false);
                }
                case "update" -> {
                    org.updateDepartment(new Department(param(request, "id"), param(request, "parentId"),
                            param(request, "name"), intParam(request, "sort", 0)));
                    redirect(request, response, PAGE, "已保存", false);
                }
                case "delete" -> {
                    org.deleteDepartment(param(request, "id"));
                    redirect(request, response, PAGE, "已删除", false);
                }
                default -> redirect(request, response, PAGE, "未知操作：" + action, true);
            }
        } catch (IllegalArgumentException e) {
            redirect(request, response, PAGE, e.getMessage(), true);
        } catch (SQLException e) {
            log("部门操作失败", e);
            redirect(request, response, PAGE, "数据库错误：" + e.getMessage(), true);
        }
    }

    /**
     * 按树形顺序列出全部部门。
     *
     * @param org 组织架构服务
     * @return 每项为 [部门 ID, 带缩进的显示名]
     */
    static List<String[]> tree(OrgService org) {
        Map<String, List<Department>> byParent = org.allDepartments().stream()
                .collect(Collectors.groupingBy(Department::parentId));
        List<String[]> result = new ArrayList<>();
        appendChildren(byParent, Department.ROOT, 0, result);
        return result;
    }

    /**
     * 递归添加下级部门。
     *
     * @param byParent 上级部门 ID → 下级部门
     * @param parentId 上级部门 ID
     * @param depth    层级（缩进）
     * @param result   结果
     */
    private static void appendChildren(Map<String, List<Department>> byParent, String parentId, int depth, List<String[]> result) {
        // 最多 20 级，防止数据异常时死循环
        if (depth > 20) {
            return;
        }
        List<Department> children = new ArrayList<>(byParent.getOrDefault(parentId, List.of()));
        children.sort(Comparator.comparingInt(Department::sort).thenComparing(Department::name));
        for (Department d : children) {
            result.add(new String[]{d.id(), "　".repeat(depth) + d.name()});
            appendChildren(byParent, d.id(), depth + 1, result);
        }
    }
}
