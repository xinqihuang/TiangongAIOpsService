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
            Query AOM (Application Operations Management) metric time-series data for a service.
            Supports CPU usage, memory usage, HTTP QPS, error rate and other metrics.
            Returns sampled data points in the specified time range for performance anomaly analysis.
            """)
    public MetricResult queryMetrics(
            @ToolParam(description = "Target service name, e.g. user-service, order-service") String service,
            @ToolParam(description = "Metric name, e.g. cpu_usage_idle, mem_usage, http_requests_per_second") String metric,
            @ToolParam(description = "Query start time in ISO-8601 format, e.g. 2025-01-01T00:00:00Z") String startTime,
            @ToolParam(description = "Query end time in ISO-8601 format") String endTime,
            @ToolParam(description = "Sampling interval in seconds, common values: 60 or 300, default 60") int periodSecs
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
            Search logs for a service in LTS (Log Tank Service).
            Supports exact keyword matching and regular expression search.
            Returns matching log entries with timestamp, log level, Trace ID and other structured fields.
            Use this to locate error logs, exception stack traces, timeout warnings, and other failure signals.
            """)
    public LogSearchResult searchLogs(
            @ToolParam(description = "Target service name, e.g. user-service") String service,
            @ToolParam(description = "Search keyword or regex, e.g. ERROR, NullPointerException") String keyword,
            @ToolParam(description = "Look-back time window in minutes, e.g. 30 means the last 30 minutes") int rangeMinutes,
            @ToolParam(description = "Maximum number of log entries to return, range 1-1000, default 100") int maxResults
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
            Analyze the full call chain of a trace in APM (Application Performance Management).
            Returns call relationships, duration, and error details for every span.
            Automatically highlights error spans and the slowest call to pinpoint inter-service failure points.
            """)
    public TraceResult analyzeTraces(
            @ToolParam(description = "Trace ID found in alerts or logs, e.g. abc123def456") String traceId
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
            Query the service dependency topology graph from the Neo4j knowledge graph.
            Shows upstream/downstream dependencies, node health status, call latency, and error rate.
            Use for fault propagation analysis: determine whether the failure originated locally or propagated from an upstream dependency.
            depth=1 shows direct dependencies only; depth=3 shows three hops.
            """)
    public TopologyResult queryTopology(
            @ToolParam(description = "Starting service name, e.g. user-service") String serviceName,
            @ToolParam(description = "Expansion depth 1-5, default 2. Higher values give broader coverage but slower queries") int depth
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
            Query CTS (Cloud Trace Service) change records for a service or resource.
            Covers configuration changes, code deployments, scaling operations, permission changes, and other audit events.
            Use to correlate incidents with recent changes: determine whether the failure was introduced by a recent release or config modification.
            Returns change events in reverse chronological order, including operator, operation type, and change details.
            """)
    public Object queryChanges(
            @ToolParam(description = "Service name or resource name, e.g. user-service or CCE cluster name") String service,
            @ToolParam(description = "Query start time in ISO-8601 format, e.g. 2025-01-01T00:00:00Z") String startTime,
            @ToolParam(description = "Query end time in ISO-8601 format") String endTime
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
            Execute a read-only Cypher query on the Neo4j knowledge graph.
            The graph stores service dependencies, historical failure patterns, and SRE knowledge entries.
            Use Cypher to retrieve known failure patterns, related SOPs, and knowledge entries for a specific service.
            Example: MATCH (s:Service {name:'user-service'})-[:HAS_PATTERN]->(fp:FailurePattern) RETURN fp
            """)
    public KgQueryResult kgQuery(
            @ToolParam(description = "Read-only Cypher query statement") String cypher,
            @ToolParam(description = "Query parameters as a JSON object, e.g. {\"name\": \"user-service\"}; pass {} if none") String params
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
            Semantic vector search to retrieve the most similar historical incidents from the incident knowledge base.
            Converts the incident description to a vector embedding and finds the nearest historical incidents.
            Returns root cause, resolution, and duration for each historical case to help quickly identify the current failure's cause.
            Recommended topK: 3-5. Results with similarity below 0.6 are filtered out.
            """)
    public List<IncidentSummary> findSimilarIncidents(
            @ToolParam(description = "Natural language description of the current incident, including symptoms, affected services, and alert details") String incidentDescription,
            @ToolParam(description = "Number of most similar historical incidents to return, recommended 3-10") int topK
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
            Correlate multiple concurrent alerts to remove alert storm noise and identify alert clusters sharing a common root cause.
            Groups alerts by time window and semantic similarity.
            Returns alert groups, each with an inferred common cause, affected service, and severity level.
            Helps SRE teams focus on the true root cause rather than being overwhelmed by secondary alerts.
            """)
    public AlertCorrelationResult correlateAlerts(
            @ToolParam(description = "Comma-separated alert ID list, e.g. alert-001,alert-002") String alertIds,
            @ToolParam(description = "Comma-separated alert description list, in the same order as alertIds") String alertTexts
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
            Core root cause analysis tool: synthesizes evidence from metrics, logs, traces, topology, and change records,
            then calls vLLM (Qwen2.5-72B) via RAG to infer the root cause.
            Before calling this tool, it is recommended to first collect sufficient evidence using
            queryMetrics, searchLogs, analyzeTraces, and queryTopology for higher confidence results.
            Returns a structured report: root cause component, description, confidence (0-1), immediate actions, and prevention measures.
            """)
    public RcaReport analyzeRootCause(
            @ToolParam(description = "Incident ID for linking subsequent reports; pass 'auto' to auto-generate") String incidentId,
            @ToolParam(description = "Incident context: describe the failure symptoms, affected services, business impact, and discovery time") String incidentContext,
            @ToolParam(description = "Collected evidence summary, comma-separated, e.g. metric anomaly description, error log summary, abnormal trace spans") String evidenceSummary
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
            Generate a structured RCA report from the root cause analysis results and persist it to PostgreSQL.
            The report can be used for postmortem review and historical incident knowledge accumulation.
            Must call analyzeRootCause first before invoking this tool.
            Returns the report ID, root cause summary, confidence score, and storage status.
            """)
    public Map<String, Object> generateRcaReport(
            @ToolParam(description = "Incident ID, same as the one used in analyzeRootCause") String incidentId,
            @ToolParam(description = "Supplemental context or manually confirmed root cause information; can be empty string") String additionalContext
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
