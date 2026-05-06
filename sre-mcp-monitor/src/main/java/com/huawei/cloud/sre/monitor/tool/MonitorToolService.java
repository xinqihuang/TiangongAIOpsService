package com.huawei.cloud.sre.monitor.tool;

import com.huawei.cloud.sre.common.adapter.AomAdapter;
import com.huawei.cloud.sre.common.adapter.SmnAdapter;
import com.huawei.cloud.sre.common.dto.MetricResult;
import com.huawei.cloud.sre.monitor.dto.AlertGroup;
import com.huawei.cloud.sre.monitor.dto.AlertHandlingResult;
import com.huawei.cloud.sre.monitor.dto.AlertRuleDto;
import com.huawei.cloud.sre.monitor.dto.AnomalyResult;
import com.huawei.cloud.sre.monitor.dto.BaselineStatus;
import com.huawei.cloud.sre.monitor.dto.CapacityForecast;
import com.huawei.cloud.sre.monitor.dto.EmergencyPlan;
import com.huawei.cloud.sre.monitor.dto.MemoryAlert;
import com.huawei.cloud.sre.monitor.dto.NotificationResult;
import com.huawei.cloud.sre.monitor.kafka.MonitorEvent;
import com.huawei.cloud.sre.monitor.kafka.MonitorEventProducer;
import com.huawei.cloud.sre.monitor.repository.AlertRuleEntity;
import com.huawei.cloud.sre.monitor.repository.AlertRuleRepository;
import com.huawei.cloud.sre.monitor.scheduler.MemoryMonitorScheduler;
import com.huawei.cloud.sre.monitor.service.AlertAggregator;
import com.huawei.cloud.sre.monitor.service.BaselineEngine;
import com.huawei.cloud.sre.monitor.service.EmergencyPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Monitor MCP Tool 服务。
 *
 * <p>包含 9 个 {@code @Tool} 方法，提供动态基线管理、异常检测、容量预测、
 * 指标关联分析、告警去重/静默/管理、通知发送等智能监控能力。
 */
@Service
public class MonitorToolService {

    private static final Logger log = LoggerFactory.getLogger(MonitorToolService.class);

    private final BaselineEngine baselineEngine;
    private final AlertAggregator alertAggregator;
    private final AomAdapter aomAdapter;
    private final SmnAdapter smnAdapter;
    private final AlertRuleRepository alertRuleRepository;
    private final MonitorEventProducer eventProducer;
    private final EmergencyPlanService emergencyPlanService;
    private final MemoryMonitorScheduler memoryMonitorScheduler;

    /**
     * @param baselineEngine          动态基线引擎
     * @param alertAggregator         告警聚合服务
     * @param aomAdapter              华为云 AOM 适配器
     * @param smnAdapter              华为云 SMN 适配器
     * @param alertRuleRepository     告警规则仓库
     * @param eventProducer           Kafka 事件生产者
     * @param emergencyPlanService    应急预案 RAG 服务
     * @param memoryMonitorScheduler  内存巡检定时任务
     */
    public MonitorToolService(
            BaselineEngine baselineEngine,
            AlertAggregator alertAggregator,
            AomAdapter aomAdapter,
            SmnAdapter smnAdapter,
            AlertRuleRepository alertRuleRepository,
            MonitorEventProducer eventProducer,
            EmergencyPlanService emergencyPlanService,
            MemoryMonitorScheduler memoryMonitorScheduler
    ) {
        this.baselineEngine = baselineEngine;
        this.alertAggregator = alertAggregator;
        this.aomAdapter = aomAdapter;
        this.smnAdapter = smnAdapter;
        this.alertRuleRepository = alertRuleRepository;
        this.eventProducer = eventProducer;
        this.emergencyPlanService = emergencyPlanService;
        this.memoryMonitorScheduler = memoryMonitorScheduler;
    }

