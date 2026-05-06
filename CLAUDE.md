# CLAUDE.md

> 本文件是 **Claude Code** 的项目配置文件。Claude Code 在该项目目录下启动时会自动加载此文件作为长期项目记忆。
> 本项目是华为云 AI For SRE 智能运维平台的 **MCP Server 微服务**，采用 Java 技术栈。

---

## 🎯 项目使命（Project Mission）

构建一套基于 **Model Context Protocol (MCP)** 标准协议交付的 SRE 智能运维微服务集群。三个 MCP Server 部署在华为云 CCE 上，依赖部署在 ECS 上的开源大模型（vLLM + Qwen2.5-72B），可被 Claude Desktop、Cursor、自研 Agent 平台等任意 MCP Client 调用。

**核心交付物（按优先级）：**

1. `sre-mcp-rca` —— 故障排查与根因定位 MCP Server（端口 8001）
2. `sre-mcp-monitor` —— 指标智能监控 MCP Server（端口 8002）
3. `sre-mcp-remediation` —— 故障自动修复 MCP Server（端口 8003）
4. `sre-mcp-common` —— 共享库（华为云 SDK Adapter、安全、可观测性）

---

## 🛠 技术栈（不可妥协的硬约束）

| 类别 | 技术 | 版本 | 说明 |
|---|---|---|---|
| **JDK** | OpenJDK | **21** (LTS) | 必须使用，启用 Virtual Threads |
| **构建工具** | Maven | 3.9+ | **不要用 Gradle** |
| **微服务框架** | Spring Boot | 3.3.5 | 锁定版本 |
| **MCP 实现** | Spring AI MCP | 1.0.0 | `spring-ai-starter-mcp-server-webmvc`（**WebMVC 不是 WebFlux**） |
| **编程模型** | Spring MVC + Virtual Threads | — | **不要用 Reactor / WebFlux** |
| **华为云 SDK** | huaweicloud-sdk-java-v3 | 3.1.105 | 通过 BOM 管理版本 |
| **LLM 客户端** | Spring AI OpenAI Starter | 1.0.0 | 调用 ECS 上的 vLLM (OpenAI 兼容 API) |
| **缓存** | Spring Data Redis (Lettuce) | Spring Boot 内置 | 会话状态外置 |
| **数据库** | Spring Data JPA + PostgreSQL 15 | Spring Boot 内置 | 审计与执行记录 |
| **消息队列** | Spring Kafka | 3.x | 接入华为云 DMS Kafka |
| **容错** | Resilience4j | 2.2.0 | 熔断/限流/重试 |
| **可观测性** | Micrometer + OpenTelemetry | 1.13.x / 2.x | Metrics / Tracing |
| **日志** | Logback + logstash-logback-encoder | 7.x | 结构化 JSON 日志 |
| **状态机** | **自研轻量状态机** | — | **不要引入 Camunda / Flowable / Spring StateMachine** |
| **测试** | JUnit 5 + Mockito + Testcontainers | — | 单测 + 集成测试 |
| **镜像构建** | Spring Boot Buildpacks | — | **不要写 Dockerfile**，使用 `mvn spring-boot:build-image` |

**❌ 禁止使用的技术：**
- Gradle、Lombok 之外的代码生成框架（如 MapStruct 仅在必要时使用）
- WebFlux / Reactor / RxJava
- Spring Cloud（本项目不需要服务发现/配置中心，CCE 已提供）
- GraalVM Native Image（保持传统 JVM）
- 任何 BPMN 工作流引擎

---

## 📁 项目目录结构（必须严格遵守）

