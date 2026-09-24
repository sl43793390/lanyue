package com.sl.security;

import com.sl.util.DigestUtil;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 旧项目 {@code users} 表的密码格式：无盐 SM3，大写十六进制，无 {@code {id}} 前缀。
 * <p>
 * 这个编码器只用于**校验老密码**，不用于加密新密码。新密码由
 * {@link com.sl.security.SecurityConfig#passwordEncoder()} 里配置的
 * {@code DelegatingPasswordEncoder} 以 BCrypt 写入。
 * <p>
 * 老密码在用户登录成功的那一刻会被自动升级成 BCrypt，见
 * {@link DbUserDetailsPasswordService}。所以这个类是个过渡件：
 * 等全库密码都升级完、且确认没有别的系统在用 SM3 校验之后，就可以删掉。
 */
public class Sm3PasswordEncoder implements PasswordEncoder {

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null) {
            return null;
        }
        return DigestUtil.sm3(rawPassword.toString());
    }

    /**
     * 校验：忽略大小写、忽略首尾空白。
     * <p>
     * 用 {@link MessageDigest#isEqual} 而不是 {@code String.equals} 做比较，
     * 保证比较耗时与内容无关——避免通过响应时间差反推摘要前缀。
     */
    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        String actual = encode(rawPassword);
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                encodedPassword.trim().toUpperCase().getBytes(StandardCharsets.UTF_8));
    }
}