    /**
     * 使用最新观测值更新指定服务/指标的动态基线（EWMA + 3σ 算法）。
     * 应在每次采集到新指标数据后调用，保持基线持续学习。
     *
     * @param service      服务名称，如 order-service
     * @param metric       指标名称，如 cpu_usage、http_latency_p99
     * @param observations 最新观测值列表（按时间升序），如 [45.2, 46.1, 47.8]
     * @return 更新后的基线状态，含均值、标准差、上下界
     */
    @Tool(description = "更新指定服务/指标的动态基线（EWMA算法），提交新观测值使基线持续学习")
    public BaselineStatus updateBaseline(
            @ToolParam(description = "服务名称，如 order-service") String service,
            @ToolParam(description = "指标名称，如 cpu_usage、http_latency_p99") String metric,
            @ToolParam(description = "最新观测值列表（按时间升序），如 [45.2, 46.1, 47.8]") List<Double> observations
    ) {
        log.info("Tool[updateBaseline] service={} metric={} count={}", service, metric, observations.size());
        BaselineStatus status = baselineEngine.updateBaseline(service, metric, observations);

        MonitorEvent event = new MonitorEvent(UUID.randomUUID().toString(),
                "BASELINE_UPDATED", service, metric, "LOW",
                "Baseline updated: mean=" + String.format("%.4f", status.ewmaMean()),
                Map.of("mean", status.ewmaMean(), "stdDev", status.stdDev(),
                        "samples", status.sampleCount()),
                Instant.now());
        eventProducer.publishBaselineUpdate(event);

        log.info("Tool[updateBaseline] done service={} metric={} stable={}", service, metric, status.isStable());
        return status;
    }

    /**
     * 检测指定服务/指标的当前值是否偏离动态基线（3σ 准则）。
     * 用于实时告警判断，返回异常类型（SPIKE/DROP/NORMAL）和严重程度。
     *
     * @param service      服务名称
     * @param metric       指标名称
     * @param currentValue 当前观测值
     * @return 异常检测结果，含是否异常、偏差 sigma、告警建议
     */
    @Tool(description = "检测指定服务/指标当前值是否偏离动态基线（3σ准则），返回异常类型和严重程度")
    public AnomalyResult detectAnomaly(
            @ToolParam(description = "服务名称") String service,
            @ToolParam(description = "指标名称") String metric,
            @ToolParam(description = "当前观测值") double currentValue
    ) {
        log.info("Tool[detectAnomaly] service={} metric={} value={}", service, metric, currentValue);
        AnomalyResult result = baselineEngine.detectAnomaly(service, metric, currentValue);

        if (result.isAnomaly()) {
            MonitorEvent event = new MonitorEvent(UUID.randomUUID().toString(),
                    "ANOMALY_DETECTED", service, metric, result.severity(),
                    result.suggestion(),
                    Map.of("sigma", result.deviationSigma(), "value", currentValue,
                            "mean", result.baselineMean()),
                    Instant.now());
            eventProducer.publishAlert(event);
        }

        log.info("Tool[detectAnomaly] done service={} metric={} isAnomaly={} sigma={}",
                service, metric, result.isAnomaly(), result.deviationSigma());
        return result;
    }

    /**
     * 基于历史数据预测指定服务/指标未来 N 小时的容量走势（线性外推）。
     * 用于主动容量规划，识别潜在的资源耗尽风险。
     *
     * @param service        服务名称
     * @param metric         指标名称，如 disk_usage_percent、memory_usage
     * @param startTime      历史数据查询起始时间（ISO-8601，如 2025-01-01T00:00:00Z）
     * @param forecastHours  预测时间范围（小时，1-168）
     * @return 容量预测结果，含趋势、斜率、预计耗尽时间、扩容建议
     */
    @Tool(description = "基于历史数据预测服务容量走势，识别潜在资源耗尽风险，生成扩容建议")
    public CapacityForecast forecastCapacity(
            @ToolParam(description = "服务名称") String service,
            @ToolParam(description = "指标名称，如 disk_usage_percent、memory_usage") String metric,
            @ToolParam(description = "历史数据查询起始时间（ISO-8601）") String startTime,
            @ToolParam(description = "预测时间范围（小时，1-168）") int forecastHours
    ) {
        log.info("Tool[forecastCapacity] service={} metric={} start={} hours={}", service, metric, startTime, forecastHours);
        int clampedHours = Math.max(1, Math.min(forecastHours, 168));

        List<Double> historicalData;
        try {
            Instant start = Instant.parse(startTime);
            MetricResult metricResult = aomAdapter.queryMetric(service, metric, start, Instant.now(), 3600);
            historicalData = metricResult.dataPoints().stream()
                    .map(MetricResult.DataPoint::value)
                    .toList();
        } catch (Exception e) {
            log.warn("Tool[forecastCapacity] failed to query AOM metrics, using empty data: {}", e.getMessage());
            historicalData = List.of();
        }

        CapacityForecast forecast = baselineEngine.forecastCapacity(service, metric, historicalData, clampedHours);
        log.info("Tool[forecastCapacity] done service={} metric={} trend={} risk={}",
                service, metric, forecast.trend(), forecast.hasCapacityRisk());
        return forecast;
    }

