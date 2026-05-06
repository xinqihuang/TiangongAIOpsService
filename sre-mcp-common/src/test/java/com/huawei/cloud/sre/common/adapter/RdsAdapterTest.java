package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.rds.v3.RdsClient;
import com.huaweicloud.sdk.rds.v3.model.InstanceResponse;
import com.huaweicloud.sdk.rds.v3.model.ListInstancesResponse;
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
class RdsAdapterTest {

    @Mock
    private RdsClient client;

    private RdsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RdsAdapter(client, new SimpleMeterRegistry());
    }

    @Test
    void listInstances_returnsInstanceList() {
        var response = mock(ListInstancesResponse.class);
        var instance = mock(InstanceResponse.class);

        when(client.listInstances(any())).thenReturn(response);
        when(response.getInstances()).thenReturn(List.of(instance));
        when(instance.getId()).thenReturn("rds-001");
        when(instance.getName()).thenReturn("prod-mysql");
        when(instance.getStatus()).thenReturn("ACTIVE");
        when(instance.getType()).thenReturn("Single");

        var result = adapter.listInstances();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEqualTo("rds-001");
        assertThat(result.get(0).get("name")).isEqualTo("prod-mysql");
        assertThat(result.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat(result.get(0).get("type")).isEqualTo("Single");
    }

    @Test
    void listInstances_returnsEmpty_whenNull() {
        var response = mock(ListInstancesResponse.class);
        when(client.listInstances(any())).thenReturn(response);
        when(response.getInstances()).thenReturn(null);

        var result = adapter.listInstances();

        assertThat(result).isEmpty();
    }

    @Test
    void listInstances_throwsHuaweiCloudException_onApiError() {
        when(client.listInstances(any()))
                .thenThrow(new ServiceResponseException(500, "DB Error", "RDS.0001", "req-010"));

        assertThatThrownBy(() -> adapter.listInstances())
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("RDS");
    }

    @Test
    void getInstance_returnsInstance_whenFound() {
        var response = mock(ListInstancesResponse.class);
        var instance = mock(InstanceResponse.class);

        when(client.listInstances(any())).thenReturn(response);
        when(response.getInstances()).thenReturn(List.of(instance));
        when(instance.getId()).thenReturn("rds-001");
        when(instance.getName()).thenReturn("prod-mysql");
        when(instance.getStatus()).thenReturn("ACTIVE");
        when(instance.getType()).thenReturn("Ha");

        var result = adapter.getInstance("rds-001");

        assertThat(result).isPresent();
        assertThat(result.get().get("id")).isEqualTo("rds-001");
        assertThat(result.get().get("type")).isEqualTo("Ha");
    }

    @Test
    void getInstance_returnsEmpty_whenNotFound() {
        var response = mock(ListInstancesResponse.class);
        when(client.listInstances(any())).thenReturn(response);
        when(response.getInstances()).thenReturn(List.of());

        var result = adapter.getInstance("rds-not-found");

        assertThat(result).isEmpty();
    }

    @Test
    void getInstance_throwsHuaweiCloudException_onApiError() {
        when(client.listInstances(any()))
                .thenThrow(new ServiceResponseException(404, "Not found", "RDS.0002", "req-011"));

        assertThatThrownBy(() -> adapter.getInstance("rds-bad"))
                .isInstanceOf(HuaweiCloudException.class);
    }
}
