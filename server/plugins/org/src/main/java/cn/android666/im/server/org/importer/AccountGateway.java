package cn.android666.im.server.org.importer;

/**
 * 导入时对 Openfire 账号的操作。生产环境用 Openfire 的 UserManager（{@link OpenfireAccountGateway}），测试中用假实现。
 */
public interface AccountGateway {

    /**
     * 账号是否已存在。
     *
     * @param username 工号
     * @return 存在时为 true
     */
    boolean exists(String username);

    /**
     * 创建账号。
     *
     * @param username 工号
     * @param password 初始密码
     * @param name     姓名
     * @param email    邮箱，可为空字符串
     * @throws Exception 创建失败
     */
    void create(String username, String password, String name, String email) throws Exception;

    /**
     * 更新账号的姓名和邮箱（与组织架构保持一致，Openfire 管理后台、vCard 等处显示）。
     *
     * @param username 工号
     * @param name     姓名
     * @param email    邮箱，可为空字符串
     * @throws Exception 更新失败
     */
    void update(String username, String name, String email) throws Exception;
}
