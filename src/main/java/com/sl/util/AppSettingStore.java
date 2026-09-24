package com.sl.util;

import java.util.List;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cn.hutool.core.util.StrUtil;

/**
 * 界面偏好 / 零散状态的小型键值存储（表 {@code app_setting}）。
 * <p>
 * 为什么要有它：像「上次用的目标服务器」「这个主机用过哪些 compose 根目录」这类东西
 * 不属于任何业务实体，为它们各建一张表不值当；但只放在 VaadinSession 里又会在
 * 重新登录后丢干净（session 是 {@code persistent=false}），而用户要的恰恰是"下次进来还在"。
 * 所以落一张通用的 kv 表，键由调用方自己约定。
 * <p>
 * 表由本类自己在启动时幂等创建（{@code CREATE TABLE IF NOT EXISTS}），不依赖
 * {@code demo.sql} 是否被执行过 —— 与 {@link DbInitializer} 同样的理由：
 * 引导性数据不能挂在外部文件上。建表失败只降级成"记不住偏好"，不影响任何主流程。
 * <p>
 * <b>读写都不抛异常</b>：偏好丢失的代价远小于把一个 compose 操作带崩。失败只写日志。
 */
@Service
public class AppSettingStore {

    private static final Logger log = LoggerFactory.getLogger(AppSettingStore.class);

    private static final String TABLE = "app_setting";

    /** SQLite 同时只允许一个写事务，写操作串行化，避免日志里刷 "database is locked" */
    private static final Object WRITE_LOCK = new Object();

    private final JdbcTemplate jdbc;

    @Autowired
    public AppSettingStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void init() {
        ensureTable();
    }

    /** 建表：只在启动时跑一次，失败不致命 */
    private void ensureTable() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS \"" + TABLE + "\" ("
                    + "\"setting_key\" TEXT(191) NOT NULL, "
                    + "\"setting_value\" TEXT, "
                    + "\"update_time\" TEXT(32), "
                    + "PRIMARY KEY (\"setting_key\"))");
        } catch (Exception e) {
            log.warn("初始化 {} 表失败，界面偏好将无法保存：{}", TABLE, e.getMessage());
        }
    }

    /**
     * 读一个键。没有就返回 {@code null}；任何异常也返回 {@code null}（调用方按"没有"处理）。
     */
    public String get(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        try {
            List<String> values = jdbc.queryForList(
                    "SELECT \"setting_value\" FROM \"" + TABLE + "\" WHERE \"setting_key\" = ?",
                    String.class, key);
            return values.isEmpty() ? null : values.get(0);
        } catch (Exception e) {
            log.warn("读取配置 {} 失败：{}", key, e.getMessage());
            return null;
        }
    }

    /** 写一个键。先 UPDATE 再补 INSERT，不用 UPSERT 语法（老版本 SQLite 不认）。 */
    public boolean put(String key, String value) {
        if (StrUtil.isBlank(key)) {
            return false;
        }
        String now = cn.hutool.core.date.DateUtil.now();
        synchronized (WRITE_LOCK) {
            try {
                int updated = jdbc.update("UPDATE \"" + TABLE + "\" SET \"setting_value\" = ?, "
                                + "\"update_time\" = ? WHERE \"setting_key\" = ?",
                        value, now, key);
                if (updated > 0) {
                    return true;
                }
                jdbc.update("INSERT INTO \"" + TABLE + "\" (\"setting_key\", \"setting_value\", \"update_time\") "
                        + "VALUES (?, ?, ?)", key, value, now);
                return true;
            } catch (Exception e) {
                log.warn("保存配置 {} 失败：{}", key, e.getMessage());
                return false;
            }
        }
    }

    /** 删一个键。返回是否真的删掉了行。 */
    public boolean remove(String key) {
        if (StrUtil.isBlank(key)) {
            return false;
        }
        synchronized (WRITE_LOCK) {
            try {
                return jdbc.update("DELETE FROM \"" + TABLE + "\" WHERE \"setting_key\" = ?", key) > 0;
            } catch (Exception e) {
                log.warn("删除配置 {} 失败：{}", key, e.getMessage());
                return false;
            }
        }
    }
}
