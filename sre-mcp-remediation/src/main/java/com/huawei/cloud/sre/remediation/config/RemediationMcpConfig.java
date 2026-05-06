package com.huawei.cloud.sre.remediation.config;

import com.huawei.cloud.sre.remediation.prompt.RemediationPromptProvider;
import com.huawei.cloud.sre.remediation.resource.RemediationResourceProvider;
import com.huawei.cloud.sre.remediation.tool.RemediationToolService;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Remediation MCP Server 核心配置。
 *
 * <p>将修复模块的 Tool、Resource、Prompt 注册到 Spring AI MCP 框架。
 */
@Configuration
public class RemediationMcpConfig {

    /**
     * 注册 14 个修复 Tool 方法。
     *
     * @param remediationToolService 修复工具服务
     * @return ToolCallbackProvider
     */
    @Bean
    public ToolCallbackProvider remediationTools(RemediationToolService remediationToolService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(remediationToolService)
                .build();
    }

    /**
     * 注册修复模块 MCP Resources（4 个）。
     *
     * @param provider Resource 注册提供者
     * @return SyncResourceSpecification 列表
     */
    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> remediationResources(
            RemediationResourceProvider provider) {
        return provider.registrations();
    }

    /**
     * 注册修复模块 MCP Prompts（4 个）。
     *
     * @param provider Prompt 注册提供者
     * @return SyncPromptSpecification 列表
     */
    @Bean
    public List<McpServerFeatures.SyncPromptSpecification> remediationPrompts(
            RemediationPromptProvider provider) {
        return provider.registrations();
    }
}