    /**
     * 分析多个指标之间的相关性，识别具有共同根因的指标群。
     * 适用于告警风暴时快速定位核心异常指标。
     *
     * @param service  服务名称
     * @param metrics  需要关联分析的指标名称列表（最多 10 个）
     * @param timeRange 分析时间窗口（分钟）
     * @return 指标相关性分析结果（Map，含相关指标对及相关系数）
     */
    @Tool(description = "分析多个指标之间的相关性，识别具有共同根因的指标群，用于告警风暴定位")
    public Map<String, Object> correlateMetrics(
            @ToolParam(description = "服务名称") String service,
            @ToolParam(description = "需关联分析的指标名称列表（最多10个）") List<String> metrics,
            @ToolParam(description = "分析时间窗口（分钟）") int timeRange
    ) {
        log.info("Tool[correlateMetrics] service={} metrics={} range={}min", service, metrics, timeRange);
        List<String> limitedMetrics = metrics.size() > 10 ? metrics.subList(0, 10) : metrics;
        Instant start = Instant.now().minus(Duration.ofMinutes(timeRange));

        Map<String, List<Double>> metricSeries = new HashMap<>();
        for (String m : limitedMetrics) {
            try {
                MetricResult result = aomAdapter.queryMetric(service, m, start, Instant.now(), 60);
                metricSeries.put(m, result.dataPoints().stream()
                        .map(MetricResult.DataPoint::value).toList());
            } catch (Exception e) {
                log.warn("correlateMetrics failed to fetch metric={}: {}", m, e.getMessage());
                metricSeries.put(m, List.of());
            }
        }

        List<Map<String, Object>> correlations = new ArrayList<>();
        List<String> metricList = new ArrayList<>(metricSeries.keySet());
        for (int i = 0; i < metricList.size(); i++) {
            for (int j = i + 1; j < metricList.size(); j++) {
                String mA = metricList.get(i);
                String mB = metricList.get(j);
                List<Double> seriesA = metricSeries.get(mA);
                List<Double> seriesB = metricSeries.get(mB);
                double corr = pearsonCorrelation(seriesA, seriesB);
                correlations.add(Map.of("metricA", mA, "metricB", mB,
                        "correlation", corr, "strength", correlationStrength(corr)));
            }
        }

        correlations.sort((a, b) -> Double.compare(
                Math.abs((Double) b.get("correlation")),
                Math.abs((Double) a.get("correlation"))));

        Map<String, Object> result = new HashMap<>();
        result.put("service", service);
        result.put("analyzedMetrics", limitedMetrics);
        result.put("correlations", correlations);
        result.put("topCorrelatedPair", correlations.isEmpty() ? null : correlations.get(0));
        result.put("analysisTime", Instant.now().toString());
        log.info("Tool[correlateMetrics] done service={} pairs={}", service, correlations.size());
        return result;
    }

