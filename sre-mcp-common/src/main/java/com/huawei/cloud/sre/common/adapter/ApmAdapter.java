package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.dto.TraceResult;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.apm.v1.ApmClient;
import com.huaweicloud.sdk.apm.v1.model.ShowSpanSearchRequest;
import com.huaweicloud.sdk.apm.v1.model.TraceSearchParam;
import com.huaweicloud.sdk.apm.v1.region.ApmRegion;
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
import java.util.List;

/**
 * 华为云 APM（应用性能管理）适配器。
 *
 * <p>封装 APM v1 链路追踪 API，支持按 Trace ID 查询 Span 列表。
 */
@Component
public class ApmAdapter {

    private static final Logger log = LoggerFactory.getLogger(ApmAdapter.class);
    private static final String SERVICE_NAME = "APM";

    private final ApmClient client;
    private final MeterRegistry meterRegistry;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public ApmAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        ApmClient tempClient = null;
        try {
            tempClient = ApmClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(ApmRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("ApmAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock ApmClient。 */
    ApmAdapter(ApmClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 根据 Trace ID 查询完整链路追踪信息。
     *
     * @param traceId     链路追踪 ID
     * @param businessId  APM 业务 ID（环境归属）
     * @return 链路追踪分析结果，含所有 Span 信息
     * @throws HuaweiCloudException 若 APM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public TraceResult analyzeTrace(String traceId, long businessId) {
        log.info("APM analyzeTrace traceId={} businessId={}", traceId, businessId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var param = new TraceSearchParam().withTraceId(traceId);
            var request = new ShowSpanSearchRequest()
                    .withXBusinessId(businessId)
                    .withBody(param);
            var response = client.showSpanSearch(request);

            List<TraceResult.Span> spans = List.of();
            List<TraceResult.Span> errorSpans = List.of();
            String rootService = "unknown";
            long durationMs = 0L;
            int statusCode = 200;

            if (response.getSpanInfoList() != null) {
                spans = response.getSpanInfoList().stream()
                        .map(s -> new TraceResult.Span(
                                s.getSpanId() != null ? s.getSpanId() : "",
                                null,
                                s.getSource() != null ? s.getSource() : "unknown",
                                s.getRealSource() != null ? s.getRealSource() : "",
                                Instant.ofEpochMilli(s.getStartTime() != null ? s.getStartTime() : 0L),
                                s.getTimeUsed() != null ? s.getTimeUsed() : 0L,
                                s.getCode() != null ? s.getCode() : 200,
                                Boolean.TRUE.equals(s.getHasError()),
                                s.getErrorReasons()
                        ))
                        .toList();

                errorSpans = spans.stream().filter(TraceResult.Span::error).toList();

                if (!spans.isEmpty()) {
                    var root = spans.get(0);
                    rootService = root.service();
                    durationMs = root.durationMs();
                    statusCode = errorSpans.isEmpty() ? 200 : 500;
                }
            }

            log.info("APM analyzeTrace success traceId={} spans={} errors={}", traceId, spans.size(), errorSpans.size());
            return new TraceResult(traceId, rootService,
                    Instant.now().minusMillis(durationMs), durationMs, statusCode, spans, errorSpans);
        } catch (ServiceResponseException e) {
            log.error("APM analyzeTrace failed traceId={} httpStatus={} errorCode={}",
                    traceId, e.getHttpStatusCode(), e.getErrorCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "APM 链路查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "analyzeTrace"));
        }
    }
    /** 检查 client 是否可用（区域不支持时为 null）。*/
    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException("Apm", "Apm adapter not available in current region", 503, "REGION_NOT_SUPPORTED", null, null);
        }
    }

}