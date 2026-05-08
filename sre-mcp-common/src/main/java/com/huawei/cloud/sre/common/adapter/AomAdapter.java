package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.dto.MetricResult;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.aom.v2.AomClient;
import com.huaweicloud.sdk.aom.v2.model.DeleteAlarmRuleRequest;
import com.huaweicloud.sdk.aom.v2.model.Dimension;
import com.huaweicloud.sdk.aom.v2.model.EventQueryParam2;
import com.huaweicloud.sdk.aom.v2.model.ListAlarmRuleRequest;
import com.huaweicloud.sdk.aom.v2.model.ListEventsRequest;
import com.huaweicloud.sdk.aom.v2.model.MetricQueryMetricParam;
import com.huaweicloud.sdk.aom.v2.model.QueryMetricDataParam;
import com.huaweicloud.sdk.aom.v2.model.ShowAlarmRuleRequest;
import com.huaweicloud.sdk.aom.v2.model.ShowMetricsDataRequest;
import com.huaweicloud.sdk.aom.v2.region.AomRegion;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 AOM（应用运维管理）适配器。
 *
 * <p>封装 AOM v2 指标查询、告警事件查询与告警规则管理 API，
 * 提供 Resilience4j 熔断重试与 Micrometer 埋点。
 */
@Component
public class AomAdapter {

    private static final Logger log = LoggerFactory.getLogger(AomAdapter.class);
    private static final String SERVICE_NAME = "AOM";

    private final AomClient client;
    private final MeterRegistry meterRegistry;

