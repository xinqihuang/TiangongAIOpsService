package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.cts.v3.CtsClient;
import com.huaweicloud.sdk.cts.v3.model.ListTracesResponse;
import com.huaweicloud.sdk.cts.v3.model.Traces;
import com.huaweicloud.sdk.cts.v3.model.UserInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CtsAdapterTest {

    @Mock
    private CtsClient client;

    private CtsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CtsAdapter(client, new SimpleMeterRegistry());
    }

    @Test
    void queryTraces_returnsEvents_whenResponseHasData() {
        var response = mock(ListTracesResponse.class);
        var trace = mock(Traces.class);
        var user = mock(UserInfo.class);

        when(client.listTraces(any())).thenReturn(response);
        when(response.getTraces()).thenReturn(List.of(trace));
        when(trace.getTraceName()).thenReturn("createInstance");
        when(trace.getResourceType()).thenReturn("ecs");
        when(trace.getResourceName()).thenReturn("app-server-1");
        when(trace.getTime()).thenReturn(1_700_000_000_000L);
        when(trace.getUser()).thenReturn(user);
        when(user.getName()).thenReturn("admin");

        var now = Instant.now();
        var result = adapter.queryTraces("ecs", "app-server-1",
                now.minusSeconds(3600), now, 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("eventName")).isEqualTo("createInstance");
        assertThat(result.get(0).get("resourceType")).isEqualTo("ecs");
        assertThat(result.get(0).get("user")).isEqualTo("admin");
    }

    @Test
    void queryTraces_withNullFilters_works() {
        var response = mock(ListTracesResponse.class);
        when(client.listTraces(any())).thenReturn(response);
        when(response.getTraces()).thenReturn(null);

        var now = Instant.now();
        var result = adapter.queryTraces(null, null, now.minusSeconds(60), now, 10);

        assertThat(result).isEmpty();
    }

    @Test
    void queryTraces_handlesNullUserAndTime() {
        var response = mock(ListTracesResponse.class);
        var trace = mock(Traces.class);

        when(client.listTraces(any())).thenReturn(response);
        when(response.getTraces()).thenReturn(List.of(trace));
        when(trace.getTraceName()).thenReturn("deleteInstance");
        when(trace.getResourceType()).thenReturn(null);
        when(trace.getResourceName()).thenReturn(null);
        when(trace.getTime()).thenReturn(null);
        when(trace.getUser()).thenReturn(null);

        var now = Instant.now();
        var result = adapter.queryTraces(null, null, now.minusSeconds(60), now, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("user")).isEqualTo("unknown");
        assertThat(result.get(0).get("time")).isEmpty();
    }

    @Test
    void queryTraces_throwsHuaweiCloudException_onApiError() {
        when(client.listTraces(any()))
                .thenThrow(new ServiceResponseException(403, "Forbidden", "CTS.0001", "req-012"));

        var now = Instant.now();
        assertThatThrownBy(() -> adapter.queryTraces("ecs", null, now.minusSeconds(60), now, 10))
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("CTS");
    }
}
