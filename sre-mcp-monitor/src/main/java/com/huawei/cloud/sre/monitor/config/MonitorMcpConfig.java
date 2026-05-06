package com.huawei.cloud.sre.monitor.config;

import com.huawei.cloud.sre.monitor.prompt.MonitorPromptProvider;
import com.huawei.cloud.sre.monitor.resource.MonitorResourceProvider;
import com.huawei.cloud.sre.monitor.tool.MonitorToolService;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Monitor MCP Server 核心配置。
 *
 * <p>将 Monitor 模块的 Tool、Resource、Prompt 注册到 Spring AI MCP 框架。
 */
@Configuration
public class MonitorMcpConfig {

    /**
     * 注册 Monitor Tool 回调提供者（9 个 {@code @Tool} 方法）。
     *
     * @param toolService Monitor Tool 服务
     * @return ToolCallbackProvider
     */
    @Bean
    public ToolCallbackProvider monitorTools(MonitorToolService toolService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(toolService)
                .build();
    }

    /**
     * 注册 Monitor MCP Resources（4 个）。
     *
     * @param provider Monitor Resource 注册提供者
     * @return SyncResourceSpecification 列表
     */
    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> monitorResources(MonitorResourceProvider provider) {
        return provider.registrations();
    }

    /**
     * 注册 Monitor MCP Prompts（4 个）。
     *
     * @param provider Monitor Prompt 注册提供者
     * @return SyncPromptSpecification 列表
     */
    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> monitorPrompts(MonitorPromptProvider provider) {
        return provider.registrations();
    }
}
