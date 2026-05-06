package com.huawei.cloud.sre.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.monitor.dto.AlertGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 告警聚合服务。
 *
 * <p>实现三级告警聚合：
 * <ol>
 *   <li><b>时间聚合</b>：将滑动时间窗口（默认 5 分钟）内来自同一服务的告警合并为一组</li>
 *   <li><b>拓扑聚合</b>：将同一逻辑组（服务名前缀相同）的告警归并</li>
 *   <li><b>语义去重</b>：对相似描述（编辑距离 / Jaccard 相似度 ≥ 0.6）的告警去重</li>
 * </ol>
 *
 * <p>静默规则存储于 Redis（Key：{@code monitor:silence:{service}:{metric}}）。
 */
@Service
public class AlertAggregator {

    private static final Logger log = LoggerFactory.getLogger(AlertAggregator.class);
    private static final String SILENCE_KEY_PREFIX = "monitor:silence:";
    private static final String ACTIVE_ALERTS_KEY = "monitor:active_alerts";
    private static final double SIMILARITY_THRESHOLD = 0.6;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration timeWindow;

    /**
     * @param redisTemplate       Redis 模板
     * @param objectMapper        JSON 序列化
     * @param windowMinutes       时间窗口大小（分钟，默认 5）
     */
    public AlertAggregator(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${monitor.alert.window-minutes:5}") int windowMinutes
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.timeWindow = Duration.ofMinutes(windowMinutes);
    }

