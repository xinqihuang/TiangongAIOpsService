package com.huawei.cloud.sre.rca.config;

import com.huawei.cloud.sre.rca.prompt.RcaPromptProvider;
import com.huawei.cloud.sre.rca.resource.RcaResourceProvider;
import com.huawei.cloud.sre.rca.tool.RcaToolService;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP Server 核心配置。
 *
 * <p>将 RCA 模块的 Tool、Resource、Prompt 注册到 Spring AI MCP 框架。
 * Spring AI MCP 自动配置会发现以下 Bean 类型并注册到 MCP 协议层：
 * <ul>
 *   <li>{@link ToolCallbackProvider} — 注册 {@code @Tool} 方法</li>
 *   <li>{@code List<McpServerFeatures.SyncResourceSpecification>} — 注册 MCP Resources</li>
 *   <li>{@code List<McpServerFeatures.SyncPromptSpecification>} — 注册 MCP Prompts</li>
 * </ul>
 */
@Configuration
public class RcaMcpConfig {

    /**
     * 注册 RCA Tool 回调提供者，让 MCP 自动配置发现 10 个 {@code @Tool} 方法。
     *
     * @param rcaToolService 包含 10 个 {@code @Tool} 方法的服务类
     * @return ToolCallbackProvider
     */
    @Bean
    public ToolCallbackProvider rcaTools(RcaToolService rcaToolService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(rcaToolService)
                .build();
    }

    /**
     * 注册 RCA MCP Resources（4 个）。
     *
     * @param provider RCA Resource 注册提供者
     * @return SyncResourceSpecification 列表
     */
    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> rcaResources(RcaResourceProvider provider) {
        return provider.registrations();
    }

    /**
     * 注册 RCA MCP Prompts（5 个）。
     *
     * @param provider RCA Prompt 注册提供者
     * @return SyncPromptSpecification 列表
     */
    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> rcaPrompts(RcaPromptProvider provider) {
        return provider.registrations();
    }
}
