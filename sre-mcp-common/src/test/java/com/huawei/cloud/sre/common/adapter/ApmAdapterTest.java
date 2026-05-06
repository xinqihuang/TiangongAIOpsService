package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.apm.v1.ApmClient;
import com.huaweicloud.sdk.apm.v1.model.ClientSpanInfo;
import com.huaweicloud.sdk.apm.v1.model.ShowSpanSearchResponse;
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
class ApmAdapterTest {

    @Mock
    private ApmClient client;

    private ApmAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ApmAdapter(client, new SimpleMeterRegistry());
    }

    @Test
    void analyzeTrace_returnsSpans_whenResponseHasData() {
        var response = mock(ShowSpanSearchResponse.class);
        var span = mock(ClientSpanInfo.class);

        when(client.showSpanSearch(any())).thenReturn(response);
        when(response.getSpanInfoList()).thenReturn(List.of(span));
        when(span.getSpanId()).thenReturn("span-001");
        when(span.getSource()).thenReturn("user-service");
        when(span.getRealSource()).thenReturn("user-service-v2");
        when(span.getStartTime()).thenReturn(1_700_000_000_000L);
        when(span.getTimeUsed()).thenReturn(120L);
        when(span.getCode()).thenReturn(200);
        when(span.getHasError()).thenReturn(false);
        when(span.getErrorReasons()).thenReturn(null);

        var result = adapter.analyzeTrace("trace-abc", 12345L);

        assertThat(result).isNotNull();
        assertThat(result.traceId()).isEqualTo("trace-abc");
        assertThat(result.spans()).hasSize(1);
        assertThat(result.spans().get(0).spanId()).isEqualTo("span-001");
        assertThat(result.errorSpans()).isEmpty();
    }

    @Test
    void analyzeTrace_detectsErrorSpans() {
        var response = mock(ShowSpanSearchResponse.class);
        var span = mock(ClientSpanInfo.class);

        when(client.showSpanSearch(any())).thenReturn(response);
        when(response.getSpanInfoList()).thenReturn(List.of(span));
        when(span.getSpanId()).thenReturn("span-err");
        when(span.getSource()).thenReturn("order-service");
        when(span.getRealSource()).thenReturn("");
        when(span.getStartTime()).thenReturn(1_700_000_000_000L);
        when(span.getTimeUsed()).thenReturn(5000L);
        when(span.getCode()).thenReturn(500);
        when(span.getHasError()).thenReturn(true);
        when(span.getErrorReasons()).thenReturn("Connection timeout");

        var result = adapter.analyzeTrace("trace-err", 12345L);

        assertThat(result.errorSpans()).hasSize(1);
        assertThat(result.spans().get(0).error()).isTrue();
    }

    @Test
    void analyzeTrace_returnsEmpty_whenNoSpans() {
        var response = mock(ShowSpanSearchResponse.class);
        when(client.showSpanSearch(any())).thenReturn(response);
        when(response.getSpanInfoList()).thenReturn(null);

        var result = adapter.analyzeTrace("trace-empty", 12345L);

        assertThat(result.spans()).isEmpty();
        assertThat(result.errorSpans()).isEmpty();
    }

    @Test
    void analyzeTrace_throwsHuaweiCloudException_onApiError() {
        when(client.showSpanSearch(any()))
                .thenThrow(new ServiceResponseException(503, "Service unavailable", "APM.0002", "req-002"));

        assertThatThrownBy(() -> adapter.analyzeTrace("trace-fail", 12345L))
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("APM");
    }
}
