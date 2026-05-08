package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.dto.LogSearchResult;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.lts.v2.LtsClient;
import com.huaweicloud.sdk.lts.v2.model.ListLogGroupsRequest;
import com.huaweicloud.sdk.lts.v2.model.ListLogStreamRequest;
import com.huaweicloud.sdk.lts.v2.model.ListLogsRequest;
import com.huaweicloud.sdk.lts.v2.model.QueryLtsLogParams;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 LTS（云日志服务）适配器。
 *
 * <p>封装 LTS v2 日志查询 API，支持日志组/流列表查询与关键词全文搜索。
 */
@Component
public class LtsAdapter {

    private static final Logger log = LoggerFactory.getLogger(LtsAdapter.class);
    private static final String SERVICE_NAME = "LTS";

    private final LtsClient client;
    private final MeterRegistry meterRegistry;
    private final String defaultLogGroupId;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param defaultLogGroupId  LTS 默认日志组 ID
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public LtsAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            @Value("${huaweicloud.lts.log-group-id:}") String defaultLogGroupId,
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
        this.defaultLogGroupId = defaultLogGroupId;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock LtsClient。 */
    LtsAdapter(LtsClient client, String defaultLogGroupId, MeterRegistry meterRegistry) {
        this.client = client;
        this.defaultLogGroupId = defaultLogGroupId;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 按关键词搜索日志（使用默认日志组）。
     *
     * @param service      服务名（用作日志流 ID）
     * @param keyword      搜索关键词
     * @param rangeMinutes 时间范围（分钟，从当前时间向前推）
     * @param maxResults   最大返回条数（1-100）
     * @return 日志搜索结果
     * @throws HuaweiCloudException 若 LTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public LogSearchResult searchLogs(String service, String keyword, int rangeMinutes, int maxResults) {
        requireClient();
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
                    .withLogGroupId(defaultLogGroupId)
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
                    SERVICE_NAME, Messages.get("err.lts.log.search", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "searchLogs"));
        }
    }

    /**
     * 列出当前项目下所有日志组。
     *
     * @return 日志组列表，每项包含 logGroupId、logGroupName、ttlInDays、creationTime 字段
     * @throws HuaweiCloudException 若 LTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listLogGroups() {
        requireClient();
        log.info("LTS listLogGroups");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var response = client.listLogGroups(new ListLogGroupsRequest());

            List<Map<String, String>> groups = new ArrayList<>();
            if (response.getLogGroups() != null) {
                for (var group : response.getLogGroups()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("logGroupId", group.getLogGroupId() != null ? group.getLogGroupId() : "");
                    item.put("logGroupName", group.getLogGroupName() != null ? group.getLogGroupName() : "");
                    item.put("ttlInDays", group.getTtlInDays() != null ? group.getTtlInDays().toString() : "");
                    item.put("creationTime", group.getCreationTime() != null ? group.getCreationTime().toString() : "");
                    groups.add(item);
                }
            }
            log.info("LTS listLogGroups success count={}", groups.size());
            return groups;
        } catch (ServiceResponseException e) {
            log.error("LTS listLogGroups failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.lts.loggroup.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listLogGroups"));
        }
    }

    /**
     * 列出指定日志组下的所有日志流。
     *
     * @param logGroupId 日志组 ID；传 null 则使用 {@code huaweicloud.lts.log-group-id} 配置值
     * @return 日志流列表，每项包含 logStreamId、logStreamName、creationTime 字段
     * @throws HuaweiCloudException 若 LTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listLogStreams(String logGroupId) {
        requireClient();
        String groupId = logGroupId != null ? logGroupId : defaultLogGroupId;
        log.info("LTS listLogStreams logGroupId={}", groupId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListLogStreamRequest().withLogGroupId(groupId);
            var response = client.listLogStream(request);

            List<Map<String, String>> streams = new ArrayList<>();
            if (response.getLogStreams() != null) {
                for (var stream : response.getLogStreams()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("logStreamId", stream.getLogStreamId() != null ? stream.getLogStreamId() : "");
                    item.put("logStreamName", stream.getLogStreamName() != null ? stream.getLogStreamName() : "");
                    item.put("creationTime", stream.getCreationTime() != null ? stream.getCreationTime().toString() : "");
                    streams.add(item);
                }
            }
            log.info("LTS listLogStreams success logGroupId={} count={}", groupId, streams.size());
            return streams;
        } catch (ServiceResponseException e) {
            log.error("LTS listLogStreams failed logGroupId={} httpStatus={}", groupId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.lts.logstream.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listLogStreams"));
        }
    }

    /**
     * 在指定日志组和日志流中搜索日志。
     *
     * @param logGroupId   日志组 ID
     * @param logStreamId  日志流 ID
     * @param keyword      搜索关键词
     * @param rangeMinutes 时间范围（分钟）
     * @param maxResults   最大返回条数
     * @return 日志搜索结果
     * @throws HuaweiCloudException 若 LTS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public LogSearchResult searchLogsByStream(
            String logGroupId, String logStreamId, String keyword, int rangeMinutes, int maxResults) {
        requireClient();
        log.info("LTS searchLogsByStream logGroupId={} logStreamId={} keyword={}", logGroupId, logStreamId, keyword);
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
                    .withLogStreamId(logStreamId)
                    .withBody(queryParam);

            var response = client.listLogs(request);

            List<LogSearchResult.LogEntry> entries = new ArrayList<>();
            long total = 0L;
            if (response.getLogs() != null) {
                total = response.getLogs().size();
                for (var l : response.getLogs()) {
                    entries.add(new LogSearchResult.LogEntry(
                            Instant.ofEpochMilli(Long.parseLong(l.getLineNum() != null ? l.getLineNum() : "0")),
                            "INFO",
                            l.getContent(),
                            null,
                            Map.of("logGroupId", logGroupId, "logStreamId", logStreamId)
                    ));
                }
            }
            log.info("LTS searchLogsByStream success count={}", total);
            return new LogSearchResult(logStreamId, keyword, total, entries);
        } catch (ServiceResponseException e) {
            log.error("LTS searchLogsByStream failed logStreamId={} httpStatus={}", logStreamId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.lts.log.search", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "searchLogsByStream"));
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "LTS adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
