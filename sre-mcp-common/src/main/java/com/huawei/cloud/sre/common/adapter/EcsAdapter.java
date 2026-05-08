package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.ecs.v2.EcsClient;
import com.huaweicloud.sdk.ecs.v2.model.BatchRebootSeversOption;
import com.huaweicloud.sdk.ecs.v2.model.BatchRebootServersRequest;
import com.huaweicloud.sdk.ecs.v2.model.BatchRebootServersRequestBody;
import com.huaweicloud.sdk.ecs.v2.model.BatchStartServersOption;
import com.huaweicloud.sdk.ecs.v2.model.BatchStartServersRequest;
import com.huaweicloud.sdk.ecs.v2.model.BatchStartServersRequestBody;
import com.huaweicloud.sdk.ecs.v2.model.BatchStopServersOption;
import com.huaweicloud.sdk.ecs.v2.model.BatchStopServersRequest;
import com.huaweicloud.sdk.ecs.v2.model.BatchStopServersRequestBody;
import com.huaweicloud.sdk.ecs.v2.model.ListServersDetailsRequest;
import com.huaweicloud.sdk.ecs.v2.model.ServerId;
import com.huaweicloud.sdk.ecs.v2.model.ShowServerRequest;
import com.huaweicloud.sdk.ecs.v2.region.EcsRegion;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 ECS（弹性云服务器）适配器。
 *
 * <p>提供 ECS 实例查询、启停、重启操作，用于故障自动修复与容量管理场景。
 */
@Component
public class EcsAdapter {

    private static final Logger log = LoggerFactory.getLogger(EcsAdapter.class);
    private static final String SERVICE_NAME = "ECS";