    /**
     * 对给定告警列表执行三级聚合去重（时间/拓扑/语义），减少告警噪声。
     * 输入原始告警，输出聚合后的告警分组，标注重复和关联关系。
     *
     * @param alerts 原始告警列表（每个 Map 含 id/service/metric/severity/message/timestamp 字段）
     * @return 聚合后的告警分组列表
     */
    @Tool(description = "对告警列表执行时间/拓扑/语义三级聚合去重，减少告警噪声，返回聚合分组")
    public List<AlertGroup> dedupAlerts(
            @ToolParam(description = "原始告警列表，每个元素含 id/service/metric/severity/message/timestamp 字段") List<Map<String, Object>> alerts
    ) {
        log.info("Tool[dedupAlerts] alertCount={}", alerts.size());
        List<AlertGroup> groups = alertAggregator.aggregate(alerts);
        log.info("Tool[dedupAlerts] done groups={}", groups.size());
        return groups;
    }

    /**
     * 创建、更新或删除告警规则。
     * 规则持久化到 PostgreSQL，用于自动化告警阈值触发和通知路由。
     *
     * @param action    操作类型：CREATE / UPDATE / DELETE / LIST
     * @param ruleId    规则 ID（UPDATE/DELETE 时必填）
     * @param name      规则名称（CREATE 时必填）
     * @param service   监控服务名（CREATE 时必填）
     * @param metric    监控指标名（CREATE 时必填）
     * @param condition 触发条件，如 "> 90"（CREATE 时必填）
     * @return 操作结果（包含规则列表或操作确认）
     */
    @Tool(description = "创建/更新/删除/列出告警规则，规则持久化到数据库，用于自动化告警阈值触发")
    public Map<String, Object> manageAlertRule(
            @ToolParam(description = "操作：CREATE / UPDATE / DELETE / LIST") String action,
            @ToolParam(description = "规则ID，UPDATE/DELETE 时必填，CREATE 时忽略") String ruleId,
            @ToolParam(description = "规则名称，CREATE 时必填") String name,
            @ToolParam(description = "监控服务名，CREATE 时必填") String service,
            @ToolParam(description = "监控指标名，CREATE 时必填") String metric,
            @ToolParam(description = "触发条件和阈值，格式为 \"> 90\" 表示超过90时触发") String condition
    ) {
        log.info("Tool[manageAlertRule] action={} ruleId={} service={}", action, ruleId, service);
        return switch (action.toUpperCase()) {
            case "CREATE" -> createRule(name, service, metric, condition);
            case "UPDATE" -> updateRule(ruleId, condition);
            case "DELETE" -> deleteRule(ruleId);
            case "LIST" -> listRules(service);
            default -> Map.of("error", "Unknown action: " + action, "validActions", List.of("CREATE", "UPDATE", "DELETE", "LIST"));
        };
    }

    /**
     * 为指定服务/指标设置静默规则，在维护窗口期间抑制告警通知。
     *
     * @param service        服务名称（传 "*" 表示所有服务）
     * @param metric         指标名称（传 "*" 表示该服务所有指标）
     * @param durationMinutes 静默持续时间（分钟，1-1440）
     * @param reason         静默原因（用于审计）
     * @return 静默操作结果
     */
    @Tool(description = "为服务/指标设置静默规则，在维护窗口抑制告警，支持按服务/指标粒度控制")
    public Map<String, Object> silenceAlerts(
            @ToolParam(description = "服务名称，传 \"*\" 表示所有服务") String service,
            @ToolParam(description = "指标名称，传 \"*\" 表示该服务所有指标") String metric,
            @ToolParam(description = "静默持续时间（分钟，1-1440）") int durationMinutes,
            @ToolParam(description = "静默原因（用于审计）") String reason
    ) {
        log.info("Tool[silenceAlerts] service={} metric={} minutes={} reason={}", service, metric, durationMinutes, reason);
        int clampedMinutes = Math.max(1, Math.min(durationMinutes, 1440));
        alertAggregator.silence(service, metric, Duration.ofMinutes(clampedMinutes));
        log.info("Tool[silenceAlerts] done service={} metric={}", service, metric);
        return Map.of(
                "status", "silenced",
                "service", service,
                "metric", metric,
                "durationMinutes", clampedMinutes,
                "expiresAt", Instant.now().plus(Duration.ofMinutes(clampedMinutes)).toString(),
                "reason", reason
        );
    }

