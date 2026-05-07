# sre-mcp-remediation

故障自动修复 MCP Server（端口 8003）

## 概述

本模块实现了基于 [Model Context Protocol (MCP)](https://spec.modelcontextprotocol.io/) 的智能故障修复服务，提供：

- **14 个 MCP Tool**：涵盖低/中/高三个风险级别的修复操作
- **4 个 MCP Resource**：活跃工单、SOP 策略库、待审批队列、执行历史
- **4 个 MCP Prompt**：风险评估、审批申请、修复计划、事后复盘

## 核心特性

| 特性 | 实现方式 |
|------|---------|
| 风险分级 | LOW → 直接执行；MEDIUM → 单人审批；HIGH → 双人审批 |
| 幂等保证 | Redis SETNX（`IdempotencyStore`）防止重复执行 |
| 熔断保护 | 自研 `RemediationCircuitBreaker`（30分钟内3次失败→熔断30分钟）|
| 状态管理 | 自研轻量状态机（`EnumMap<State, Set<State>>`，无第三方依赖）|
| 审批工作流 | Redis Token 存储审批记录，TTL 4小时，全员通过后自动推进 |
| 持久化 | PostgreSQL + JPA（工单全生命周期记录）|

## 修复工单状态机

```
INITIATED → STRATEGY_MATCHED → RISK_ASSESSED ─┬→ EXECUTING (LOW risk, 直接执行)
                                                └→ PENDING_APPROVAL → APPROVED → EXECUTING
EXECUTING → VERIFYING → COMPLETED
EXECUTING → FAILED → ROLLING_BACK → ROLLED_BACK
PENDING_APPROVAL → REJECTED
任意状态 → CANCELLED
```

## Tool 列表

### 🟢 LOW 风险（直接执行）

| Tool | 描述 | 对应适配器 |
|------|------|---------|
| `restartPod` | 重启 CCE Pod（OOM/假死）| CceAdapter |
| `scaleDeployment` | 调整 Deployment 副本数 | CceAdapter |
| `renewCertificate` | 续期 SSL/TLS 证书 | ScmAdapter |

### 🟡 MEDIUM 风险（单人审批）

| Tool | 描述 | 对应适配器 |
|------|------|---------|
| `cleanDisk` | 清理 ECS 磁盘空间 | EcsAdapter |
| `adjustConnectionPool` | 调整数据库连接池 | RdsAdapter |
| `switchTraffic` | ELB 流量切换 | ElbAdapter |

### 🔴 HIGH 风险（工单 + 双人审批）

| Tool | 描述 | 对应适配器 |
|------|------|---------|
| `replaceNode` | 替换故障节点 | CceAdapter |
| `dbFailover` | 数据库主备切换 | RdsAdapter |
| `rollbackRelease` | 版本回滚 | — |

### ⚙️ 通用工具

| Tool | 描述 |
|------|------|
| `matchStrategy` | 根据症状匹配 SOP 策略 |
| `assessRisk` | 评估操作风险 |
| `requestApproval` | 提交审批意见（APPROVE/REJECT）|
| `verifyRemediation` | 验证修复效果并更新工单状态 |
| `rollbackAction` | 发起回滚流程 |

## 本地启动

### 前置条件

```bash
# 启动基础设施
docker compose -f docker-compose.dev.yml up -d redis postgres
```

### 启动应用

```bash
mvn spring-boot:run -pl sre-mcp-remediation -Dspring-boot.run.profiles=dev
```

应用启动后：
- MCP Server：`http://localhost:8003`
- Health Check：`http://localhost:8003/actuator/health`
- Prometheus 指标：`http://localhost:8003/actuator/prometheus`

## Claude Desktop 接入

在 `claude_desktop_config.json` 中添加：

```json
{
  "mcpServers": {
    "sre-remediation": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/sre-mcp-remediation.jar",
        "--spring.profiles.active=dev"
      ]
    }
  }
}
```

## 典型使用流程

```
1. matchStrategy("order-service pod frequently restarting", "order-service")
   → 返回推荐 SOP：RestartPod-OOM（LOW 风险）

2. assessRisk("restartPod", "order-service", "production")
   → 风险评分: 30, 审批要求: NONE

3. restartPod("default", "order-service-abc12", "OOM killed", "key-20240101-001")
   → 执行修复，返回 COMPLETED 状态

4. verifyRemediation("ctx-xxx", "order-service", "pod_restart_count", "0")
   → 验证修复效果
```

### 高风险操作流程（DB 故障转移）

```
1. assessRisk("dbFailover", "payment-db", "prod")
   → HIGH 风险，需要双人审批

2. dbFailover("rds-001", "sre-lead", "dba-lead", "主库 I/O 异常", "key-dbfail-001")
   → 返回 PENDING_APPROVAL，contextId="ctx-yyy"

3. requestApproval("ctx-yyy", "sre-lead", "APPROVE", "已确认主库异常")
   → 等待第二人审批

4. requestApproval("ctx-yyy", "dba-lead", "APPROVE", "已验证备库延迟 < 5s")
   → 两人均批准，状态自动推进到 APPROVED → EXECUTING
```

## 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `remediation.circuit-breaker.failure-threshold` | 3 | 熔断失败次数阈值 |
| `remediation.circuit-breaker.window-minutes` | 30 | 失败统计窗口（分钟）|
| `remediation.circuit-breaker.open-minutes` | 30 | 熔断持续时长（分钟）|
| `remediation.approval.ttl-hours` | 4 | 审批 Token 有效期（小时）|

## 测试

```bash
# 单元测试
mvn test -pl sre-mcp-remediation -Dtest="RemediationStateMachineTest,StrategyMatcherTest,RemediationCircuitBreakerTest,RemediationToolServiceTest"

# 集成测试（需要 Docker）
mvn test -pl sre-mcp-remediation -Dtest="RemediationContextRepositoryIT"

# 全量测试
mvn verify -pl sre-mcp-remediation
```