```
sre-mcp-platform/                                  # 项目根目录（Maven Parent）
├── pom.xml                                        # Parent POM，packaging=pom
├── CLAUDE.md                                      # 本文件
├── README.md                                      # 项目说明
├── .gitignore
├── .editorconfig
├── docs/
│   ├── architecture.md                            # 架构设计参考
│   └── api-examples.md                            # MCP 调用示例
├── sre-mcp-common/                                # 共享库 (jar)
│   ├── pom.xml
│   └── src/main/java/com/huawei/cloud/sre/common/
│       ├── adapter/                               # 华为云 SDK 适配器
│       │   ├── AomAdapter.java
│       │   ├── ApmAdapter.java
│       │   ├── LtsAdapter.java
│       │   ├── CceAdapter.java
│       │   ├── EcsAdapter.java
│       │   ├── ElbAdapter.java
│       │   ├── RdsAdapter.java
│       │   ├── CtsAdapter.java
│       │   ├── ScmAdapter.java
│       │   └── SmnAdapter.java
│       ├── credential/
│       │   ├── HuaweiCloudCredentialProvider.java
│       │   └── KmsDecryptor.java
│       ├── llm/
│       │   ├── VllmChatClientConfig.java
│       │   └── EmbeddingClientConfig.java
│       ├── memory/
│       │   ├── SessionMemoryStore.java
│       │   └── IdempotencyStore.java
│       ├── security/
│       │   ├── McpSecurityConfig.java
│       │   ├── RequireToolPermission.java
│       │   ├── ToolPermissionAspect.java
│       │   └── TenantContext.java
│       ├── observability/
│       │   ├── ToolMetricsAspect.java
│       │   └── McpMetricsCustomizer.java
│       ├── exception/
│       │   ├── HuaweiCloudException.java
│       │   ├── McpToolException.java
│       │   └── GlobalExceptionHandler.java
│       └── dto/                                   # 公共 DTO
├── sre-mcp-rca/                                   # RCA MCP Server
│   ├── pom.xml
│   └── src/main/java/com/huawei/cloud/sre/rca/
│       ├── McpRcaApplication.java
│       ├── config/
│       ├── tool/
│       │   └── RcaToolService.java                # 所有 @Tool 方法
│       ├── service/
│       │   ├── RcaInferenceService.java           # LLM 推理逻辑
│       │   └── KnowledgeGraphService.java
│       ├── prompt/
│       │   └── RcaPromptProvider.java
│       ├── resource/
│       │   └── RcaResourceProvider.java
│       └── repository/
│   └── src/main/resources/
│       ├── application.yml
│       ├── application-dev.yml
│       ├── application-prod.yml
│       ├── logback-spring.xml
│       └── prompts/                               # Prompt 模板
├── sre-mcp-monitor/                               # Monitor MCP Server
├── sre-mcp-remediation/                           # Remediation MCP Server
└── sre-mcp-deploy/                                # 部署制品
    ├── helm/
    │   ├── Chart.yaml
    │   ├── values.yaml
    │   ├── values-dev.yaml
    │   ├── values-prod.yaml
    │   └── templates/
    └── k8s/
```

**目录命名规则：**
- 包名前缀：`com.huawei.cloud.sre.{module}`（例如 `com.huawei.cloud.sre.rca`）
- 共享库包名：`com.huawei.cloud.sre.common`
- 类名：使用 PascalCase，避免缩写（除 RCA、SDK、API、HTTP、URL、ID 等通用缩写）
- 测试类：`{ClassName}Test.java`（单测）/ `{ClassName}IT.java`（集成测试）

---

## 🚀 分阶段交付计划（必读）

本项目采用 **5 个阶段** 渐进式交付。Claude Code 在每个阶段开始前 **必须** 使用 `TodoWrite` 工具创建该阶段的任务清单，按顺序完成后再进入下一阶段。

### Phase 1：脚手架与共享库（首要任务）

**交付目标：** 可编译通过的 Maven 多模块工程骨架 + `sre-mcp-common` 完整实现。

**任务清单：**
1. 创建 Parent POM（packaging=pom），定义所有依赖版本（Spring Boot BOM、Spring AI BOM、华为云 SDK BOM）
2. 创建 4 个子模块：`sre-mcp-common`、`sre-mcp-rca`、`sre-mcp-monitor`、`sre-mcp-remediation`
3. 实现 `sre-mcp-common` 的所有类：
   - 10 个华为云 SDK Adapter（每个都要完整实现，包含异常处理、Metrics 埋点、Resilience4j 注解）
   - `HuaweiCloudCredentialProvider`（KMS 解密 + 定时刷新）
   - `VllmChatClientConfig`（OpenAI 兼容客户端配置）
   - `SessionMemoryStore`（基于 Redis）
   - `IdempotencyStore`（基于 Redis SETNX）
   - `McpSecurityConfig`（OAuth 2.1 Resource Server）
   - `RequireToolPermission` 注解 + Aspect 实现
   - `TenantContext`（基于 ThreadLocal，注意虚拟线程兼容性）
   - `ToolMetricsAspect`（自动埋点）
   - `GlobalExceptionHandler`
   - 公共 DTO（`MetricResult`、`LogSearchResult`、`TraceResult` 等）
