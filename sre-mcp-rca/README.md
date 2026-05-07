# sre-mcp-rca — RCA MCP Server

故障排查与根因定位 MCP Server，端口 **8001**。  
通过 Spring AI MCP 协议对外暴露 **10 个 Tools、4 个 Resources、5 个 Prompts**。

---

## 快速启动

### 1. 启动依赖基础设施（Docker Compose）

```bash
cd <project-root>
docker compose -f docker-compose.dev.yml up -d
# 启动 Redis、PostgreSQL、Kafka、Neo4j
```

### 2. 配置 Ollama（本地 LLM，替代 vLLM）

```bash
# 安装 Ollama: https://ollama.com/
ollama serve
ollama pull qwen2.5:7b          # Chat 模型
ollama pull nomic-embed-text    # Embedding 模型
```

### 3. 运行 RCA Server（HTTP 模式）

```bash
# 从项目根目录
mvn spring-boot:run -pl sre-mcp-rca -Dspring-boot.run.profiles=dev
```

Server 启动后监听 `http://localhost:8001`。  
健康检查：`curl http://localhost:8001/actuator/health`

---

## MCP Tools（10 个）

| Tool | 描述 |
|------|------|
| `queryMetrics` | 查询 AOM 指标时序数据（CPU、内存、QPS 等） |
| `searchLogs` | 在 LTS 中搜索服务日志（关键词 / 正则） |
| `analyzeTraces` | 分析 APM Trace，定位异常 Span 和性能瓶颈 |
| `queryTopology` | 查询 Neo4j 服务调用拓扑图 |
| `queryChanges` | 查询 CTS 变更记录（部署、配置变更等） |
| `kgQuery` | 执行自定义 Cypher 查询知识图谱 |
| `findSimilarIncidents` | 向量语义检索历史相似事故 |
| `correlateAlerts` | 多告警关联分析，识别同源告警风暴 |
| `analyzeRootCause` | **核心**：RAG + vLLM 根因推断 |
| `generateRcaReport` | 生成并持久化结构化 RCA 报告 |

---

## MCP Resources（4 个）

| URI | 描述 |
|-----|------|
| `sre://incidents/list` | 最近 50 条事故记录 |
| `sre://services/topology` | 所有服务的拓扑快照 |
| `sre://knowledge-base/all` | SRE 知识库条目 |
| `sre://rca-reports/recent` | 最近 20 份 RCA 报告 |

---

## MCP Prompts（5 个）

| Prompt | 描述 |
|--------|------|
| `rca-analyze` | 综合证据进行根因分析 |
| `rca-evidence-collection` | 指导证据收集顺序 |
| `rca-timeline` | 从原始事件重建时间线 |
| `rca-similar-incidents` | 历史事故模式匹配分析 |
| `rca-remediation-suggest` | 按风险等级建议修复措施 |

---

## Claude Desktop 接入

编辑 `~/Library/Application Support/Claude/claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "sre-mcp-rca": {
      "url": "http://localhost:8001/mcp",
      "transport": "http"
    }
  }
}
```

> **STDIO 模式**（本地调试）：在 `application-dev.yml` 中取消 `spring.ai.mcp.server.transport: stdio` 的注释，
> 并按 Claude Desktop STDIO 格式配置 `command`。

---

## 环境变量

| 变量 | 说明 | Dev 默认 |
|------|------|---------|
| `VLLM_ENDPOINT` | LLM 服务地址 | `http://localhost:11434/v1`（Ollama） |
| `VLLM_MODEL` | Chat 模型名 | `qwen2.5:7b` |
| `VLLM_EMBED_MODEL` | Embedding 模型名 | `nomic-embed-text` |
| `PG_HOST` | PostgreSQL 地址 | `localhost` |
| `PG_USER` / `PG_PASSWORD` | PostgreSQL 凭证 | `sre` / `dev` |
| `REDIS_HOST` | Redis 地址 | `localhost` |
| `NEO4J_URI` | Neo4j Bolt URI | `bolt://localhost:7687` |
| `HW_AK` / `HW_SK` | 华为云凭证（prod） | mock 值 |

---

## 运行测试

```bash
# 单元测试
mvn test -pl sre-mcp-rca

# 集成测试（需要 Docker）
mvn verify -pl sre-mcp-rca
```
