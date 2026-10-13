package cn.android666.im.server.org.importer;

import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;

import java.util.Objects;

/**
 * 用 Openfire 的 {@link UserManager} 操作账号（{@link AccountGateway} 的生产实现）。
 */
public class OpenfireAccountGateway implements AccountGateway {

    /**
     * 账号是否已存在。
     *
     * @param username 工号
     * @return 存在时为 true
     */
    @Override
    public boolean exists(String username) {
        try {
            UserManager.getInstance().getUser(username);
            return true;
        } catch (UserNotFoundException e) {
            return false;
        }
    }

    /**
     * 创建账号。
     *
     * @param username 工号
     * @param password 初始密码
     * @param name     姓名
     * @param email    邮箱，可为空字符串
     * @throws Exception 创建失败
     */
    @Override
    public void create(String username, String password, String name, String email) throws Exception {
        UserManager.getInstance().createUser(username, password, name, email.isEmpty() ? null : email);
    }

    /**
     * 更新账号的姓名和邮箱（没有变化时不写数据库）。
     *
     * @param username 工号
     * @param name     姓名
     * @param email    邮箱，可为空字符串
     * @throws Exception 更新失败
     */
    @Override
    public void update(String username, String name, String email) throws Exception {
        User user = UserManager.getInstance().getUser(username);
        if (!Objects.equals(user.getName(), name)) {
            user.setName(name);
        }
        String newEmail = email.isEmpty() ? null : email;
        if (!Objects.equals(user.getEmail(), newEmail)) {
            user.setEmail(newEmail);
        }
    }
}
