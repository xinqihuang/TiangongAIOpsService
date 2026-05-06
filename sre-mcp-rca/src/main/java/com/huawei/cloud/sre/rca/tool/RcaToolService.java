package com.huawei.cloud.sre.rca.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.common.adapter.AomAdapter;
import com.huawei.cloud.sre.common.adapter.ApmAdapter;
import com.huawei.cloud.sre.common.adapter.CtsAdapter;
import com.huawei.cloud.sre.common.adapter.LtsAdapter;
import com.huawei.cloud.sre.common.dto.LogSearchResult;
import com.huawei.cloud.sre.common.dto.MetricResult;
import com.huawei.cloud.sre.common.dto.TraceResult;
import com.huawei.cloud.sre.rca.dto.AlertCorrelationResult;
import com.huawei.cloud.sre.rca.dto.IncidentSummary;
import com.huawei.cloud.sre.rca.dto.KgQueryResult;
import com.huawei.cloud.sre.rca.dto.RcaReport;
import com.huawei.cloud.sre.rca.dto.TopologyResult;
import com.huawei.cloud.sre.rca.repository.RcaIncidentEntity;
import com.huawei.cloud.sre.rca.repository.RcaIncidentRepository;
import com.huawei.cloud.sre.rca.service.KnowledgeGraphService;
import com.huawei.cloud.sre.rca.service.RcaInferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RCA MCP Tool 服务。
 *
 * <p>提供 10 个 MCP Tool 方法，覆盖故障排查全链路：指标→日志→Trace→拓扑→变更→知识图谱→相似事故→告警关联→根因推断→报告生成。
 * 所有方法通过 {@code @Tool} 注解对 MCP Client 可见。
 */
@Service
public class RcaToolService {

    private static final Logger log = LoggerFactory.getLogger(RcaToolService.class);

