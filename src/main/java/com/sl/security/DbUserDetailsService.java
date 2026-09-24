package com.sl.security;

import com.sl.entity.User;
import com.sl.mapper.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 从 {@code users} 表读取账号。
 * <p>
 * 账号的唯一来源就是这张表，没有任何外部用户文件——这一点是旧项目刻意做的改造：
 * 早期版本要求把 {@code users.properties} 放在 jar 同级目录，那个文件丢了或编码错了
 * 就会出现"库是空的、谁都登不进去、也没人能进页面把账号建出来"的死局。
 * <p>
 * 关于"用户不存在"的处理：这里抛 {@link UsernameNotFoundException}，但 Spring Security 的
 * {@code DaoAuthenticationProvider} 默认会把 {@code hideUserNotFoundExceptions} 打开，
 * 把它转换成 {@code BadCredentialsException}。也就是说"用户名不存在"和"密码错误"
 * 对外都是同一句提示，不会泄露哪些账号真实存在。这是有意保留的行为。
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(DbUserDetailsService.class);

    private final UserDao userDao;

    public DbUserDetailsService(UserDao userDao) {
        this.userDao = userDao;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.trim().isEmpty()) {
            throw new UsernameNotFoundException("用户名不能为空");
        }
        String userId = username.trim();
        User user = userDao.selectById(userId);
        if (user == null) {
            // 只记用户名，不记密码；这条日志用于区分"确实没这个账号"和"密码错了"
            log.debug("登录失败：用户 {} 不存在", userId);
            throw new UsernameNotFoundException("用户不存在: " + userId);
        }
        return new UserPrincipal(user);
    }

    /**
     * 按登录名查实体，供登录失败时拼装中文提示用（需要拿到过期日期等展示信息）。
     * 查不到返回 null，不抛异常。
     */
    public User findByUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return null;
        }
        return userDao.selectById(userId.trim());
    }
}
