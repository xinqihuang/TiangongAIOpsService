package com.huawei.cloud.sre.rca.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TopologyResultTest {

    @Test
    void hasUnhealthyNodes_trueWhenErrorNode() {
        var nodes = List.of(
                new TopologyResult.ServiceNode("svc-a", "deployment", "Running", Map.of()),
                new TopologyResult.ServiceNode("svc-b", "deployment", "Error", Map.of())
        );
        TopologyResult topology = new TopologyResult("svc-a", 2, nodes, List.of());
        assertThat(topology.hasUnhealthyNodes()).isTrue();
    }

    @Test
    void hasUnhealthyNodes_falseWhenAllRunning() {
        var nodes = List.of(
                new TopologyResult.ServiceNode("svc-a", "deployment", "Running", Map.of()),
                new TopologyResult.ServiceNode("svc-b", "pod", "Running", Map.of())
        );
        TopologyResult topology = new TopologyResult("svc-a", 1, nodes, List.of());
        assertThat(topology.hasUnhealthyNodes()).isFalse();
    }

    @Test
    void hasUnhealthyNodes_falseWhenEmptyNodes() {
        TopologyResult topology = new TopologyResult("svc", 1, List.of(), List.of());
        assertThat(topology.hasUnhealthyNodes()).isFalse();
    }
}
