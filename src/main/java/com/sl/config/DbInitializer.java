package com.sl.config;

import cn.hutool.core.util.StrUtil;
import com.sl.db.DatabaseDialect;
import com.sl.db.DatabaseInfo;
import com.sl.entity.User;
import com.sl.mapper.UserDao;
import com.sl.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 启动时的数据库引导：保证 {@code users} 表存在、字段齐全，并且内置管理员 {@code admin}
 * 一定在库里、一定能登录。
 * <p>
 * 为什么需要它：登录认证读的是数据库，引导入口不能依赖任何外部文件——那个文件丢了、没拷、
 * 编码错了，就会出现"库是空的、谁都登不进去、也没人能进页面把账号建出来"的死局。
 * 这里改成由代码在启动时幂等写入，删掉任何配置文件都不会锁死系统。
 * <p>
 * 四个保证，都是幂等的：
 * <ol>
 * <li>数据库级的准备动作（SQLite 开 WAL / MySQL 校验字符集，见 {@link #prepareDatabase()}）；</li>
 * <li>表不存在则建表，缺字段则补字段（从旧版本升级上来的库可以直接用，不用手工改表）；</li>
 * <li>{@code admin} 不存在则按默认密码写入；</li>
 * <li>{@code admin} 存在但已经登不进去（密码为空 / 被禁用 / 已过期 / 权限被摘），自动修回可用状态。</li>
 * </ol>
 * <p>
 * <b>SQLite 与 MySQL 8 共用这一份代码</b>：所有方言差异都收敛在 {@link DatabaseDialect}，
 * 这里只按 {@code dialect} 分支，不写任何库名判断。
 *
 * <h2>业务表不在这里创建（但列改名会管）</h2>
 * 业务表（{@code connection_info} / {@code log_path} / {@code projects} /
 * {@code tomcat_info} / {@code common_project_mgmt}）在 {@code demo.sql} / {@code demo-mysql8.sql}
 * 里，那两个脚本开头是一串 {@code DROP TABLE}，属于"给人手工初始化"的脚本，
 * 不能挂到每次启动的流程上，否则重启一次数据就没了。
 * <p>
 * 但**列改名**是例外，见 {@link #applyCompatMigrations()}：它只改元数据、不碰数据，
 * 而且是幂等的。留着它是因为旧库里的 {@code connection_info.desc} 用的是 MySQL 保留字，
 * 不迁移的话等切到 MySQL 8 那天会直接报语法错，而不是数据错——那种错更难定位。
 */
@Component
public class DbInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    private static final String TABLE = "users";

    /**
     * 表结构与 {@code User} 实体的 {@code @TableId} / {@code @TableField} 一一对应，
     * 增删字段时两边必须同时改，否则 MyBatis-Plus 拼出来的 SQL 会找不到列。
     * <p>
     * 长度是给 MySQL 用的（VARCHAR 必须带长度）。SQLite 不看长度，多写几位没有副作用。
     */
    private static final Object[][] COLUMNS = {
            {"id_user", 50},
            {"name_user", 50},
            {"password", 100},
            {"create_time", 32},
            {"email", 64},
            {"organization", 64},
            {"cd_phone", 32},
            {"expire_time", 32},
            {"user_flag", 1},
            {"permission", 255}};

    /**
     * 历史列名 → 新列名。只放**必须改**的项。
     * <p>
     * {@code desc} 是 MySQL 8 的保留字（官网关键字表里标着 {@code DESC (R)}），
     * 于是 {@code connection_info} 这张表在 MySQL 上连 {@code CREATE TABLE} 都过不去，
     * 更别说 {@code SELECT desc FROM ...}。改名为 {@code cd_desc}，顺带和同族字段
     * （{@code cd_password} / {@code cd_key_path} / {@code cd_logpath}）对齐。
     */
    private static final String[][] COLUMN_RENAMES = {
            {"connection_info", "desc", "cd_desc"}};

    private final DataSource dataSource;
    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;
    private final DatabaseInfo databaseInfo;

    public DbInitializer(DataSource dataSource, UserDao userDao,
                         PasswordEncoder passwordEncoder, DatabaseInfo databaseInfo) {
        this.dataSource = dataSource;
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.databaseInfo = databaseInfo;
    }

    @Override
    public void run(ApplicationArguments args) {
        prepareDatabase();
        try {
            ensureUserTable();
        } catch (Exception e) {
            log.error("初始化 {} 表失败，管理员账号可能不可用", TABLE, e);
        }
        try {
            applyCompatMigrations();
        } catch (Exception e) {
            log.error("执行表结构兼容迁移失败", e);
        }
        try {
            ensureAdminRow();
        } catch (Exception e) {
            log.error("检查内置管理员失败", e);
        }
    }

    /* ------------------------------------------------------------------ *
     * 一、数据库级准备
     * ------------------------------------------------------------------ */

    private void prepareDatabase() {
        DatabaseDialect dialect = databaseInfo.dialect();
        log.info("数据库方言：{}（{}）", dialect.getDisplayName(), databaseInfo.product());
        if (dialect.isSqlite()) {
            enableWal();
        } else if (dialect.isMysql()) {
            checkMysqlCharset();
        }
    }

    /**
     * 打开 WAL 日志模式（仅 SQLite）。
     * <p>
     * SQLite 默认的 rollback journal 在写入时会阻塞所有读取。日志平台是"读多写少"的形态
     * （页面在刷日志，后台偶尔写一条偏好），用 WAL 可以让读写在大多数时刻互不阻塞。
     * <p>
     * WAL 是**数据库级持久设置**，执行一次就写进 db 文件头，之后每次打开都生效，
     * 所以不需要放进 HikariCP 的 connection-init-sql 里逐连接重复执行
     * （那里面只放 busy_timeout，因为它是每连接才生效的）。
     * <p>
     * 这两个都是 SQLite 专有语法，连到 MySQL 时会直接报错，所以必须按方言分支——
     * 这也是 {@code application-mysql.properties} 里不能再出现 {@code PRAGMA} 的原因。
     */
    private void enableWal() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
                if (rs.next()) {
                    String mode = rs.getString(1);
                    if ("wal".equalsIgnoreCase(mode)) {
                        log.info("SQLite 已启用 WAL 日志模式");
                    } else {
                        // 内存库、只读库或文件系统不支持时会是 delete / memory，属于可接受的降级
                        log.warn("SQLite 未启用 WAL，当前 journal_mode={}，并发写入时可能出现 SQLITE_BUSY", mode);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("设置 SQLite journal_mode 失败，按默认模式继续", e);
        }
        logBusyTimeout();
    }

    /**
     * 打出当前连接的 {@code busy_timeout}。
     * <p>
     * 这个值不是在这里设的，而是写在 JDBC URL 上（{@code jdbc:sqlite:demo.db?busy_timeout=5000}，
     * sqlite-jdbc 支持把 PRAGMA 当 URL 参数传）。之所以特意打一条日志：这个参数**配错了不会有任何报错**
     * ——写成 {@code busyTimeout}、或者被前面的问号/&amp; 拼错，都会安安静静地回落到默认值 0，
     * 然后在并发写入时才零星冒出 SQLITE_BUSY。所以启动时把生效值摆出来，
     * 免得以后有人拿着"偶尔写失败"的现象从头查起。
     */
    private void logBusyTimeout() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA busy_timeout")) {
            if (rs.next()) {
                int timeout = rs.getInt(1);
                if (timeout > 0) {
                    log.info("SQLite busy_timeout={}ms", timeout);
                } else {
                    log.warn("SQLite busy_timeout=0（未生效）：并发写入遇到锁会立刻失败而不是等待，"
                            + "检查 spring.datasource.url 上的 busy_timeout 参数是否拼对");
                }
            }
        } catch (SQLException e) {
            log.warn("读取 SQLite busy_timeout 失败，跳过", e);
        }
    }

    /**
     * 校验 MySQL 库和连接的字符集。
     * <p>
     * 为什么要专门查：MySQL 的 {@code utf8} 是**残缺的**——每个字符最多 3 字节，存不下 emoji
     * 和部分生僻字（包括某些增补平面的汉字）。本项目的列名、日志内容、路径都可能有中文，
     * 一旦落在 utf8 上，写入时会报 {@code Incorrect string value} 或者被静默截断。
     * 必须用 {@code utf8mb4}。
     * <p>
     * 这里只**告警不阻断**：字符集能读出来就说明连接本身是通的，剩下的是配置问题，
     * 应该让应用起来、把信息摆在日志里，而不是让运维对着一堆启动失败猜。
     */
    private void checkMysqlCharset() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT @@character_set_database, @@collation_database, @@character_set_client, @@character_set_connection")) {
            if (!rs.next()) {
                return;
            }
            String dbCharset = rs.getString(1);
            String dbCollation = rs.getString(2);
            String clientCharset = rs.getString(3);
            String connCharset = rs.getString(4);
            log.info("MySQL 字符集：库={}（{}）客户端={} 连接={}", dbCharset, dbCollation, clientCharset, connCharset);

            List<String> bad = new ArrayList<String>();
            if (!isUtf8mb4(dbCharset)) {
                bad.add("库字符集是 " + dbCharset + "，应为 utf8mb4（utf8mb3 存不下 4 字节字符）");
            }
            if (!isUtf8mb4(clientCharset)) {
                bad.add("character_set_client 是 " + clientCharset + "，应在 JDBC URL 上显式指定 characterEncoding=UTF-8");
            }
            if (!isUtf8mb4(connCharset)) {
                bad.add("character_set_connection 是 " + connCharset + "，同样应由 JDBC URL 指定");
            }
            if (!bad.isEmpty()) {
                log.warn("MySQL 字符集可能有问题，中文/emoji 有截断或报错风险：{}", StrUtil.join("；", bad));
            }
        } catch (SQLException e) {
            log.warn("检查 MySQL 字符集失败，跳过", e);
        }
    }

    private boolean isUtf8mb4(String charset) {
        return null != charset && charset.toLowerCase(Locale.ROOT).startsWith("utf8mb4");
    }

    /* ------------------------------------------------------------------ *
     * 二、users 表的建表与补列
     * ------------------------------------------------------------------ */

    /**
     * 表不存在就建，缺字段就补。
     * <p>
     * 用 {@code SELECT * ... WHERE 1 = 0} 读列名而不是各家的元数据表
     * （SQLite 的 {@code PRAGMA table_info} / MySQL 的 {@code information_schema}）：
     * 前者是标准 SQL，换数据库不用改，且不产生任何数据副作用。
     */
    private void ensureUserTable() throws SQLException {
        synchronized (DbInitializer.class) {
            DatabaseDialect dialect = databaseInfo.dialect();
            try (Connection conn = dataSource.getConnection()) {
                List<String> existing = readColumns(conn, TABLE);
                if (null == existing) {
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate(buildCreateSql(dialect));
                    }
                    log.info("已创建 {} 表", TABLE);
                    return;
                }
                List<String> added = new ArrayList<String>();
                for (Object[] column : COLUMNS) {
                    String name = (String) column[0];
                    if (existing.contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    // SQLite 的 ADD COLUMN 不接受 NOT NULL（除非同时给默认值），
                    // MySQL 接受但会因为已有行需要默认值而失败。补列一律只补类型，
                    // 主键约束由原表保留 —— 反正只有建表那条路径才需要 NOT NULL。
                    try (Statement st = conn.createStatement()) {
                        st.executeUpdate("ALTER TABLE " + dialect.quote(TABLE)
                                + " ADD COLUMN " + dialect.quote(name)
                                + " " + dialect.text((Integer) column[1]));
                    }
                    added.add(name);
                }
                if (!added.isEmpty()) {
                    log.warn("{} 表缺少字段 {}，已自动补齐", TABLE, added);
                }
            }
        }
    }

    private String buildCreateSql(DatabaseDialect dialect) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE IF NOT EXISTS ").append(dialect.quote(TABLE)).append(" (");
        for (Object[] column : COLUMNS) {
            String name = (String) column[0];
            sql.append(dialect.quote(name)).append(" ").append(dialect.text((Integer) column[1]));
            if ("id_user".equals(name)) {
                sql.append(" NOT NULL");
            }
            sql.append(", ");
        }
        sql.append("PRIMARY KEY (").append(dialect.quote("id_user")).append("))");
        return sql.toString();
    }

    /**
     * 读一张表的列名（小写）。
     *
     * @return 表存在时返回列名列表；表不存在返回 {@code null}（而不是抛异常）
     */
    private List<String> readColumns(Connection conn, String table) {
        List<String> names = new ArrayList<String>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + databaseInfo.dialect().quote(table) + " WHERE 1 = 0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                names.add(meta.getColumnLabel(i).toLowerCase(Locale.ROOT));
            }
            return names;
        } catch (SQLException e) {
            // 查询失败基本只有一个原因：表还不存在
            return null;
        }
    }

    /* ------------------------------------------------------------------ *
     * 三、表结构兼容迁移（只改元数据，幂等）
     * ------------------------------------------------------------------ */

    private void applyCompatMigrations() {
        for (String[] rename : COLUMN_RENAMES) {
            String table = rename[0];
            String from = rename[1];
            String to = rename[2];
            try (Connection conn = dataSource.getConnection()) {
                List<String> columns = readColumns(conn, table);
                if (null == columns) {
                    // 表还没建出来（首次部署、或这个模块还没启用），没什么可迁移的
                    continue;
                }
                if (!columns.contains(from.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (columns.contains(to.toLowerCase(Locale.ROOT))) {
                    log.warn("{}.{} 与 {} 同时存在，跳过改名以免覆盖已有列；请人工确认后删掉多余的列",
                            table, from, to);
                    continue;
                }
                DatabaseDialect dialect = databaseInfo.dialect();
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("ALTER TABLE " + dialect.quote(table)
                            + " RENAME COLUMN " + dialect.quote(from) + " TO " + dialect.quote(to));
                }
                log.warn("表结构兼容迁移：{}.{} 已改名为 {}（{} 是 MySQL 保留字）",
                        table, from, to, from.toUpperCase(Locale.ROOT));
            } catch (SQLException e) {
                // 不阻断启动：这个列当前还没被任何功能使用，改名失败不影响现有能力，
                // 但要在日志里留痕，等切 MySQL 时好查。
                log.warn("表结构兼容迁移失败：{}.{} -> {}（可忽略，但切 MySQL 前必须处理）", table, from, to, e);
            }
        }
    }

    /* ------------------------------------------------------------------ *
     * 四、内置管理员
     * ------------------------------------------------------------------ */

    /**
     * admin 不存在则写入；存在但状态已经登不进去则修回来。
     * <p>
     * 只修复"会导致无法登录"的项，不碰姓名、邮箱这些展示信息。
     */
    private void ensureAdminRow() {
        User admin = userDao.selectById(User.ADMIN_USER_ID);
        if (null == admin) {
            userDao.insert(buildDefaultAdmin());
            log.warn("数据库中不存在内置管理员 {}，已自动创建，初始密码 {}，请登录后立即修改",
                    User.ADMIN_USER_ID, User.ADMIN_DEFAULT_PASSWORD);
            return;
        }
        List<String> repairs = new ArrayList<String>();
        if (StrUtil.isBlank(admin.getPassword())) {
            admin.setPassword(passwordEncoder.encode(User.ADMIN_DEFAULT_PASSWORD));
            repairs.add("密码为空，已重置为默认密码 " + User.ADMIN_DEFAULT_PASSWORD);
        }
        if (!admin.isEnabled()) {
            admin.setUserFlag(User.FLAG_ENABLED);
            repairs.add("处于禁用状态，已恢复启用");
        }
        if (admin.isExpired()) {
            // 置 null 后能真正写进库：User.expireTime 的 updateStrategy 是 ALWAYS
            admin.setExpireTime(null);
            repairs.add("已过期，已改为长期有效");
        }
        if (!admin.isAllPermission()) {
            admin.setPermission(Constants.ALL);
            repairs.add("权限不含 " + Constants.ALL + "，已恢复为全部权限");
        }
        if (!repairs.isEmpty()) {
            userDao.updateById(admin);
            log.warn("内置管理员 {} 状态异常，已自动修复：{}", User.ADMIN_USER_ID, StrUtil.join("；", repairs));
        }
    }

    private User buildDefaultAdmin() {
        User admin = new User();
        admin.setUserId(User.ADMIN_USER_ID);
        admin.setUserName("系统管理员");
        admin.setPassword(passwordEncoder.encode(User.ADMIN_DEFAULT_PASSWORD));
        admin.setPermission(Constants.ALL);
        admin.setUserFlag(User.FLAG_ENABLED);
        admin.setCreateTime(new Date());
        // 不设有效期：内置管理员必须长期有效，否则到期后没人能登进来续期
        admin.setExpireTime(null);
        return admin;
    }
}
