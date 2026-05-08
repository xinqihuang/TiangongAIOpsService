package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.cts.v3.CtsClient;
import com.huaweicloud.sdk.cts.v3.model.ListTracesRequest;
import com.huaweicloud.sdk.cts.v3.model.ListTrackersRequest;
import com.huaweicloud.sdk.cts.v3.model.Traces;
import com.huaweicloud.sdk.cts.v3.region.CtsRegion;
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
 * 华为云 CTS（云审计服务）适配器。
 *
 * <p>提供操作审计日志查询能力，用于 RCA 中的变更关联分析，
 * 帮助判断故障是否与近期变更操作相关。
 */
@Component
public class CtsAdapter {

    private static final Logger log = LoggerFactory.getLogger(CtsAdapter.class);
    private static final String SERVICE_NAME = "CTS";

    private final CtsClient client;
    private final MeterRegistry meterRegistry;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public CtsAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        CtsClient tempClient = null;
        try {
            tempClient = CtsClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(CtsRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("CtsAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock CtsClient。 */
    CtsAdapter(CtsClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 查询指定时间范围内的操作审计事件。
     *
     * @param resourceType 资源类型，如 "ecs"、"cce"，传 null 则查所有类型
     * @param resourceName 资源名称，传 null 则不过滤
     * @param startTime    查询开始时间
     * @param endTime      查询结束时间
     * @param limit        最大返回条数（1-200）
     * @return 审计事件列表，每项包含 eventName、resourceType、resourceName、time、user 字段
     * @throws HuaweiCloudException 若 CTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> queryTraces(
            String resourceType,
            String resourceName,
            Instant startTime,
            Instant endTime,
            int limit
    ) {
        requireClient();
        log.info("CTS queryTraces resourceType={} resourceName={} start={} end={}", resourceType, resourceName, startTime, endTime);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListTracesRequest()
                    .withTraceType(ListTracesRequest.TraceTypeEnum.SYSTEM)
                    .withLimit(limit)
                    .withFrom(startTime.toEpochMilli())
                    .withTo(endTime.toEpochMilli());

            if (resourceType != null) {
                request.withResourceType(resourceType);
            }
            if (resourceName != null) {
                request.withResourceName(resourceName);
            }

            var response = client.listTraces(request);
            return extractTraces(response.getTraces());
        } catch (ServiceResponseException e) {
            log.error("CTS queryTraces failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.cts.trace.query", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "queryTraces"));
        }
    }

    /**
     * 查询指定用户在时间范围内的操作审计事件。
     *
     * @param userName  IAM 用户名
     * @param startTime 查询开始时间
     * @param endTime   查询结束时间
     * @param limit     最大返回条数（1-200）
     * @return 审计事件列表
     * @throws HuaweiCloudException 若 CTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> queryByUser(
            String userName, Instant startTime, Instant endTime, int limit
    ) {
        requireClient();
        log.info("CTS queryByUser userName={} start={} end={}", userName, startTime, endTime);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListTracesRequest()
                    .withTraceType(ListTracesRequest.TraceTypeEnum.SYSTEM)
                    .withUser(userName)
                    .withLimit(limit)
                    .withFrom(startTime.toEpochMilli())
                    .withTo(endTime.toEpochMilli());

            var response = client.listTraces(request);
            List<Map<String, String>> traces = extractTraces(response.getTraces());
            log.info("CTS queryByUser success userName={} count={}", userName, traces.size());
            return traces;
        } catch (ServiceResponseException e) {
            log.error("CTS queryByUser failed userName={} httpStatus={}", userName, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.cts.trace.user", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "queryByUser"));
        }
    }

    /**
     * 查询指定资源类型在时间范围内的变更操作（CREATE、UPDATE、DELETE）。
     *
     * @param resourceType 资源类型，如 "ecs"、"rds"
     * @param startTime    查询开始时间
     * @param endTime      查询结束时间
     * @param limit        最大返回条数（1-200）
     * @return 变更事件列表，每项包含 eventName、resourceType、resourceName、time、user 字段
     * @throws HuaweiCloudException 若 CTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> queryResourceChanges(
            String resourceType, Instant startTime, Instant endTime, int limit
    ) {
        requireClient();
        log.info("CTS queryResourceChanges resourceType={} start={} end={}", resourceType, startTime, endTime);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListTracesRequest()
                    .withTraceType(ListTracesRequest.TraceTypeEnum.SYSTEM)
                    .withResourceType(resourceType)
                    .withLimit(limit)
                    .withFrom(startTime.toEpochMilli())
                    .withTo(endTime.toEpochMilli());

            var response = client.listTraces(request);
            // Filter to change operations only
            List<Map<String, String>> all = extractTraces(response.getTraces());
            List<Map<String, String>> changes = all.stream()
                    .filter(t -> {
                        String name = t.getOrDefault("eventName", "").toLowerCase();
                        return name.contains("create") || name.contains("update") || name.contains("delete")
                                || name.contains("scale") || name.contains("restart") || name.contains("reboot");
                    })
                    .toList();
            log.info("CTS queryResourceChanges success resourceType={} total={} changes={}", resourceType, all.size(), changes.size());
            return changes;
        } catch (ServiceResponseException e) {
            log.error("CTS queryResourceChanges failed resourceType={} httpStatus={}", resourceType, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.cts.resource.change", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "queryResourceChanges"));
        }
    }

    /**
     * 列出当前项目下所有审计追踪器（Tracker）。
     *
     * @return 追踪器列表，每项包含 trackerName、trackerType、status、bucketName 字段
     * @throws HuaweiCloudException 若 CTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listTrackers() {
        requireClient();
        log.info("CTS listTrackers");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var response = client.listTrackers(new ListTrackersRequest());

            List<Map<String, String>> trackers = new ArrayList<>();
            if (response.getTrackers() != null) {
                for (var tracker : response.getTrackers()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("trackerName", tracker.getTrackerName() != null ? tracker.getTrackerName() : "");
                    item.put("trackerType", tracker.getTrackerType() != null ? tracker.getTrackerType().getValue() : "");
                    item.put("id", tracker.getId() != null ? tracker.getId() : "");
                    item.put("createTime", tracker.getCreateTime() != null ? tracker.getCreateTime().toString() : "");
                    trackers.add(item);
                }
            }
            log.info("CTS listTrackers success count={}", trackers.size());
            return trackers;
        } catch (ServiceResponseException e) {
            log.error("CTS listTrackers failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.cts.tracker.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listTrackers"));
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private List<Map<String, String>> extractTraces(List<Traces> rawTraces) {
        if (rawTraces == null) return List.of();
        List<Map<String, String>> events = new ArrayList<>();
        for (Traces trace : rawTraces) {
            Map<String, String> item = new HashMap<>();
            item.put("eventName", trace.getTraceName() != null ? trace.getTraceName() : "");
            item.put("resourceType", trace.getResourceType() != null ? trace.getResourceType() : "");
            item.put("resourceName", trace.getResourceName() != null ? trace.getResourceName() : "");
            item.put("resourceId", trace.getResourceId() != null ? trace.getResourceId() : "");
            item.put("time", trace.getTime() != null ? trace.getTime().toString() : "");
            item.put("user", trace.getUser() != null && trace.getUser().getName() != null
                    ? trace.getUser().getName() : "unknown");
            item.put("traceRating", trace.getTraceRating() != null ? trace.getTraceRating().getValue() : "");
            item.put("traceId", trace.getTraceId() != null ? trace.getTraceId() : "");
            item.put("code", trace.getCode() != null ? trace.getCode() : "");
            events.add(item);
        }
        log.info("CTS queryTraces success count={}", events.size());
        return events;
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "CTS adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
