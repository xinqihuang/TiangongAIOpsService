package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.dto.LogSearchResult;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.lts.v2.LtsClient;
import com.huaweicloud.sdk.lts.v2.model.QueryLtsLogParams;
import com.huaweicloud.sdk.lts.v2.model.ListLogsRequest;
import com.huaweicloud.sdk.lts.v2.region.LtsRegion;
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
 * 华为云 LTS（云日志服务）适配器。
 *
 * <p>封装 LTS v2 日志查询 API，支持关键词全文搜索和时间范围过滤。
 */
@Component
public class LtsAdapter {

    private static final Logger log = LoggerFactory.getLogger(LtsAdapter.class);
    private static final String SERVICE_NAME = "LTS";

    private final LtsClient client;
    private final MeterRegistry meterRegistry;
    private final String logGroupId;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param logGroupId         LTS 日志组 ID
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public LtsAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            @Value("${huaweicloud.lts.log-group-id:}") String logGroupId,
            MeterRegistry meterRegistry
    ) {
        LtsClient tempClient = null;
        try {
            tempClient = LtsClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(LtsRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("LtsAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.logGroupId = logGroupId;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock LtsClient。 */
    LtsAdapter(LtsClient client, String logGroupId, MeterRegistry meterRegistry) {
        this.client = client;
        this.logGroupId = logGroupId;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 按关键词搜索日志。
     *
     * @param service      服务名（用作日志流名称前缀）
     * @param keyword      搜索关键词
     * @param rangeMinutes 时间范围（分钟，从当前时间向前推）
     * @param maxResults   最大返回条数（1-100）
     * @return 日志搜索结果
     * @throws HuaweiCloudException 若 LTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public LogSearchResult searchLogs(String service, String keyword, int rangeMinutes, int maxResults) {
        log.info("LTS searchLogs service={} keyword={} rangeMinutes={}", service, keyword, rangeMinutes);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Instant end = Instant.now();
            Instant start = end.minusSeconds((long) rangeMinutes * 60);

            var queryParam = new QueryLtsLogParams()
                    .withStartTime(String.valueOf(start.toEpochMilli()))
                    .withEndTime(String.valueOf(end.toEpochMilli()))
                    .withKeywords(keyword)
                    .withLineNum(String.valueOf(maxResults))
                    .withIsCount(false);

            var request = new ListLogsRequest()
                    .withLogGroupId(logGroupId)
                    .withLogStreamId(service)
                    .withBody(queryParam);

            var response = client.listLogs(request);

            List<LogSearchResult.LogEntry> entries = List.of();
            long total = 0L;
            if (response.getLogs() != null) {
                total = response.getLogs().size();
                entries = response.getLogs().stream()
                        .map(l -> new LogSearchResult.LogEntry(
                                Instant.ofEpochMilli(Long.parseLong(l.getLineNum() != null ? l.getLineNum() : "0")),
                                "INFO",
                                l.getContent(),
                                null,
                                Map.of("service", service)
                        ))
                        .toList();
            }

            log.info("LTS searchLogs success service={} keyword={} count={}", service, keyword, total);
            return new LogSearchResult(service, keyword, total, entries);
        } catch (ServiceResponseException e) {
            log.error("LTS searchLogs failed service={} keyword={} httpStatus={} errorCode={}",
                    service, keyword, e.getHttpStatusCode(), e.getErrorCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "LTS 日志搜索失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "searchLogs"));
        }
    }
    /** 检查 client 是否可用（区域不支持时为 null）。*/
    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException("Lts", "Lts adapter not available in current region", 503, "REGION_NOT_SUPPORTED", null, null);
        }
    }

}