    /**
     * Spring 生产构造器。
     *
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public AomAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        this.client = AomClient.newBuilder()
                .withCredential(credentialProvider.getCredentials())
                .withRegion(AomRegion.valueOf(region))
                .build();
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock AomClient。 */
    AomAdapter(AomClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 查询指定服务的性能指标时间序列数据。
     *
     * @param service    服务名称，如 user-service
     * @param metricName 指标名称，如 cpu_usage_idle
     * @param startTime  查询开始时间
     * @param endTime    查询结束时间
     * @param period     采样间隔（秒），如 60
     * @return 指标查询结果，含时序数据点列表
     * @throws HuaweiCloudException 若 AOM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public MetricResult queryMetric(
            String service, String metricName, Instant startTime, Instant endTime, int period
    ) {
        log.info("AOM queryMetric service={} metric={} start={} end={}", service, metricName, startTime, endTime);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            long startMs = startTime.toEpochMilli();
            long endMs = endTime.toEpochMilli();
            String timerange = startMs + "," + endMs + "," + (period * 1000L);

            var dimension = new Dimension().withName("appName").withValue(service);
            var metricParam = new MetricQueryMetricParam()
                    .withNamespace("PAAS.CONTAINER")
                    .withMetricName(metricName)
                    .withDimensions(List.of(dimension));

            var body = new QueryMetricDataParam()
                    .withMetrics(List.of(metricParam))
                    .withPeriod(period)
                    .withStatistics(List.of("average"))
                    .withTimerange(timerange);

            var response = client.showMetricsData(new ShowMetricsDataRequest().withBody(body));

            List<MetricResult.DataPoint> dataPoints = List.of();
            if (response.getMetrics() != null && !response.getMetrics().isEmpty()) {
                var firstMetric = response.getMetrics().get(0);
                if (firstMetric.getDataPoints() != null) {
                    dataPoints = firstMetric.getDataPoints().stream()
                            .map(dp -> {
                                double value = 0.0;
                                if (dp.getStatistics() != null && !dp.getStatistics().isEmpty()) {
                                    Double v = dp.getStatistics().get(0).getValue();
                                    if (v != null) {
                                        value = v;
                                    }
                                }
                                return new MetricResult.DataPoint(
                                        Instant.ofEpochMilli(dp.getTimestamp() != null ? dp.getTimestamp() : 0L),
                                        value);
                            })
                            .toList();
                }
            }

            log.info("AOM queryMetric success service={} metric={} points={}", service, metricName, dataPoints.size());
            return new MetricResult(service, metricName, startTime, endTime, "unknown", dataPoints);
        } catch (ServiceResponseException e) {
            log.error("AOM queryMetric failed service={} metric={} httpStatus={}", service, metricName, e.getHttpStatusCode());
            throw new HuaweiCloudException(SERVICE_NAME, Messages.get("err.aom.metric.query", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e);
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "queryMetric"));
        }
    }

    /**
     * 查询华为云 AOM 当前活跃告警事件列表。
     *
     * @param recentMinutes 查询最近多少分钟内的活跃告警（1-1440）
     * @return 告警事件列表，每个元素包含 id、startsAt、severity、eventName、service、message 等字段
     * @throws HuaweiCloudException 若 AOM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, Object>> listActiveAlarms(int recentMinutes) {
        int clampedMinutes = Math.max(1, Math.min(recentMinutes, 1440));
        log.info("AOM listActiveAlarms recentMinutes={}", clampedMinutes);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var body = new EventQueryParam2()
                    .withTimeRange("-1.-1." + clampedMinutes);
            var request = new ListEventsRequest()
                    .withType(ListEventsRequest.TypeEnum.ACTIVE_ALERT)
                    .withBody(body);

            var response = client.listEvents(request);
            List<Map<String, Object>> result = new ArrayList<>();
            if (response.getEvents() != null) {
                for (var event : response.getEvents()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", event.getId());
                    item.put("startsAt", event.getStartsAt());
                    item.put("endsAt", event.getEndsAt());
                    if (event.getMetadata() != null) {
                        item.put("eventName", event.getMetadata().get("event_name"));
                        item.put("severity", event.getMetadata().get("event_severity"));
                        item.put("resourceType", event.getMetadata().get("resource_type"));
                        item.put("resourceId", event.getMetadata().get("resource_id"));
                        item.put("resourceProvider", event.getMetadata().get("resource_provider"));
                        item.put("metadata", event.getMetadata());
                    }
                    if (event.getAnnotations() != null) {
                        item.put("message", event.getAnnotations().get("message"));
                        item.put("annotations", event.getAnnotations());
                    }
                    result.add(item);
                }
            }

            log.info("AOM listActiveAlarms success count={}", result.size());
            return result;
        } catch (ServiceResponseException e) {
            log.error("AOM listActiveAlarms failed httpStatus={} errorCode={}", e.getHttpStatusCode(), e.getErrorCode());
            throw new HuaweiCloudException(SERVICE_NAME, Messages.get("err.aom.alarm.query", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e);
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listActiveAlarms"));
        }
    }

    /**
     * 列出 AOM 告警规则列表。
     *
     * @param limit 最大返回条数（1-1000）
     * @return 告警规则列表，每项包含 alarmRuleId、alarmRuleName、alarmLevel、namespace、metricName、stateValue 字段
     * @throws HuaweiCloudException 若 AOM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listAlarmRules(int limit) {
        int clampedLimit = Math.max(1, Math.min(limit, 1000));
        log.info("AOM listAlarmRules limit={}", clampedLimit);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListAlarmRuleRequest().withLimit(clampedLimit);
            var response = client.listAlarmRule(request);

            List<Map<String, String>> rules = new ArrayList<>();
            if (response.getThresholds() != null) {
                for (var rule : response.getThresholds()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("alarmRuleId", rule.getAlarmRuleId() != null ? rule.getAlarmRuleId() : "");
                    item.put("alarmRuleName", rule.getAlarmRuleName() != null ? rule.getAlarmRuleName() : "");
                    item.put("alarmLevel", rule.getAlarmLevel() != null ? rule.getAlarmLevel() : "");
                    item.put("alarmDescription", rule.getAlarmDescription() != null ? rule.getAlarmDescription() : "");
                    item.put("namespace", rule.getNamespace() != null ? rule.getNamespace() : "");
                    item.put("metricName", rule.getMetricName() != null ? rule.getMetricName() : "");
                    item.put("comparisonOperator", rule.getComparisonOperator() != null ? rule.getComparisonOperator() : "");
                    item.put("stateReason", rule.getStateReason() != null ? rule.getStateReason() : "");
                    item.put("stateUpdatedAt", rule.getStateUpdatedTimestamp() != null ? rule.getStateUpdatedTimestamp() : "");
                    item.put("enabled", rule.getIdTurnOn() != null ? rule.getIdTurnOn().toString() : "true");
                    rules.add(item);
                }
            }
            log.info("AOM listAlarmRules success count={}", rules.size());
            return rules;
        } catch (ServiceResponseException e) {
            log.error("AOM listAlarmRules failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(SERVICE_NAME, Messages.get("err.aom.alarm.rule.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e);
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listAlarmRules"));
        }
    }

    /**
     * 查询指定告警规则详情。
     *
     * @param alarmRuleId 告警规则 ID
     * @return 告警规则详情 Map
     * @throws HuaweiCloudException 若 AOM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public Map<String, String> getAlarmRule(String alarmRuleId) {
        log.info("AOM getAlarmRule alarmRuleId={}", alarmRuleId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ShowAlarmRuleRequest().withAlarmRuleId(alarmRuleId);
            var response = client.showAlarmRule(request);
            var thresholds = response.getThresholds();

            Map<String, String> result = new HashMap<>();
            if (thresholds != null && !thresholds.isEmpty()) {
                var rule = thresholds.get(0);
                result.put("alarmRuleId", rule.getAlarmRuleId() != null ? rule.getAlarmRuleId() : "");
                result.put("alarmRuleName", rule.getAlarmRuleName() != null ? rule.getAlarmRuleName() : "");
                result.put("alarmLevel", rule.getAlarmLevel() != null ? rule.getAlarmLevel() : "");
                result.put("alarmDescription", rule.getAlarmDescription() != null ? rule.getAlarmDescription() : "");
                result.put("namespace", rule.getNamespace() != null ? rule.getNamespace() : "");
                result.put("metricName", rule.getMetricName() != null ? rule.getMetricName() : "");
                result.put("stateReason", rule.getStateReason() != null ? rule.getStateReason() : "");
                result.put("stateUpdatedAt", rule.getStateUpdatedTimestamp() != null ? rule.getStateUpdatedTimestamp() : "");
                result.put("enabled", rule.getIdTurnOn() != null ? rule.getIdTurnOn().toString() : "true");
                result.put("alarmActions", rule.getAlarmActions() != null ? String.join(",", rule.getAlarmActions()) : "");
            }
            log.info("AOM getAlarmRule success alarmRuleId={}", alarmRuleId);
            return result;
        } catch (ServiceResponseException e) {
            log.error("AOM getAlarmRule failed alarmRuleId={} httpStatus={}", alarmRuleId, e.getHttpStatusCode());
            throw new HuaweiCloudException(SERVICE_NAME, Messages.get("err.aom.alarm.rule.get", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e);
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "getAlarmRule"));
        }
    }

    /**
     * 删除指定告警规则。
     *
     * @param alarmRuleId 告警规则 ID
     * @throws HuaweiCloudException 若 AOM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public void deleteAlarmRule(String alarmRuleId) {
        log.info("AOM deleteAlarmRule alarmRuleId={}", alarmRuleId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            client.deleteAlarmRule(new DeleteAlarmRuleRequest().withAlarmRuleId(alarmRuleId));
            log.info("AOM deleteAlarmRule success alarmRuleId={}", alarmRuleId);
        } catch (ServiceResponseException e) {
            log.error("AOM deleteAlarmRule failed alarmRuleId={} httpStatus={}", alarmRuleId, e.getHttpStatusCode());
            throw new HuaweiCloudException(SERVICE_NAME, Messages.get("err.aom.alarm.rule.delete", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e);
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "deleteAlarmRule"));
        }
    }
}
