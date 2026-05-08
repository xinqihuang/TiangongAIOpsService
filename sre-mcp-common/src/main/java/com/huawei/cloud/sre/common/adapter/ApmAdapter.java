package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.dto.TraceResult;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.apm.v1.ApmClient;
import com.huaweicloud.sdk.apm.v1.model.InstanceSearchParam;
import com.huaweicloud.sdk.apm.v1.model.ListAppEnvsRequest;
import com.huaweicloud.sdk.apm.v1.model.ListAppsRequest;
import com.huaweicloud.sdk.apm.v1.model.ListEnvInstancesRequest;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 APM（应用性能管理）适配器。
 *
 * <p>封装 APM v1 链路追踪、应用列表、环境实例查询 API，
 * 用于 RCA 中的分布式链路分析与服务拓扑查询。
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
        requireClient();
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
                    SERVICE_NAME, Messages.get("err.apm.trace.analyze", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "analyzeTrace"));
        }
    }

    /**
     * 列出指定业务 ID 下的所有应用。
     *
     * @param businessId APM 业务 ID
     * @return 应用列表，每项包含 id、name、businessId、onlineCount、offlineCount 字段
     * @throws HuaweiCloudException 若 APM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listApps(long businessId) {
        requireClient();
        log.info("APM listApps businessId={}", businessId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListAppsRequest()
                    .withXBusinessId(businessId)
                    .withBusinessId(businessId);
            var response = client.listApps(request);

            List<Map<String, String>> apps = new ArrayList<>();
            if (response.getApps() != null) {
                for (var app : response.getApps()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("id", app.getId() != null ? app.getId().toString() : "");
                    item.put("name", app.getName() != null ? app.getName() : "");
                    item.put("businessId", app.getBusinessId() != null ? app.getBusinessId().toString() : "");
                    apps.add(item);
                }
            }
            log.info("APM listApps success businessId={} count={}", businessId, apps.size());
            return apps;
        } catch (ServiceResponseException e) {
            log.error("APM listApps failed businessId={} httpStatus={}", businessId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.apm.app.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listApps"));
        }
    }

    /**
     * 列出指定应用下的所有环境（如 dev、staging、prod）。
     *
     * @param businessId APM 业务 ID
     * @param appId      应用 ID
     * @return 环境列表，每项包含 id、name、appName、region、isDefault 字段
     * @throws HuaweiCloudException 若 APM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listAppEnvs(long businessId, long appId) {
        requireClient();
        log.info("APM listAppEnvs businessId={} appId={}", businessId, appId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListAppEnvsRequest()
                    .withXBusinessId(businessId)
                    .withAppId(appId);
            var response = client.listAppEnvs(request);

            List<Map<String, String>> envs = new ArrayList<>();
            if (response.getEnvs() != null) {
                for (var env : response.getEnvs()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("id", env.getId() != null ? env.getId().toString() : "");
                    item.put("name", env.getName() != null ? env.getName() : "");
                    item.put("appName", env.getAppName() != null ? env.getAppName() : "");
                    item.put("businessName", env.getBusinessName() != null ? env.getBusinessName() : "");
                    item.put("region", env.getRegion() != null ? env.getRegion() : "");
                    item.put("isDefault", env.getIsDefault() != null ? env.getIsDefault().toString() : "false");
                    envs.add(item);
                }
            }
            log.info("APM listAppEnvs success appId={} count={}", appId, envs.size());
            return envs;
        } catch (ServiceResponseException e) {
            log.error("APM listAppEnvs failed appId={} httpStatus={}", appId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.apm.app.env.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listAppEnvs"));
        }
    }

    /**
     * 列出指定环境下的所有实例（Agent 接入的服务实例）。
     *
     * @param businessId APM 业务 ID
     * @param envId      环境 ID
     * @param pageSize   每页返回条数（1-100）
     * @return 实例列表，每项包含 instanceId、instanceName、hostName、ipAddress、instanceStatus、agentVersion 字段
     * @throws HuaweiCloudException 若 APM API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listEnvInstances(long businessId, long envId, int pageSize) {
        requireClient();
        int clampedSize = Math.max(1, Math.min(pageSize, 100));
        log.info("APM listEnvInstances businessId={} envId={}", businessId, envId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var body = new InstanceSearchParam()
                    .withEnvId(envId)
                    .withPage(1)
                    .withPageSize(clampedSize)
                    .withReturnCount(true);

            var request = new ListEnvInstancesRequest()
                    .withXBusinessId(businessId)
                    .withBody(body);
            var response = client.listEnvInstances(request);

            List<Map<String, String>> instances = new ArrayList<>();
            if (response.getInstanceInfoList() != null) {
                for (var inst : response.getInstanceInfoList()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("instanceId", inst.getInstanceId() != null ? inst.getInstanceId().toString() : "");
                    item.put("instanceName", inst.getInstanceName() != null ? inst.getInstanceName() : "");
                    item.put("hostName", inst.getHostName() != null ? inst.getHostName() : "");
                    item.put("ipAddress", inst.getIpAddress() != null ? inst.getIpAddress() : "");
                    item.put("appName", inst.getAppName() != null ? inst.getAppName() : "");
                    item.put("instanceStatus", inst.getInstanceStatus() != null ? inst.getInstanceStatus().toString() : "");
                    item.put("agentVersion", inst.getAgentVersion() != null ? inst.getAgentVersion() : "");
                    item.put("lastHeartbeat", inst.getLastHeartbeat() != null ? inst.getLastHeartbeat().toString() : "");
                    instances.add(item);
                }
            }
            int total = response.getTotalCount() != null ? response.getTotalCount() : 0;
            int online = response.getOnlineCount() != null ? response.getOnlineCount() : 0;
            int offline = response.getOfflineCount() != null ? response.getOfflineCount() : 0;
            log.info("APM listEnvInstances success envId={} total={} online={} offline={}",
                    envId, total, online, offline);
            return instances;
        } catch (ServiceResponseException e) {
            log.error("APM listEnvInstances failed envId={} httpStatus={}", envId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.apm.env.instance.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listEnvInstances"));
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "APM adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
