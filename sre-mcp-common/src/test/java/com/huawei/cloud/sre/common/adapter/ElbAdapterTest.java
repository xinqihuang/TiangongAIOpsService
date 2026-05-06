package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.elb.v3.ElbClient;
import com.huaweicloud.sdk.elb.v3.model.ListLoadBalancersResponse;
import com.huaweicloud.sdk.elb.v3.model.LoadBalancer;
import com.huaweicloud.sdk.elb.v3.model.ShowLoadBalancerResponse;
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
class ElbAdapterTest {

    @Mock
    private ElbClient client;

    private ElbAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ElbAdapter(client, new SimpleMeterRegistry());
    }

    @Test
    void listLoadBalancers_returnsBalancerList() {
        var response = mock(ListLoadBalancersResponse.class);
        var lb = mock(LoadBalancer.class);

        when(client.listLoadBalancers(any())).thenReturn(response);
        when(response.getLoadbalancers()).thenReturn(List.of(lb));
        when(lb.getId()).thenReturn("elb-001");
        when(lb.getName()).thenReturn("prod-lb");
        when(lb.getOperatingStatus()).thenReturn("ONLINE");

        var result = adapter.listLoadBalancers();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEqualTo("elb-001");
        assertThat(result.get(0).get("name")).isEqualTo("prod-lb");
        assertThat(result.get(0).get("operatingStatus")).isEqualTo("ONLINE");
    }

    @Test
    void listLoadBalancers_returnsEmpty_whenNull() {
        var response = mock(ListLoadBalancersResponse.class);
        when(client.listLoadBalancers(any())).thenReturn(response);
        when(response.getLoadbalancers()).thenReturn(null);

        var result = adapter.listLoadBalancers();

        assertThat(result).isEmpty();
    }

    @Test
    void listLoadBalancers_throwsHuaweiCloudException_onApiError() {
        when(client.listLoadBalancers(any()))
                .thenThrow(new ServiceResponseException(500, "Error", "ELB.0001", "req-008"));

        assertThatThrownBy(() -> adapter.listLoadBalancers())
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("ELB");
    }

    @Test
    void getLoadBalancer_returnsDetails() {
        var response = mock(ShowLoadBalancerResponse.class);
        var lb = mock(LoadBalancer.class);

        when(client.showLoadBalancer(any())).thenReturn(response);
        when(response.getLoadbalancer()).thenReturn(lb);
        when(lb.getId()).thenReturn("elb-001");
        when(lb.getName()).thenReturn("prod-lb");
        when(lb.getOperatingStatus()).thenReturn("ONLINE");
        when(lb.getProvisioningStatus()).thenReturn("ACTIVE");

        var result = adapter.getLoadBalancer("elb-001");

        assertThat(result.get("id")).isEqualTo("elb-001");
        assertThat(result.get("provisioningStatus")).isEqualTo("ACTIVE");
    }

    @Test
    void getLoadBalancer_handlesNullLoadBalancer() {
        var response = mock(ShowLoadBalancerResponse.class);
        when(client.showLoadBalancer(any())).thenReturn(response);
        when(response.getLoadbalancer()).thenReturn(null);

        var result = adapter.getLoadBalancer("elb-001");

        assertThat(result.get("id")).isEmpty();
        assertThat(result.get("operatingStatus")).isEqualTo("UNKNOWN");
    }

    @Test
    void getLoadBalancer_throwsHuaweiCloudException_onApiError() {
        when(client.showLoadBalancer(any()))
                .thenThrow(new ServiceResponseException(404, "Not found", "ELB.0002", "req-009"));

        assertThatThrownBy(() -> adapter.getLoadBalancer("elb-bad"))
                .isInstanceOf(HuaweiCloudException.class);
    }
}
