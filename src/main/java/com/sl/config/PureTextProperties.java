package com.sl.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「文件管理 → 编辑」功能的白名单配置（见 application.properties）：
 * <ul>
 *   <li>{@code pure.text.type}：允许在线编辑的纯文本扩展名，逗号分隔（不区分大小写，
 *       写的时候带不带点都行）；</li>
 *   <li>{@code pure.text.type.maxsize}：允许编辑的大小上限，支持 B/K/M/G 后缀
 *       （如 {@code 10M}、{@code 512K}、{@code 1G}），不带后缀按字节算。</li>
 * </ul>
 * 两个配置都给了默认值：就算 properties 里没写，功能也按上面这组扩展名 + 10M 生效。
 */
@Component
public class PureTextProperties {

    private static final Pattern SIZE_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([bBkKmMgG]?)");

    private final Set<String> extensions;
    private final long maxSizeBytes;

    public PureTextProperties(
            @Value("${pure.text.type:text,markdown,md,yml,yaml,conf,ini,log,csv,properties}")
            String types,
            @Value("${pure.text.type.maxsize:10M}") String maxSize) {
        this.extensions = Arrays.stream(types.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s.substring(1) : s)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        this.maxSizeBytes = parseSize(maxSize);
    }

    /** 解析 "10M"/"512K"/"1G"/"2048" 这类容量写法，返回字节数。写错直接抛，让启动就失败。 */
    public static long parseSize(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        Matcher m = SIZE_PATTERN.matcher(trimmed);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "无法识别的容量写法：" + raw + "（支持 10M / 512K / 1G / 2048）");
        }
        double value = Double.parseDouble(m.group(1));
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "", "b" -> (long) value;
            case "k" -> (long) (value * 1024);
            case "m" -> (long) (value * 1024 * 1024);
            case "g" -> (long) (value * 1024 * 1024 * 1024);
            default -> throw new IllegalArgumentException("无法识别的容量单位：" + raw);
        };
    }

    /**
     * 该文件是否可在线编辑：扩展名在白名单里，且大小不超过上限。
     * 目录不进这个方法；空文件（size=0）允许编辑。
     */
    public boolean isEditable(String fileName, long size) {
        if (size > maxSizeBytes) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extensions.contains(ext);
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public Set<String> getExtensions() {
        return extensions;
    }
}