4. 编写共享库的单元测试（目标覆盖率 ≥ 70%）
5. 配置 `.gitignore`、`.editorconfig`、根 `README.md`
6. 验证：`mvn clean install` 成功

**完成标准：**
- ✅ `mvn clean install -DskipTests=false` 全绿
- ✅ 共享库 jar 可被子模块依赖
- ✅ Adapter 单测全部通过（使用 Mockito mock SDK Client）

---

### Phase 2：sre-mcp-rca 完整实现

**交付目标：** 一个完整可运行的 RCA MCP Server，支持本地 Stdio 模式和 HTTP 模式。

**任务清单：**
1. 实现 `McpRcaApplication` 主类与配置
2. 实现 `RcaToolService`，包含以下 **10 个 @Tool 方法**（每个方法都要完整实现，不能用 TODO 占位）：
   - `queryMetrics(service, metric, startTime, endTime)` —— 调用 AomAdapter
   - `searchLogs(service, keyword, rangeMinutes)` —— 调用 LtsAdapter
   - `analyzeTraces(traceId)` —— 调用 ApmAdapter
   - `queryTopology(serviceName, depth)` —— 调用 Neo4j Driver
   - `queryChanges(service, startTime, endTime)` —— 调用 CtsAdapter
   - `kgQuery(cypher, params)` —— 通用知识图谱查询
   - `findSimilarIncidents(incidentDescription, topK)` —— 调用 VectorStore
   - `correlateAlerts(alertIds)` —— 告警关联分析
   - `analyzeRootCause(incidentContext, evidence)` —— **核心方法**：调用 vLLM + RAG
   - `generateRcaReport(rcaResult)` —— 生成结构化报告
3. 实现 `RcaResourceProvider`：
   - `sre://incidents/{id}`
   - `sre://services/{name}/topology`
   - `sre://knowledge-base/{category}`
   - `sre://rca-reports/{id}`
4. 实现 `RcaPromptProvider`，提供 5 个 Prompt 模板（YAML 文件存于 `resources/prompts/`）
5. 实现 `KnowledgeGraphService`（Neo4j 连接管理）
6. 实现 `RcaInferenceService`（封装 RAG 流程）
7. 编写 `application.yml`、`application-dev.yml`、`application-prod.yml`
8. 编写 `logback-spring.xml`（结构化 JSON 日志）
9. 编写完整测试：单测（Mockito）+ 集成测试（Testcontainers + Redis + PostgreSQL）
10. 编写 `sre-mcp-rca/README.md`，说明本地启动方式与 Claude Desktop 接入示例

**完成标准：**
- ✅ `mvn spring-boot:run -pl sre-mcp-rca` 启动成功
- ✅ 通过 MCP Inspector 工具可看到 10 个 Tools / 4 个 Resources / 5 个 Prompts
- ✅ Claude Desktop 配置后可调用所有 Tools
- ✅ Actuator 端点 `/actuator/health` 返回 UP
- ✅ Prometheus 指标 `mcp_tool_calls_total` 可见

---

### Phase 3：sre-mcp-monitor 完整实现

**任务清单：**
1. 实现 `McpMonitorApplication`
2. 实现 `MonitorToolService`，包含 **9 个 @Tool 方法**：
   - `updateBaseline` / `detectAnomaly` / `forecastCapacity` / `correlateMetrics` / `dedupAlerts` / `manageAlertRule` / `silenceAlerts` / `getBaselineStatus` / `sendNotification`
3. 实现动态基线算法 `BaselineEngine`（EWMA + 3σ）
4. 实现告警聚合服务 `AlertAggregator`（时间/拓扑/语义三级聚合）
5. 实现 Kafka Producer/Consumer（事件总线对接）
6. 完整测试与文档

**完成标准：**
- ✅ 异常检测算法精度通过测试（注入合成数据验证）
- ✅ Kafka 集成测试通过（Testcontainers Kafka）
- ✅ MCP 协议端到端调用通过

---

### Phase 4：sre-mcp-remediation 完整实现

