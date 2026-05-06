# sre-mcp-monitor

指标智能监控 MCP Server — 端口 **8002**

## 功能概述

| 类型 | 数量 | 说明 |
|------|------|------|
| MCP Tools | 9 | 基线管理、异常检测、容量预测、告警处理、通知发送 |
| MCP Resources | 4 | 基线状态、告警规则、活跃告警、异常摘要 |
| MCP Prompts | 4 | 异常分析、容量规划、告警分类、基线审查 |

## 9 个 Tool 说明

| Tool | 说明 |
|------|------|
| `updateBaseline` | 提交新观测值，更新 EWMA 动态基线 |
| `detectAnomaly` | 基于 3σ 准则检测当前值是否异常 |
| `forecastCapacity` | 线性外推预测未来 N 小时容量趋势 |
| `correlateMetrics` | Pearson 相关分析，识别共同根因指标群 |
| `dedupAlerts` | 时间/拓扑/语义三级聚合，压缩告警噪声 |
| `manageAlertRule` | 创建/更新/删除/查询告警规则（PostgreSQL 持久化） |
| `silenceAlerts` | 设置维护窗口静默规则（Redis TTL 控制） |
| `getBaselineStatus` | 查询当前基线状态（均值、σ、告警边界） |
| `sendNotification` | 通过华为云 SMN 发送告警通知 |

## 本地启动

### 前置依赖

```bash
# 启动 Redis + PostgreSQL + Kafka
docker-compose -f docker-compose.dev.yml up -d redis postgres kafka
```

### 启动服务

```bash
cd TiangongAIOpsService
mvn spring-boot:run -pl sre-mcp-monitor -Dspring-boot.run.profiles=dev
```

服务启动后：
- MCP endpoint: `http://localhost:8002/mcp`
- Health: `http://localhost:8002/actuator/health`
- Metrics: `http://localhost:8002/actuator/prometheus`

### 验证 Tools

```bash
# 更新基线
curl -X POST http://localhost:8002/mcp \
  -H "Content-Type: application/json" \
  -d '{"method":"tools/call","params":{"name":"updateBaseline","arguments":{"service":"order-service","metric":"cpu_usage","observations":[45,50,55,48,52]}}}'

# 检测异常
curl -X POST http://localhost:8002/mcp \
  -H "Content-Type: application/json" \
  -d '{"method":"tools/call","params":{"name":"detectAnomaly","arguments":{"service":"order-service","metric":"cpu_usage","currentValue":95.0}}}'
```

## Claude Desktop 接入

```json
{
  "mcpServers": {
    "sre-monitor": {
      "command": "java",
      "args": ["-jar", "/path/to/sre-mcp-monitor-0.1.0-SNAPSHOT.jar"],
      "env": {
        "SPRING_PROFILES_ACTIVE": "dev"
      }
    }
  }
}
```

## 算法说明

### EWMA 动态基线

```
mean_new = α * x + (1-α) * mean_old        (α=0.2 默认)
var_new  = (1-α) * (var_old + α*(x-mean_old)²)
异常条件: |x - mean| > 3σ  且  样本数 ≥ 30
```

### 三级告警聚合

1. **时间聚合**：5 分钟窗口内同 service:metric 的告警合并
2. **拓扑聚合**：同服务名前缀（如 `order-*`）的告警归并
3. **语义去重**：Jaccard 相似度 ≥ 0.6 的告警消重

## 运行测试

```bash
# 单元测试（快速）
mvn test -pl sre-mcp-monitor -Dsurefire.failIfNoSpecifiedTests=false

# 集成测试（需要 Docker）
mvn verify -pl sre-mcp-monitor
```