    /**
     * 查询指定服务/指标的当前基线状态，了解基线是否已稳定、当前均值和告警边界。
     *
     * @param service 服务名称（传 "*" 查询所有服务）
     * @param metric  指标名称（传 "*" 查询该服务所有指标）
     * @return 基线状态详情
     */
    @Tool(description = "查询服务/指标的动态基线状态，包含均值、标准差、告警边界、是否已稳定")
    public BaselineStatus getBaselineStatus(
            @ToolParam(description = "服务名称") String service,
            @ToolParam(description = "指标名称") String metric
    ) {
        log.info("Tool[getBaselineStatus] service={} metric={}", service, metric);
        BaselineStatus status = baselineEngine.getBaselineStatus(service, metric);
        log.info("Tool[getBaselineStatus] done service={} metric={} stable={}", service, metric, status.isStable());
        return status;
    }

    /**
     * 从华为云 AOM 查询当前活跃告警事件，用于实时了解系统告警状态。
     *
     * @param recentMinutes 查询最近多少分钟内的活跃告警（1-1440，默认 60）
     * @return 活跃告警列表，每个元素含 id、eventName、severity、resourceType、message、startsAt 等字段
     */
    @Tool(description = "从华为云AOM查询当前活跃告警列表，返回最近N分钟内新产生的告警事件，含严重级别、资源信息和告警消息")
    public List<Map<String, Object>> queryAlerts(
            @ToolParam(description = "查询最近多少分钟内的活跃告警，范围1-1440，默认60") int recentMinutes
    ) {
        int minutes = recentMinutes <= 0 ? 60 : recentMinutes;
        log.info("Tool[queryAlerts] recentMinutes={}", minutes);
        try {
            List<Map<String, Object>> alerts = aomAdapter.listActiveAlarms(minutes);
            log.info("Tool[queryAlerts] done count={}", alerts.size());
            return alerts;
        } catch (Exception e) {
            log.error("Tool[queryAlerts] failed: {}", e.getMessage());
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            error.put("recentMinutes", minutes);
            return List.of(error);
        }
    }

    /**
     * 处理告警：先从 RAG 知识库检索存量应急预案，命中则直接返回预案处置步骤；
     * 未命中则调用大模型进行自主决策，始终保证有可执行的处置建议输出。
     *
     * <p>决策来源由返回结果的 {@code source} 字段标识：
     * {@code RAG_PLAN}（知识库命中）/ {@code LLM_DECISION}（大模型决策）/ {@code FALLBACK}（降级兜底）。
     *
     * @param alertDescription 告警描述，包含告警名称、触发原因、资源信息等，越详细匹配越准确
     * @param service          受影响的服务名称，如 order-service
     * @param severity         告警严重级别：CRITICAL / HIGH / MEDIUM / LOW
     * @param ragThreshold     RAG 命中阈值（0.0-1.0），建议 0.65；低于此值则触发 LLM 自主决策
     * @return 告警处置决策，含处置步骤、决策来源、根因假设、是否需要升级等
     */
    @Tool(description = """
            处理告警：先从RAG知识库检索存量应急预案，命中（相似度≥ragThreshold）则返回预案步骤；
            未命中则由大模型自主决策。返回结果含处置步骤、决策来源(RAG_PLAN/LLM_DECISION)、根因假设。
            """)
    public AlertHandlingResult handleAlert(
            @ToolParam(description = "告警描述，包含告警名称、触发原因、资源等，越详细匹配越准确") String alertDescription,
            @ToolParam(description = "受影响的服务名称，如 order-service") String service,
            @ToolParam(description = "告警严重级别：CRITICAL / HIGH / MEDIUM / LOW") String severity,
            @ToolParam(description = "RAG命中阈值（0.0-1.0），建议0.65；低于此值触发LLM自主决策") double ragThreshold
    ) {
        double threshold = ragThreshold <= 0 ? 0.65 : ragThreshold;
        log.info("Tool[handleAlert] service={} severity={} threshold={}", service, severity, threshold);
        AlertHandlingResult result = emergencyPlanService.handle(alertDescription, service, severity, threshold);
        log.info("Tool[handleAlert] done source={} planTitle={} steps={}",
                result.source(), result.planTitle(), result.responseSteps().size());
        return result;
    }

