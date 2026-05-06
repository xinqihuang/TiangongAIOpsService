package com.huawei.cloud.sre.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * MCP Server 安全配置。
 *
 * <p>使用 OAuth 2.1 Resource Server 模式（JWT Bearer Token），无状态 Session。
 * Actuator 端点（{@code /actuator/**}）和 MCP SSE 端点（{@code /sse}）对内部调用放行。
 *
 * <p>通过 {@code mcp.security.enabled=false} 可在本地开发环境完全关闭鉴权（开发模式）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class McpSecurityConfig {

    /**
     * 生产安全过滤链：启用 JWT Bearer Token 校验。
     *
     * @param http           HttpSecurity 构建器
     * @param securityEnabled 是否启用安全校验
     * @return 安全过滤链
     * @throws Exception Spring Security 配置异常
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.security.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain securedFilterChain(
            HttpSecurity http,
            @Value("${mcp.security.enabled:true}") boolean securityEnabled
    ) throws Exception {
        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/webhooks/**").permitAll()
                        .requestMatchers("/sse", "/mcp/**").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );
        return http.build();
    }

    /**
     * 开发模式安全过滤链：放行所有请求。
     *
     * @param http HttpSecurity 构建器
     * @return 无鉴权的安全过滤链
     * @throws Exception Spring Security 配置异常
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.security.enabled", havingValue = "false")
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * JWT -> Spring Security Authorities 转换器。
     *
     * <p>从 JWT 的 {@code scope} claim 提取权限，前缀为 {@code SCOPE_}（Spring 约定）。
     *
     * @return JwtAuthenticationConverter
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        var grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("scope");
        grantedAuthoritiesConverter.setAuthorityPrefix("SCOPE_");

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }
}
