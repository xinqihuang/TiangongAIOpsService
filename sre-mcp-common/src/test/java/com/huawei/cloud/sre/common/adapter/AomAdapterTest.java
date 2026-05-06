package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.aom.v2.AomClient;
import com.huaweicloud.sdk.aom.v2.model.MetricDataPoints;
import com.huaweicloud.sdk.aom.v2.model.MetricDataValue;
import com.huaweicloud.sdk.aom.v2.model.ShowMetricsDataResponse;
import com.huaweicloud.sdk.aom.v2.model.StatisticValue;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
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
class AomAdapterTest {

    @Mock
    private AomClient client;

    private AomAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AomAdapter(client, new SimpleMeterRegistry());
    }

    @Test
    void queryMetric_returnsDataPoints_whenResponseHasData() {
        var response = mock(ShowMetricsDataResponse.class);
        var metricDataValue = mock(MetricDataValue.class);
        var dataPoint = mock(MetricDataPoints.class);
        var statValue = mock(StatisticValue.class);

        when(client.showMetricsData(any())).thenReturn(response);
        when(response.getMetrics()).thenReturn(List.of(metricDataValue));
        when(metricDataValue.getDataPoints()).thenReturn(List.of(dataPoint));
        when(dataPoint.getStatistics()).thenReturn(List.of(statValue));
        when(dataPoint.getTimestamp()).thenReturn(1_000_000L);
        when(statValue.getValue()).thenReturn(75.5);

        var result = adapter.queryMetric("user-service", "cpu_usage",
                Instant.now().minusSeconds(300), Instant.now(), 60);

        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo("user-service");
        assertThat(result.metric()).isEqualTo("cpu_usage");
        assertThat(result.dataPoints()).hasSize(1);
        assertThat(result.dataPoints().get(0).value()).isEqualTo(75.5);
    }

    @Test
    void queryMetric_returnsEmpty_whenResponseMetricsNull() {
        var response = mock(ShowMetricsDataResponse.class);
        when(client.showMetricsData(any())).thenReturn(response);
        when(response.getMetrics()).thenReturn(null);

        var result = adapter.queryMetric("svc", "metric",
                Instant.now().minusSeconds(60), Instant.now(), 60);

        assertThat(result.dataPoints()).isEmpty();
    }

    @Test
    void queryMetric_returnsEmpty_whenDataPointsNull() {
        var response = mock(ShowMetricsDataResponse.class);
        var metricDataValue = mock(MetricDataValue.class);

        when(client.showMetricsData(any())).thenReturn(response);
        when(response.getMetrics()).thenReturn(List.of(metricDataValue));
        when(metricDataValue.getDataPoints()).thenReturn(null);

        var result = adapter.queryMetric("svc", "metric",
                Instant.now().minusSeconds(60), Instant.now(), 60);

        assertThat(result.dataPoints()).isEmpty();
    }

    @Test
    void queryMetric_throwsHuaweiCloudException_onApiError() {
        when(client.showMetricsData(any()))
                .thenThrow(new ServiceResponseException(500, "Internal Error", "AOM.0001", "req-001"));

        assertThatThrownBy(() -> adapter.queryMetric("svc", "metric",
                Instant.now().minusSeconds(60), Instant.now(), 60))
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("AOM");
    }

    @Test
    void queryMetric_handlesNullStatisticsValue() {
        var response = mock(ShowMetricsDataResponse.class);
        var metricDataValue = mock(MetricDataValue.class);
        var dataPoint = mock(MetricDataPoints.class);
        var statValue = mock(StatisticValue.class);

        when(client.showMetricsData(any())).thenReturn(response);
        when(response.getMetrics()).thenReturn(List.of(metricDataValue));
        when(metricDataValue.getDataPoints()).thenReturn(List.of(dataPoint));
        when(dataPoint.getStatistics()).thenReturn(List.of(statValue));
        when(dataPoint.getTimestamp()).thenReturn(null);
        when(statValue.getValue()).thenReturn(null);

        var result = adapter.queryMetric("svc", "metric",
                Instant.now().minusSeconds(60), Instant.now(), 60);

        assertThat(result.dataPoints()).hasSize(1);
        assertThat(result.dataPoints().get(0).value()).isEqualTo(0.0);
    }
}
