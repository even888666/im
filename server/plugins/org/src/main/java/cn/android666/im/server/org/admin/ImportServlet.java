package cn.android666.im.server.org.admin;

import cn.android666.im.server.org.OrgPlugin;
import cn.android666.im.server.org.importer.ImportResult;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 管理后台：Excel / CSV 导入员工（开发计划 3.3）。
 *
 * - GET：导入页面（模板说明、选择文件、导入结果区域）；{@code ?template=csv} 下载 CSV 模板；
 * - POST：请求体为文件内容（页面用 JavaScript 直接上传文件，不用 multipart，免去解析 multipart 的依赖），
 *   文件名在参数 fileName 中，返回纯文本的导入结果；新的 CSRF 令牌放在响应头 X-Csrf 中供下次上传使用。
 */
public class ImportServlet extends AdminPage {

    private static final long serialVersionUID = 1L;

    /** 上传文件大小上限 */
    private static final int MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

    /** CSV 模板的表头与示例行 */
    private static final String TEMPLATE = "工号,姓名,部门路径,职位,手机,座机,邮箱,初始密码\r\n"
            + "10086,张三,总部/研发中心/Android组,工程师,13800000000,010-12345678,zhangsan@example.com,\r\n";

    /**
     * 显示导入页面或下载模板。
     *
     * @param request  请求
     * @param response 响应
     * @throws IOException 输出失败
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if ("csv".equals(request.getParameter("template"))) {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"org-import-template.csv\"");
            // 带 BOM，Excel 打开 UTF-8 的 CSV 时中文不乱码
            response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            response.getOutputStream().write(TEMPLATE.getBytes(StandardCharsets.UTF_8));
            return;
        }
        PrintWriter out = begin(response, "组织架构导入", "org-import");
        boolean mustChange = OrgPlugin.importMustChangePassword();
        out.println("<p>从 Excel（.xlsx）或 CSV 文件导入员工。表头（第一行）为："
                + "<b>工号、姓名、部门路径</b>（必填）、职位、手机、座机、邮箱、初始密码。"
                + " <a href=\"org-import.jsp?template=csv\">下载模板</a></p>");
        out.println("<ul>"
                + "<li>以工号为准：已存在的员工更新信息，不存在的新建账号；表中没有的员工不会被删除（离职请在员工管理中禁用）。</li>"
                + "<li>部门路径用 / 分隔，如 <code>总部/研发中心/Android组</code>，不存在的部门自动创建。</li>"
                + "<li>新账号的初始密码为空时由系统随机生成，导入结果中会列出，请发给员工。</li>"
                + "<li>新账号首次登录" + (mustChange ? "需要" : "<b>不需要</b>") + "修改密码（系统属性 "
                + OrgPlugin.PROP_IMPORT_MUST_CHANGE_PASSWORD + "）。已存在的账号不修改密码。</li>"
                + "<li>也可以把文件放到服务器的 <code>{Openfire 目录}/org-import/</code> 目录中，10 秒内自动导入，结果在 done/ 目录中。</li>"
                + "</ul>");
        out.println("<p><input type=\"file\" id=\"orgFile\" accept=\".xlsx,.csv\"> "
                + "<button type=\"button\" id=\"orgImport\">导入</button></p>");
        out.println("<pre class=\"org-result\" id=\"orgResult\" style=\"display:none\"></pre>");
        out.println("<script>(function(){var csrf='" + esc(csrf(request)) + "';"
                + "document.getElementById('orgImport').onclick=function(){"
                + "var f=document.getElementById('orgFile').files[0];var r=document.getElementById('orgResult');"
                + "if(!f){alert('请先选择文件');return;}"
                + "r.style.display='block';r.textContent='正在导入，请稍候…';"
                + "var x=new XMLHttpRequest();"
                + "x.open('POST','org-import.jsp?csrf='+encodeURIComponent(csrf)+'&fileName='+encodeURIComponent(f.name));"
                + "x.setRequestHeader('Content-Type','application/octet-stream');"
                + "x.onload=function(){var t=x.getResponseHeader('X-Csrf');if(t){csrf=t;}"
                + "r.textContent=x.status==200?x.responseText:('导入失败（HTTP '+x.status+'），请刷新页面后重试');};"
                + "x.onerror=function(){r.textContent='上传失败，请检查网络';};x.send(f);};})();</script>");
        end(out);
    }

    /**
     * 接收上传的文件并导入。
     *
     * @param request  请求（请求体为文件内容）
     * @param response 响应（纯文本的导入结果）
     * @throws IOException 读取或输出失败
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("text/plain; charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Csrf", csrf(request));
        String fileName = param(request, "fileName");
        byte[] data;
        try (InputStream in = request.getInputStream()) {
            data = readLimited(in);
        }
        if (data == null) {
            response.getWriter().println("文件超过 20 MB，请拆分后导入");
            return;
        }
        String text;
        try {
            ImportResult result = plugin().importer().importFile(fileName, data, OrgPlugin.importMustChangePassword());
            text = result.toText();
        } catch (IOException | IllegalArgumentException e) {
            text = "导入失败：" + e.getMessage();
        }
        response.getWriter().print(text);
    }

    /**
     * 读取请求体，超过上限时返回 null。
     *
     * @param in 输入流
     * @return 内容；超过上限时为 null
     * @throws IOException 读取失败
     */
    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_UPLOAD_BYTES) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