    /**
     * 将应急预案写入 RAG 向量知识库，后续 {@code handleAlert} 可检索命中。
     *
     * <p>建议在事故复盘后将优化的处置步骤入库，持续丰富知识库。
     *
     * @param title         预案标题，如"order-service OOM 应急预案"
     * @param alertPattern  该预案对应的告警描述模式，越详细向量匹配越精准
     * @param service       适用服务名（"*" 表示通用预案）
     * @param severity      预案对应告警级别：CRITICAL / HIGH / MEDIUM / LOW
     * @param responseSteps 有序处置步骤列表，如 ["检查Pod状态", "重启异常Pod", "通知业务方"]
     * @param rootCauseHint 已知根因提示，供 LLM 参考
     * @param notes         备注（维护人、生效范围等）
     * @return 操作结果，含新建预案 ID
     */
    @Tool(description = "将应急预案写入RAG向量知识库，后续handleAlert告警处理时可检索命中该预案")
    public Map<String, Object> indexEmergencyPlan(
            @ToolParam(description = "预案标题，如\"order-service OOM 应急预案\"") String title,
            @ToolParam(description = "该预案对应的告警描述模式，越详细匹配越精准") String alertPattern,
            @ToolParam(description = "适用服务名，\"*\" 表示通用预案") String service,
            @ToolParam(description = "对应告警级别：CRITICAL / HIGH / MEDIUM / LOW") String severity,
            @ToolParam(description = "有序处置步骤列表，如 [\"检查Pod状态\", \"重启异常Pod\"]") List<String> responseSteps,
            @ToolParam(description = "已知根因提示") String rootCauseHint,
            @ToolParam(description = "备注（维护人、版本等）") String notes
    ) {
        String planId = UUID.randomUUID().toString();
        log.info("Tool[indexEmergencyPlan] planId={} title={}", planId, title);
        try {
            EmergencyPlan plan = new EmergencyPlan(
                    planId, title, alertPattern, service, severity,
                    responseSteps, rootCauseHint, notes, Instant.now()
            );
            emergencyPlanService.indexPlan(plan);
            log.info("Tool[indexEmergencyPlan] done planId={}", planId);
            return Map.of(
                    "status", "indexed",
                    "planId", planId,
                    "title", title,
                    "service", service,
                    "stepsCount", responseSteps.size()
            );
        } catch (Exception e) {
            log.error("Tool[indexEmergencyPlan] failed planId={}: {}", planId, e.getMessage());
            return Map.of("status", "failed", "planId", planId, "error", e.getMessage());
        }
    }