**任务清单：**
1. 实现 `McpRemediationApplication`
2. 实现 **自研轻量状态机** `RemediationStateMachine`（基于 EnumMap）
3. 实现 `RemediationContext` JPA Entity
4. 实现 `RemediationToolService`，包含 **14 个 @Tool 方法**：
   - 🟢 低风险：`restartPod` / `scaleDeployment` / `renewCertificate`
   - 🟡 中风险：`cleanDisk` / `adjustConnectionPool` / `switchTraffic`
   - 🔴 高风险：`replaceNode` / `dbFailover` / `rollbackRelease`
   - 通用：`matchStrategy` / `assessRisk` / `requestApproval` / `verifyRemediation` / `rollbackAction`
5. 实现幂等性保证（Redisson 分布式锁 + Redis SETNX）
6. 实现熔断机制（Resilience4j：30 分钟内 3 次失败自动熔断）
7. 实现审批工作流（轻量状态机 + Webhook 回调）
8. 实现 SOP 策略库（PostgreSQL + JPA Repository）
9. 完整测试与文档

**完成标准：**
- ✅ 风险分级正确路由（低=直接执行，中=单人审批，高=工单+双人审批）
- ✅ 熔断机制可触发（注入失败验证）
- ✅ 幂等保证可验证（重复调用相同 idempotencyKey 返回相同结果）

---

### Phase 5：部署与 CI/CD

**任务清单：**
1. 编写 Helm Chart（`sre-mcp-deploy/helm/`），覆盖 3 个 Server 部署
2. 编写 `values-dev.yaml`、`values-prod.yaml`
3. 编写 K8s 资源（Deployment/Service/Ingress/HPA/PDB/ConfigMap/Secret/ServiceAccount/NetworkPolicy/ServiceMonitor）
4. 配置镜像构建（Spring Boot Buildpacks，目标仓库 SWR）
5. 编写 GitHub Actions / CodeArts Pipeline 配置
6. 编写部署文档 `sre-mcp-deploy/README.md`

---

## 📐 编码规范（必须遵守）

### 通用规范
- 使用 **Java 21 语法特性**：record、pattern matching、sealed classes、virtual threads、text blocks
- **不要使用 Lombok**（团队约定，使用 Java 21 record 替代）
- 所有 public 类必须有 Javadoc，说明用途
- 所有 public 方法必须有 Javadoc，含 `@param`、`@return`、`@throws`
- 包外可见的所有字段必须 final
- 优先使用 `Optional<T>` 而非 nullable 返回

### Spring 规范
- 配置类用 `@Configuration`，不要把配置写在主类里
- 业务类用 `@Service`，数据访问层用 `@Repository`
- **使用构造器注入，不使用 `@Autowired` 字段注入**
- `@Value` 注入的配置必须有默认值或在 `@ConfigurationProperties` 中定义
- 不要使用 `@ComponentScan` 显式声明（让 Spring Boot 自动扫描）

### MCP 规范
- 每个 `@Tool` 方法必须包含详细的 `description`，描述清楚用途、参数含义、返回内容
- 每个 `@ToolParam` 必须有清晰的 `description`，让 LLM 理解参数意图
- Tool 方法返回值必须是可被 Jackson 序列化的 POJO 或 record
- Tool 方法不要返回 `void`，至少返回执行结果状态
- Tool 入参不要超过 6 个，超过则封装为 record

### 异常处理规范
- 业务异常继承 `RuntimeException`，按场景细分（`HuaweiCloudException`、`McpToolException`）
- 不要捕获 `Exception` 然后吞掉，至少要 log
- Tool 方法的所有外部调用必须有超时控制（Resilience4j `@TimeLimiter` 或 SDK 配置）
- 通过 `GlobalExceptionHandler` 统一映射为 MCP 错误码

### 日志规范
- 使用 `org.slf4j.Logger`，**不要用 `System.out.println`**
- 每个 Tool 方法入口 log INFO，出口（成功）log INFO，异常 log ERROR
- 敏感信息（AK/SK、Token、密码）禁止打印到日志
- 使用结构化日志：`log.info("Tool[{}] executed in {}ms", toolName, duration)`

