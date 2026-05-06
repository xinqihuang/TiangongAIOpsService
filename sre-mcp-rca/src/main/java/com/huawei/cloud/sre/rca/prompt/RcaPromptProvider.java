package com.huawei.cloud.sre.rca.prompt;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RCA MCP Prompt 注册提供者。
 *
 * <p>从 {@code resources/prompts/*.yaml} 加载所有 Prompt 模板，注册为 MCP Prompt。
 * 支持 5 个 Prompt 模板：rca-analyze、rca-evidence-collection、rca-timeline、rca-similar-incidents、rca-remediation-suggest。
 */
@Component
public class RcaPromptProvider {

    private static final Logger log = LoggerFactory.getLogger(RcaPromptProvider.class);

    /**
     * 加载 {@code resources/prompts/} 目录下所有 YAML 文件并构建注册列表。
     *
     * @return SyncPromptSpecification 列表
     */
    public List<McpServerFeatures.SyncPromptSpecification> registrations() {
        List<McpServerFeatures.SyncPromptSpecification> registrations = new ArrayList<>();
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:prompts/*.yaml");
            for (Resource res : resources) {
                try (InputStream is = res.getInputStream()) {
                    McpServerFeatures.SyncPromptSpecification reg = parsePromptYaml(is);
                    if (reg != null) {
                        registrations.add(reg);
                        log.info("Registered MCP prompt from {}", res.getFilename());
                    }
                } catch (Exception e) {
                    log.warn("Failed to load prompt from {}: {}", res.getFilename(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to scan prompt resources", e);
        }
        log.info("Total MCP prompts registered: {}", registrations.size());
        return registrations;
    }

    @SuppressWarnings("unchecked")
    private McpServerFeatures.SyncPromptSpecification parsePromptYaml(InputStream is) {
        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(is);
        if (data == null) {
            return null;
        }

        String name = String.valueOf(data.get("name"));
        String description = String.valueOf(data.get("description"));
        String template = String.valueOf(data.getOrDefault("template", ""));

        List<McpSchema.PromptArgument> arguments = new ArrayList<>();
        Object argsObj = data.get("arguments");
        if (argsObj instanceof List<?> argList) {
            for (Object argItem : argList) {
                if (argItem instanceof Map<?, ?> argMap) {
                    String argName = String.valueOf(argMap.get("name"));
                    Object descObj = argMap.get("description");
                    String argDesc = descObj != null ? String.valueOf(descObj) : "";
                    Boolean required = Boolean.TRUE.equals(argMap.get("required"));
                    arguments.add(new McpSchema.PromptArgument(argName, argDesc, required));
                }
            }
        }

        var prompt = new McpSchema.Prompt(name, description, arguments);
        final String finalTemplate = template;

        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> {
            String rendered = renderTemplate(finalTemplate, request.arguments());
            return new McpSchema.GetPromptResult(
                    description,
                    List.of(new McpSchema.PromptMessage(
                            McpSchema.Role.USER,
                            new McpSchema.TextContent(rendered)
                    ))
            );
        });
    }

    private String renderTemplate(String template, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return result;
    }
}
