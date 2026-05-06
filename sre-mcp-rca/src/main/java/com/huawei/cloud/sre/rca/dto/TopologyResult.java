package com.huawei.cloud.sre.rca.dto;

import java.util.List;
import java.util.Map;

/**
 * 服务拓扑查询结果。
 *
 * @param rootService 查询起点的服务名
 * @param depth       展开的调用深度
 * @param nodes       拓扑节点列表
 * @param edges       拓扑边列表（调用关系）
 */
public record TopologyResult(
        String rootService,
        int depth,
        List<ServiceNode> nodes,
        List<ServiceEdge> edges
) {

    /**
     * 拓扑节点（服务实例）。
     *
     * @param name   服务名
     * @param type   类型，如 pod / deployment / external
     * @param status 当前状态，如 Running / Error / Unknown
     * @param labels 附加标签，如 namespace、version 等
     */
    public record ServiceNode(
            String name,
            String type,
            String status,
            Map<String, String> labels
    ) {}

    /**
     * 拓扑边（服务间调用关系）。
     *
     * @param source     调用方服务名
     * @param target     被调用方服务名
     * @param protocol   协议类型，如 HTTP / gRPC / Kafka
     * @param latencyMs  近期平均延迟（毫秒），-1 表示无数据
     * @param errorRate  近期错误率 0.0–1.0，-1 表示无数据
     */
    public record ServiceEdge(
            String source,
            String target,
            String protocol,
            double latencyMs,
            double errorRate
    ) {}

    /** 存在异常节点（status 非 Running）。 */
    public boolean hasUnhealthyNodes() {
        return nodes != null && nodes.stream()
                .anyMatch(n -> !"Running".equalsIgnoreCase(n.status()));
    }
}
