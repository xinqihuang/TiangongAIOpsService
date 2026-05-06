package com.huawei.cloud.sre.monitor.tool;

import com.huawei.cloud.sre.common.adapter.AomAdapter;
import com.huawei.cloud.sre.common.adapter.SmnAdapter;
import com.huawei.cloud.sre.common.dto.MetricResult;
import com.huawei.cloud.sre.monitor.dto.AlertGroup;
import com.huawei.cloud.sre.monitor.dto.AnomalyResult;
import com.huawei.cloud.sre.monitor.dto.BaselineStatus;
import com.huawei.cloud.sre.monitor.dto.NotificationResult;
import com.huawei.cloud.sre.monitor.kafka.MonitorEventProducer;
import com.huawei.cloud.sre.monitor.repository.AlertRuleRepository;
import com.huawei.cloud.sre.monitor.service.AlertAggregator;
import com.huawei.cloud.sre.monitor.service.BaselineEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonitorToolServiceTest {

    @Mock
    private BaselineEngine baselineEngine;

    @Mock
    private AlertAggregator alertAggregator;

    @Mock
    private AomAdapter aomAdapter;

    @Mock
    private SmnAdapter smnAdapter;

    @Mock
    private AlertRuleRepository alertRuleRepository;

    @Mock
    private MonitorEventProducer eventProducer;

    @InjectMocks
    private MonitorToolService toolService;

    @Test
    void updateBaseline_delegatesToEngine_andPublishesEvent() {
        BaselineStatus expected = new BaselineStatus("svc", "cpu",
                50.0, 25.0, 5.0, 65.0, 35.0, 40L, Instant.now(), true);
        when(baselineEngine.updateBaseline("svc", "cpu", List.of(55.0))).thenReturn(expected);

        BaselineStatus result = toolService.updateBaseline("svc", "cpu", List.of(55.0));

        assertThat(result.service()).isEqualTo("svc");
        assertThat(result.isStable()).isTrue();
        verify(eventProducer).publishBaselineUpdate(any());
    }

    @Test
    void detectAnomaly_publishesEvent_whenAnomalyFound() {
        AnomalyResult anomaly = new AnomalyResult("svc", "cpu", Instant.now(),
                true, 200.0, 50.0, 5.0, 30.0, "CRITICAL", "SPIKE",
                "Investigate CPU spike", List.of());
        when(baselineEngine.detectAnomaly("svc", "cpu", 200.0)).thenReturn(anomaly);

        AnomalyResult result = toolService.detectAnomaly("svc", "cpu", 200.0);

        assertThat(result.isAnomaly()).isTrue();
        assertThat(result.anomalyType()).isEqualTo("SPIKE");
        verify(eventProducer).publishAlert(any());
    }

    @Test
    void detectAnomaly_doesNotPublish_whenNoAnomaly() {
        AnomalyResult normal = new AnomalyResult("svc", "cpu", Instant.now(),
                false, 55.0, 50.0, 5.0, 1.0, "NORMAL", "NORMAL",
                "Within bounds", List.of());
        when(baselineEngine.detectAnomaly("svc", "cpu", 55.0)).thenReturn(normal);

        AnomalyResult result = toolService.detectAnomaly("svc", "cpu", 55.0);

        assertThat(result.isAnomaly()).isFalse();
    }

    @Test
    void dedupAlerts_delegatesToAggregator() {
        Map<String, Object> alert = new HashMap<>();
        alert.put("id", "a1");
        alert.put("service", "order-service");
        alert.put("severity", "HIGH");
        alert.put("message", "CPU spike");
        alert.put("timestamp", Instant.now().toString());

        AlertGroup group = new AlertGroup("g1", "order-service", "CPU spike",
                "HIGH", List.of("a1"), 1, Instant.now(), Instant.now(), "SEMANTIC", false);
        when(alertAggregator.aggregate(anyList())).thenReturn(List.of(group));

        List<AlertGroup> result = toolService.dedupAlerts(List.of(alert));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).service()).isEqualTo("order-service");
    }

    @Test
    void silenceAlerts_delegatesToAggregator() {
        Map<String, Object> result = toolService.silenceAlerts("order-service", "cpu_usage", 30, "maintenance");

        assertThat(result.get("status")).isEqualTo("silenced");
        assertThat(result.get("durationMinutes")).isEqualTo(30);
        verify(alertAggregator).silence(anyString(), anyString(), any());
    }

    @Test
    void silenceAlerts_clampsMaxDuration_to1440() {
        Map<String, Object> result = toolService.silenceAlerts("svc", "metric", 9999, "test");
        assertThat(result.get("durationMinutes")).isEqualTo(1440);
    }

    @Test
    void getBaselineStatus_delegatesToEngine() {
        BaselineStatus expected = new BaselineStatus("svc", "mem",
                70.0, 100.0, 10.0, 100.0, 40.0, 100L, Instant.now(), true);
        when(baselineEngine.getBaselineStatus("svc", "mem")).thenReturn(expected);

        BaselineStatus result = toolService.getBaselineStatus("svc", "mem");

        assertThat(result.metric()).isEqualTo("mem");
        assertThat(result.isStable()).isTrue();
    }

    @Test
    void sendNotification_returnsSuccess_whenSmnSucceeds() {
        when(smnAdapter.publishMessage(anyString(), anyString(), anyString())).thenReturn("msg-123");

        NotificationResult result = toolService.sendNotification(
                "urn:smn:cn-north-4:proj:topic", "Alert!", "CPU spike on order-service");

        assertThat(result.success()).isTrue();
        assertThat(result.messageId()).isEqualTo("msg-123");
    }

    @Test
    void sendNotification_returnsFailure_whenSmnThrows() {
        when(smnAdapter.publishMessage(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("SMN unavailable"));

        NotificationResult result = toolService.sendNotification(
                "urn:smn:cn-north-4:proj:topic", "Alert!", "test");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMsg()).contains("SMN unavailable");
    }

    @Test
    void manageAlertRule_returnsError_forUnknownAction() {
        Map<String, Object> result = toolService.manageAlertRule(
                "INVALID", null, null, null, null, null);
        assertThat(result).containsKey("error");
    }

    @Test
    void correlateMetrics_returnsStructuredResult() {
        MetricResult metricResult = new MetricResult("svc", "cpu", Instant.now(), Instant.now(), "percent",
                List.of(new MetricResult.DataPoint(Instant.now(), 50.0),
                        new MetricResult.DataPoint(Instant.now(), 55.0)));
        when(aomAdapter.queryMetric(anyString(), anyString(), any(), any(), any(int.class)))
                .thenReturn(metricResult);

        Map<String, Object> result = toolService.correlateMetrics("svc", List.of("cpu", "memory"), 60);

        assertThat(result).containsKey("correlations");
        assertThat(result).containsKey("analyzedMetrics");
        assertThat(result.get("service")).isEqualTo("svc");
    }
}
