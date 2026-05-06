# Phase 4 Summary — sre-mcp-remediation

**完成日期：** 2026-05-03

## 完成项清单

- [x] 更新 `sre-mcp-remediation/pom.xml`，添加 JPA、Redis、Testcontainers 依赖
- [x] 实现自研轻量状态机 `RemediationStateMachine`（基于 `EnumMap<State, Set<State>>`）
  - 13 个状态，全量合法转换定义
  - 每次转换发布 `StateChangedEvent`（Spring 事件）
  - 无第三方工作流引擎依赖
- [x] 实现 `RemediationContext` JPA Entity（工单全生命周期持久化）
  - UUID PK，枚举状态存 String，历史记录换行拼接
- [x] 实现 `SopStrategy` JPA Entity + `SopStrategyRepository`
  - 关键词匹配：`matches(symptomText)` 方法
  - 自定义查询：`searchByKeyword()`、`findByRiskLevelOrderByPriorityAsc()`
- [x] 实现 `RemediationToolService`（14 个 `@Tool` 方法）
  - 低风险（3个）：restartPod / scaleDeployment / renewCertificate
  - 中风险（3个）：cleanDisk / adjustConnectionPool / switchTraffic
  - 高风险（3个）：replaceNode / dbFailover / rollbackRelease
  - 通用（5个）：matchStrategy / assessRisk / requestApproval / verifyRemediation / rollbackAction
  - 私有 `executeWithGuards()`：统一应用幂等 + 熔断保护
- [x] 实现 `StrategyMatcher`：SOP 匹配 + 风险评估
- [x] 实现 `ApprovalService`：多级审批工作流（Redis Token + 状态机联动）
- [x] 实现 `RemediationCircuitBreaker`：自研熔断器（Redis 存储，3次/30分钟 → 熔断30分钟）
- [x] 扩展 `CceAdapter`：新增 `restartPod()` / `scaleDeployment()` 方法
- [x] 扩展 `ScmAdapter`：新增 `renewCertificate()` 方法
- [x] 实现 `RemediationResourceProvider`（4 个 MCP Resource）
  - `sre://remediation/active-contexts`
  - `sre://remediation/sop-library`
  - `sre://remediation/approval-queue`
  - `sre://remediation/execution-history`
- [x] 实现 `RemediationPromptProvider`（扫描 `classpath:prompts/*.yaml`）
  - 4 个 Prompt：risk-assessment / approval-request / remediation-plan / post-remediation-review
- [x] 配置文件：`application.yml`（端口 8003）/ `application-dev.yml` / `application-prod.yml` / `logback-spring.xml`
- [x] 单元测试（46 个，全部通过）：
  - `RemediationStateMachineTest`（12 个）
  - `StrategyMatcherTest`（11 个）
  - `RemediationCircuitBreakerTest`（10 个）
  - `RemediationToolServiceTest`（13 个）
- [x] 集成测试 `RemediationContextRepositoryIT`（Testcontainers PostgreSQL，9 个测试，需 Docker）
- [x] `sre-mcp-remediation/README.md`

## 技术决策记录

| 决策 | 方案 | 原因 |
|------|------|------|
| 状态机实现 | 自研 EnumMap | 项目约定：禁止引入 Camunda/Flowable/Spring StateMachine |
| 熔断器实现 | 自研 Redis-based | 需要 30 分钟窗口的业务语义，不适合 Resilience4j 标准配置 |
| Pod 重启/扩容 | CceAdapter 新增模拟方法 | CCE SDK 仅支持集群管理 API；Pod 级操作需 K8s API（后续扩展点）|
| 审批 Token 存储 | Redis（Key TTL 4小时）| 无持久化审批记录需求；Redis 天然支持自动过期 |

## 遗留问题

1. **CCE Pod/Deployment 操作**：`restartPod()` 和 `scaleDeployment()` 目前为模拟实现。生产环境需集成 K8s API Client（如 `io.kubernetes:client-java`）
2. **SCM 证书续期**：`renewCertificate()` 依赖 CA 集成（Let's Encrypt / CFCA），目前记录操作意图
3. **集成测试**：`RemediationContextRepositoryIT` 需要 Docker 环境，当前机器 Docker 未运行
4. **ELB switchTraffic / ECS cleanDisk**：MEDIUM 风险操作返回 `PENDING_APPROVAL` 后，需运营人员通过 `requestApproval` Tool 推进

## 下阶段计划（Phase 5）

- 编写 Helm Chart（`sre-mcp-deploy/helm/`），覆盖 3 个 MCP Server 部署
- 编写 K8s 资源（Deployment/Service/Ingress/HPA/PDB/ConfigMap/Secret）
- 配置 Spring Boot Buildpacks 镜像构建（目标仓库 SWR）
- 编写部署文档
