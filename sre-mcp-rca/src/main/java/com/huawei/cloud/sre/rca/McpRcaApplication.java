package com.huawei.cloud.sre.rca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RCA (Root Cause Analysis) MCP Server 启动类。
 *
 * <p>提供故障排查与根因定位的 MCP Tools / Resources / Prompts：
 * <ul>
 *   <li>10 个 Tools：queryMetrics / searchLogs / analyzeTraces / queryTopology / queryChanges /
 *       kgQuery / findSimilarIncidents / correlateAlerts / analyzeRootCause / generateRcaReport</li>
 *   <li>4 个 Resources：incidents / topology / knowledge-base / rca-reports</li>
 *   <li>5 个 Prompts：rca-analyze / rca-evidence-collection / rca-timeline /
 *       rca-similar-incidents / rca-remediation-suggest</li>
 * </ul>
 *
 * <p>监听端口：8001（HTTP 模式）；本地开发可切换为 STDIO 模式（见 application-dev.yml）。
 */
@SpringBootApplication(scanBasePackages = {
        "com.huawei.cloud.sre.rca",
        "com.huawei.cloud.sre.common"
})
public class McpRcaApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpRcaApplication.class, args);
    }
}
