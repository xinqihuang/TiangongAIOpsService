package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.rds.v3.RdsClient;
import com.huaweicloud.sdk.rds.v3.model.ListBackupsRequest;
import com.huaweicloud.sdk.rds.v3.model.ListErrorLogsNewRequest;
import com.huaweicloud.sdk.rds.v3.model.ListInstancesRequest;
import com.huaweicloud.sdk.rds.v3.model.ListSlowLogsNewRequest;
import com.huaweicloud.sdk.rds.v3.region.RdsRegion;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 华为云 RDS（云数据库）适配器。
 *
 * <p>提供数据库实例查询、慢查询日志、错误日志、备份信息等能力，用于数据库故障排查与主备切换场景。
 */
@Component
public class RdsAdapter {

    private static final Logger log = LoggerFactory.getLogger(RdsAdapter.class);
    private static final String SERVICE_NAME = "RDS";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private final RdsClient client;
    private final MeterRegistry meterRegistry;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public RdsAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        RdsClient tempClient = null;
        try {
            tempClient = RdsClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(RdsRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("RdsAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock RdsClient。 */
    RdsAdapter(RdsClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 列出所有 RDS 实例信息。
     *
     * @return RDS 实例列表，每项包含 id、name、status、type 字段
     * @throws HuaweiCloudException 若 RDS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listInstances() {
        requireClient();
        log.info("RDS listInstances");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListInstancesRequest().withLimit(100).withOffset(0);
            var response = client.listInstances(request);

            List<Map<String, String>> instances = List.of();
            if (response.getInstances() != null) {
                instances = response.getInstances().stream()
                        .map(inst -> Map.of(
                                "id", inst.getId() != null ? inst.getId() : "",
                                "name", inst.getName() != null ? inst.getName() : "",
                                "status", inst.getStatus() != null ? inst.getStatus() : "Unknown",
                                "type", inst.getType() != null ? inst.getType() : "Unknown"
                        ))
                        .toList();
            }
            log.info("RDS listInstances success count={}", instances.size());
            return instances;
        } catch (ServiceResponseException e) {
            log.error("RDS listInstances failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.rds.instance.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listInstances"));
        }
    }

    /**
     * 按实例 ID 过滤，返回指定实例信息。
     *
     * @param instanceId RDS 实例 ID
     * @return 实例信息，若不存在则返回 empty
     * @throws HuaweiCloudException 若 RDS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public Optional<Map<String, String>> getInstance(String instanceId) {
        requireClient();
        log.info("RDS getInstance instanceId={}", instanceId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListInstancesRequest().withId(instanceId).withLimit(1).withOffset(0);
            var response = client.listInstances(request);

            if (response.getInstances() == null || response.getInstances().isEmpty()) {
                return Optional.empty();
            }
            var inst = response.getInstances().get(0);
            Map<String, String> info = Map.of(
                    "id", inst.getId() != null ? inst.getId() : "",
                    "name", inst.getName() != null ? inst.getName() : "",
                    "status", inst.getStatus() != null ? inst.getStatus() : "Unknown",
                    "type", inst.getType() != null ? inst.getType() : "Unknown"
            );
            log.info("RDS getInstance success instanceId={}", instanceId);
            return Optional.of(info);
        } catch (ServiceResponseException e) {
            log.error("RDS getInstance failed instanceId={} httpStatus={}", instanceId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.rds.instance.get", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "getInstance"));
        }
    }

    /**
     * 查询指定实例的错误日志（最近 N 分钟内）。
     *
     * @param instanceId    RDS 实例 ID
     * @param recentMinutes 查询最近多少分钟内的错误日志（1-1440）
     * @param limit         最大返回条数（1-100）
     * @return 错误日志列表，每项包含 time、level、content 字段
     * @throws HuaweiCloudException 若 RDS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listErrorLogs(String instanceId, int recentMinutes, int limit) {
        requireClient();
        int clampedMinutes = Math.max(1, Math.min(recentMinutes, 1440));
        int clampedLimit = Math.max(1, Math.min(limit, 100));
        log.info("RDS listErrorLogs instanceId={} recentMinutes={}", instanceId, clampedMinutes);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Instant end = Instant.now();
            Instant start = end.minusSeconds((long) clampedMinutes * 60);

            var request = new ListErrorLogsNewRequest()
                    .withInstanceId(instanceId)
                    .withStartDate(DATE_FMT.format(start))
                    .withEndDate(DATE_FMT.format(end))
                    .withLimit((long) clampedLimit)
                    .withOffset(0L);

            var response = client.listErrorLogsNew(request);
            List<Map<String, String>> logs = new ArrayList<>();
            if (response.getErrorLogList() != null) {
                for (var entry : response.getErrorLogList()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("time", entry.getTime() != null ? entry.getTime() : "");
                    item.put("level", entry.getLevel() != null ? entry.getLevel() : "");
                    item.put("content", entry.getContent() != null ? entry.getContent() : "");
                    logs.add(item);
                }
            }
            log.info("RDS listErrorLogs success instanceId={} count={}", instanceId, logs.size());
            return logs;
        } catch (ServiceResponseException e) {
            log.error("RDS listErrorLogs failed instanceId={} httpStatus={}", instanceId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.rds.error.log", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listErrorLogs"));
        }
    }

    /**
     * 查询指定实例的慢查询日志。
     *
     * @param instanceId    RDS 实例 ID
     * @param recentMinutes 查询最近多少分钟内（1-1440）
     * @param limit         最大返回条数（1-100）
     * @return 慢查询日志列表，每项包含 queryTime、lockTime、rowsSent、rowsExamined、database、querySample 字段
     * @throws HuaweiCloudException 若 RDS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listSlowLogs(String instanceId, int recentMinutes, int limit) {
        requireClient();
        int clampedMinutes = Math.max(1, Math.min(recentMinutes, 1440));
        int clampedLimit = Math.max(1, Math.min(limit, 100));
        log.info("RDS listSlowLogs instanceId={} recentMinutes={}", instanceId, clampedMinutes);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Instant end = Instant.now();
            Instant start = end.minusSeconds((long) clampedMinutes * 60);

            var request = new ListSlowLogsNewRequest()
                    .withInstanceId(instanceId)
                    .withStartDate(DATE_FMT.format(start))
                    .withEndDate(DATE_FMT.format(end))
                    .withLimit((long) clampedLimit)
                    .withOffset(0L);

            var response = client.listSlowLogsNew(request);
            List<Map<String, String>> logs = new ArrayList<>();
            if (response.getSlowLogList() != null) {
                for (var entry : response.getSlowLogList()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("count", entry.getCount() != null ? entry.getCount() : "");
                    item.put("time", entry.getTime() != null ? entry.getTime() : "");
                    item.put("lockTime", entry.getLockTime() != null ? entry.getLockTime() : "");
                    item.put("rowsSent", entry.getRowsSent() != null ? entry.getRowsSent() : "");
                    item.put("rowsExamined", entry.getRowsExamined() != null ? entry.getRowsExamined() : "");
                    item.put("database", entry.getDatabase() != null ? entry.getDatabase() : "");
                    item.put("users", entry.getUsers() != null ? entry.getUsers() : "");
                    item.put("querySample", entry.getQuerySample() != null ? entry.getQuerySample() : "");
                    item.put("clientIp", entry.getClientIp() != null ? entry.getClientIp() : "");
                    logs.add(item);
                }
            }
            log.info("RDS listSlowLogs success instanceId={} count={}", instanceId, logs.size());
            return logs;
        } catch (ServiceResponseException e) {
            log.error("RDS listSlowLogs failed instanceId={} httpStatus={}", instanceId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.rds.slow.log", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listSlowLogs"));
        }
    }

    /**
     * 列出指定实例的备份信息。
     *
     * @param instanceId RDS 实例 ID
     * @param limit      最大返回条数（1-100）
     * @return 备份列表，每项包含 id、name、beginTime、endTime、status、type、size 字段
     * @throws HuaweiCloudException 若 RDS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listBackups(String instanceId, int limit) {
        requireClient();
        int clampedLimit = Math.max(1, Math.min(limit, 100));
        log.info("RDS listBackups instanceId={} limit={}", instanceId, clampedLimit);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListBackupsRequest()
                    .withInstanceId(instanceId)
                    .withLimit(clampedLimit)
                    .withOffset(0);

            var response = client.listBackups(request);
            List<Map<String, String>> backups = new ArrayList<>();
            if (response.getBackups() != null) {
                for (var b : response.getBackups()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("id", b.getId() != null ? b.getId() : "");
                    item.put("name", b.getName() != null ? b.getName() : "");
                    item.put("instanceId", b.getInstanceId() != null ? b.getInstanceId() : "");
                    item.put("beginTime", b.getBeginTime() != null ? b.getBeginTime() : "");
                    item.put("endTime", b.getEndTime() != null ? b.getEndTime() : "");
                    item.put("status", b.getStatus() != null ? b.getStatus().getValue() : "");
                    item.put("type", b.getType() != null ? b.getType().getValue() : "");
                    item.put("sizeMb", b.getSize() != null ? String.valueOf(b.getSize()) : "");
                    backups.add(item);
                }
            }
            log.info("RDS listBackups success instanceId={} count={}", instanceId, backups.size());
            return backups;
        } catch (ServiceResponseException e) {
            log.error("RDS listBackups failed instanceId={} httpStatus={}", instanceId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.rds.backup.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listBackups"));
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "RDS adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
