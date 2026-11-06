package com.llmetor.dataagent.dataagent.file;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVector 独立连接池装配（M5）。
 *
 * <p>只暴露 JdbcTemplate 而非 DataSource：避免污染 DataSource 类型候选，
 * 让 Spring Boot 默认的 DataSourceAutoConfiguration 正常接管主库 MySQL。
 * 与主库物理隔离（PG 192.168.2.77），互不拖累。配置 embeddings.store.host 才启用。
 */
@Configuration
@ConditionalOnProperty(name = "data-agent.embeddings.store.host")
public class PgVectorConfig {

    @Bean(name = "pgVectorJdbcTemplate")
    public JdbcTemplate pgVectorJdbcTemplate(
            @Value("${data-agent.embeddings.store.host}") String host,
            @Value("${data-agent.embeddings.store.port:5432}") String port,
            @Value("${data-agent.embeddings.store.database}") String database,
            @Value("${data-agent.embeddings.store.user}") String user,
            @Value("${data-agent.embeddings.store.password}") String password) {

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setPoolName("data-agent-PgVectorPool");
        ds.setInitializationFailTimeout(-1); // PG 暂不可达时先放行建池，首次真实使用才报错（E01 降级）
        return new JdbcTemplate(ds);
    }
}
