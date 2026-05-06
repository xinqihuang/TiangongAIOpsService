package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.ces.v1.CesClient;
import com.huaweicloud.sdk.ces.v1.model.BatchListMetricDataRequest;
import com.huaweicloud.sdk.ces.v1.model.BatchListMetricDataRequestBody;
import com.huaweicloud.sdk.ces.v1.model.BatchMetricData;
import com.huaweicloud.sdk.ces.v1.model.BatchPeriod;
import com.huaweicloud.sdk.ces.v1.model.DatapointForBatchMetric;
import com.huaweicloud.sdk.ces.v1.model.Filter;
import com.huaweicloud.sdk.ces.v1.model.ListMetricsRequest;
import com.huaweicloud.sdk.ces.v1.model.MetricInfo;
import com.huaweicloud.sdk.ces.v1.model.MetricInfoList;
import com.huaweicloud.sdk.ces.v1.model.MetricsDimension;
import com.huaweicloud.sdk.ces.v1.region.CesRegion;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 CES（Cloud Eye 云监控）适配器。
 *
 * <p>封装 CES v1 指标列表与批量查询 API，提供：
 * <ul>
 *   <li>{@link #listInstances} — 发现某命名空间下上报了指定指标的所有实例 ID</li>
 *   <li>{@link #batchQueryAverage} — 批量查询实例最新平均值（单次最多 {@value #BATCH_SIZE} 个）</li>
 *   <li>{@link #findHighInstances} — 组合以上两步，返回超过阈值的实例列表</li>
 * </ul>
 */
@Component
public class CesAdapter {

    private static final Logger log = LoggerFactory.getLogger(CesAdapter.class);
    private static final String SERVICE_NAME = "CES";
    /** CES 批量查询单次上限。 */
    private static final int BATCH_SIZE = 500;

    private final CesClient client;
    private final MeterRegistry meterRegistry;

    /**
     * 单个实例的指标采样值。
     *
     * @param instanceId 实例 ID（dimension value）
     * @param value      指标值（如内存使用率百分比）
     */
    public record InstanceMetricValue(String instanceId, double value) {}

    /**
     * Spring 生产构造器。
     *
     * @param credentialProvider 华为云凭证
     * @param region             区域，如 cn-north-9
     * @param meterRegistry      Micrometer 注册表
     */
    @Autowired
    public CesAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        CesClient tempClient = null;
        try {
            tempClient = CesClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(CesRegion.valueOf(region))
                    .build();
            log.info("CesAdapter initialized region={}", region);
        } catch (Exception e) {
            log.warn("CesAdapter disabled (region not supported or credentials invalid): {}", e.getMessage());
        }
        this.client = tempClient;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器。 */
    CesAdapter(CesClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /** 适配器是否可用（当前 region 支持 CES 且凭证有效）。 */
    public boolean isAvailable() {
        return client != null;
    }

    /**
     * 列出在某命名空间下上报了指定指标的所有实例 ID（最多 1000 个）。
     *
     * <p>原理：调用 CES {@code ListMetrics} 接口，按 namespace + metric_name 过滤，
     * 从 dimensions 中提取 {@code dimensionName} 对应的 value 作为实例 ID。
     *
     * @param namespace     命名空间，如 SYS.ECS / SYS.RDS / SYS.DCS
     * @param metricName    指标名，如 mem_usedPercent
     * @param dimensionName dimension 键名，如 instance_id / rds_instance_id
     * @return 实例 ID 列表
     * @throws HuaweiCloudException 若 API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<String> listInstances(String namespace, String metricName, String dimensionName) {
        requireClient();
        log.debug("CES listInstances namespace={} metric={}", namespace, metricName);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListMetricsRequest()
                    .withNamespace(namespace)
                    .withMetricName(metricName)
                    .withLimit(1000);

            var response = client.listMetrics(request);
            if (response.getMetrics() == null) return List.of();

            List<String> ids = new ArrayList<>();
            for (MetricInfoList metric : response.getMetrics()) {
                if (metric.getDimensions() == null) continue;
                metric.getDimensions().stream()
                        .filter(d -> dimensionName.equals(d.getName()) && d.getValue() != null)
                        .map(MetricsDimension::getValue)
                        .findFirst()
                        .ifPresent(ids::add);
            }

            if (ids.size() == 1000) {
                log.warn("CES listInstances returned max 1000 results; environment may have more");
            }
            log.debug("CES listInstances found {} instances namespace={} metric={}", ids.size(), namespace, metricName);
            return ids;
        } catch (ServiceResponseException e) {
            log.error("CES listInstances failed namespace={} metric={} httpStatus={}",
                    namespace, metricName, e.getHttpStatusCode());
            throw new HuaweiCloudException(SERVICE_NAME, "CES 实例列表查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e);
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listInstances"));
        }
    }

    /**
     * 批量查询多个实例在最近 {@code lookbackSeconds} 秒内的平均指标值。
     *
     * <p>单次请求最多 {@value #BATCH_SIZE} 个实例，超出时自动分批。
     *
     * @param namespace       命名空间
     * @param metricName      指标名
     * @param dimensionName   dimension 键名
     * @param instanceIds     实例 ID 列表
     * @param lookbackSeconds 回溯时间窗口（秒），建议 300-600
     * @return instanceId → 平均值 Map；无数据的实例不在 Map 中
     */
    public Map<String, Double> batchQueryAverage(
            String namespace, String metricName, String dimensionName,
            List<String> instanceIds, int lookbackSeconds
    ) {
        requireClient();
        Map<String, Double> result = new HashMap<>();
        if (instanceIds.isEmpty()) return result;

        long toMs = Instant.now().toEpochMilli();
        long fromMs = toMs - (long) lookbackSeconds * 1000;

        for (int i = 0; i < instanceIds.size(); i += BATCH_SIZE) {
            List<String> batch = instanceIds.subList(i, Math.min(i + BATCH_SIZE, instanceIds.size()));
            result.putAll(queryBatch(namespace, metricName, dimensionName, batch, fromMs, toMs));
        }
        return result;
    }

    /**
     * 发现并返回指标值超过阈值的实例列表（按值降序）。
     *
     * <p>组合 {@link #listInstances} + {@link #batchQueryAverage}。
     *
     * @param namespace       命名空间
     * @param metricName      指标名（如 mem_usedPercent）
     * @param dimensionName   dimension 键名
     * @param threshold       阈值（如 90.0 表示 90%）
     * @param lookbackSeconds 回溯时间窗口
     * @return 超过阈值的实例列表，值越大越靠前
     */
    public List<InstanceMetricValue> findHighInstances(
            String namespace, String metricName, String dimensionName,
            double threshold, int lookbackSeconds
    ) {
        if (!isAvailable()) {
            log.warn("CesAdapter not available, skipping findHighInstances namespace={}", namespace);
            return List.of();
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<String> ids = listInstances(namespace, metricName, dimensionName);
            if (ids.isEmpty()) return List.of();

            Map<String, Double> values = batchQueryAverage(namespace, metricName, dimensionName, ids, lookbackSeconds);
            return values.entrySet().stream()
                    .filter(e -> e.getValue() > threshold)
                    .map(e -> new InstanceMetricValue(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingDouble(InstanceMetricValue::value).reversed())
                    .toList();
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "findHighInstances"));
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    private Map<String, Double> queryBatch(
            String namespace, String metricName, String dimensionName,
            List<String> instanceIds, long fromMs, long toMs
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            List<MetricInfo> metricInfos = instanceIds.stream()
                    .map(id -> new MetricInfo()
                            .withNamespace(namespace)
                            .withMetricName(metricName)
                            .withDimensions(List.of(
                                    new MetricsDimension().withName(dimensionName).withValue(id))))
                    .toList();

            var body = new BatchListMetricDataRequestBody()
                    .withMetrics(metricInfos)
                    .withFrom(fromMs)
                    .withTo(toMs)
                    .withPeriod(BatchPeriod._300)
                    .withFilter(Filter.AVERAGE);

            var response = client.batchListMetricData(new BatchListMetricDataRequest().withBody(body));
            if (response.getMetrics() == null) return Map.of();

            Map<String, Double> result = new HashMap<>();
            for (BatchMetricData metricData : response.getMetrics()) {
                String instanceId = extractDimValue(metricData.getDimensions(), dimensionName);
                if (instanceId == null) continue;

                List<DatapointForBatchMetric> dps = metricData.getDatapoints();
                if (dps == null || dps.isEmpty()) continue;

                dps.stream()
                        .filter(dp -> dp.getTimestamp() != null)
                        .max(Comparator.comparingLong(DatapointForBatchMetric::getTimestamp))
                        .map(DatapointForBatchMetric::getAverage)
                        .filter(v -> v != null && !Double.isNaN(v))
                        .ifPresent(v -> result.put(instanceId, v));
            }
            return result;
        } catch (ServiceResponseException e) {
            log.error("CES batchQueryAverage failed namespace={} metric={} httpStatus={}",
                    namespace, metricName, e.getHttpStatusCode());
            throw new HuaweiCloudException(SERVICE_NAME, "CES 批量指标查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e);
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "batchQueryAverage"));
        }
    }

    private String extractDimValue(List<MetricsDimension> dims, String dimName) {
        if (dims == null) return null;
        return dims.stream()
                .filter(d -> dimName.equals(d.getName()))
                .map(MetricsDimension::getValue)
                .findFirst()
                .orElse(null);
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "CES adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