    private final EcsClient client;
    private final MeterRegistry meterRegistry;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public EcsAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        EcsClient tempClient = null;
        try {
            tempClient = EcsClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(EcsRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("EcsAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock EcsClient。 */
    EcsAdapter(EcsClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 列出所有 ECS 实例的基本信息。
     *
     * @return ECS 实例列表，每个包含 id、name、status 字段
     * @throws HuaweiCloudException 若 ECS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listServers() {
        requireClient();
        log.info("ECS listServers");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListServersDetailsRequest().withLimit(100);
            var response = client.listServersDetails(request);

            List<Map<String, String>> servers = List.of();
            if (response.getServers() != null) {
                servers = response.getServers().stream()
                        .map(s -> Map.of(
                                "id", s.getId() != null ? s.getId() : "",
                                "name", s.getName() != null ? s.getName() : "",
                                "status", s.getStatus() != null ? s.getStatus() : "Unknown"
                        ))
                        .toList();
            }
            log.info("ECS listServers success count={}", servers.size());
            return servers;
        } catch (ServiceResponseException e) {
            log.error("ECS listServers failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.ecs.instance.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listServers"));
        }
    }

    /**
     * 查询指定 ECS 实例的详细信息（规格、IP、磁盘、镜像等）。
     *
     * @param serverId ECS 实例 ID
     * @return 实例详情 Map
     * @throws HuaweiCloudException 若 ECS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public Map<String, String> getServerDetail(String serverId) {
        requireClient();
        log.info("ECS getServerDetail serverId={}", serverId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var response = client.showServer(new ShowServerRequest().withServerId(serverId));
            var server = response.getServer();
            if (server == null) {
                return Map.of("id", serverId, "status", "Not Found");
            }

            Map<String, String> detail = new HashMap<>();
            detail.put("id", server.getId() != null ? server.getId() : "");
            detail.put("name", server.getName() != null ? server.getName() : "");
            detail.put("status", server.getStatus() != null ? server.getStatus() : "Unknown");
            detail.put("hostId", server.getHostId() != null ? server.getHostId() : "");
            detail.put("imageId", server.getImage() != null && server.getImage().getId() != null
                    ? server.getImage().getId() : "");
            if (server.getFlavor() != null) {
                detail.put("flavorId", server.getFlavor().getId() != null ? server.getFlavor().getId() : "");
                detail.put("flavorName", server.getFlavor().getName() != null ? server.getFlavor().getName() : "");
                detail.put("vcpus", server.getFlavor().getVcpus() != null ? server.getFlavor().getVcpus() : "");
                detail.put("ram", server.getFlavor().getRam() != null ? server.getFlavor().getRam().toString() : "");
            }
            detail.put("created", server.getCreated() != null ? server.getCreated() : "");
            detail.put("updated", server.getUpdated() != null ? server.getUpdated() : "");
            detail.put("availabilityZone", server.getOsEXTAZAvailabilityZone() != null
                    ? server.getOsEXTAZAvailabilityZone() : "");
            detail.put("taskState", server.getOsEXTSTSTaskState() != null
                    ? server.getOsEXTSTSTaskState() : "");

            log.info("ECS getServerDetail success serverId={} status={}", serverId, detail.get("status"));
            return detail;
        } catch (ServiceResponseException e) {
            log.error("ECS getServerDetail failed serverId={} httpStatus={}", serverId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.ecs.instance.get", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "getServerDetail"));
        }
    }

    /**
     * 重启指定 ECS 实例（软重启）。
     *
     * @param serverId ECS 实例 ID
     * @throws HuaweiCloudException 若 ECS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public void rebootServer(String serverId) {
        requireClient();
        log.info("ECS rebootServer serverId={}", serverId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // SDK 类名拼写为 BatchRebootSeversOption（官方 SDK 拼写错误，保持原样）
            var option = new BatchRebootSeversOption()
                    .withServers(List.of(new ServerId().withId(serverId)))
                    .withType(BatchRebootSeversOption.TypeEnum.SOFT);
            var body = new BatchRebootServersRequestBody().withReboot(option);
            client.batchRebootServers(new BatchRebootServersRequest().withBody(body));
            log.info("ECS rebootServer success serverId={}", serverId);
        } catch (ServiceResponseException e) {
            log.error("ECS rebootServer failed serverId={} httpStatus={}", serverId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.ecs.instance.reboot", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "rebootServer"));
        }
    }

    /**
     * 启动指定 ECS 实例。
     *
     * @param serverId ECS 实例 ID
     * @throws HuaweiCloudException 若 ECS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public void startServer(String serverId) {
        requireClient();
        log.info("ECS startServer serverId={}", serverId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var option = new BatchStartServersOption()
                    .withServers(List.of(new ServerId().withId(serverId)));
            var body = new BatchStartServersRequestBody().withOsStart(option);
            client.batchStartServers(new BatchStartServersRequest().withBody(body));
            log.info("ECS startServer success serverId={}", serverId);
        } catch (ServiceResponseException e) {
            log.error("ECS startServer failed serverId={} httpStatus={}", serverId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.ecs.instance.start", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "startServer"));
        }
    }

    /**
     * 关机指定 ECS 实例（软关机）。
     *
     * @param serverId ECS 实例 ID
     * @throws HuaweiCloudException 若 ECS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public void stopServer(String serverId) {
        requireClient();
        log.info("ECS stopServer serverId={}", serverId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var option = new BatchStopServersOption()
                    .withServers(List.of(new ServerId().withId(serverId)))
                    .withType(BatchStopServersOption.TypeEnum.SOFT);
            var body = new BatchStopServersRequestBody().withOsStop(option);
            client.batchStopServers(new BatchStopServersRequest().withBody(body));
            log.info("ECS stopServer success serverId={}", serverId);
        } catch (ServiceResponseException e) {
            log.error("ECS stopServer failed serverId={} httpStatus={}", serverId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.ecs.instance.stop", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "stopServer"));
        }
    }

    /**
     * 按状态过滤 ECS 实例列表。
     *
     * @param status 实例状态，如 ACTIVE、SHUTOFF、ERROR
     * @return 匹配状态的实例列表
     * @throws HuaweiCloudException 若 ECS API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listServersByStatus(String status) {
        requireClient();
        log.info("ECS listServersByStatus status={}", status);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListServersDetailsRequest().withStatus(status).withLimit(100);
            var response = client.listServersDetails(request);

            List<Map<String, String>> servers = List.of();
            if (response.getServers() != null) {
                servers = response.getServers().stream()
                        .map(s -> Map.of(
                                "id", s.getId() != null ? s.getId() : "",
                                "name", s.getName() != null ? s.getName() : "",
                                "status", s.getStatus() != null ? s.getStatus() : "Unknown",
                                "availabilityZone", s.getOsEXTAZAvailabilityZone() != null
                                        ? s.getOsEXTAZAvailabilityZone() : ""
                        ))
                        .toList();
            }
            log.info("ECS listServersByStatus success status={} count={}", status, servers.size());
            return servers;
        } catch (ServiceResponseException e) {
            log.error("ECS listServersByStatus failed status={} httpStatus={}", status, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.ecs.instance.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listServersByStatus"));
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "ECS adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
