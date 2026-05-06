package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.lts.v2.LtsClient;
import com.huaweicloud.sdk.lts.v2.model.ListLogsResponse;
import com.huaweicloud.sdk.lts.v2.model.LogContents;
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
class LtsAdapterTest {

    @Mock
    private LtsClient client;

    private LtsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LtsAdapter(client, "log-group-test", new SimpleMeterRegistry());
    }

    @Test
    void searchLogs_returnsEntries_whenResponseHasLogs() {
        var response = mock(ListLogsResponse.class);
        var logEntry = mock(LogContents.class);

        when(client.listLogs(any())).thenReturn(response);
        when(response.getLogs()).thenReturn(List.of(logEntry));
        when(logEntry.getLineNum()).thenReturn("1700000000000");
        when(logEntry.getContent()).thenReturn("ERROR: NullPointerException in OrderService");

        var result = adapter.searchLogs("order-service", "NullPointerException", 30, 100);

        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo("order-service");
        assertThat(result.keyword()).isEqualTo("NullPointerException");
        assertThat(result.totalCount()).isEqualTo(1L);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().get(0).message()).contains("NullPointerException");
    }

    @Test
    void searchLogs_returnsEmpty_whenNoLogs() {
        var response = mock(ListLogsResponse.class);
        when(client.listLogs(any())).thenReturn(response);
        when(response.getLogs()).thenReturn(null);

        var result = adapter.searchLogs("svc", "keyword", 5, 10);

        assertThat(result.entries()).isEmpty();
        assertThat(result.totalCount()).isEqualTo(0L);
    }

    @Test
    void searchLogs_handlesNullLineNum() {
        var response = mock(ListLogsResponse.class);
        var logEntry = mock(LogContents.class);

        when(client.listLogs(any())).thenReturn(response);
        when(response.getLogs()).thenReturn(List.of(logEntry));
        when(logEntry.getLineNum()).thenReturn(null);
        when(logEntry.getContent()).thenReturn("some log content");

        var result = adapter.searchLogs("svc", "keyword", 5, 10);

        assertThat(result.entries()).hasSize(1);
    }

    @Test
    void searchLogs_throwsHuaweiCloudException_onApiError() {
        when(client.listLogs(any()))
                .thenThrow(new ServiceResponseException(401, "Unauthorized", "LTS.0001", "req-003"));

        assertThatThrownBy(() -> adapter.searchLogs("svc", "err", 10, 50))
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("LTS");
    }
}
