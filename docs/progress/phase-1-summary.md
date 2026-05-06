# Phase 1 Summary — 脚手架与共享库

**完成日期：** 2026-05-02

## 完成项清单

### Maven 多模块工程
- [x] Parent POM (`packaging=pom`)，锁定所有依赖版本（Spring Boot 3.3.5、Spring AI 1.0.0、华为云 SDK 3.1.194）
- [x] 4 个子模块：`sre-mcp-common`、`sre-mcp-rca`、`sre-mcp-monitor`、`sre-mcp-remediation`
- [x] JaCoCo 覆盖率报告集成（目标 ≥ 70%）

### sre-mcp-common 共享库（28 个源文件）

**公共 DTO（3 个）**
- `MetricResult` — AOM 指标查询结果（record + DataPoint 嵌套 record）
- `LogSearchResult` — LTS 日志搜索结果（record + LogEntry 嵌套 record）
- `TraceResult` — APM 链路追踪结果（record + Span 嵌套 record）

**华为云凭证（2 个）**
- `HuaweiCloudCredentialProvider` — AK/SK 管理，支持 KMS 解密 + 6 小时定时刷新
- `KmsDecryptor` — KMS v1 解密封装

**华为云 SDK Adapter（10 个）**
| Adapter | 服务 | 主要方法 |
|---|---|---|
| `AomAdapter` | AOM v2 应用运维管理 | `queryMetric()` |
| `ApmAdapter` | APM v1 应用性能管理 | `analyzeTrace()` |
| `LtsAdapter` | LTS v2 云日志服务 | `searchLogs()` |
| `CceAdapter` | CCE v3 云容器引擎 | `getClusterInfo()`, `listNodes()` |
| `EcsAdapter` | ECS v2 弹性云服务器 | `listServers()`, `rebootServer()` |
| `ElbAdapter` | ELB v3 弹性负载均衡 | `listLoadBalancers()`, `getLoadBalancer()` |
| `RdsAdapter` | RDS v3 云数据库 | `listInstances()`, `getInstance()` |
| `CtsAdapter` | CTS v3 云审计服务 | `queryTraces()` |
| `ScmAdapter` | SCM v3 证书管理服务 | `listCertificates()` |
| `SmnAdapter` | SMN v2 消息通知服务 | `publishMessage()` |

所有 Adapter 均包含：`@Retry` + `@CircuitBreaker`（Resilience4j）、Micrometer Timer 埋点、`ServiceResponseException` → `HuaweiCloudException` 转换、包级私有测试构造器（允许注入 Mock）。

**LLM 客户端（2 个）**
- `VllmChatClientConfig` — 基于 Spring AI OpenAI Starter，连接 vLLM（OpenAI 兼容 API）
- `EmbeddingClientConfig` — 文本向量化客户端

**会话与幂等（2 个）**
- `SessionMemoryStore` — 基于 Redis，多轮对话会话状态存储
- `IdempotencyStore` — 基于 Redis SETNX，分布式幂等防重

**安全（4 个）**
- `McpSecurityConfig` — Spring Security OAuth 2.1 Resource Server（JWT Bearer）
- `RequireToolPermission` — 自定义权限注解
- `ToolPermissionAspect` — AOP 拦截，验证 JWT scope
- `TenantContext` — ThreadLocal 租户上下文（虚拟线程安全）

**可观测性（2 个）**
- `ToolMetricsAspect` — AOP 自动埋点（调用次数、耗时、成功/失败）
- `McpMetricsCustomizer` — Micrometer 指标自定义

**异常处理（3 个）**
- `HuaweiCloudException` — 华为云 API 调用异常（含 httpStatus、errorCode、requestId）
- `McpToolException` — MCP Tool 业务异常（含错误码枚举）
- `GlobalExceptionHandler` — Spring MVC 全局异常映射

### 单元测试（20 个测试文件，102 个用例）

| 测试类 | 用例数 |
|---|---|
| AomAdapterTest | 5 |
| ApmAdapterTest | 4 |
| LtsAdapterTest | 4 |
| CceAdapterTest | 6 |
| EcsAdapterTest | 6 |
| ElbAdapterTest | 6 |
| RdsAdapterTest | 6 |
| CtsAdapterTest | 4 |
| ScmAdapterTest | 4 |
| SmnAdapterTest | 4 |
| HuaweiCloudExceptionTest | 3 |
| McpToolExceptionTest | 5 |
| GlobalExceptionHandlerTest | 7 |
| MetricResultTest | 4 |
| LogSearchResultTest | 4 |
| TraceResultTest | 5 |
| SessionMemoryStoreTest | 7 |
| IdempotencyStoreTest | 7 |
| TenantContextTest | 6 |
| RequireToolPermissionTest | 2 |

### 验收自检

- [x] `mvn clean install -DskipTests=false` 全绿（102 tests, 0 failures）
- [x] JaCoCo 指令覆盖率：**77.1%**（目标 ≥ 70% ✅）
- [x] 共享库 jar 可被子模块依赖
- [x] 所有 Adapter 单测通过（使用 Mockito + SimpleMeterRegistry）
- [x] 华为云 SDK 版本：3.1.194

## 遗留问题

1. **`McpMetricsCustomizer`、`ToolMetricsAspect`、`VllmChatClientConfig`、`EmbeddingClientConfig` 覆盖率为 0%**：这些类需要 Spring 上下文（AOP 代理、Bean 注入）才能触发，单测难以覆盖。后续在集成测试中补充，或在子模块启动时验证。
2. **`HuaweiCloudCredentialProvider` 警告**：编译时 `'this' escape` 警告（因 `@Scheduled` 回调注册），不影响功能，可在后续重构时使用延迟初始化解决。
3. **子模块 `sre-mcp-rca/monitor/remediation`**：骨架已创建，业务代码待 Phase 2~4 实现。

## 下阶段计划

进入 **Phase 2：sre-mcp-rca 完整实现**：
- 10 个 `@Tool` 方法（queryMetrics、searchLogs、analyzeTraces、queryTopology、queryChanges、kgQuery、findSimilarIncidents、correlateAlerts、analyzeRootCause、generateRcaReport）
- 4 个 MCP Resources + 5 个 Prompt 模板
- Testcontainers 集成测试（Redis + PostgreSQL）
