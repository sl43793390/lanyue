package com.sl.db;

/**
 * 数据库方言。
 * <p>
 * 目标很明确：**同一套 Java 代码，既能跑在 SQLite（当前默认，单机/开发），也能跑在 MySQL 8（后续生产）**。
 * 换库时只换连接配置和一份建表脚本，不动任何业务代码。
 * <p>
 * 要做到这点，就必须把两边真正有差异的东西收敛到一个地方，而不是散落在各处写 if。
 * 本项目的差异只有三项：
 * <ol>
 * <li><b>标识符引号</b>：SQLite 认 ANSI 双引号，MySQL 认反引号（双引号在 MySQL 里默认是字符串字面量，
 * 除非开了 {@code ANSI_QUOTES}）。</li>
 * <li><b>文本列类型</b>：见 {@link #text(int)}。</li>
 * <li><b>启动期的额外动作</b>：SQLite 要开 WAL，MySQL 要确认字符集是 utf8mb4。
 * 见 {@code DbInitializer}。</li>
 * </ol>
 *
 * <h2>为什么不直接用反引号兼容两边</h2>
 * SQLite 出于兼容 MySQL 的目的也接受反引号，所以"全程用反引号"看似能让一份 DDL 通吃两边。
 * 但那是**依赖实现方的兼容性妥协**，不是标准行为；一旦以后要接 PostgreSQL（双引号才是标准）就会翻车。
 * 按方言选引号，多写一行代码，换来的是迁移路径清晰。
 */
public enum DatabaseDialect {

    /** 文件型数据库，当前默认。 */
    SQLITE("SQLite", "\""),

    /** MySQL 8。 */
    MYSQL("MySQL", "`");

    private final String displayName;
    private final String identifierQuote;

    DatabaseDialect(String displayName, String identifierQuote) {
        this.displayName = displayName;
        this.identifierQuote = identifierQuote;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 给表名 / 列名加引号。 */
    public String quote(String identifier) {
        return identifierQuote + identifier + identifierQuote;
    }

    /**
     * 文本列的类型声明。
     * <p>
     * 统一用 {@code VARCHAR(n)}，**不要用 {@code TEXT}**：MySQL 的 PRIMARY KEY 不允许建在 TEXT / BLOB 上
     * ——没有前缀长度就无法参与排序比较。而本项目的业务表主键全是字符串列
     * （{@code connection_info} 是 host + port + user 三个字符串组成的复合主键），用 TEXT 会直接建不出表。
     * <p>
     * 同一条 {@code VARCHAR(n)} 在 SQLite 这里也不会坏事：SQLite 的列类型名只用来决定**亲和性**，
     * {@code VARCHAR(50)} 的亲和性是 TEXT，而且长度**不做校验**（SQLite 不实现 VARCHAR 的长度约束）。
     * 所以两边可以共用一模一样的 DDL。
     *
     * @param length 长度。MySQL 下必须给，且要够用；SQLite 下仅作说明用途。
     */
    public String text(int length) {
        return "VARCHAR(" + length + ")";
    }

    public boolean isSqlite() {
        return this == SQLITE;
    }

    public boolean isMysql() {
        return this == MYSQL;
    }
}
