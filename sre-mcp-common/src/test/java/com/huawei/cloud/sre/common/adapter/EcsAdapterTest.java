package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.ecs.v2.EcsClient;
import com.huaweicloud.sdk.ecs.v2.model.BatchRebootServersResponse;
import com.huaweicloud.sdk.ecs.v2.model.ListServersDetailsResponse;
import com.huaweicloud.sdk.ecs.v2.model.ServerDetail;
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
class EcsAdapterTest {

    @Mock
    private EcsClient client;

    private EcsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new EcsAdapter(client, new SimpleMeterRegistry());
    }

    @Test
    void listServers_returnsServerList() {
        var response = mock(ListServersDetailsResponse.class);
        var server = mock(ServerDetail.class);

        when(client.listServersDetails(any())).thenReturn(response);
        when(response.getServers()).thenReturn(List.of(server));
        when(server.getId()).thenReturn("ecs-001");
        when(server.getName()).thenReturn("app-server-1");
        when(server.getStatus()).thenReturn("ACTIVE");

        var result = adapter.listServers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEqualTo("ecs-001");
        assertThat(result.get(0).get("name")).isEqualTo("app-server-1");
        assertThat(result.get(0).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void listServers_returnsEmpty_whenServersNull() {
        var response = mock(ListServersDetailsResponse.class);
        when(client.listServersDetails(any())).thenReturn(response);
        when(response.getServers()).thenReturn(null);

        var result = adapter.listServers();

        assertThat(result).isEmpty();
    }

    @Test
    void listServers_handlesNullFields() {
        var response = mock(ListServersDetailsResponse.class);
        var server = mock(ServerDetail.class);

        when(client.listServersDetails(any())).thenReturn(response);
        when(response.getServers()).thenReturn(List.of(server));
        when(server.getId()).thenReturn(null);
        when(server.getName()).thenReturn(null);
        when(server.getStatus()).thenReturn(null);

        var result = adapter.listServers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEmpty();
        assertThat(result.get(0).get("status")).isEqualTo("Unknown");
    }

    @Test
    void listServers_throwsHuaweiCloudException_onApiError() {
        when(client.listServersDetails(any()))
                .thenThrow(new ServiceResponseException(403, "Forbidden", "ECS.0001", "req-006"));

        assertThatThrownBy(() -> adapter.listServers())
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("ECS");
    }

    @Test
    void rebootServer_succeeds() {
        when(client.batchRebootServers(any())).thenReturn(mock(BatchRebootServersResponse.class));

        adapter.rebootServer("ecs-001");
        // no exception = success
    }

    @Test
    void rebootServer_throwsHuaweiCloudException_onApiError() {
        when(client.batchRebootServers(any()))
                .thenThrow(new ServiceResponseException(500, "Reboot failed", "ECS.0002", "req-007"));

        assertThatThrownBy(() -> adapter.rebootServer("ecs-bad"))
                .isInstanceOf(HuaweiCloudException.class);
    }
}
