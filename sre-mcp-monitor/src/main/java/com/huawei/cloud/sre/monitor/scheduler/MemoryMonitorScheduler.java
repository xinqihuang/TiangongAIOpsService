package com.huawei.cloud.sre.monitor.scheduler;

import com.huawei.cloud.sre.common.adapter.CesAdapter;
import com.huawei.cloud.sre.monitor.dto.AlertHandlingResult;
import com.huawei.cloud.sre.monitor.dto.MemoryAlert;
import com.huawei.cloud.sre.monitor.service.EmergencyPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存使用率定时巡检任务。
 *
 * <p>每隔 {@code monitor.memory.check-interval-ms}（默认 5 分钟）执行一次，
 * 通过 CES 批量查询 ECS / RDS / DCS / CCE 等组件的内存使用率，
 * 对超过阈值（默认 90%）的实例触发 RAG 应急预案检索或 LLM 自主决策。
 *
 * <h3>去重策略</h3>
 * <p>同一实例在 {@code monitor.memory.alert-dedup-minutes}（默认 15 分钟）内只触发一次处置，
 * 避免因持续高内存产生大量重复 LLM 调用。
 *
 * <h3>告警存储</h3>
 * <p>最近 {@value #MAX_STORED_ALERTS} 条告警保存在内存环形缓冲区，
 * 可通过 MCP Tool {@code getMemoryAlerts} 查询。
 */
@Component
public class MemoryMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryMonitorScheduler.class);
    private static final int MAX_STORED_ALERTS = 200;

    /** 需要监控的组件类型及其 CES 指标配置。 */
    private record ComponentConfig(
            String type,
            String namespace,
            String metricName,
            String dimensionName
    ) {}

    private static final List<ComponentConfig> COMPONENTS = List.of(
            new ComponentConfig("ECS", "SYS.ECS",  "mem_usedPercent",    "instance_id"),
            new ComponentConfig("RDS", "SYS.RDS",  "rds002_mem_util",    "rds_instance_id"),
            new ComponentConfig("DCS", "SYS.DCS",  "memory_usage_ratio", "dcs_instance_id"),
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

    private final CesAdapter cesAdapter;
    private final EmergencyPlanService emergencyPlanService;

    /** 环形缓冲区，存储最近 N 条告警（最新在队首）。 */
    private final Deque<MemoryAlert> recentAlerts = new ArrayDeque<>(MAX_STORED_ALERTS);
    private final ReentrantLock alertsLock = new ReentrantLock();

    /** 去重 Map：key = "ComponentType:instanceId"，value = 上次告警时间。 */
    private final ConcurrentHashMap<String, Instant> dedupMap = new ConcurrentHashMap<>();

    /**
     * @param cesAdapter           华为云 CES 适配器
     * @param emergencyPlanService RAG + LLM 告警处置服务
     */
    public MemoryMonitorScheduler(CesAdapter cesAdapter, EmergencyPlanService emergencyPlanService) {
        this.cesAdapter = cesAdapter;
        this.emergencyPlanService = emergencyPlanService;
    }

    /**
     * 定时内存巡检入口，间隔由 {@code monitor.memory.check-interval-ms} 控制（默认 5 分钟）。
     * 使用 fixedDelay 确保上一次执行完成后才开始下一次，避免 CES API 并发过载。
     */
    @Scheduled(fixedDelayString = "${monitor.memory.check-interval-ms:300000}")
    public void checkMemoryUsage() {
        if (!enabled) {
            log.debug("MemoryMonitorScheduler disabled, skipping");
            return;
        }
        log.info("MemoryMonitorScheduler starting memory check threshold={}%", threshold);

        int totalFound = 0;
        for (ComponentConfig comp : COMPONENTS) {
            int found = checkComponent(comp);
            totalFound += found;
        }

        // Clean up stale dedup entries
        cleanupDedup();
        log.info("MemoryMonitorScheduler done totalHighMemoryInstances={}", totalFound);
    }

    /**
     * 返回最近检测到的高内存告警列表（最新在前，最多 {@value #MAX_STORED_ALERTS} 条）。
     *
     * @return 不可变快照
     */
    public List<MemoryAlert> getRecentAlerts() {
        alertsLock.lock();
        try {
            return List.copyOf(recentAlerts);
        } finally {
            alertsLock.unlock();
        }
    }

    /** 返回当前去重窗口内已触发告警的实例数量（用于监控/调试）。 */
    public int getDedupMapSize() {
        return dedupMap.size();
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
        String dedupKey = comp.type() + ":" + instance.instanceId();

        // Dedup check
        Instant lastAlert = dedupMap.get(dedupKey);
        if (lastAlert != null &&
                lastAlert.plusSeconds((long) dedupMinutes * 60).isAfter(Instant.now())) {
            log.debug("MemoryMonitorScheduler: skip dedup instanceId={} lastAlert={}", instance.instanceId(), lastAlert);
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

        // Store alert
        MemoryAlert alert = new MemoryAlert(
                instance.instanceId(),
                comp.type(),
                comp.namespace(),
                instance.value(),
                threshold,
                Instant.now(),
                handling
        );
        storeAlert(alert);

        // Mark as alerted
        dedupMap.put(dedupKey, Instant.now());
    }

    private String buildAlertDescription(ComponentConfig comp, CesAdapter.InstanceMetricValue instance) {
        return String.format(
                "%s 内存使用率高告警 | namespace=%s metric=%s instance=%s value=%.1f%% threshold=%.1f%%",
                comp.type(), comp.namespace(), comp.metricName(),
                instance.instanceId(), instance.value(), threshold
        );
    }

    private String severityOf(double memPercent) {
        if (memPercent >= 95) return "CRITICAL";
        if (memPercent >= 90) return "HIGH";
        return "MEDIUM";
    }

    private void storeAlert(MemoryAlert alert) {
        alertsLock.lock();
        try {
            recentAlerts.addFirst(alert);
            while (recentAlerts.size() > MAX_STORED_ALERTS) {
                recentAlerts.removeLast();
            }
        } finally {
            alertsLock.unlock();
        }
    }

    private void cleanupDedup() {
        Instant cutoff = Instant.now().minusSeconds((long) dedupMinutes * 60);
        dedupMap.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
        log.debug("MemoryMonitorScheduler: dedup map size after cleanup={}", dedupMap.size());
    }
}
