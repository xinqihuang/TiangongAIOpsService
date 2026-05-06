package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.cts.v3.CtsClient;
import com.huaweicloud.sdk.cts.v3.model.ListTracesRequest;
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

            List<Map<String, String>> events = List.of();
            if (response.getTraces() != null) {
                events = response.getTraces().stream()
                        .map(t -> Map.of(
                                "eventName", t.getTraceName() != null ? t.getTraceName() : "",
                                "resourceType", t.getResourceType() != null ? t.getResourceType() : "",
                                "resourceName", t.getResourceName() != null ? t.getResourceName() : "",
                                "time", t.getTime() != null ? t.getTime().toString() : "",
                                "user", t.getUser() != null && t.getUser().getName() != null ? t.getUser().getName() : "unknown"
                        ))
                        .toList();
            }
            log.info("CTS queryTraces success count={}", events.size());
            return events;
        } catch (ServiceResponseException e) {
            log.error("CTS queryTraces failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "CTS 审计事件查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "queryTraces"));
        }
    }
    /** 检查 client 是否可用（区域不支持时为 null）。*/
    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException("Cts", "Cts adapter not available in current region", 503, "REGION_NOT_SUPPORTED", null, null);
        }
    }

}