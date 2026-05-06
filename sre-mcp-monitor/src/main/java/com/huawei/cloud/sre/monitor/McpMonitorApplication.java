package com.huawei.cloud.sre.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Monitor (智能监控) MCP Server 启动类。
 *
 * <p>提供动态基线、异常检测、容量预测、告警去重等 MCP Tools，详见 Phase 3 实现。
 *
 * <p>对应监听端口：8002。
 */
@SpringBootApplication(scanBasePackages = {
        "com.huawei.cloud.sre.monitor",
        "com.huawei.cloud.sre.common"
})
public class McpMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpMonitorApplication.class, args);
    }
}
