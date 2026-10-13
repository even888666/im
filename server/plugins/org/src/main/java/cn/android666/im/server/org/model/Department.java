package cn.android666.im.server.org.model;

/**
 * 部门（《三端协议规范.md》§4.1）。
 *
 * @param id       部门 ID，服务器生成（如 {@code d1001}）
 * @param parentId 上级部门 ID，顶级部门为空字符串
 * @param name     部门名称
 * @param sort     同级排序，升序
 */
public record Department(String id, String parentId, String name, int sort) {

    /** 顶级部门的 parentId */
    public static final String ROOT = "";

    /**
     * 是否为顶级部门。
     *
     * @return parentId 为空时为 true
     */
    public boolean isTopLevel() {
        return ROOT.equals(parentId);
    }
}
