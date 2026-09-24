package com.sl.mapper.handler;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;

/**
 * 把 {@link Date} 按「文本」读写到数据库的 TypeHandler。
 * <p>
 * 为什么需要它：{@code users} 表的 {@code create_time} / {@code expire_time} 在 demo.sql 里
 * 声明为 {@code text(32)}，是文本列。若直接交给驱动处理 {@link Date}，MyBatis 会调
 * {@code PreparedStatement.setTimestamp}，具体写成什么格式由驱动自己决定（sqlite-jdbc 会写成
 * 带毫秒的 {@code yyyy-MM-dd HH:mm:ss.SSS}），读取时又依赖驱动把字符串反解成 {@link Timestamp}。
 * 一旦库里已有手工写入的 {@code 2026-09-21} 这类不同格式的值，反解就可能抛
 * {@code SQLException}，页面直接打不开。
 * <p>
 * 这里把两端的格式都固定下来：写入一律 {@code yyyy-MM-dd HH:mm:ss}，读取用宽容解析吃下
 * {@code yyyy-MM-dd}、{@code yyyy/MM/dd} 等常见写法，解析不了只记日志并返回 null，
 * 不会把整个查询带崩。
 * <p>
 * 本类刻意不加 {@code @MappedTypes(Date.class)}，以免被全局注册后影响到其它表里的日期字段。
 * 它通过实体上的 {@code @TableField(typeHandler = TextDateTypeHandler.class)} 指定，
 * <b>但光有这一句不够，实体上还必须打开 {@code @TableName(autoResultMap = true)}</b>。
 * 原因：{@code @TableField} 的 typeHandler 只会被拼进 insert / update 的 SQL（形如
 * {@code #{et.expireTime,typeHandler=...}}），select 走的是 resultMap 自动映射，处理器按
 * Java 类型推断，{@link Date} 会落到默认的 {@code DateTypeHandler}——它调
 * {@code rs.getTimestamp()}，而 sqlite-jdbc 只认 {@code yyyy-MM-dd HH:mm:ss.SSS}。
 * 库里只要有一条 {@code date('now')} 写出来的纯日期，查询就会抛
 * {@code SQLException: Error parsing time stamp}，整个页面打不开。
 */
public class TextDateTypeHandler extends BaseTypeHandler<Date> {

    private static final Logger log = LoggerFactory.getLogger(TextDateTypeHandler.class);

    /** 写入数据库时使用的统一格式，长度控制在列宽 text(32) 之内 */
    public static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Date parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, DateUtil.format(parameter, PATTERN));
    }

    @Override
    public Date getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseText(rs.getString(columnName));
    }

    @Override
    public Date getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseText(rs.getString(columnIndex));
    }

    @Override
    public Date getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseText(cs.getString(columnIndex));
    }

    private Date parseText(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            // Hutool 的 parse 对 "yyyy-MM-dd"、"yyyy-MM-dd HH:mm:ss"、"yyyy/MM/dd" 都能识别
            return DateUtil.parse(text.trim());
        } catch (Exception e) {
            log.warn("无法把 [{}] 解析成日期，该字段按空值处理", text);
            return null;
        }
    }
}
