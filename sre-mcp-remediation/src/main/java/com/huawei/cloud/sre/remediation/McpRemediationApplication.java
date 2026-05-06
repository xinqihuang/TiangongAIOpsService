package com.huawei.cloud.sre.remediation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Remediation (故障自动修复) MCP Server 启动类。
 *
 * <p>提供分级风险修复 Tools、自研轻量状态机、审批工作流等能力，详见 Phase 4 实现。
 *
 * <p>对应监听端口：8003。
 */
@SpringBootApplication(scanBasePackages = {
        "com.huawei.cloud.sre.remediation",
        "com.huawei.cloud.sre.common"
})
public class McpRemediationApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpRemediationApplication.class, args);
    }
}
