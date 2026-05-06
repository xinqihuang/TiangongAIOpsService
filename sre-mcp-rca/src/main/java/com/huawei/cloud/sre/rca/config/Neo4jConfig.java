package com.huawei.cloud.sre.rca.config;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Neo4j Driver 配置。
 *
 * <p>创建一个连接池化的 Neo4j Driver bean，供 {@code KnowledgeGraphService} 注入使用。
 * 连接参数通过 {@code application.yml} 的 {@code neo4j.*} 配置项控制。
 */
@Configuration
public class Neo4jConfig {

    private final String uri;
    private final String username;
    private final String password;
    private final int maxConnectionPoolSize;
    private final long connectionTimeoutMs;

    /**
     * @param uri                    Neo4j bolt URI，如 bolt://localhost:7687
     * @param username               用户名，默认 neo4j
     * @param password               密码
     * @param maxConnectionPoolSize  连接池最大连接数，默认 50
     * @param connectionTimeoutMs    连接超时（毫秒），默认 5000
     */
    public Neo4jConfig(
            @Value("${neo4j.uri:bolt://localhost:7687}") String uri,
            @Value("${neo4j.authentication.username:neo4j}") String username,
            @Value("${neo4j.authentication.password:devpassword}") String password,
            @Value("${neo4j.pool.max-connection-pool-size:50}") int maxConnectionPoolSize,
            @Value("${neo4j.connection-timeout:5000}") long connectionTimeoutMs
    ) {
        this.uri = uri;
        this.username = username;
        this.password = password;
        this.maxConnectionPoolSize = maxConnectionPoolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    /**
     * 创建 Neo4j Driver bean。
     *
     * @return 配置好连接池和超时的 Driver 实例
     */
    @Bean
    public Driver neo4jDriver() {
        Config config = Config.builder()
                .withMaxConnectionPoolSize(maxConnectionPoolSize)
                .withConnectionTimeout(connectionTimeoutMs, TimeUnit.MILLISECONDS)
                .withConnectionAcquisitionTimeout(connectionTimeoutMs, TimeUnit.MILLISECONDS)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }
}
