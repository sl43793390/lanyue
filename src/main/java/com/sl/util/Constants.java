package com.sl.util;

/**
 * 全局常量。
 * <p>
 * 权限码（{@link #ADD} / {@link #DELETE} / {@link #UPDATE} / {@link #QUERY} / {@link #UPLOAD} /
 * {@link #ALL}）存在 {@code users.permission} 列里，是逗号分隔的字符串，{@code ALL} 表示全部权限。
 * 这套权限模型与旧项目 logviewer 保持一致，便于 demo.db 直接复用。
 */
public class Constants {

    public static final String ISO_8859_1 = "ISO-8859-1";
    public static final String UTF_8 = "UTF-8";
    public static final String GBK = "GBK";
    //日志级别
    public static final String INFO = "INFO";
    public static final String WARN = "WARN";
    public static final String ERROR = "ERROR";
    //权限字符串
    public static final String ADD = "ADD";
    public static final String DELETE = "DELETE";
    public static final String UPDATE = "UPDATE";
    public static final String QUERY = "QUERY";
    public static final String ALL = "ALL";
    public static final String UPLOAD = "UPLOAD";

    public static final String CUP_CMD = "top -bn1 | grep \"Cpu(s)\" | sed \"s/.*, *\\([0-9.]*\\)%* id.*/\\1/\" | awk '{print 100 - $1\"%\"}'\n";

    public static final Integer defaulutPageSize = 500;
}
