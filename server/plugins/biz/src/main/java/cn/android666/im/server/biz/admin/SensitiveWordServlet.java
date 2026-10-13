package cn.android666.im.server.biz.admin;

import cn.android666.im.server.biz.BizService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * 管理后台"敏感词"页面（开发计划 4.21，协议规范 §6.4）：查看、批量添加（每行一个）、删除。
 * 修改后立即生效（词库在内存中整体替换）。
 */
public class SensitiveWordServlet extends AdminPage {

    private static final long serialVersionUID = 1L;

    /** 页面地址 */
    private static final String PAGE = "biz-sensitive-words.jsp";

    /**
     * 显示词库与添加表单。
     *
     * @param request  请求
     * @param response 响应
     * @throws IOException 输出失败
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        PrintWriter out = begin(response, "敏感词", "biz-sensitive-words");
        message(out, request.getParameter("ok"), false);
        message(out, request.getParameter("error"), true);
        out.println("<p>单聊、群聊消息的正文包含以下任意一个词时，服务器拒绝投递，发送方提示\"消息包含敏感内容，发送失败\"。"
                + "匹配时忽略大小写、空格和标点。</p>");
        out.println("<form method=\"post\" action=\"" + PAGE + "\">" + csrfField(request)
                + "<input type=\"hidden\" name=\"action\" value=\"add\">"
                + "<p>添加敏感词（每行一个，最长 " + BizService.MAX_WORD_LENGTH + " 个字）：</p>"
                + "<textarea name=\"words\" rows=\"6\" cols=\"40\"></textarea><br>"
                + "<input type=\"submit\" value=\"添加\"></form>");
        try {
            List<String> words = plugin().service().words();
            out.println("<h3>词库（" + words.size() + " 个）</h3>");
            out.println("<table class=\"org-table\"><tr><th>敏感词</th><th></th></tr>");
            for (String word : words) {
                out.println("<tr><td>" + esc(word) + "</td><td><form method=\"post\" action=\"" + PAGE + "\" style=\"margin:0\">"
                        + csrfField(request) + "<input type=\"hidden\" name=\"action\" value=\"delete\">"
                        + "<input type=\"hidden\" name=\"word\" value=\"" + esc(word) + "\">"
                        + "<input type=\"submit\" value=\"删除\"></form></td></tr>");
            }
            out.println("</table>");
        } catch (SQLException e) {
            message(out, "读取词库失败：" + e.getMessage(), true);
        }
        end(out);
    }

    /**
     * 添加或删除。
     *
     * @param request  请求
     * @param response 响应
     * @throws IOException 重定向失败
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        BizService service = plugin().service();
        try {
            switch (param(request, "action")) {
                case "add" -> {
                    String raw = request.getParameter("words");
                    List<String> words = raw == null ? List.of() : Arrays.asList(raw.split("\\r?\\n"));
                    int added = service.addWords(words);
                    redirect(request, response, PAGE, "已添加 " + added + " 个敏感词", false);
                }
                case "delete" -> {
                    String word = request.getParameter("word");
                    boolean deleted = word != null && service.deleteWord(word);
                    redirect(request, response, PAGE, deleted ? "已删除：" + word : "敏感词不存在", !deleted);
                }
                default -> redirect(request, response, PAGE, "未知操作", true);
            }
        } catch (SQLException e) {
            redirect(request, response, PAGE, "操作失败：" + e.getMessage(), true);
        }
    }
}
