package com.huawei.cloud.sre.monitor.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.cloud.sre.common.adapter.CesAdapter;
import com.huawei.cloud.sre.common.util.Messages;
import com.huawei.cloud.sre.monitor.dto.AlertHandlingResult;
import com.huawei.cloud.sre.monitor.dto.MemoryAlert;
import com.huawei.cloud.sre.monitor.service.EmergencyPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存使用率定时巡检任务（无状态版本，所有状态存储于 Redis）。
 *
 * <p>每隔 {@code monitor.memory.check-interval-ms}（默认 5 分钟）执行一次，
 * 通过 CES 批量查询 ECS / RDS / DCS / CCE 等组件的内存使用率，
 * 对超过阈值（默认 90%）的实例触发 RAG 应急预案检索或 LLM 自主决策。
 *
 * <h3>分布式去重策略</h3>
 * <p>同一实例在 {@code monitor.memory.alert-dedup-minutes}（默认 15 分钟）内只触发一次处置。
 * 去重状态存储在 Redis（Key TTL = dedupMinutes），多副本间共享，不会产生重复告警。
 *
 * <h3>分布式调度锁</h3>
 * <p>多副本部署时，通过 Redis SETNX 抢占调度锁，只有一个副本执行巡检，
 * 锁 TTL = checkIntervalMs，副本崩溃后锁自动过期，其他副本可接管。
 *
 * <h3>告警存储</h3>
 * <p>最近 {@value #MAX_STORED_ALERTS} 条告警保存在 Redis List（{@value #REDIS_ALERTS_KEY}），
 * 可通过 MCP Tool {@code getMemoryAlerts} 查询。
 */
@Component
public class MemoryMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryMonitorScheduler.class);
    private static final int MAX_STORED_ALERTS = 200;

    static final String REDIS_ALERTS_KEY = "monitor:memory:recent-alerts";
    private static final String REDIS_DEDUP_PREFIX = "monitor:memory:dedup:";
    private static final String REDIS_LOCK_KEY = "monitor:memory:lock";

    /** 需要监控的组件类型及其 CES 指标配置。 */
    private record ComponentConfig(
            String type,
            String namespace,
            String metricName,
            String dimensionName
    ) {}

    private static final List<ComponentConfig> COMPONENTS = List.of(
            new ComponentConfig("ECS", "SYS.ECS",  "mem_usedPercent",         "instance_id"),
            new ComponentConfig("RDS", "SYS.RDS",  "rds002_mem_util",         "rds_instance_id"),
            new ComponentConfig("DCS", "SYS.DCS",  "memory_usage_ratio",      "dcs_instance_id"),
            new ComponentConfig("CCE", "SYS.CCE",  "node_memory_utilization", "node_id")
    );

    @Value("${monitor.memory.enabled:true}")
    private boolean enabled;

    @Value("${monitor.memory.threshold:90.0}")
    private double threshold;

    @Value("${monitor.memory.lookback-seconds:600}")
    private int lookbackSeconds;

    @Value("${monitor.memory.alert-dedup-minutes:15}")
    private int dedupMinutes;

    @Value("${monitor.memory.rag-threshold:0.65}")
    private double ragThreshold;

    @Value("${monitor.memory.check-interval-ms:300000}")
    private long checkIntervalMs;

    /** Pod 名称，由 Kubernetes Downward API 注入，用作分布式锁持有者标识。 */
    @Value("${POD_NAME:localhost}")
    private String podName;

    private final CesAdapter cesAdapter;
    private final EmergencyPlanService emergencyPlanService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * @param cesAdapter           华为云 CES 适配器
     * @param emergencyPlanService RAG + LLM 告警处置服务
     * @param redisTemplate        Redis 操作模板（用于分布式锁、去重、告警存储）
     * @param objectMapper         JSON 序列化工具
     */
    public MemoryMonitorScheduler(CesAdapter cesAdapter,
                                  EmergencyPlanService emergencyPlanService,
                                  StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper) {
        this.cesAdapter = cesAdapter;
        this.emergencyPlanService = emergencyPlanService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 定时内存巡检入口，间隔由 {@code monitor.memory.check-interval-ms} 控制（默认 5 分钟）。
     *
     * <p>多副本部署时，通过 Redis SETNX 分布式锁确保只有一个副本执行巡检，
     * 避免重复调用 CES API 和 LLM，同时防止产生重复告警。
     */
    @Scheduled(fixedDelayString = "${monitor.memory.check-interval-ms:300000}")
    public void checkMemoryUsage() {
        if (!enabled) {
            log.debug("MemoryMonitorScheduler disabled, skipping");
            return;
        }

        // Distributed lock — only one replica runs per interval
        Duration lockTtl = Duration.ofMillis(checkIntervalMs);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(REDIS_LOCK_KEY, podName, lockTtl);
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("MemoryMonitorScheduler: lock held by another replica, skipping this cycle");
            return;
        }

        log.info("MemoryMonitorScheduler starting memory check threshold={}% pod={}", threshold, podName);
        int totalFound = 0;
        for (ComponentConfig comp : COMPONENTS) {
            int found = checkComponent(comp);
            totalFound += found;
        }
        log.info("MemoryMonitorScheduler done totalHighMemoryInstances={}", totalFound);
    }

    /**
     * 返回最近检测到的高内存告警列表（最新在前，最多 {@value #MAX_STORED_ALERTS} 条）。
     *
     * <p>数据从 Redis List 读取，多副本共享同一视图。
     *
     * @return 不可变快照
     */
    public List<MemoryAlert> getRecentAlerts() {
        try {
            List<String> jsons = redisTemplate.opsForList()
                    .range(REDIS_ALERTS_KEY, 0, MAX_STORED_ALERTS - 1);
            if (jsons == null || jsons.isEmpty()) return List.of();
            List<MemoryAlert> alerts = new ArrayList<>(jsons.size());
            for (String json : jsons) {
                alerts.add(objectMapper.readValue(json, MemoryAlert.class));
            }
            return List.copyOf(alerts);
        } catch (Exception e) {
            log.error("MemoryMonitorScheduler: failed to read alerts from Redis: {}", e.getMessage());
            return List.of();
        }
    }

    /** 返回当前去重窗口内已触发告警的实例数量（用于监控/调试）。 */
    public int getDedupMapSize() {
        try {
            var keys = redisTemplate.keys(REDIS_DEDUP_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.warn("MemoryMonitorScheduler: failed to count dedup keys: {}", e.getMessage());
            return -1;
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private int checkComponent(ComponentConfig comp) {
        try {
            List<CesAdapter.InstanceMetricValue> highInstances = cesAdapter.findHighInstances(
                    comp.namespace(), comp.metricName(), comp.dimensionName(),
                    threshold, lookbackSeconds
            );

            if (highInstances.isEmpty()) {
                log.debug("MemoryMonitorScheduler: {} — no instances above threshold", comp.type());
                return 0;
            }

            log.warn("MemoryMonitorScheduler: {} has {} instance(s) with memory > {}%",
                    comp.type(), highInstances.size(), threshold);

            for (CesAdapter.InstanceMetricValue instance : highInstances) {
                processHighMemoryInstance(comp, instance);
            }
            return highInstances.size();

        } catch (Exception e) {
            log.warn("MemoryMonitorScheduler: failed to check {} — {}", comp.type(), e.getMessage());
            return 0;
        }
    }

    private void processHighMemoryInstance(ComponentConfig comp, CesAdapter.InstanceMetricValue instance) {
        String dedupKey = REDIS_DEDUP_PREFIX + comp.type() + ":" + instance.instanceId();

        // Distributed dedup check via Redis SETNX with TTL
        Duration dedupTtl = Duration.ofMinutes(dedupMinutes);
        Boolean firstAlert = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", dedupTtl);
        if (!Boolean.TRUE.equals(firstAlert)) {
            log.debug("MemoryMonitorScheduler: skip dedup instanceId={}", instance.instanceId());
            return;
        }

        log.warn("MemoryMonitorScheduler: HIGH MEMORY type={} instanceId={} memory={}%",
                comp.type(), instance.instanceId(), String.format("%.1f", instance.value()));

        // Trigger RAG → LLM handling
        AlertHandlingResult handling = null;
        try {
            String alertDescription = buildAlertDescription(comp, instance);
            handling = emergencyPlanService.handle(alertDescription, comp.type(), severityOf(instance.value()), ragThreshold);
            log.info("MemoryMonitorScheduler: handling result source={} steps={}",
                    handling.source(), handling.responseSteps().size());
        } catch (Exception e) {
            log.error("MemoryMonitorScheduler: EmergencyPlanService failed instanceId={}: {}",
                    instance.instanceId(), e.getMessage());
        }

        storeAlert(new MemoryAlert(
                instance.instanceId(),
                comp.type(),
                comp.namespace(),
                instance.value(),
                threshold,
                Instant.now(),
                handling
        ));
    }

    private String buildAlertDescription(ComponentConfig comp, CesAdapter.InstanceMetricValue instance) {
        return Messages.get("monitor.memory.alert.description",
                comp.type(), comp.namespace(), comp.metricName(),
                instance.instanceId(), instance.value(), threshold);
    }

    private String severityOf(double memPercent) {
        if (memPercent >= 95) return "CRITICAL";
        if (memPercent >= 90) return "HIGH";
        return "MEDIUM";
    }

    private void storeAlert(MemoryAlert alert) {
        try {
            String json = objectMapper.writeValueAsString(alert);
            redisTemplate.opsForList().leftPush(REDIS_ALERTS_KEY, json);
            redisTemplate.opsForList().trim(REDIS_ALERTS_KEY, 0, MAX_STORED_ALERTS - 1);
            redisTemplate.expire(REDIS_ALERTS_KEY, Duration.ofDays(1));
        } catch (Exception e) {
            log.error("MemoryMonitorScheduler: failed to store alert to Redis: {}", e.getMessage());
        }
    }
}
