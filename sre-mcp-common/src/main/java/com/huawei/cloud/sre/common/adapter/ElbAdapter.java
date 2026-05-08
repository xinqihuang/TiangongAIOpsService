package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huawei.cloud.sre.common.util.Messages;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.elb.v3.ElbClient;
import com.huaweicloud.sdk.elb.v3.model.ListHealthMonitorsRequest;
import com.huaweicloud.sdk.elb.v3.model.ListListenersRequest;
import com.huaweicloud.sdk.elb.v3.model.ListLoadBalancersRequest;
import com.huaweicloud.sdk.elb.v3.model.ListMembersRequest;
import com.huaweicloud.sdk.elb.v3.model.ListPoolsRequest;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 ELB（弹性负载均衡）适配器。
 *
 * <p>提供负载均衡器、监听器、后端池、成员与健康检查查询能力，用于流量切换与故障诊断。
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
        requireClient();
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
                                        ? lb.getOperatingStatus() : "UNKNOWN",
                                "provisioningStatus", lb.getProvisioningStatus() != null
                                        ? lb.getProvisioningStatus() : "UNKNOWN"
                        ))
                        .toList();
            }
            log.info("ELB listLoadBalancers success count={}", lbs.size());
            return lbs;
        } catch (ServiceResponseException e) {
            log.error("ELB listLoadBalancers failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.elb.lb.list", e.getErrorMsg()),
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
        requireClient();
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
                    SERVICE_NAME, Messages.get("err.elb.lb.get", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "getLoadBalancer"));
        }
    }

    /**
     * 列出指定负载均衡器下的所有监听器。
     *
     * @param loadBalancerId 负载均衡器 ID，传 null 则查询所有监听器
     * @return 监听器列表，每项包含 id、name、protocol、port、defaultPoolId、operatingStatus 字段
     * @throws HuaweiCloudException 若 ELB API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listListeners(String loadBalancerId) {
        requireClient();
        log.info("ELB listListeners loadBalancerId={}", loadBalancerId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListListenersRequest().withLimit(100);
            if (loadBalancerId != null) {
                request.withLoadbalancerId(List.of(loadBalancerId));
            }
            var response = client.listListeners(request);

            List<Map<String, String>> listeners = new ArrayList<>();
            if (response.getListeners() != null) {
                for (var listener : response.getListeners()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("id", listener.getId() != null ? listener.getId() : "");
                    item.put("name", listener.getName() != null ? listener.getName() : "");
                    item.put("protocol", listener.getProtocol() != null ? listener.getProtocol() : "");
                    item.put("protocolPort", listener.getProtocolPort() != null
                            ? listener.getProtocolPort().toString() : "");
                    item.put("defaultPoolId", listener.getDefaultPoolId() != null
                            ? listener.getDefaultPoolId() : "");
                    item.put("adminStateUp", listener.getAdminStateUp() != null
                            ? listener.getAdminStateUp().toString() : "true");
                    listeners.add(item);
                }
            }
            log.info("ELB listListeners success loadBalancerId={} count={}", loadBalancerId, listeners.size());
            return listeners;
        } catch (ServiceResponseException e) {
            log.error("ELB listListeners failed loadBalancerId={} httpStatus={}", loadBalancerId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.elb.listener.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listListeners"));
        }
    }

    /**
     * 列出所有后端服务器组（Pool）。
     *
     * @return 后端池列表，每项包含 id、name、lbAlgorithm、healthmonitorId、operatingStatus 字段
     * @throws HuaweiCloudException 若 ELB API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listPools() {
        requireClient();
        log.info("ELB listPools");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListPoolsRequest().withLimit(100);
            var response = client.listPools(request);

            List<Map<String, String>> pools = new ArrayList<>();
            if (response.getPools() != null) {
                for (var pool : response.getPools()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("id", pool.getId() != null ? pool.getId() : "");
                    item.put("name", pool.getName() != null ? pool.getName() : "");
                    item.put("lbAlgorithm", pool.getLbAlgorithm() != null ? pool.getLbAlgorithm() : "");
                    item.put("healthmonitorId", pool.getHealthmonitorId() != null ? pool.getHealthmonitorId() : "");
                    item.put("description", pool.getDescription() != null ? pool.getDescription() : "");
                    pools.add(item);
                }
            }
            log.info("ELB listPools success count={}", pools.size());
            return pools;
        } catch (ServiceResponseException e) {
            log.error("ELB listPools failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.elb.pool.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listPools"));
        }
    }

    /**
     * 列出指定后端池中的所有成员（后端服务器）。
     *
     * @param poolId 后端池 ID
     * @return 后端成员列表，每项包含 id、name、address、port、weight、operatingStatus 字段
     * @throws HuaweiCloudException 若 ELB API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listMembers(String poolId) {
        requireClient();
        log.info("ELB listMembers poolId={}", poolId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListMembersRequest().withPoolId(poolId).withLimit(100);
            var response = client.listMembers(request);

            List<Map<String, String>> members = new ArrayList<>();
            if (response.getMembers() != null) {
                for (var member : response.getMembers()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("id", member.getId() != null ? member.getId() : "");
                    item.put("name", member.getName() != null ? member.getName() : "");
                    item.put("address", member.getAddress() != null ? member.getAddress() : "");
                    item.put("protocolPort", member.getProtocolPort() != null
                            ? member.getProtocolPort().toString() : "");
                    item.put("weight", member.getWeight() != null ? member.getWeight().toString() : "1");
                    item.put("operatingStatus", member.getOperatingStatus() != null
                            ? member.getOperatingStatus() : "UNKNOWN");
                    item.put("adminStateUp", member.getAdminStateUp() != null
                            ? member.getAdminStateUp().toString() : "true");
                    members.add(item);
                }
            }
            log.info("ELB listMembers success poolId={} count={}", poolId, members.size());
            return members;
        } catch (ServiceResponseException e) {
            log.error("ELB listMembers failed poolId={} httpStatus={}", poolId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.elb.member.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listMembers"));
        }
    }

    /**
     * 列出所有健康检查配置。
     *
     * @return 健康检查列表，每项包含 id、type、delay、timeout、maxRetries、urlPath 字段
     * @throws HuaweiCloudException 若 ELB API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listHealthMonitors() {
        requireClient();
        log.info("ELB listHealthMonitors");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListHealthMonitorsRequest().withLimit(100);
            var response = client.listHealthMonitors(request);

            List<Map<String, String>> monitors = new ArrayList<>();
            if (response.getHealthmonitors() != null) {
                for (var hm : response.getHealthmonitors()) {
                    Map<String, String> item = new HashMap<>();
                    item.put("id", hm.getId() != null ? hm.getId() : "");
                    item.put("name", hm.getName() != null ? hm.getName() : "");
                    item.put("type", hm.getType() != null ? hm.getType() : "");
                    item.put("delay", hm.getDelay() != null ? hm.getDelay().toString() : "");
                    item.put("timeout", hm.getTimeout() != null ? hm.getTimeout().toString() : "");
                    item.put("maxRetries", hm.getMaxRetries() != null ? hm.getMaxRetries().toString() : "");
                    item.put("urlPath", hm.getUrlPath() != null ? hm.getUrlPath() : "");
                    item.put("adminStateUp", hm.getAdminStateUp() != null ? hm.getAdminStateUp().toString() : "true");
                    monitors.add(item);
                }
            }
            log.info("ELB listHealthMonitors success count={}", monitors.size());
            return monitors;
        } catch (ServiceResponseException e) {
            log.error("ELB listHealthMonitors failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, Messages.get("err.elb.healthmonitor.list", e.getErrorMsg()),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listHealthMonitors"));
        }
    }

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "ELB adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
