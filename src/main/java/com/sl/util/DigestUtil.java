package com.sl.util;

import cn.hutool.crypto.digest.SM3;

import java.nio.charset.StandardCharsets;

/**
 * 摘要素算工具。
 * <p>
 * 目前只有 SM3 一处用途：兼容旧项目 logviewer 写进 {@code users} 表的密码。
 * 旧库里的密码是「无盐 SM3，大写十六进制」，{@code admin} 的密文即
 * {@code DC1FD00E3EEEB940FF46F457BF97D66BA7FCC36E0B20802383DE142860E76AE6}。
 * <p>
 * 新密码不再用这个算法（见 {@code com.sl.security.SecurityConfig} 的编码器链），
 * 这里保留单纯是为了让老库能继续登录，并在登录成功后自动升级成 BCrypt。
 */
public final class DigestUtil {

    private DigestUtil() {
    }

    /**
     * 计算字符串的 SM3 摘要，返回大写十六进制。
     * <p>
     * 字符集必须显式指定为 UTF-8：{@code String.getBytes()} 用的是平台默认编码，
     * 同一串中文在 Windows(GBK) 和 Linux(UTF-8) 上算出来的摘要不一致，登录校验会直接失效。
     */
    public static String sm3(String raw) {
        if (raw == null) {
            return null;
        }
        return new SM3().digestHex(raw, StandardCharsets.UTF_8).toUpperCase();
    }

    /**
     * SM3 摘要的字节长度（十六进制字符数），用于识别「这串密文是旧格式还是 BCrypt」。
     */
    public static final int SM3_HEX_LENGTH = 64;

    /**
     * 判断一段密文是否是旧格式（无 {@code {id}} 前缀的裸 SM3 十六进制）。
     * <p>
     * Spring Security 的 {@code DelegatingPasswordEncoder} 约定密文以 {@code {bcrypt}}
     * 这类前缀开头；旧库里的值没有前缀，据此可以区分出来并触发密码升级。
     */
    public static boolean isLegacySm3(String encoded) {
        if (encoded == null) {
            return false;
        }
        String trimmed = encoded.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) == '{') {
            return false;
        }
        if (trimmed.length() != SM3_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