### 测试规范
- 单测目标覆盖率 ≥ 70%
- 集成测试使用 Testcontainers（Redis、PostgreSQL、Kafka）
- 测试类与被测类在同一包路径下（`src/test/java/...`）
- 使用 `@SpringBootTest` 时按需指定 `webEnvironment`
- Mockito 优先使用 `@ExtendWith(MockitoExtension.class)` 而非旧 API

---

## 🔑 关键实现要点（容易踩坑）

### 1. Virtual Threads 启用
```yaml
spring:
  threads:
    virtual:
      enabled: true
```
**注意：** 业务代码中避免使用 `synchronized`（导致 carrier thread pinning），改用 `ReentrantLock`。

### 2. Spring AI MCP Server 依赖（不要选错）
```xml
<!-- ✅ 正确：WebMVC 版本 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>

<!-- ❌ 错误：不要用 WebFlux 版本 -->
<!-- spring-ai-starter-mcp-server-webflux -->
```

### 3. @Tool 注解在 Spring AI 中的使用
```java
@Tool(description = "查询 AOM 性能指标")
public MetricResult queryMetrics(
    @ToolParam(description = "服务名，如 user-service") String service,
    @ToolParam(description = "指标名，如 cpu_usage") String metric,
    @ToolParam(description = "开始时间，ISO-8601 格式，如 2025-01-01T00:00:00Z") String startTime,
    @ToolParam(description = "结束时间") String endTime
) {
    // 实现
}
```
然后在主类注册：
```java
@Bean
public ToolCallbackProvider rcaTools(RcaToolService rcaToolService) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(rcaToolService)
        .build();
}
```

### 4. 华为云 SDK 调用模式
```java
// ✅ 正确模式
@Component
public class AomAdapter {
    private final AomClient client;

    public AomAdapter(HuaweiCloudCredentialProvider provider,
                      @Value("${huaweicloud.region}") String region) {
        this.client = AomClient.newBuilder()
            .withCredential(provider.getCredentials())
            .withRegion(AomRegion.valueOf(region.toUpperCase().replace("-", "_")))
            .build();
    }

    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public MetricResult queryMetric(...) {
        try {
            return MetricResult.from(client.listSample(req));
        } catch (ServiceResponseException e) {
            throw new HuaweiCloudException("AOM 查询失败", e);
        }
    }
}
```

### 5. Spring AI 调用 vLLM
配置使用 OpenAI 兼容协议：
```yaml
spring:
  ai:
    openai:
      base-url: ${VLLM_ENDPOINT:http://localhost:8000}
      api-key: ${VLLM_API_KEY:not-needed}
      chat:
        options:
          model: Qwen2.5-72B-Instruct
          temperature: 0.1
```
代码使用：
```java
@Service
public class RcaInferenceService {
    private final ChatClient chatClient;

    public RcaInferenceService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public RcaReport analyze(String context, List<Document> ragDocs) {
        return chatClient.prompt()
            .system(SystemPrompts.RCA_ANALYST)
            .user(u -> u.text("...").param("ctx", context))
            .call()
            .entity(RcaReport.class);  // 自动反序列化为 POJO
    }
}
```

### 6. 自研状态机（不要引入第三方库）
使用 `EnumMap<State, Set<State>>` 定义合法转换，简单高效：
```java
private final Map<RemediationState, Set<RemediationState>> transitions = new EnumMap<>(RemediationState.class);

@PostConstruct
public void init() {
    transitions.put(INITIATED, EnumSet.of(STRATEGY_MATCHED));
    transitions.put(STRATEGY_MATCHED, EnumSet.of(RISK_ASSESSED));
    // ...
}

public void transit(RemediationContext ctx, RemediationState target) {
    Set<RemediationState> allowed = transitions.get(ctx.getState());
    if (allowed == null || !allowed.contains(target)) {
        throw new IllegalStateTransitionException(ctx.getState(), target);
    }
    ctx.setState(target);
    ctx.recordTransition(...);
    eventPublisher.publishEvent(new StateChangedEvent(ctx));
}
```

### 7. 虚拟线程 + ThreadLocal 注意事项
`TenantContext` 使用 `ThreadLocal` 在虚拟线程中是 **安全的**（虚拟线程独立持有 ThreadLocal）。但 Spring `RequestContextHolder` 等需要确认配置正确。

---

## 🌍 环境与配置

