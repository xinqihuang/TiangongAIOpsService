# Phase 2 — sre-mcp-rca 完整实现 总结

完成日期：2026-05-02

---

## 完成项清单

### 核心交付物

- [x] **pom.xml** — 添加 `spring-boot-starter-data-jpa`、`postgresql`、Testcontainers（postgresql、neo4j）
- [x] **McpRcaApplication** — 主类更新，扫描 `com.huawei.cloud.sre.rca` + `common`
- [x] **RcaMcpConfig** — 注册 ToolCallbackProvider、Resources Bean、Prompts Bean

### DTOs（5 个）
- [x] `RcaReport` — 根因分析报告（含置信度、措施）
- [x] `TopologyResult` — 服务拓扑（节点 + 边）
- [x] `AlertCorrelationResult` — 告警关联分组结果
- [x] `IncidentSummary` — 历史相似事故摘要
- [x] `KgQueryResult` — Neo4j 查询结果

### 数据层
- [x] `RcaIncidentEntity` — JPA 实体，存储 RCA 报告（UUID 主键、审计时间戳）
- [x] `RcaIncidentRepository` — Spring Data JPA 仓库（含自定义查询）

### 配置
- [x] `Neo4jConfig` — Neo4j Driver Bean（可配置连接池、超时）
- [x] `VectorStoreConfig` — SimpleVectorStore Bean（dev；生产可替换为 pgvector）

### 服务
- [x] `KnowledgeGraphService` — Neo4j Cypher 查询、拓扑查询、故障模式检索、事故写入
- [x] `RcaInferenceService` — RAG 流程（向量检索 → Prompt 构建 → vLLM 调用 → 解析 → 持久化 → 索引）

### MCP 层（Tools / Resources / Prompts）
- [x] `RcaToolService` — 10 个 `@Tool` 方法全量实现（无 TODO 占位）
- [x] `RcaResourceProvider` — 4 个 MCP Resource（incidents、topology、knowledge-base、rca-reports）
- [x] `RcaPromptProvider` — 从 YAML 动态加载 5 个 Prompt 模板
- [x] Prompt 模板 YAML × 5：rca-analyze / rca-evidence-collection / rca-timeline / rca-similar-incidents / rca-remediation-suggest

### 配置文件
- [x] `application.yml` — 通用配置（JPA、Redis、Spring AI、Resilience4j、Actuator）
- [x] `application-dev.yml` — 本地 Docker Compose + Ollama 配置
- [x] `application-prod.yml` — 生产 CCE 部署配置（严格 Resilience4j）
- [x] `logback-spring.xml` — dev 彩色控制台 / prod 异步 JSON 结构化日志

### 测试
- [x] `KnowledgeGraphServiceTest` — Mock Driver，验证空结果回退、深度截断
- [x] `RcaInferenceServiceTest` — Mock ChatClient，验证 LLM 解析、失败回退、向量检索
- [x] `RcaToolServiceTest` — Mock 所有适配器，验证参数传递和边界值处理
- [x] `RcaIncidentRepositoryIT` — Testcontainers PostgreSQL 集成测试
- [x] `RcaReportTest` / `TopologyResultTest` — DTO 业务方法验证

### 文档
- [x] `sre-mcp-rca/README.md` — 本地启动、Claude Desktop 接入、工具列表
- [x] `docs/progress/phase-2-summary.md` — 本文件

---

## 关键设计决策

1. **向量存储**：Dev 使用 `SimpleVectorStore`（内存），生产可无缝替换为 pgvector。EmbeddingModel 由 `spring-ai-starter-model-openai` 自动配置，指向 vLLM Embeddings API。

2. **MCP Resource 注册**：使用固定 URI（非 URI 模板），资源内容通过 Neo4j/JPA 动态查询，避免引入资源模板复杂性。

3. **LLM 调用失败回退**：`analyzeRootCause` 在 LLM 不可用时返回低置信度的 fallback 报告（confidence=0.0），而非抛出异常，保证 Tool 调用的可靠性。

4. **Prompt 热加载**：`RcaPromptProvider` 通过 `PathMatchingResourcePatternResolver` 扫描 `classpath:prompts/*.yaml`，新增 Prompt 只需放入文件，无需修改代码。

---

## 遗留问题 / 后续优化

| 问题 | 说明 | 建议 Phase |
|------|------|-----------|
| 向量存储持久化 | `SimpleVectorStore` 重启后丢失已索引的历史事故 | Phase 5（生产部署时接入 pgvector） |
| MCP Resource URI 模板 | 当前使用固定 URI，不支持按 ID 查单个事故 | Phase 5 |
| 安全认证 | `McpSecurityConfig` 已在 common 中配置 OAuth 2.1，但 dev 未开启 | Phase 5 |
| RCA 报告 HTML 导出 | 当前仅 JSON，postmortem 需要 HTML/PDF | 可选 |

---

## 下阶段计划（Phase 3）

Phase 3 目标：实现 `sre-mcp-monitor` MCP Server（端口 8002）。  
核心交付：动态基线算法（EWMA + 3σ）、告警聚合服务、Kafka 事件总线、9 个 @Tool 方法。
