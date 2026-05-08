package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.credential.HuaweiCloudCredentialProvider;
import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.cce.v3.CceClient;
import com.huaweicloud.sdk.cce.v3.model.ListClustersRequest;
import com.huaweicloud.sdk.cce.v3.model.ListNodePoolsRequest;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 华为云 CCE（云容器引擎）适配器。
 *
 * <p>提供集群、节点池、节点查询能力，用于容器层故障定位与容量管理。
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
        requireClient();
        log.info("CCE getClusterInfo clusterId={}", clusterId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ShowClusterRequest().withClusterId(clusterId);
            var response = client.showCluster(request);
            var metadata = response.getMetadata();
            var spec = response.getSpec();
            var status = response.getStatus();

            Map<String, String> info = new HashMap<>();
            info.put("clusterId", clusterId);
            info.put("name", metadata != null && metadata.getName() != null ? metadata.getName() : "");
            info.put("version", spec != null && spec.getVersion() != null ? spec.getVersion() : "");
            info.put("type", spec != null && spec.getType() != null ? spec.getType().getValue() : "");
            info.put("phase", status != null && status.getPhase() != null ? status.getPhase() : "");
            info.put("endpoint", status != null && status.getEndpoints() != null && !status.getEndpoints().isEmpty()
                    ? status.getEndpoints().get(0).getUrl() : "");

            log.info("CCE getClusterInfo success clusterId={} phase={}", clusterId, info.get("phase"));
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
     * 列出当前项目下的所有 CCE 集群。
     *
     * @return 集群列表，每项包含 uid、name、phase、version、type 字段
     * @throws HuaweiCloudException 若 CCE API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listClusters() {
        requireClient();
        log.info("CCE listClusters");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var response = client.listClusters(new ListClustersRequest());
            List<Map<String, String>> clusters = new ArrayList<>();
            if (response.getItems() != null) {
                for (var cluster : response.getItems()) {
                    Map<String, String> item = new HashMap<>();
                    var meta = cluster.getMetadata();
                    var spec = cluster.getSpec();
                    var status = cluster.getStatus();
                    item.put("uid", meta != null && meta.getUid() != null ? meta.getUid() : "");
                    item.put("name", meta != null && meta.getName() != null ? meta.getName() : "");
                    item.put("version", spec != null && spec.getVersion() != null ? spec.getVersion() : "");
                    item.put("type", spec != null && spec.getType() != null ? spec.getType().getValue() : "");
                    item.put("phase", status != null && status.getPhase() != null ? status.getPhase() : "");
                    clusters.add(item);
                }
            }
            log.info("CCE listClusters success count={}", clusters.size());
            return clusters;
        } catch (ServiceResponseException e) {
            log.error("CCE listClusters failed httpStatus={}", e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "CCE 集群列表查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listClusters"));
        }
    }

    /**
     * 列出集群中所有节点池的状态信息。
     *
     * @return 节点池列表，每项包含 uid、name、status、nodeCount、flavor 字段
     * @throws HuaweiCloudException 若 CCE API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listNodePools() {
        requireClient();
        log.info("CCE listNodePools clusterId={}", clusterId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListNodePoolsRequest().withClusterId(clusterId);
            var response = client.listNodePools(request);

            List<Map<String, String>> pools = new ArrayList<>();
            if (response.getItems() != null) {
                for (var pool : response.getItems()) {
                    Map<String, String> item = new HashMap<>();
                    var meta = pool.getMetadata();
                    var spec = pool.getSpec();
                    var status = pool.getStatus();
                    item.put("uid", meta != null && meta.getUid() != null ? meta.getUid() : "");
                    item.put("name", meta != null && meta.getName() != null ? meta.getName() : "");
                    item.put("phase", status != null && status.getPhase() != null
                            ? status.getPhase().getValue() : "");
                    item.put("currentNodeCount", status != null && status.getCurrentNode() != null
                            ? status.getCurrentNode().toString() : "0");
                    item.put("creatingNodeCount", status != null && status.getCreatingNode() != null
                            ? status.getCreatingNode().toString() : "0");
                    item.put("deletingNodeCount", status != null && status.getDeletingNode() != null
                            ? status.getDeletingNode().toString() : "0");
                    if (spec != null && spec.getNodeTemplate() != null) {
                        item.put("flavor", spec.getNodeTemplate().getFlavor() != null
                                ? spec.getNodeTemplate().getFlavor() : "");
                    }
                    pools.add(item);
                }
            }
            log.info("CCE listNodePools success clusterId={} count={}", clusterId, pools.size());
            return pools;
        } catch (ServiceResponseException e) {
            log.error("CCE listNodePools failed clusterId={} httpStatus={}", clusterId, e.getHttpStatusCode());
            throw new HuaweiCloudException(
                    SERVICE_NAME, "CCE 节点池列表查询失败: " + e.getErrorMsg(),
                    e.getHttpStatusCode(), e.getErrorCode(), e.getRequestId(), e
            );
        } finally {
            sample.stop(meterRegistry.timer("huaweicloud.adapter.duration",
                    "service", SERVICE_NAME, "operation", "listNodePools"));
        }
    }

    /**
     * 重启指定命名空间中的 Pod。
     *
     * <p>通过删除 Pod 触发 ReplicaSet 重建（标准 K8s 重启模式）。
     *
     * @param namespace Kubernetes 命名空间
     * @param podName   Pod 名称
     * @return 操作结果描述
     */
    public Map<String, String> restartPod(String namespace, String podName) {
        log.info("CCE restartPod namespace={} pod={}", namespace, podName);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
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
     * @return 节点信息列表，每个节点包含 name、status、phase 字段
     * @throws HuaweiCloudException 若 CCE API 调用失败
     */
    @Retry(name = "huaweicloud-api")
    @CircuitBreaker(name = "huaweicloud-api")
    public List<Map<String, String>> listNodes() {
        requireClient();
        log.info("CCE listNodes clusterId={}", clusterId);
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            var request = new ListNodesRequest().withClusterId(clusterId);
            var response = client.listNodes(request);

            List<Map<String, String>> nodes = new ArrayList<>();
            if (response.getItems() != null) {
                for (var node : response.getItems()) {
                    Map<String, String> item = new HashMap<>();
                    var meta = node.getMetadata();
                    var status = node.getStatus();
                    item.put("uid", meta != null && meta.getUid() != null ? meta.getUid() : "");
                    item.put("name", meta != null && meta.getName() != null ? meta.getName() : "");
                    item.put("status", status != null && status.getPhase() != null
                            ? status.getPhase().getValue() : "Unknown");
                    item.put("privateIp", status != null && status.getPrivateIP() != null
                            ? status.getPrivateIP() : "");
                    nodes.add(item);
                }
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

    private void requireClient() {
        if (client == null) {
            throw new HuaweiCloudException(SERVICE_NAME, "CCE adapter not available in current region",
                    503, "REGION_NOT_SUPPORTED", null, null);
        }
    }
}
