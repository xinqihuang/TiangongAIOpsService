package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.elb.v3.ElbClient;
import com.huaweicloud.sdk.elb.v3.model.ListLoadBalancersRequest;
import com.huaweicloud.sdk.elb.v3.model.ShowLoadBalancerRequest;
import com.huaweicloud.sdk.elb.v3.region.ElbRegion;
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
 * 华为云 ELB（弹性负载均衡）适配器。
 *
 * <p>提供负载均衡器查询能力，用于流量切换与故障诊断。
 */
@Component
public class ElbAdapter {

    private static final Logger log = LoggerFactory.getLogger(ElbAdapter.class);
    private static final String SERVICE_NAME = "ELB";

    private final ElbClient client;
    private final MeterRegistry meterRegistry;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public ElbAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            MeterRegistry meterRegistry
    ) {
        ElbClient tempClient = null;
        try {
            tempClient = ElbClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(ElbRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("ElbAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock ElbClient。 */
    ElbAdapter(ElbClient client, MeterRegistry meterRegistry) {
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 列出所有负载均衡器。
     *
     * @return 负载均衡器列表，每项包含 id、name、operatingStatus 字段
     * @throws HuaweiCloudException 若 ELB API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listLoadBalancers() {
        log.info("ELB listLoadBalancers");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListLoadBalancersRequest().withLimit(100);
            var response = client.listLoadBalancers(request);

            List<Map<String, String>> lbs = List.of();
            if (response.getLoadbalancers() != null) {
                lbs = response.getLoadbalancers().stream()
                        .map(lb -> Map.of(
                                "id", lb.getId() != null ? lb.getId() : "",
                                "name", lb.getName() != null ? lb.getName() : "",
                                "operatingStatus", lb.getOperatingStatus() != null
                                        ? lb.getOperatingStatus() : "UNKNOWN"
                        ))
                        .toList();
            }
            log.info("ELB listLoadBalancers success count={}", lbs.size());
            return lbs;
        } catch (ServiceResponseException e) {
            log.error("ELB listLoadBalancers failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "ELB 负载均衡器列表查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listLoadBalancers"));
        }
    }

    /**
     * 查询指定负载均衡器详情。
     *
     * @param loadBalancerId 负载均衡器 ID
     * @return 负载均衡器详情 Map
     * @throws HuaweiCloudException 若 ELB API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public Map<String, String> getLoadBalancer(String loadBalancerId) {
        log.info("ELB getLoadBalancer loadBalancerId={}", loadBalancerId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ShowLoadBalancerRequest().withLoadbalancerId(loadBalancerId);
            var response = client.showLoadBalancer(request);
            var lb = response.getLoadbalancer();

            Map<String, String> info = Map.of(
                    "id", lb != null && lb.getId() != null ? lb.getId() : "",
                    "name", lb != null && lb.getName() != null ? lb.getName() : "",
                    "operatingStatus", lb != null && lb.getOperatingStatus() != null
                            ? lb.getOperatingStatus() : "UNKNOWN",
                    "provisioningStatus", lb != null && lb.getProvisioningStatus() != null
                            ? lb.getProvisioningStatus() : "UNKNOWN"
            );
            log.info("ELB getLoadBalancer success loadBalancerId={}", loadBalancerId);
            return info;
        } catch (ServiceResponseException e) {
            log.error("ELB getLoadBalancer failed loadBalancerId={} httpStatus={}", loadBalancerId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "ELB 负载均衡器查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "getLoadBalancer"));
        }
    }
    /** 检查 client 是否可用（区域不支持时为 null）。*/
    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException("Elb", "Elb adapter not available in current region", 503, "REGION_NOT_SUPPORTED", null, null);
        }
    }

}