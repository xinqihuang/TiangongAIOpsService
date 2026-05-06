package com.huawei.cloud.sre.rca.service;

import com.huawei.cloud.sre.rca.dto.KgQueryResult;
import com.huawei.cloud.sre.rca.dto.TopologyResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;
import org.neo4j.driver.exceptions.Neo4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识图谱服务。
 *
 * <p>封装 Neo4j Driver，提供服务拓扑查询、任意 Cypher 查询及图谱数据写入能力。
 * 所有读操作使用 Resilience4j 重试和熔断保护。
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final Driver driver;

    /**
     * @param driver Neo4j Java Driver，由 {@code Neo4jConfig} 提供
     */
    public KnowledgeGraphService(Driver driver) {
        this.driver = driver;
    }

    /**
     * 查询指定服务的调用拓扑（广度优先，按 depth 展开）。
     *
     * @param serviceName 起点服务名
     * @param depth       展开深度（1–5）
     * @return 拓扑结果，含节点和边
     * @throws Neo4jException 若 Neo4j 查询失败
     */
    @Retry(name = "neo4j")
    @CircuitBreaker(name = "neo4j")
    public TopologyResult queryTopology(String serviceName, int depth) {
        log.info("KG queryTopology service={} depth={}", serviceName, depth);
        int safeDepth = Math.min(Math.max(depth, 1), 5);

        String cypher = """
                MATCH path = (root:Service {name: $name})-[r:CALLS*1..%d]->(dep:Service)
                RETURN root, r, dep, nodes(path) AS pathNodes, relationships(path) AS pathRels
                """.formatted(safeDepth);

        List<TopologyResult.ServiceNode> nodes = new ArrayList<>();
        List<TopologyResult.ServiceEdge> edges = new ArrayList<>();
        Map<String, Boolean> visitedNodes = new HashMap<>();

        long start = System.currentTimeMillis();
        try (Session session = driver.session()) {
            var result = session.run(cypher, Values.parameters("name", serviceName));
            while (result.hasNext()) {
                Record record = result.next();

                List<Value> pathNodes = record.get("pathNodes").asList(v -> v);
                for (Value nodeVal : pathNodes) {
                    var node = nodeVal.asNode();
                    String name = node.get("name").asString("unknown");
                    if (!visitedNodes.containsKey(name)) {
                        visitedNodes.put(name, true);
                        String type = node.get("type").asString("deployment");
                        String status = node.get("status").asString("Unknown");
                        Map<String, String> labels = new HashMap<>();
                        node.asMap().forEach((k, v) -> {
                            if (!"name".equals(k) && !"type".equals(k) && !"status".equals(k)) {
                                labels.put(k, String.valueOf(v));
                            }
                        });
                        nodes.add(new TopologyResult.ServiceNode(name, type, status, labels));
                    }
                }

                List<Value> pathRels = record.get("pathRels").asList(v -> v);
                for (Value relVal : pathRels) {
                    var rel = relVal.asRelationship();
                    String src = rel.get("sourceName").asString("");
                    String tgt = rel.get("targetName").asString("");
                    String protocol = rel.get("protocol").asString("HTTP");
                    double latency = rel.get("latencyMs").isNull() ? -1.0 : rel.get("latencyMs").asDouble();
                    double errorRate = rel.get("errorRate").isNull() ? -1.0 : rel.get("errorRate").asDouble();
                    if (!src.isEmpty() && !tgt.isEmpty()) {
                        edges.add(new TopologyResult.ServiceEdge(src, tgt, protocol, latency, errorRate));
                    }
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("KG queryTopology done service={} nodes={} edges={} elapsed={}ms",
                    serviceName, nodes.size(), edges.size(), elapsed);
        }

        if (nodes.isEmpty()) {
            nodes.add(new TopologyResult.ServiceNode(serviceName, "deployment", "Unknown", Map.of()));
        }
        return new TopologyResult(serviceName, safeDepth, nodes, edges);
    }

    /**
     * 执行任意 Cypher 查询，返回通用结果集。
     *
     * @param cypher 合法的 Cypher 查询语句（只读）
     * @param params 查询参数，key 为参数名，value 为参数值
     * @return KG 查询结果
     */
    @Retry(name = "neo4j")
    @CircuitBreaker(name = "neo4j")
    public KgQueryResult executeCypher(String cypher, Map<String, Object> params) {
        log.info("KG executeCypher cypher={}", cypher);
        long start = System.currentTimeMillis();
        List<Map<String, Object>> records = new ArrayList<>();
        try (Session session = driver.session()) {
            var result = session.run(cypher, params != null ? params : Map.of());
            while (result.hasNext()) {
                Record record = result.next();
                Map<String, Object> row = new HashMap<>();
                record.keys().forEach(key -> row.put(key, record.get(key).asObject()));
                records.add(row);
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("KG executeCypher done records={} elapsed={}ms", records.size(), elapsed);
        return new KgQueryResult(cypher, records, records.size(), elapsed);
    }

    /**
     * 查找与给定服务相关联的所有已知故障模式节点（知识库检索）。
     *
     * @param serviceName 服务名
     * @return KG 查询结果
     */
    @Retry(name = "neo4j")
    @CircuitBreaker(name = "neo4j")
    public KgQueryResult findFailurePatterns(String serviceName) {
        String cypher = """
                MATCH (s:Service {name: $name})-[:HAS_PATTERN]->(fp:FailurePattern)
                RETURN fp.name AS patternName, fp.description AS description,
                       fp.symptoms AS symptoms, fp.remediation AS remediation
                """;
        return executeCypher(cypher, Map.of("name", serviceName));
    }

    /**
     * 将新事故节点写入知识图谱（供后续检索）。
     *
     * @param incidentId 事故 ID
     * @param service    相关服务名
     * @param rootCause  根因描述
     * @param occurredAt 发生时间
     */
    public void recordIncident(String incidentId, String service, String rootCause, Instant occurredAt) {
        String cypher = """
                MERGE (i:Incident {id: $id})
                SET i.service = $service, i.rootCause = $rootCause,
                    i.occurredAt = $occurredAt, i.updatedAt = $now
                WITH i
                MATCH (s:Service {name: $service})
                MERGE (i)-[:AFFECTS]->(s)
                """;
        try (Session session = driver.session()) {
            session.run(cypher, Map.of(
                    "id", incidentId,
                    "service", service,
                    "rootCause", rootCause,
                    "occurredAt", occurredAt.toString(),
                    "now", Instant.now().toString()
            ));
            log.info("KG recordIncident incidentId={} service={}", incidentId, service);
        }
    }
}
