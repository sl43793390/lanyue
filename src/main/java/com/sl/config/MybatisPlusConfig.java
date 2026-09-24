package com.sl.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 * <p>
 * {@code dataAccessConfiguration.xml} 里的全局 settings 通过 application.properties 的
 * {@code mybatis-plus.config-location} 引入，不在这里重复声明——注意其中的
 * {@code localCacheScope=STATEMENT}：MyBatis 的一级缓存默认是 SESSION 级，
 * 用裸 JDBC 直接 UPDATE 一行后，同一 SqlSession 里的 selectById 会读到旧对象。
 * 改成 STATEMENT 后每次查询都重新查库，用户管理页"改完立刻看到新值"才不会翻车。
 */
@Configuration
@MapperScan("com.sl.mapper")
public class MybatisPlusConfig {

    /**
     * 分页插件。
     * <p>
     * <b>刻意不传 DbType。</b>传了就必须写死一个库（之前写的是 {@code DbType.SQLITE}），
     * 那样一换数据库，分页 SQL 的语法就跟着错，而且要改代码——这正是要避免的。
     * <p>
     * 不传时，插件自己探测：{@code PaginationInnerInterceptor.findIDialect(Executor)} 里
     * {@code dbType == null} 会走 {@code JdbcUtils.getDbType(executor)}，
     * 取连接的 {@code getMetaData().getURL()}，按 URL 里的 {@code :sqlite:} / {@code :mysql:}
     * 判定 {@code DbType}，并把结果缓存起来（见 mybatis-plus-jsqlparser 3.5.12 源码，已核对）。
     * 所以 SQLite 与 MySQL 8 都能正确分页，换库不用改这里。
     * <p>
     * 唯一的兜底需求是 {@code JdbcUtils} 需要一个能识别的 URL；本项目的两种驱动都在 pom 里，
     * 且 URL 形如 {@code jdbc:sqlite:demo.db} / {@code jdbc:mysql://host:3306/db}，都能命中。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
