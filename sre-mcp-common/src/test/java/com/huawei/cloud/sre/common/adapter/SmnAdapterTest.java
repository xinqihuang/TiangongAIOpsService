package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.smn.v2.SmnClient;
import com.huaweicloud.sdk.smn.v2.model.PublishMessageResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmnAdapterTest {

    @Mock
    private SmnClient client;

    private SmnAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SmnAdapter(client, new SimpleMeterRegistry());
    }

    @Test
    void publishMessage_returnsMessageId() {
        var response = mock(PublishMessageResponse.class);
        when(client.publishMessage(any())).thenReturn(response);
        when(response.getMessageId()).thenReturn("msg-001");

        var topicUrn = "urn:smn:cn-north-4:project123:alert-topic";
        var result = adapter.publishMessage(topicUrn, "Alert: High CPU", "CPU usage exceeded 90%");

        assertThat(result).isEqualTo("msg-001");
    }

    @Test
    void publishMessage_returnsEmpty_whenMessageIdNull() {
        var response = mock(PublishMessageResponse.class);
        when(client.publishMessage(any())).thenReturn(response);
        when(response.getMessageId()).thenReturn(null);

        var result = adapter.publishMessage("urn:smn:cn-north-4:proj:topic", "Subject", "Body");

        assertThat(result).isEmpty();
    }

    @Test
    void publishMessage_throwsHuaweiCloudException_onApiError() {
        when(client.publishMessage(any()))
                .thenThrow(new ServiceResponseException(400, "Bad Request", "SMN.0001", "req-014"));

        assertThatThrownBy(() -> adapter.publishMessage(
                "urn:smn:cn-north-4:proj:topic", "Subject", "Body"))
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("SMN");
    }

    @Test
    void publishMessage_handlesEmptyTopic() {
        var response = mock(PublishMessageResponse.class);
        when(client.publishMessage(any())).thenReturn(response);
        when(response.getMessageId()).thenReturn("msg-002");

        var result = adapter.publishMessage("urn:smn:cn-north-4:proj:topic2",
                "Disk Alert", "Disk usage is at 95%");

        assertThat(result).isEqualTo("msg-002");
    }
}
