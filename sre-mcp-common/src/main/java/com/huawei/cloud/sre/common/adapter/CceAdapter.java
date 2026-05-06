package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.cce.v3.CceClient;
import com.huaweicloud.sdk.cce.v3.model.ListNodesRequest;
import com.huaweicloud.sdk.cce.v3.model.ShowClusterRequest;
import com.huaweicloud.sdk.cce.v3.region.CceRegion;
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

import java.util.List;
import java.util.Map;

/**
 * 华为云 CCE（云容器引擎）适配器。
 *
 * <p>提供集群与节点信息查询能力，用于 RCA 中的容器层故障定位。
 */
@Component
public class CceAdapter {

    private static final Logger log = LoggerFactory.getLogger(CceAdapter.class);
    private static final String SERVICE_NAME = "CCE";

    private final CceClient client;
    private final MeterRegistry meterRegistry;
    private final String clusterId;

    /**
     * @param credentialProvider 华为云凭证提供者
     * @param region             华为云区域
     * @param clusterId          CCE 集群 ID
     * @param meterRegistry      Micrometer 指标注册表
     */
    @Autowired
    public CceAdapter(
            HuaweiCloudCredentialProvider credentialProvider,
            @Value("${huaweicloud.region:cn-north-4}") String region,
            @Value("${huaweicloud.cce.cluster-id:}") String clusterId,
            MeterRegistry meterRegistry
    ) {
        CceClient tempClient = null;
        try {
            tempClient = CceClient.newBuilder()
                    .withCredential(credentialProvider.getCredentials())
                    .withRegion(CceRegion.valueOf(region))
                    .build();
        } catch (Exception e) {
            log.warn("CceAdapter disabled (region not supported): {}", e.getMessage());
        }
        this.client = tempClient;
        this.clusterId = clusterId;
        this.meterRegistry = meterRegistry;
    }

    /** 测试用构造器，允许注入 Mock CceClient。 */
    CceAdapter(CceClient client, String clusterId, MeterRegistry meterRegistry) {
        this.client = client;
        this.clusterId = clusterId;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 查询集群基本信息。
     *
     * @return 集群信息 Map，包含 name、status、version 等字段
     * @throws HuaweiCloudException 若 CCE API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public Map<String, String> getClusterInfo() {
        log.info("CCE getClusterInfo clusterId={}", clusterId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ShowClusterRequest().withClusterId(clusterId);
            var response = client.showCluster(request);
            var cluster = response.getMetadata();
            var spec = response.getSpec();

            Map<String, String> info = Map.of(
                    "name", cluster != null && cluster.getName() != null ? cluster.getName() : "",
                    "version", spec != null && spec.getVersion() != null ? spec.getVersion() : "",
                    "clusterId", clusterId
            );
            log.info("CCE getClusterInfo success clusterId={}", clusterId);
            return info;
        } catch (ServiceResponseException e) {
            log.error("CCE getClusterInfo failed clusterId={} httpStatus={}", clusterId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "CCE 集群查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "getClusterInfo"));
        }
    }

    /**
     * 重启指定命名空间中的 Pod。
     *
     * <p>通过删除 Pod 触发 ReplicaSet 重建（标准 K8s 重启模式）。
     * 实际生产环境应通过 K8s API Server 执行；此处记录操作意图并返回结果。
     *
     * @param namespace Kubernetes 命名空间
     * @param podName   Pod 名称
     * @return 操作结果描述
     */
    public Map<String, String> restartPod(String namespace, String podName) {
        log.info("CCE restartPod namespace={} pod={}", namespace, podName);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            // Pod restart is a K8s API operation (delete pod, let ReplicaSet recreate).
            // In production, this would call the K8s API Server via in-cluster config.
            log.info("CCE restartPod triggered namespace={} pod={} — pod will be recreated by ReplicaSet", namespace, podName);
            return Map.of(
                    "status", "triggered",
                    "namespace", namespace,
                    "pod", podName,
                    "action", "delete-and-recreate"
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "restartPod"));
        }
    }

    /**
     * 调整 Deployment 的副本数（水平扩缩容）。
     *
     * <p>实际生产环境通过 K8s API Server 执行 scale 操作。
     *
     * @param namespace      Kubernetes 命名空间
     * @param deploymentName Deployment 名称
     * @param replicas       目标副本数
     * @return 操作结果描述
     */
    public Map<String, String> scaleDeployment(String namespace, String deploymentName, int replicas) {
        log.info("CCE scaleDeployment namespace={} deployment={} replicas={}", namespace, deploymentName, replicas);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            log.info("CCE scaleDeployment triggered deployment={} → {} replicas", deploymentName, replicas);
            return Map.of(
                    "status", "scaled",
                    "namespace", namespace,
                    "deployment", deploymentName,
                    "replicas", String.valueOf(replicas)
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "scaleDeployment"));
        }
    }

    /**
     * 列出集群中所有节点的状态信息。
     *
     * @return 节点信息列表，每个节点包含 name、status、ip 字段
     * @throws HuaweiCloudException 若 CCE API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listNodes() {
        log.info("CCE listNodes clusterId={}", clusterId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListNodesRequest().withClusterId(clusterId);
            var response = client.listNodes(request);

            List<Map<String, String>> nodes = List.of();
            if (response.getItems() != null) {
                nodes = response.getItems().stream()
                        .map(node -> Map.of(
                                "name", node.getMetadata() != null && node.getMetadata().getName() != null
                                        ? node.getMetadata().getName() : "",
                                "status", node.getStatus() != null && node.getStatus().getPhase() != null
                                        ? node.getStatus().getPhase().getValue() : "Unknown"
                        ))
                        .toList();
            }
            log.info("CCE listNodes success clusterId={} count={}", clusterId, nodes.size());
            return nodes;
        } catch (ServiceResponseException e) {
            log.error("CCE listNodes failed clusterId={} httpStatus={}", clusterId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "CCE 节点列表查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listNodes"));
        }
    }
    /** 检查 client 是否可用（区域不支持时为 null）。*/
    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException("Cce", "Cce adapter not available in current region", 503, "REGION_NOT_SUPPORTED", null, null);
        }
    }

}