package com.lion.agent.text2sql.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 业务主数据源(显式声明为 {@code @Primary})。
 * <p>
 * <b>为什么必须显式声明</b>: 问数模块注册了第二个 {@code DataSource}(只读池)之后,
 * Spring Boot 对 {@code spring.datasource.*} 的自动装配因
 * {@code @ConditionalOnMissingBean(HikariDataSource.class)} 而<b>整体退位</b>,
 * 容器里就只剩只读数据源 —— 结果是 MyBatis 的所有写操作都跑在只读连接上,
 * 报 {@code Connection is read-only. Queries leading to data modification are not allowed}。
 * 因此主数据源必须在这里手动声明, 并用 {@code @Primary} 让 MyBatis-Plus /
 * 事务管理器 / 未加限定符的 {@code JdbcTemplate} 注入都指向它。
 * <p>
 * 连接参数仍从 {@code spring.datasource.*} 读取, 与原自动装配行为一致。
 */
@Configuration
public class PrimaryDataSourceConfig {

    /**
     * 主数据源连接属性(spring.datasource.url / username / password / driver-class-name)。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 业务主数据源(MyBatis-Plus / 事务 / 元数据扫描都用它)。
     * 池参数从 spring.datasource.hikari.* 读取。
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * 业务主 JdbcTemplate(元数据扫描 information_schema 用)。
     * <p>
     * 必须显式声明: 只读池已注册了一个 {@code JdbcTemplate}, 会让 Spring Boot 的
     * {@code JdbcTemplateAutoConfiguration} 退位, 否则主库扫描会误注入只读模板,
     * 元数据刷新(INSERT)会报只读连接错误。
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