    /**
     * 对给定告警列表执行三级聚合，返回去重后的告警分组。
     *
     * @param alerts 原始告警列表，每个元素为 Map，包含 id/service/metric/severity/message/timestamp
     * @return 聚合后的告警分组列表
     */
    public List<AlertGroup> aggregate(List<Map<String, Object>> alerts) {
        log.info("AlertAggregator.aggregate alertCount={}", alerts.size());
        if (alerts.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> phase1 = timeBasedAggregation(alerts);
        List<Map<String, Object>> phase2 = topologyBasedAggregation(phase1);
        List<AlertGroup> result = buildGroups(phase2);

        log.info("AlertAggregator.aggregate done inputCount={} groupCount={}", alerts.size(), result.size());
        return result;
    }

    /**
     * 为指定服务/指标设置静默规则。
     *
     * @param service  服务名
     * @param metric   指标名（传 "*" 静默该服务所有指标）
     * @param duration 静默持续时间
     */
    public void silence(String service, String metric, Duration duration) {
        String key = SILENCE_KEY_PREFIX + service + ":" + metric;
        redisTemplate.opsForValue().set(key, "silenced", duration);
        log.info("AlertAggregator.silence service={} metric={} duration={}", service, metric, duration);
    }

    /**
     * 取消指定服务/指标的静默。
     *
     * @param service 服务名
     * @param metric  指标名
     */
    public void unsilence(String service, String metric) {
        String key = SILENCE_KEY_PREFIX + service + ":" + metric;
        redisTemplate.delete(key);
        log.info("AlertAggregator.unsilence service={} metric={}", service, metric);
    }

    /**
     * 判断指定告警是否处于静默状态。
     *
     * @param service 服务名
     * @param metric  指标名
     * @return true 表示已被静默
     */
    public boolean isSilenced(String service, String metric) {
        String specificKey = SILENCE_KEY_PREFIX + service + ":" + metric;
        String wildcardKey = SILENCE_KEY_PREFIX + service + ":*";
        return Boolean.TRUE.equals(redisTemplate.hasKey(specificKey))
                || Boolean.TRUE.equals(redisTemplate.hasKey(wildcardKey));
    }

    /**
     * 将告警存入 Redis 活跃告警集合。
     *
     * @param alertId   告警 ID
     * @param alertJson 告警 JSON 字符串
     */
    public void recordActiveAlert(String alertId, String alertJson) {
        try {
            redisTemplate.opsForHash().put(ACTIVE_ALERTS_KEY, alertId, alertJson);
        } catch (Exception e) {
            log.warn("Failed to record active alert {}: {}", alertId, e.getMessage());
        }
    }

    /**
     * 获取当前活跃告警列表。
     *
     * @return 活跃告警 Map（alertId → JSON）
     */
    public Map<String, String> getActiveAlerts() {
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(ACTIVE_ALERTS_KEY);
            Map<String, String> result = new HashMap<>();
            entries.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        } catch (Exception e) {
            log.warn("Failed to get active alerts: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 从活跃告警中移除已解决的告警。
     *
     * @param alertId 告警 ID
     */
    public void resolveAlert(String alertId) {
        redisTemplate.opsForHash().delete(ACTIVE_ALERTS_KEY, alertId);
    }

    // ── Phase 1: 时间窗口聚合 ──────────────────────────────────────────────────

    private List<Map<String, Object>> timeBasedAggregation(List<Map<String, Object>> alerts) {
        Instant windowStart = Instant.now().minus(timeWindow);
        List<Map<String, Object>> recent = new ArrayList<>();

        for (Map<String, Object> alert : alerts) {
            Instant ts = parseTimestamp(alert.getOrDefault("timestamp", "").toString());
            if (ts.isAfter(windowStart)) {
                recent.add(alert);
            }
        }

        Map<String, List<Map<String, Object>>> byServiceMetric = new HashMap<>();
        for (Map<String, Object> alert : recent) {
            String key = alert.getOrDefault("service", "unknown") + ":"
                    + alert.getOrDefault("metric", "unknown");
            byServiceMetric.computeIfAbsent(key, k -> new ArrayList<>()).add(alert);
        }

        List<Map<String, Object>> aggregated = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byServiceMetric.entrySet()) {
            if (entry.getValue().size() == 1) {
                aggregated.add(entry.getValue().get(0));
            } else {
                aggregated.add(mergeAlerts(entry.getValue(), "TIME"));
            }
        }
        return aggregated;
    }

    // ── Phase 2: 拓扑聚合 ─────────────────────────────────────────────────────

    private List<Map<String, Object>> topologyBasedAggregation(List<Map<String, Object>> alerts) {
        Map<String, List<Map<String, Object>>> byServicePrefix = new HashMap<>();
        for (Map<String, Object> alert : alerts) {
            String service = String.valueOf(alert.getOrDefault("service", "unknown"));
            String prefix = servicePrefix(service);
            byServicePrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(alert);
        }

        List<Map<String, Object>> aggregated = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byServicePrefix.entrySet()) {
            if (entry.getValue().size() == 1) {
                aggregated.add(entry.getValue().get(0));
            } else {
                aggregated.add(mergeAlerts(entry.getValue(), "TOPOLOGY"));
            }
        }
        return aggregated;
    }

    // ── Phase 3: 语义去重 ─────────────────────────────────────────────────────

    private List<AlertGroup> buildGroups(List<Map<String, Object>> alerts) {
        List<AlertGroup> groups = new ArrayList<>();
        Set<Integer> processed = new HashSet<>();

        for (int i = 0; i < alerts.size(); i++) {
            if (processed.contains(i)) continue;

            Map<String, Object> alert = alerts.get(i);
            String service = String.valueOf(alert.getOrDefault("service", "unknown"));
            String severity = String.valueOf(alert.getOrDefault("severity", "MEDIUM"));
            String message = String.valueOf(alert.getOrDefault("message", ""));
            String aggregationType = String.valueOf(alert.getOrDefault("aggregationType", "SEMANTIC"));
            Object alertIdObj = alert.getOrDefault("id", UUID.randomUUID().toString());
            Instant ts = parseTimestamp(alert.getOrDefault("timestamp", "").toString());

            List<String> groupAlertIds = new ArrayList<>();
            groupAlertIds.add(String.valueOf(alertIdObj));
            processed.add(i);
            Instant firstOccurrence = ts;
            Instant lastOccurrence = ts;

            for (int j = i + 1; j < alerts.size(); j++) {
                if (processed.contains(j)) continue;
                Map<String, Object> other = alerts.get(j);
                String otherMsg = String.valueOf(other.getOrDefault("message", ""));
                if (jaccardSimilarity(message, otherMsg) >= SIMILARITY_THRESHOLD) {
                    groupAlertIds.add(String.valueOf(other.getOrDefault("id", UUID.randomUUID())));
                    processed.add(j);
                    Instant otherTs = parseTimestamp(other.getOrDefault("timestamp", "").toString());
                    if (otherTs.isBefore(firstOccurrence)) firstOccurrence = otherTs;
                    if (otherTs.isAfter(lastOccurrence)) lastOccurrence = otherTs;
                }
            }

            boolean suppressed = isSilenced(service,
                    String.valueOf(alert.getOrDefault("metric", "*")));

            groups.add(new AlertGroup(
                    UUID.randomUUID().toString(),
                    service,
                    message.length() > 200 ? message.substring(0, 200) : message,
                    severity,
                    groupAlertIds,
                    groupAlertIds.size(),
                    firstOccurrence,
                    lastOccurrence,
                    aggregationType,
                    suppressed
            ));
        }
        return groups;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private Map<String, Object> mergeAlerts(List<Map<String, Object>> alerts, String type) {
        Map<String, Object> merged = new HashMap<>(alerts.get(0));
        List<String> ids = new ArrayList<>();
        String highestSeverity = "LOW";
        for (Map<String, Object> a : alerts) {
            ids.add(String.valueOf(a.getOrDefault("id", UUID.randomUUID())));
            highestSeverity = higherSeverity(highestSeverity,
                    String.valueOf(a.getOrDefault("severity", "LOW")));
        }
        merged.put("id", String.join(",", ids));
        merged.put("severity", highestSeverity);
        merged.put("aggregationType", type);
        merged.put("mergedCount", alerts.size());
        return merged;
    }

    private String servicePrefix(String service) {
        int dashIdx = service.lastIndexOf('-');
        return dashIdx > 0 ? service.substring(0, dashIdx) : service;
    }

    private double jaccardSimilarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> setA = new HashSet<>(List.of(a.toLowerCase().split("\\s+")));
        Set<String> setB = new HashSet<>(List.of(b.toLowerCase().split("\\s+")));
        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private String higherSeverity(String a, String b) {
        List<String> order = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
        return order.indexOf(b) > order.indexOf(a) ? b : a;
    }

    private Instant parseTimestamp(String ts) {
        try {
            return Instant.parse(ts);
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