    /**
     * 查询定时内存巡检任务检测到的高内存实例列表（内存使用率超过阈值的 ECS/RDS/DCS/CCE 实例）。
     * 数据来自后台 5 分钟定时任务，每条记录含实例 ID、内存百分比、处置结果。
     *
     * @param componentType 按组件类型过滤：ECS / RDS / DCS / CCE，传 "ALL" 返回全部
     * @param limit         最多返回条数（1-200，默认 20）
     * @return 高内存实例告警列表（最新在前）
     */
    @Tool(description = "查询内存使用率超过阈值的实例列表（ECS/RDS/DCS/CCE），数据来自5分钟定时CES巡检，含处置建议")
    public List<MemoryAlert> getMemoryAlerts(
            @ToolParam(description = "组件类型过滤：ECS / RDS / DCS / CCE，传 \"ALL\" 返回全部") String componentType,
            @ToolParam(description = "最多返回条数（1-200，默认20）") int limit
    ) {
        log.info("Tool[getMemoryAlerts] componentType={} limit={}", componentType, limit);
        int cap = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 200));
        List<MemoryAlert> all = memoryMonitorScheduler.getRecentAlerts();
        List<MemoryAlert> result = all.stream()
                .filter(a -> "ALL".equalsIgnoreCase(componentType) || componentType == null
                        || a.componentType().equalsIgnoreCase(componentType))
                .limit(cap)
                .toList();
        log.info("Tool[getMemoryAlerts] done returned={}", result.size());
        return result;
    }

    /**
     * 通过华为云 SMN 向指定通知主题发送告警通知（支持邮件、短信、Webhook）。
     *
     * @param topicUrn 通知主题 URN，格式：urn:smn:{region}:{project_id}:{topic_name}
     * @param subject  通知标题（邮件主题）
     * @param message  通知内容（支持纯文本和 JSON）
     * @return 通知发送结果，含消息 ID
     */
    @Tool(description = "通过华为云SMN发送告警通知（支持邮件/短信/Webhook），返回消息ID")
    public NotificationResult sendNotification(
            @ToolParam(description = "SMN 主题 URN，格式：urn:smn:{region}:{project_id}:{topic_name}") String topicUrn,
            @ToolParam(description = "通知标题（邮件主题）") String subject,
            @ToolParam(description = "通知内容（纯文本或 JSON）") String message
    ) {
        log.info("Tool[sendNotification] topicUrn={} subject={}", topicUrn, subject);
        try {
            String messageId = smnAdapter.publishMessage(topicUrn, subject, message);
            log.info("Tool[sendNotification] done messageId={}", messageId);
            return NotificationResult.ok(messageId, topicUrn, subject);
        } catch (Exception e) {
            log.error("Tool[sendNotification] failed topicUrn={}: {}", topicUrn, e.getMessage());
            return NotificationResult.failed(topicUrn, subject, e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> createRule(String name, String service, String metric, String condition) {
        double threshold = parseThreshold(condition);
        String severity = threshold >= 90 ? "CRITICAL" : threshold >= 80 ? "HIGH" : "MEDIUM";
        AlertRuleEntity entity = new AlertRuleEntity(name, service, metric, condition, threshold, severity, null);
        alertRuleRepository.save(entity);
        return Map.of("status", "created", "ruleId", entity.getRuleId(), "rule", toDto(entity));
    }

    private Map<String, Object> updateRule(String ruleId, String condition) {
        return alertRuleRepository.findById(ruleId).map(entity -> {
            if (condition != null && !condition.isBlank()) {
                entity.setThreshold(parseThreshold(condition));
            }
            alertRuleRepository.save(entity);
            Map<String, Object> updated = new java.util.HashMap<>();
            updated.put("status", "updated");
            updated.put("ruleId", ruleId);
            updated.put("rule", toDto(entity));
            return updated;
        }).orElse(Map.of("error", "Rule not found: " + ruleId));
    }

    private Map<String, Object> deleteRule(String ruleId) {
        if (alertRuleRepository.existsById(ruleId)) {
            alertRuleRepository.deleteById(ruleId);
            return Map.of("status", "deleted", "ruleId", ruleId);
        }
        return Map.of("error", "Rule not found: " + ruleId);
    }

    private Map<String, Object> listRules(String service) {
        List<AlertRuleEntity> rules = (service == null || service.isBlank() || "*".equals(service))
                ? alertRuleRepository.findByEnabledTrue()
                : alertRuleRepository.findByServiceAndEnabledTrue(service);
        return Map.of("rules", rules.stream().map(this::toDto).toList(), "count", rules.size());
    }

    private AlertRuleDto toDto(AlertRuleEntity e) {
        return new AlertRuleDto(e.getRuleId(), e.getName(), e.getService(), e.getMetric(),
                e.getCondition(), e.getThreshold(), e.getSeverity(), e.isEnabled(),
                e.getTopicUrn(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private double parseThreshold(String condition) {
        if (condition == null) return 0.0;
        try {
            return Double.parseDouble(condition.replaceAll("[^0-9.]", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private double pearsonCorrelation(List<Double> x, List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 2) return 0.0;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x.get(i);
            sumY += y.get(i);
            sumXY += x.get(i) * y.get(i);
            sumX2 += x.get(i) * x.get(i);
            sumY2 += y.get(i) * y.get(i);
        }

        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return Math.abs(den) < 1e-9 ? 0.0 : num / den;
    }

    private String correlationStrength(double corr) {
        double abs = Math.abs(corr);
        if (abs >= 0.8) return "STRONG";
        if (abs >= 0.5) return "MODERATE";
        if (abs >= 0.3) return "WEAK";
        return "NONE";
    }
}
