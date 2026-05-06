package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.cce.v3.CceClient;
import com.huaweicloud.sdk.cce.v3.model.ClusterMetadata;
import com.huaweicloud.sdk.cce.v3.model.ClusterSpec;
import com.huaweicloud.sdk.cce.v3.model.ListNodesResponse;
import com.huaweicloud.sdk.cce.v3.model.Node;
import com.huaweicloud.sdk.cce.v3.model.NodeMetadata;
import com.huaweicloud.sdk.cce.v3.model.NodeStatus;
import com.huaweicloud.sdk.cce.v3.model.ShowClusterResponse;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CceAdapterTest {

    @Mock
    private CceClient client;

    private CceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CceAdapter(client, "cluster-001", new SimpleMeterRegistry());
    }

    @Test
    void getClusterInfo_returnsClusterData() {
        var response = mock(ShowClusterResponse.class);
        var metadata = mock(ClusterMetadata.class);
        var spec = mock(ClusterSpec.class);

        when(client.showCluster(any())).thenReturn(response);
        when(response.getMetadata()).thenReturn(metadata);
        when(response.getSpec()).thenReturn(spec);
        when(metadata.getName()).thenReturn("prod-cluster");
        when(spec.getVersion()).thenReturn("v1.28");

        var result = adapter.getClusterInfo();

        assertThat(result).isNotNull();
        assertThat(result.get("name")).isEqualTo("prod-cluster");
        assertThat(result.get("version")).isEqualTo("v1.28");
        assertThat(result.get("clusterId")).isEqualTo("cluster-001");
    }

    @Test
    void getClusterInfo_handlesNullMetadata() {
        var response = mock(ShowClusterResponse.class);
        when(client.showCluster(any())).thenReturn(response);
        when(response.getMetadata()).thenReturn(null);
        when(response.getSpec()).thenReturn(null);

        var result = adapter.getClusterInfo();

        assertThat(result.get("name")).isEmpty();
        assertThat(result.get("version")).isEmpty();
    }

    @Test
    void getClusterInfo_throwsHuaweiCloudException_onApiError() {
        when(client.showCluster(any()))
                .thenThrow(new ServiceResponseException(404, "Cluster not found", "CCE.0001", "req-004"));

        assertThatThrownBy(() -> adapter.getClusterInfo())
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("CCE");
    }

    @Test
    void listNodes_returnsNodeList() {
        var response = mock(ListNodesResponse.class);
        var node = mock(Node.class);
        var nodeMeta = mock(NodeMetadata.class);
        var nodeStatus = mock(NodeStatus.class);
        var phase = NodeStatus.PhaseEnum.ACTIVE;

        when(client.listNodes(any())).thenReturn(response);
        when(response.getItems()).thenReturn(List.of(node));
        when(node.getMetadata()).thenReturn(nodeMeta);
        when(node.getStatus()).thenReturn(nodeStatus);
        when(nodeMeta.getName()).thenReturn("node-01");
        when(nodeStatus.getPhase()).thenReturn(phase);

        var result = adapter.listNodes();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("name")).isEqualTo("node-01");
        assertThat(result.get(0).get("status")).isEqualTo("Active");
    }

    @Test
    void listNodes_returnsEmpty_whenItemsNull() {
        var response = mock(ListNodesResponse.class);
        when(client.listNodes(any())).thenReturn(response);
        when(response.getItems()).thenReturn(null);

        var result = adapter.listNodes();

        assertThat(result).isEmpty();
    }

    @Test
    void listNodes_throwsHuaweiCloudException_onApiError() {
        when(client.listNodes(any()))
                .thenThrow(new ServiceResponseException(500, "Internal error", "CCE.0002", "req-005"));

        assertThatThrownBy(() -> adapter.listNodes())
                .isInstanceOf(HuaweiCloudException.class);
    }
}