    private final AomAdapter aomAdapter;
    private final LtsAdapter ltsAdapter;
    private final ApmAdapter apmAdapter;
    private final CtsAdapter ctsAdapter;
    private final KnowledgeGraphService kgService;
    private final RcaInferenceService inferenceService;
    private final RcaIncidentRepository incidentRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param aomAdapter         AOM 指标适配器
     * @param ltsAdapter         LTS 日志适配器
     * @param apmAdapter         APM 链路追踪适配器
     * @param ctsAdapter         CTS 变更记录适配器
     * @param kgService          知识图谱服务
     * @param inferenceService   RCA 推理服务
     * @param incidentRepository RCA 事故 JPA 仓库
     * @param objectMapper       JSON 工具
     */
    public RcaToolService(
            AomAdapter aomAdapter,
            LtsAdapter ltsAdapter,
            ApmAdapter apmAdapter,
            CtsAdapter ctsAdapter,
            KnowledgeGraphService kgService,
            RcaInferenceService inferenceService,
            RcaIncidentRepository incidentRepository,
            ObjectMapper objectMapper
    ) {
        this.aomAdapter = aomAdapter;
        this.ltsAdapter = ltsAdapter;
        this.apmAdapter = apmAdapter;
        this.ctsAdapter = ctsAdapter;
        this.kgService = kgService;
        this.inferenceService = inferenceService;
        this.incidentRepository = incidentRepository;
        this.objectMapper = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 1: queryMetrics
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 查询 AOM 指标数据。
     *
     * @param service    目标服务名，如 user-service
     * @param metric     指标名，如 cpu_usage_idle、mem_usage、http_requests_per_second
     * @param startTime  查询开始时间，ISO-8601 格式，如 2025-01-01T00:00:00Z
     * @param endTime    查询结束时间，ISO-8601 格式
     * @param periodSecs 采样间隔（秒），常用值：60、300；默认 60
     * @return 时序指标数据，含数据点列表及统计信息
     */
    @Tool(description = """
            查询华为云 AOM（应用运维管理）的性能指标时间序列数据。
            可查询 CPU 使用率、内存使用率、HTTP QPS、错误率等指标。
            返回指定时间范围内的采样数据点，用于分析服务性能异常。
            """)
    public MetricResult queryMetrics(
            @ToolParam(description = "目标服务名，如 user-service、order-service") String service,
            @ToolParam(description = "指标名称，如 cpu_usage_idle、mem_usage、http_requests_per_second") String metric,
            @ToolParam(description = "查询开始时间，ISO-8601 格式，如 2025-01-01T00:00:00Z") String startTime,
            @ToolParam(description = "查询结束时间，ISO-8601 格式") String endTime,
            @ToolParam(description = "采样间隔（秒），常用值 60 或 300，默认 60") int periodSecs
    ) {
        log.info("Tool[queryMetrics] service={} metric={} start={} end={}", service, metric, startTime, endTime);
        try {
            int period = periodSecs > 0 ? periodSecs : 60;
            MetricResult result = aomAdapter.queryMetric(
                    service, metric,
                    Instant.parse(startTime),
                    Instant.parse(endTime),
                    period
            );
            log.info("Tool[queryMetrics] success service={} points={}", service, result.dataPoints().size());
            return result;
        } catch (Exception e) {
            log.error("Tool[queryMetrics] failed service={} metric={}", service, metric, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 2: searchLogs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 在 LTS 日志服务中搜索关键词。
     *
     * @param service        目标服务名
     * @param keyword        搜索关键词或正则表达式，如 ERROR、NullPointerException、timeout
     * @param rangeMinutes   向前回溯的时间窗口（分钟），如 30 表示最近 30 分钟
     * @param maxResults     最大返回日志条数，1–1000
     * @return 日志搜索结果，含匹配条目及错误计数
     */
    @Tool(description = """
            在华为云 LTS（日志服务）中搜索指定服务的日志。
            支持关键词精确匹配和正则表达式搜索。
            返回匹配的日志条目，含时间戳、日志级别、Trace ID 等结构化信息。
            用于定位错误日志、异常堆栈、超时警告等故障现象。
            """)
    public LogSearchResult searchLogs(
            @ToolParam(description = "目标服务名，如 user-service") String service,
            @ToolParam(description = "搜索关键词或正则表达式，如 ERROR、NullPointerException") String keyword,
            @ToolParam(description = "向前回溯时间窗口（分钟），如 30 表示最近 30 分钟") int rangeMinutes,
            @ToolParam(description = "最大返回日志条数，范围 1–1000，默认 100") int maxResults
    ) {
        log.info("Tool[searchLogs] service={} keyword={} range={}min", service, keyword, rangeMinutes);
        try {
            int range = rangeMinutes > 0 ? rangeMinutes : 30;
            int limit = maxResults > 0 ? Math.min(maxResults, 1000) : 100;
            LogSearchResult result = ltsAdapter.searchLogs(service, keyword, range, limit);
            log.info("Tool[searchLogs] success service={} matched={}", service, result.totalCount());
            return result;
        } catch (Exception e) {
            log.error("Tool[searchLogs] failed service={} keyword={}", service, keyword, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 3: analyzeTraces
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 分析 APM Trace，定位链路中的异常 Span 和性能瓶颈。
     *
     * @param traceId Trace ID，格式因 APM 实现而异（如 UUID 或 hex 字符串）
     * @return 链路分析结果，含所有 Span、错误 Span 列表及最慢调用
     */
    @Tool(description = """
            分析华为云 APM（应用性能管理）中指定 Trace 的完整调用链路。
            返回所有 Span 的调用关系、耗时、错误信息，
            自动标记错误 Span 和最慢调用，用于定位服务间调用的故障点。
            """)
    public TraceResult analyzeTraces(
            @ToolParam(description = "Trace ID，在告警或日志中可找到，如 abc123def456") String traceId
    ) {
        log.info("Tool[analyzeTraces] traceId={}", traceId);
        try {
            TraceResult result = apmAdapter.analyzeTrace(traceId, 0L);
            log.info("Tool[analyzeTraces] success traceId={} spans={} errors={}",
                    traceId, result.spanCount(), result.errorSpans().size());
            return result;
        } catch (Exception e) {
            log.error("Tool[analyzeTraces] failed traceId={}", traceId, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 4: queryTopology
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 查询服务调用拓扑图。
     *
     * @param serviceName 起点服务名
     * @param depth       展开深度（1–5），1 表示直接依赖，5 表示全链路
     * @return 拓扑图结果，含服务节点列表（含状态）和调用边列表（含延迟/错误率）
     */
    @Tool(description = """
            查询服务依赖调用拓扑图（基于 Neo4j 知识图谱）。
            展示指定服务的上下游依赖关系、各节点健康状态、调用延迟和错误率。
            用于故障传播路径分析：判断故障是由当前服务引发还是由上游依赖传播而来。
            depth=1 仅展示直接依赖，depth=3 可展示三层链路。
            """)
    public TopologyResult queryTopology(
            @ToolParam(description = "起点服务名，如 user-service") String serviceName,
            @ToolParam(description = "展开深度 1–5，默认 2。越大覆盖面越广，查询越慢") int depth
    ) {
        log.info("Tool[queryTopology] service={} depth={}", serviceName, depth);
        try {
            TopologyResult result = kgService.queryTopology(serviceName, depth > 0 ? depth : 2);
            log.info("Tool[queryTopology] success service={} nodes={} edges={}",
                    serviceName, result.nodes().size(), result.edges().size());
            return result;
        } catch (Exception e) {
            log.error("Tool[queryTopology] failed service={}", serviceName, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 5: queryChanges
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 查询 CTS 变更记录（配置变更、部署发布、资源操作）。
     *
     * @param service   服务名或资源名
     * @param startTime 查询开始时间，ISO-8601 格式
     * @param endTime   查询结束时间，ISO-8601 格式
     * @return 变更记录列表，按时间倒序排列
     */
    @Tool(description = """
            查询华为云 CTS（云审计服务）中的变更记录。
            涵盖配置变更、代码部署、资源扩缩容、权限变更等操作日志。
            用于关联故障与变更：判断故障是否由近期发布或配置修改引入。
            返回按时间倒序排列的变更事件，含操作者、操作类型、变更详情。
            """)
    public Object queryChanges(
            @ToolParam(description = "服务名或资源名，如 user-service 或 CCE 集群名") String service,
            @ToolParam(description = "查询开始时间，ISO-8601 格式，如 2025-01-01T00:00:00Z") String startTime,
            @ToolParam(description = "查询结束时间，ISO-8601 格式") String endTime
    ) {
        log.info("Tool[queryChanges] service={} start={} end={}", service, startTime, endTime);
        try {
            var result = ctsAdapter.queryTraces(null, service, Instant.parse(startTime), Instant.parse(endTime), 100);
            log.info("Tool[queryChanges] success service={} events={}", service, result.size());
            return Map.of("service", service, "changes", result, "totalCount", result.size());
        } catch (Exception e) {
            log.error("Tool[queryChanges] failed service={}", service, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 6: kgQuery
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 对知识图谱执行自定义 Cypher 查询。
     *
     * @param cypher 合法的只读 Cypher 语句，如 {@code MATCH (s:Service) WHERE s.status='Error' RETURN s}
     * @param params 查询参数（JSON 对象），key 为参数名，value 为字符串值
     * @return 知识图谱查询结果，含记录列表和执行耗时
     */
    @Tool(description = """
            对 Neo4j 知识图谱执行自定义 Cypher 查询（只读）。
            知识图谱存储了服务依赖关系、历史故障模式、SRE 知识条目等。
            通过 Cypher 可查询特定服务的已知故障模式、相关 SOP、知识条目。
            示例：MATCH (s:Service {name:'user-service'})-[:HAS_PATTERN]->(fp:FailurePattern) RETURN fp
            """)
    public KgQueryResult kgQuery(
            @ToolParam(description = "只读 Cypher 查询语句") String cypher,
            @ToolParam(description = "查询参数 JSON 对象，如 {\"name\": \"user-service\"}，无参数时传 {}") String params
    ) {
        log.info("Tool[kgQuery] cypher={}", cypher);
        try {
            Map<String, Object> paramMap = parseParams(params);
            KgQueryResult result = kgService.executeCypher(cypher, paramMap);
            log.info("Tool[kgQuery] success records={} elapsed={}ms", result.totalCount(), result.executionTimeMs());
            return result;
        } catch (Exception e) {
            log.error("Tool[kgQuery] failed cypher={}", cypher, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 7: findSimilarIncidents
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 从历史事故库中检索语义相似的事故，用于根因推断参考。
     *
     * @param incidentDescription 当前事故的简要描述（自然语言）
     * @param topK                返回最相似的前 K 条，建议 3–10
     * @return 相似历史事故列表，按相似度降序排列
     */
    @Tool(description = """
            基于语义向量搜索，从历史事故库中检索与当前事故最相似的案例。
            使用嵌入模型将事故描述转为向量，检索最近邻历史事故。
            返回历史事故的根因、解决方案、持续时长等信息，辅助快速定位当前故障原因。
            topK 建议填 3–5，相似度低于 0.6 的结果将被过滤。
            """)
    public List<IncidentSummary> findSimilarIncidents(
            @ToolParam(description = "当前事故的自然语言描述，包含症状、受影响服务、告警信息等") String incidentDescription,
            @ToolParam(description = "返回最相似的前 K 条历史事故，建议 3–10") int topK
    ) {
        log.info("Tool[findSimilarIncidents] topK={}", topK);
        try {
            int k = topK > 0 ? Math.min(topK, 20) : 5;
            List<IncidentSummary> results = inferenceService.retrieveSimilarIncidents(incidentDescription, k);
            log.info("Tool[findSimilarIncidents] success found={}", results.size());
            return results;
        } catch (Exception e) {
            log.error("Tool[findSimilarIncidents] failed", e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 8: correlateAlerts
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 对多条告警进行关联分析，识别同一根因下的告警风暴。
     *
     * @param alertIds    告警 ID 列表（逗号分隔），如 alert-001,alert-002,alert-003
     * @param alertTexts  对应的告警描述列表（逗号分隔），与 alertIds 一一对应
     * @return 告警关联分析结果，含分组信息及共同根因推断
     */
    @Tool(description = """
            对多条并发告警进行关联分析，去除告警风暴噪音，识别同一根因产生的告警群。
            输入告警 ID 和告警描述列表，通过时间窗口和语义相似性分组。
            返回告警分组结果，每组包含推测的共同原因、受影响服务和严重等级。
            帮助 SRE 快速聚焦于真正的故障根源，而非被次生告警淹没。
            """)
    public AlertCorrelationResult correlateAlerts(
            @ToolParam(description = "告警 ID 列表，逗号分隔，如 alert-001,alert-002") String alertIds,
            @ToolParam(description = "对应的告警描述列表，逗号分隔，与 alertIds 顺序相同") String alertTexts
    ) {
        log.info("Tool[correlateAlerts] alertIds={}", alertIds);
        try {
            List<String> ids = parseCommaSeparated(alertIds);
            List<String> texts = parseCommaSeparated(alertTexts);

            String llmResponse = inferenceService.correlateAlerts(texts);
            AlertCorrelationResult result = parseCorrelationResult(ids, texts, llmResponse);
            log.info("Tool[correlateAlerts] success groups={}", result.correlationGroups().size());
            return result;
        } catch (Exception e) {
            log.error("Tool[correlateAlerts] failed alertIds={}", alertIds, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 9: analyzeRootCause (核心方法)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 核心根因分析：综合所有证据，调用 LLM 推断根本原因。
     *
     * @param incidentId      事故 ID，用于关联后续操作
     * @param incidentContext 事故上下文描述（包含服务名、症状、发现时间、业务影响等）
     * @param evidenceSummary 已收集的证据摘要，逗号分隔多条（如 queryMetrics/searchLogs 的结果摘要）
     * @return 结构化 RCA 报告，含根因、置信度、建议措施
     */
    @Tool(description = """
            核心根因分析工具：综合指标、日志、Trace、拓扑、变更等证据，
            调用 vLLM（Qwen2.5-72B）通过 RAG 推断根本原因。
            在调用本工具之前，建议先用 queryMetrics、searchLogs、analyzeTraces、queryTopology
            收集足够的证据，以获得高置信度的分析结果。
            返回结构化报告：根因组件、根因描述、置信度（0–1）、即时处置建议、预防措施。
            """)
    public RcaReport analyzeRootCause(
            @ToolParam(description = "事故 ID，用于关联后续报告，如果没有可填 'auto'") String incidentId,
            @ToolParam(description = "事故上下文：描述故障现象、受影响服务、业务影响、发现时间等") String incidentContext,
            @ToolParam(description = "已收集的证据摘要，逗号分隔多条，如指标异常描述、错误日志摘要、Trace 异常 Span 等") String evidenceSummary
    ) {
        log.info("Tool[analyzeRootCause] incidentId={}", incidentId);
        try {
            String id = "auto".equalsIgnoreCase(incidentId)
                    ? "INC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                    : incidentId;

            List<String> evidence = parseCommaSeparated(evidenceSummary);
            RcaReport report = inferenceService.analyze(id, incidentContext, evidence);

            log.info("Tool[analyzeRootCause] success incidentId={} confidence={} severity={}",
                    id, report.confidence(), report.severity());
            return report;
        } catch (Exception e) {
            log.error("Tool[analyzeRootCause] failed incidentId={}", incidentId, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 10: generateRcaReport
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 生成并持久化结构化 RCA 报告，供事后复盘使用。
     *
     * @param incidentId          事故 ID（与 analyzeRootCause 使用相同 ID）
     * @param additionalContext   补充上下文（如人工确认的信息），可为空字符串
     * @return 持久化后的报告确认信息（含报告 ID 和摘要）
     */
    @Tool(description = """
            将根因分析结果生成为规范化的 RCA 报告，并持久化到 PostgreSQL 数据库。
            报告可用于事后复盘（Postmortem）和历史事故知识库积累。
            调用本工具之前，必须先调用 analyzeRootCause 完成根因推断。
            返回报告 ID、根因摘要、置信度、以及存储状态。
            """)
    public Map<String, Object> generateRcaReport(
            @ToolParam(description = "事故 ID，与 analyzeRootCause 中使用的相同") String incidentId,
            @ToolParam(description = "补充上下文或人工确认的根因信息，可为空字符串") String additionalContext
    ) {
        log.info("Tool[generateRcaReport] incidentId={}", incidentId);
        try {
            List<RcaIncidentEntity> existing = incidentRepository.findRecentByService(
                    "unknown", 50
            ).stream()
                    .filter(e -> e.getReportJson() != null && e.getReportJson().contains(incidentId))
                    .toList();

            if (!existing.isEmpty()) {
                RcaIncidentEntity entity = existing.get(0);
                log.info("Tool[generateRcaReport] found existing record id={}", entity.getId());
                return buildReportSummary(entity);
            }

            String context = "Incident " + incidentId
                    + (additionalContext.isBlank() ? "" : ". Additional: " + additionalContext);
            RcaReport report = inferenceService.analyze(incidentId, context, List.of());

            log.info("Tool[generateRcaReport] success incidentId={}", incidentId);
            return Map.of(
                    "incidentId", incidentId,
                    "rootCause", report.rootCause(),
                    "component", report.rootCauseComponent(),
                    "severity", report.severity(),
                    "confidence", report.confidence(),
                    "immediateActions", report.immediateActions(),
                    "status", "persisted",
                    "analyzedAt", report.analyzedAt().toString()
            );
        } catch (Exception e) {
            log.error("Tool[generateRcaReport] failed incidentId={}", incidentId, e);
            throw e;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParams(String json) {
        try {
            if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                return Map.of();
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse params JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private List<String> parseCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private AlertCorrelationResult parseCorrelationResult(
            List<String> ids, List<String> texts, String llmResponse) {
        try {
            String cleaned = llmResponse.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```\\w*\\n?", "").replaceAll("```$", "").strip();
            }
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);
            List<Map<String, Object>> groupMaps = (List<Map<String, Object>>) map.getOrDefault("groups", List.of());
            String analysisNote = String.valueOf(map.getOrDefault("analysis", ""));

            List<AlertCorrelationResult.CorrelationGroup> groups = new ArrayList<>();
            for (Map<String, Object> gm : groupMaps) {
                List<Integer> indices = (List<Integer>) gm.getOrDefault("alertIndices", List.of());
                List<String> memberIds = new ArrayList<>();
                for (int idx : indices) {
                    if (idx >= 0 && idx < ids.size()) {
                        memberIds.add(ids.get(idx));
                    }
                }
                groups.add(new AlertCorrelationResult.CorrelationGroup(
                        String.valueOf(gm.getOrDefault("groupId", "G-" + groups.size())),
                        memberIds,
                        String.valueOf(gm.getOrDefault("commonCause", "")),
                        String.valueOf(gm.getOrDefault("affectedService", "")),
                        String.valueOf(gm.getOrDefault("severity", "MEDIUM")),
                        Instant.now().minusSeconds(300),
                        Instant.now()
                ));
            }

            long correlated = groups.stream().mapToLong(g -> g.memberAlertIds().size()).sum();
            return new AlertCorrelationResult(ids, groups, ids.size(), (int) correlated, analysisNote);
        } catch (Exception e) {
            log.warn("Failed to parse correlation result: {}", e.getMessage());
            return new AlertCorrelationResult(ids, List.of(), ids.size(), 0,
                    "Correlation parsing failed: " + e.getMessage());
        }
    }

    private Map<String, Object> buildReportSummary(RcaIncidentEntity entity) {
        return Map.of(
                "reportId", entity.getId().toString(),
                "service", entity.getService(),
                "incidentTitle", entity.getIncidentTitle(),
                "rootCause", entity.getRootCause() != null ? entity.getRootCause() : "",
                "severity", entity.getSeverity() != null ? entity.getSeverity() : "UNKNOWN",
                "confidence", entity.getConfidence(),
                "status", "retrieved_from_db",
                "createdAt", entity.getCreatedAt().toString()
        );
    }
}