### 本地开发环境
- 不需要真实华为云资源，使用 Mock Adapter 或 LocalStack 替代
- vLLM 可用 Ollama 替代（启动 `ollama serve` + `ollama pull qwen2.5:7b`）
- Redis、PostgreSQL、Kafka 用 Docker Compose 启动

需要在仓库根目录提供 `docker-compose.dev.yml`：
```yaml
services:
  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_PASSWORD: dev
      POSTGRES_DB: sre_mcp
    ports: ["5432:5432"]
  kafka:
    image: bitnami/kafka:3.7
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
    ports: ["9092:9092"]
```

### 环境变量约定
| 变量名 | 用途 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | 环境（dev/staging/prod） |
| `HW_AK` / `HW_SK` | 华为云凭证（生产从 KMS 解密） |
| `HW_PROJECT_ID` | 华为云 Project ID |
| `HW_REGION` | 区域（默认 cn-north-4） |
| `VLLM_ENDPOINT` | vLLM 服务地址 |
| `VLLM_API_KEY` | vLLM API Key |
| `REDIS_HOST` / `REDIS_PASSWORD` | Redis 配置 |
| `PG_HOST` / `PG_USER` / `PG_PASSWORD` | PostgreSQL 配置 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 地址 |

---

## ✅ 验收清单（每阶段完成时必须自检）

每个阶段完成时，Claude Code **必须自检以下项**，全部通过才算完成：

- [ ] `mvn clean install` 全绿，无 WARNING 类编译错误
- [ ] 单元测试覆盖率 ≥ 70%（用 JaCoCo 报告验证）
- [ ] 启动 Spring Boot 应用无报错（`mvn spring-boot:run`）
- [ ] `/actuator/health` 返回 UP
- [ ] MCP Inspector 可成功连接并列出 Tools/Resources/Prompts
- [ ] 当前阶段的 README.md 已写完，包含启动、调用、测试说明
- [ ] 提交一份阶段总结到 `docs/progress/phase-{N}-summary.md`，记录完成项与遗留问题

---

## 🤝 与 Claude Code 的协作约定

### 强制行为
1. **每个阶段开始前**，使用 `TodoWrite` 工具创建该阶段的任务清单
2. **完成每个文件创建后**，运行 `mvn compile -pl {module}` 验证语法正确
3. **完成关键功能后**，编写并运行测试（不要写完所有代码再测）
4. **每完成一个阶段**，主动报告并请求确认才进入下一阶段
5. **遇到不确定的设计决策**，优先采用本文档约定，无约定则询问用户
6. **每次提交代码前**，运行 `mvn verify` 全量验证

### 禁止行为
1. ❌ 不要修改本 `CLAUDE.md` 文件
2. ❌ 不要引入未在"技术栈"章节中列出的第三方库
3. ❌ 不要写 TODO 占位符代码（除非用户明确要求骨架模式）
4. ❌ 不要为了快速完成而跳过测试
5. ❌ 不要把多个 Tool 实现塞到一个超大类（>500 行需要拆分）
6. ❌ 不要修改 Parent POM 中已锁定的版本号

### 进度跟踪
请在 `docs/progress/` 目录下维护交付进度：
- `phase-1-summary.md`、`phase-2-summary.md` ...
- 每个文件包含：完成日期、完成项清单、遗留问题、下阶段计划

---

## 📚 参考资料速查

| 文档 | 链接 |
|---|---|
| MCP 协议规范 | https://spec.modelcontextprotocol.io/ |
| Spring AI MCP | https://docs.spring.io/spring-ai/reference/api/mcp/ |
| 华为云 Java SDK | https://support.huaweicloud.com/sdkreference-java/ |
| vLLM 文档 | https://docs.vllm.ai/ |
| Resilience4j | https://resilience4j.readme.io/ |
| Testcontainers | https://java.testcontainers.org/ |

---

## 🚦 启动指令

请阅读完本 CLAUDE.md 后：

1. **首先回应**：用一段话总结你对项目的理解、5 个阶段的目标、以及你将如何使用 TodoWrite 推进
2. **然后启动 Phase 1**：使用 `TodoWrite` 工具创建 Phase 1 的详细任务清单
3. **接着开始执行**：按任务清单顺序创建 Parent POM → 子模块 POM → 共享库代码

如果你已准备就绪，请回复"开始执行 Phase 1"并立即调用 TodoWrite 工具。
