package com.sl.security;

import com.sl.entity.User;
import com.sl.mapper.UserDao;
import com.sl.util.DigestUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.stereotype.Service;

/**
 * 密码摘要的渐进式升级。
 * <p>
 * 背景：旧库（以及 {@code demo.sql} 里的初始数据）存的是**无盐 SM3 摘要**，
 * 明文不落库但也没有加盐，抗彩虹表能力弱。新写入的密码统一用 BCrypt。
 * <p>
 * 升级时机由 Spring Security 控制：{@code DaoAuthenticationProvider} 在**认证成功之后**
 * 会调 {@code passwordEncoder.upgradeEncoding(库里的密文)}，返回 true 就调用本类把
 * 同一个明文重新编码后写回库。也就是说：
 * <ul>
 * <li>老用户**不需要知道密码被换过**，也不需要重置密码；</li>
 * <li>只要成功登录一次，库里的密文就从 SM3 变成 BCrypt；</li>
 * <li>明文从未离开过这次认证过程，本类拿到的是已经编码好的新密文。</li>
 * </ul>
 * <p>
 * {@code DelegatingPasswordEncoder.upgradeEncoding()} 的判定规则是"密文的 {@code {id}}
 * 与当前 encode 用的 id 不一致就返回 true"。旧密文没有前缀，id 解析为 null，因此会被判为需要升级。
 */
@Service
public class DbUserDetailsPasswordService implements UserDetailsPasswordService {

    private static final Logger log = LoggerFactory.getLogger(DbUserDetailsPasswordService.class);

    private final UserDao userDao;

    public DbUserDetailsPasswordService(UserDao userDao) {
        this.userDao = userDao;
    }

    @Override
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        if (user == null || newPassword == null) {
            return user;
        }
        String userId = user.getUsername();
        User entity = userDao.selectById(userId);
        if (entity == null) {
            // 极少数情况：认证通过后账号被别处删掉了。此时放弃升级，不影响本次登录结果。
            log.warn("准备升级 {} 的密码摘要，但账号已不存在，跳过", userId);
            return user;
        }
        String old = entity.getPassword();
        if (old != null && !DigestUtil.isLegacySm3(old) && old.startsWith("{")) {
            // 已经是新格式（或已被别的线程升级过），不必重复写库
            return user;
        }
        entity.setPassword(newPassword);
        userDao.updateById(entity);
        log.info("已将账号 {} 的密码摘要从旧格式升级为 {}", userId, passwordId(newPassword));
        return new UserPrincipal(entity);
    }

    /**
     * 从 {@code {bcrypt}$2a$...} 里摘出算法标识，只为日志好看。
     */
    private String passwordId(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.charAt(0) != '{') {
            return "未知格式";
        }
        int end = encoded.indexOf('}');
        return end > 0 ? encoded.substring(1, end) : "未知格式";
    }
}
