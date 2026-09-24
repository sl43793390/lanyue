package com.sl.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 探测当前连的是哪种数据库，并给出对应的 {@link DatabaseDialect}。
 * <p>
 * <b>探测结果只取自真实连接</b>，不去猜配置：直接问 {@link DatabaseMetaData#getDatabaseProductName()}。
 * 猜配置（比如按 {@code application.properties} 里的 URL 前缀判断）在"配置里写 MySQL、实际连 SQLite"时
 * 会得出错误结论，而错误结论会一路传到建表 DDL，报出来的错跟根因隔了好几层。
 * <p>
 * 结果缓存在字段里，只探测一次。探测失败不抛异常——上层（{@code DbInitializer}）拿不到方言时
 * 应该降级并告警，而不是让整个应用起不来。
 */
@Component
public class DatabaseInfo {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInfo.class);

    private final DataSource dataSource;

    private volatile DatabaseDialect dialect;
    private volatile String product = "未知";

    public DatabaseInfo(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** 当前数据库方言。第一次调用时才真正连库。 */
    public DatabaseDialect dialect() {
        resolveIfNeeded();
        return dialect;
    }

    /** 形如 {@code SQLite 3.46.1} / {@code MySQL 8.0.36}，用于启动日志。 */
    public String product() {
        resolveIfNeeded();
        return product;
    }

    private void resolveIfNeeded() {
        if (null != dialect) {
            return;
        }
        synchronized (this) {
            if (null != dialect) {
                return;
            }
            try (Connection conn = dataSource.getConnection()) {
                DatabaseMetaData meta = conn.getMetaData();
                String name = String.valueOf(meta.getDatabaseProductName());
                String version = String.valueOf(meta.getDatabaseProductVersion());
                dialect = detect(name, meta.getURL());
                product = name + " " + version;
                log.info("检测到数据库：{}（{}），URL={}", product, dialect.getDisplayName(), maskUrl(meta.getURL()));
            } catch (SQLException e) {
                // 拿不到连接时给出最保守的方言：双引号 + VARCHAR 是更接近 ANSI 的一侧。
                // 这里只告警不抛出 —— 连接问题的真正报错点在下一次真正用库的时候，那儿的堆栈更有用。
                dialect = DatabaseDialect.SQLITE;
                product = "未知（探测失败：" + e.getMessage() + "）";
                log.warn("探测数据库类型失败，DDL 将按 {} 的规则生成；这通常意味着数据库还没就绪", dialect.getDisplayName(), e);
            }
        }
    }

    /**
     * 按产品名判断。
     * <p>
     * 不用 URL 前缀判断：URL 是配置，产品名是事实。
     */
    private DatabaseDialect detect(String productName, String jdbcUrl) {
        String n = productName.toLowerCase(Locale.ROOT);
        if (n.contains("mysql") || n.contains("mariadb")) {
            if (n.contains("mariadb")) {
                // MariaDB 不是 MySQL：它没有 utf8mb4_0900_* 排序规则，部分函数行为也不同。
                // 当前只按 MySQL 处理，真要用 MariaDB 需要重新过一遍 DDL。
                log.warn("检测到 MariaDB（{}），当前按 MySQL 方言处理；若建表失败请优先检查排序规则与函数差异", productName);
            }
            return DatabaseDialect.MYSQL;
        }
        if (n.contains("sqlite")) {
            return DatabaseDialect.SQLITE;
        }
        log.warn("未识别的数据库产品名 [{}]（URL={}），按通用 SQL 处理：双引号标识符 + VARCHAR 文本列",
                productName, maskUrl(jdbcUrl));
        return DatabaseDialect.SQLITE;
    }

    /** 去掉 URL 里的查询串再写日志：连接参数里可能带账号口令。 */
    private String maskUrl(String jdbcUrl) {
        if (null == jdbcUrl) {
            return "null";
        }
        int q = jdbcUrl.indexOf('?');
        return q < 0 ? jdbcUrl : jdbcUrl.substring(0, q) + "?...";
    }
}
