package com.huawei.cloud.sre.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Micrometer 指标定制配置。
 *
 * <p>为所有指标添加公共标签（应用名、环境），并过滤不需要的默认指标（减少 Cardinality）。
 */
@Configuration
public class McpMetricsCustomizer {

    private final String appName;
    private final String environment;

    /**
     * @param appName     Spring 应用名（来自 spring.application.name）
     * @param environment 当前环境（来自 spring.profiles.active）
     */
    public McpMetricsCustomizer(
            @Value("${spring.application.name:sre-mcp}") String appName,
            @Value("${spring.profiles.active:dev}") String environment
    ) {
        this.appName = appName;
        this.environment = environment;
    }

    /**
     * 为所有指标添加公共标签：application 和 environment。
     *
     * @return MeterRegistryCustomizer
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer() {
        return registry -> registry.config()
                .commonTags("application", appName, "environment", environment);
    }

    /**
     * 过滤掉高 Cardinality 的 URI 指标（防止标签爆炸）。
     *
     * <p>Spring Boot 默认会对 /actuator/* 下所有子路径打 tag，此过滤器将其统一为 /actuator。
     *
     * @return MeterBinder 用于注册自定义过滤器
     */
    @Bean
    public MeterBinder actuatorUriFilter() {
        return registry -> registry.config()
                .meterFilter(MeterFilter.replaceTagValues(
                        "uri",
                        uri -> uri.startsWith("/actuator") ? "/actuator" : uri
                ));
    }
}
