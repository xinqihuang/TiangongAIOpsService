package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.ecs.v2.EcsClient;
import com.huaweicloud.sdk.ecs.v2.model.BatchRebootSeversOption;
import com.huaweicloud.sdk.ecs.v2.model.BatchRebootServersRequest;
import com.huaweicloud.sdk.ecs.v2.model.BatchRebootServersRequestBody;
import com.huaweicloud.sdk.ecs.v2.model.ListServersDetailsRequest;
import com.huaweicloud.sdk.ecs.v2.model.ServerId;
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

import java.util.List;
import java.util.Map;

/**
 * 华为云 ECS（弹性云服务器）适配器。
 *
 * <p>提供 ECS 实例查询与重启操作，用于故障自动修复场景。
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
                    SERVICE_NAME, "ECS 实例列表查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listServers"));
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
                    SERVICE_NAME, "ECS 实例重启失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "rebootServer"));
        }
    }
    /** 检查 client 是否可用（区域不支持时为 null）。*/
    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException("Ecs", "Ecs adapter not available in current region", 503, "REGION_NOT_SUPPORTED", null, null);
        }
    }

}