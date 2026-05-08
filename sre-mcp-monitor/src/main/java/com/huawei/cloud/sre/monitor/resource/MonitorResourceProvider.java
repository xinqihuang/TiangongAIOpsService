package com.huawei.cloud.sre.monitor.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.monitor.dto.BaselineStatus;
import com.huawei.cloud.sre.monitor.repository.AlertRuleRepository;
import com.huawei.cloud.sre.monitor.repository.BaselineRepository;
import com.huawei.cloud.sre.monitor.service.AlertAggregator;
import com.huawei.cloud.sre.monitor.service.BaselineEngine;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitor MCP Resource 注册提供者。
 *
 * <p>提供 4 个 MCP Resource：
 * <ul>
 *   <li>{@code sre://monitor/baselines} — 所有服务的基线状态摘要</li>
 *   <li>{@code sre://monitor/alert-rules} — 当前启用的告警规则列表</li>
 *   <li>{@code sre://monitor/active-alerts} — 当前活跃告警</li>
 *   <li>{@code sre://monitor/anomaly-summary} — 近期异常检测摘要</li>
 * </ul>
 */
@Component
public class MonitorResourceProvider {

    private static final Logger log = LoggerFactory.getLogger(MonitorResourceProvider.class);

    private final BaselineRepository baselineRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final AlertAggregator alertAggregator;
    private final ObjectMapper objectMapper;

    /**
     * @param baselineRepository  基线 JPA 仓库
     * @param alertRuleRepository 告警规则 JPA 仓库
     * @param alertAggregator     告警聚合服务
     * @param objectMapper        JSON 序列化
     */
    public MonitorResourceProvider(BaselineRepository baselineRepository,
                                   AlertRuleRepository alertRuleRepository,
                                   AlertAggregator alertAggregator,
                                   ObjectMapper objectMapper) {
        this.baselineRepository = baselineRepository;
        this.alertRuleRepository = alertRuleRepository;
        this.alertAggregator = alertAggregator;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建并返回 4 个 MCP Resource 注册规范。
     *
     * @return SyncResourceSpecification 列表
     */
    public List<McpServerFeatures.SyncResourceSpecification> registrations() {
        List<McpServerFeatures.SyncResourceSpecification> regs = new ArrayList<>();
        regs.add(baselinesResource());
        regs.add(alertRulesResource());
        regs.add(activeAlertsResource());
        regs.add(anomalySummaryResource());
        log.info("MonitorResourceProvider registered {} resources", regs.size());
        return regs;
    }

    private McpServerFeatures.SyncResourceSpecification baselinesResource() {
        var resource = new McpSchema.Resource(
                "sre://monitor/baselines",
                "Monitor Baselines",
                "Dynamic baseline status for all services, including mean, standard deviation, and alert bounds",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, req) -> {
            try {
                var entities = baselineRepository.findAll();
                List<Map<String, Object>> summaries = new ArrayList<>();
                for (var e : entities) {
                    double stdDev = Math.sqrt(e.getEwmaVariance());
                    Map<String, Object> m = new HashMap<>();
                    m.put("service", e.getService());
                    m.put("metric", e.getMetric());
                    m.put("mean", e.getEwmaMean());
                    m.put("stdDev", stdDev);
                    m.put("upperBound", e.getEwmaMean() + 3 * stdDev);
                    m.put("lowerBound", e.getEwmaMean() - 3 * stdDev);
                    m.put("samples", e.getSampleCount());
                    m.put("stable", e.getSampleCount() >= 30);
                    m.put("lastUpdated", e.getLastUpdated() != null ? e.getLastUpdated().toString() : "");
                    summaries.add(m);
                }
                String json = objectMapper.writeValueAsString(Map.of(
                        "baselines", summaries, "count", summaries.size(),
                        "generatedAt", Instant.now().toString()));
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://monitor/baselines", "application/json", json)));
            } catch (Exception e) {
                log.error("Failed to read baselines resource", e);
                return errorResult("sre://monitor/baselines", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification alertRulesResource() {
        var resource = new McpSchema.Resource(
                "sre://monitor/alert-rules",
                "Alert Rules",
                "Currently enabled alert rules, including threshold, severity level, and notification configuration",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, req) -> {
            try {
                var rules = alertRuleRepository.findByEnabledTrue();
                List<Map<String, Object>> ruleList = new ArrayList<>();
                for (var r : rules) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("ruleId", r.getRuleId());
                    m.put("name", r.getName());
                    m.put("service", r.getService());
                    m.put("metric", r.getMetric());
                    m.put("condition", r.getCondition());
                    m.put("threshold", r.getThreshold());
                    m.put("severity", r.getSeverity());
                    m.put("topicUrn", r.getTopicUrn() != null ? r.getTopicUrn() : "");
                    ruleList.add(m);
                }
                String json = objectMapper.writeValueAsString(Map.of(
                        "rules", ruleList, "count", ruleList.size(),
                        "generatedAt", Instant.now().toString()));
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://monitor/alert-rules", "application/json", json)));
            } catch (Exception e) {
                log.error("Failed to read alert-rules resource", e);
                return errorResult("sre://monitor/alert-rules", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification activeAlertsResource() {
        var resource = new McpSchema.Resource(
                "sre://monitor/active-alerts",
                "Active Alerts",
                "Currently active (unresolved) alerts, including alert content and trigger time",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, req) -> {
            try {
                Map<String, String> activeAlerts = alertAggregator.getActiveAlerts();
                String json = objectMapper.writeValueAsString(Map.of(
                        "alerts", activeAlerts,
                        "count", activeAlerts.size(),
                        "generatedAt", Instant.now().toString()));
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://monitor/active-alerts", "application/json", json)));
            } catch (Exception e) {
                log.error("Failed to read active-alerts resource", e);
                return errorResult("sre://monitor/active-alerts", e.getMessage());
            }
        });
    }

    private McpServerFeatures.SyncResourceSpecification anomalySummaryResource() {
        var resource = new McpSchema.Resource(
                "sre://monitor/anomaly-summary",
                "Anomaly Summary",
                "Recent anomaly detection summary: unstable baselines and high-risk services",
                "application/json",
                null
        );
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, req) -> {
            try {
                var unstable = baselineRepository.findUnstableBaselines(30);
                List<Map<String, Object>> unstableList = unstable.stream().map(e -> Map.of(
                        "service", (Object) e.getService(),
                        "metric", e.getMetric(),
                        "samples", e.getSampleCount(),
                        "mean", e.getEwmaMean()
                )).toList();
                String json = objectMapper.writeValueAsString(Map.of(
                        "unstableBaselines", unstableList,
                        "unstableCount", unstableList.size(),
                        "note", "Baselines with < 30 samples are not stable enough for anomaly detection",
                        "generatedAt", Instant.now().toString()));
                return new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents("sre://monitor/anomaly-summary", "application/json", json)));
            } catch (Exception e) {
                log.error("Failed to read anomaly-summary resource", e);
                return errorResult("sre://monitor/anomaly-summary", e.getMessage());
            }
        });
    }

    private McpSchema.ReadResourceResult errorResult(String uri, String error) {
        return new McpSchema.ReadResourceResult(List.of(
                new McpSchema.TextResourceContents(uri, "application/json",
                        "{\"error\":\"" + error + "\"}")));
    }
}
