# SRE MCP Platform
iq9GMJkJm5ZLYboe8_jkSqYlTm2VY-5X4SYYVGPhaBFsHNH83103TyfDguokxUHXdO16qbFadiIvyTiQg5ROCw
华为云 AI For SRE 智能运维平台 —— 基于 **Model Context Protocol (MCP)** 的微服务集群。

## 模块

| 模块 | 端口 | 职责 |
|---|---|---|
| `sre-mcp-common` | — | 共享库（华为云 SDK Adapter、安全、可观测性、凭证管理） |
| `sre-mcp-rca` | 8001 | 故障根因定位 MCP Server |
| `sre-mcp-monitor` | 8002 | 指标智能监控 MCP Server |
| `sre-mcp-remediation` | 8003 | 故障自动修复 MCP Server |

## 技术栈

- **JDK**：OpenJDK 21（启用 Virtual Threads）
- **构建**：Maven 3.9+
- **框架**：Spring Boot 3.3.5 + Spring AI MCP 1.0.0（WebMVC 模式）
- **华为云 SDK**：huaweicloud-sdk-java-v3 3.1.194
- **LLM**：vLLM + Qwen2.5-72B（OpenAI 兼容协议）
- **存储**：PostgreSQL 15、Redis 7、Kafka 3.x
- **可观测性**：Micrometer + OpenTelemetry + Prometheus

## 快速开始

### 前置条件

```bash
# JDK 21
java -version  # 21.x

# Maven 3.9+
mvn -v
```

### 本地依赖（Redis / PostgreSQL / Kafka）

```bash
docker compose -f docker-compose.dev.yml up -d
```

### 全量构建

```bash
mvn clean install
```

### 启动 RCA Server

```bash
mvn spring-boot:run -pl sre-mcp-rca
```

## 项目文档

- [CLAUDE.md](./CLAUDE.md) —— 项目规约与分阶段交付计划
- [docs/architecture.md](./docs/architecture.md) —— 架构设计
- [docs/api-examples.md](./docs/api-examples.md) —— MCP 调用示例
- [docs/progress/](./docs/progress/) —— 各阶段交付进度

## 当前状态

🚧 Phase 1 进行中 —— 脚手架与共享库（`sre-mcp-common`）。
