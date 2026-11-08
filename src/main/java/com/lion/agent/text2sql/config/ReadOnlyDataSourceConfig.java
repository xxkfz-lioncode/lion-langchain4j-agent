package com.lion.agent.text2sql.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 问数专用只读数据源。
 * <p>
 * <b>安全底线</b>: 问数执行必须与业务写库隔离 —— 用独立的数据库账号(仅授予 SELECT),
 * 并配合 SqlGuard 六层校验。
 * <p>
 * 配置方式: 若 {@code spring.datasource.readonly.jdbc-url} 未配置, 则回退到主数据源
 * (方便本地/演示环境直接跑通); 生产环境<b>必须</b>显式配置只读账号。
 * <pre>
 * spring:
 *   datasource:
 *     readonly:
 *       jdbc-url: jdbc:mysql://...
 *       username: lion_query_ro
 *       password: xxx
 * </pre>
 * <p>
 * <b>注意</b>: 主数据源在 {@link PrimaryDataSourceConfig} 中显式声明并标 {@code @Primary}。
 * 原因: 本类一旦注册了 {@code DataSource} Bean, Spring Boot 的
 * {@code @ConditionalOnMissingBean(HikariDataSource.class)} 会让自动装配的主数据源整个退位,
 * 容器里就只剩只读池, MyBatis 的写操作会全部报
 * {@code Connection is read-only. Queries leading to data modification are not allowed}。
 */
@Configuration
public class ReadOnlyDataSourceConfig {

    @Bean("readOnlyDataSource")
    @ConfigurationProperties("spring.datasource.readonly.hikari")
    public DataSource readOnlyDataSource(
            @Value("${spring.datasource.readonly.jdbc-url:${spring.datasource.url}}") String url,
            @Value("${spring.datasource.readonly.username:${spring.datasource.username}}") String username,
            @Value("${spring.datasource.readonly.password:${spring.datasource.password}}") String password,
            Text2SqlProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(props.getHikariMaxPoolSize());
        ds.setPoolName("text2sql-readonly");
        // 连接级只读提示(软约束; 真正的写保护依赖账号权限 + SqlGuard)
        ds.setConnectionInitSql("SET SESSION TRANSACTION READ ONLY");
        return ds;
    }

    /**
     * 问数执行专用 JdbcTemplate: 带语句超时 + 最大返回行数。
     */
    @Bean("readOnlyJdbcTemplate")
    public JdbcTemplate readOnlyJdbcTemplate(@Qualifier("readOnlyDataSource") DataSource ds,
                                             Text2SqlProperties props) {
        JdbcTemplate tpl = new JdbcTemplate(ds);
        tpl.setQueryTimeout(props.getQueryTimeoutSeconds());
        tpl.setMaxRows(props.getMaxRows());
        return tpl;
    }
}
