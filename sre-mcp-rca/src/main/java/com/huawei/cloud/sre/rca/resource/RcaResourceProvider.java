package com.huawei.cloud.sre.rca.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.rca.repository.RcaIncidentRepository;
import com.huawei.cloud.sre.rca.service.KnowledgeGraphService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RCA MCP Resource 注册提供者。
 *
 * <p>注册 4 个 MCP 资源供客户端（Claude Desktop、Cursor 等）直接读取：
 * <ol>
 *   <li>{@code sre://incidents/list} — 事故记录列表</li>
 *   <li>{@code sre://services/topology} — 服务拓扑快照</li>
 *   <li>{@code sre://knowledge-base/all} — SRE 知识条目</li>
 *   <li>{@code sre://rca-reports/recent} — 历史 RCA 报告</li>
 * </ol>
 */
@Component
public class RcaResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(RcaResourceProvider.class);

    private final RcaIncidentRepository incidentRepository;
    private final KnowledgeGraphService kgService;
    private final ObjectMapper objectMapper;

    /**
     * @param incidentRepository RCA 事故仓库
     * @param kgService          知识图谱服务
     * @param objectMapper       JSON 工具
     */
    public RcaResourceProvider(
            RcaIncidentRepository incidentRepository,
            KnowledgeGraphService kgService,
            ObjectMapper objectMapper
    ) {
        this.incidentRepository = incidentRepository;
        this.kgService = kgService;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回所有 MCP Resource 注册信息。
     *
     * @return 4 个 SyncResourceSpecification 列表
     */
    public List<McpServerFeatures.SyncResourceSpecification> registrations() {
        return List.of(
                incidentsResource(),
                topologyResource(),
                knowledgeBaseResource(),
                rcaReportsResource()
        );
    }

    private McpServerFeatures.SyncResourceSpecification incidentsResource() {
        var resource = new McpSchema.Resource(
                "sre://incidents/list",
                "incidents",
                "List recent production incidents stored in the RCA database.",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            log.info("Resource[incidents] read uri={}", request.uri());
            try {
                var entities = incidentRepository.findAll().stream()
                        .limit(50)
                        .map(e -> Map.of(
                                "id", e.getId().toString(),
                                "service", e.getService(),
                                "title", e.getIncidentTitle(),
                                "severity", e.getSeverity() != null ? e.getSeverity() : "UNKNOWN",
                                "rootCause", e.getRootCause() != null ? e.getRootCause() : "",
                                "createdAt", e.getCreatedAt().toString()
                        ))
                        .toList();
                String json = objectMapper.writeValueAsString(Map.of(
                        "incidents", entities,
                        "totalCount", entities.size()
                ));
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://incidents/list", "application/json", json)
                ));
            } catch (Exception e) {
                log.error("Resource[incidents] read failed", e);
                return errorResult("sre://incidents/list", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification topologyResource() {
        var resource = new McpSchema.Resource(
                "sre://services/topology",
                "service-topology",
                "Retrieve the service call topology from the knowledge graph. "
                        + "Returns nodes (services) and edges (call relationships) with health status.",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            log.info("Resource[topology] read uri={}", request.uri());
            try {
                var result = kgService.executeCypher(
                        "MATCH (s:Service) OPTIONAL MATCH (s)-[r:CALLS]->(t:Service) "
                                + "RETURN s.name AS source, s.status AS sourceStatus, "
                                + "t.name AS target, t.status AS targetStatus, r.protocol AS protocol",
                        Map.of()
                );
                String json = objectMapper.writeValueAsString(result);
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://services/topology", "application/json", json)
                ));
            } catch (Exception e) {
                log.error("Resource[topology] read failed", e);
                return errorResult("sre://services/topology", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification knowledgeBaseResource() {
        var resource = new McpSchema.Resource(
                "sre://knowledge-base/all",
                "knowledge-base",
                "SRE knowledge base entries: failure patterns, runbooks, and known issue resolutions.",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            log.info("Resource[knowledge-base] read uri={}", request.uri());
            try {
                var result = kgService.executeCypher(
                        "MATCH (kb:KnowledgeEntry) "
                                + "RETURN kb.category AS category, kb.title AS title, "
                                + "kb.content AS content, kb.tags AS tags "
                                + "ORDER BY kb.category, kb.title LIMIT 200",
                        Map.of()
                );
                String json = objectMapper.writeValueAsString(result);
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://knowledge-base/all", "application/json", json)
                ));
            } catch (Exception e) {
                log.error("Resource[knowledge-base] read failed", e);
                return errorResult("sre://knowledge-base/all", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification rcaReportsResource() {
        var resource = new McpSchema.Resource(
                "sre://rca-reports/recent",
                "rca-reports",
                "Recent RCA (Root Cause Analysis) reports. "
                        + "Contains incident details, identified root causes, and recommended actions.",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) -> {
            log.info("Resource[rca-reports] read uri={}", request.uri());
            try {
                var entities = incidentRepository.findAll().stream()
                        .limit(20)
                        .filter(e -> e.getReportJson() != null)
                        .map(e -> Map.of(
                                "id", e.getId().toString(),
                                "service", e.getService(),
                                "title", e.getIncidentTitle(),
                                "rootCause", e.getRootCause() != null ? e.getRootCause() : "",
                                "rootCauseComponent", e.getRootCauseComponent() != null ? e.getRootCauseComponent() : "",
                                "severity", e.getSeverity() != null ? e.getSeverity() : "UNKNOWN",
                                "confidence", e.getConfidence(),
                                "createdAt", e.getCreatedAt().toString()
                        ))
                        .toList();
                String json = objectMapper.writeValueAsString(Map.of("reports", entities));
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://rca-reports/recent", "application/json", json)
                ));
            } catch (Exception e) {
                log.error("Resource[rca-reports] read failed", e);
                return errorResult("sre://rca-reports/recent", e.getMessage());
            }
        });
    }

    private McpSchema.ReadResourceResult errorResult(String uri, String message) {
        String json = "{\"error\": \"" + (message != null ? message.replace("\"", "'") : "unknown") + "\"}";
        return new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, "application/json", json)
        ));
    }
}
